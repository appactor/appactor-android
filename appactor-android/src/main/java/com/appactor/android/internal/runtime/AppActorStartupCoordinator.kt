package com.appactor.android.internal.runtime

import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorDebugCategory
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorLogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal interface AppActorStartupCoordinatorHost {
    suspend fun performStartupIdentify(
        runtimeState: AppActorRuntimeState,
    ): Pair<((AppActorCustomerInfo) -> Unit)?, AppActorCustomerInfo>?

    fun confirmIdentity(runtimeState: AppActorRuntimeState)

    suspend fun captureOperationSnapshot(
        resolveAppUserId: Boolean,
        awaitBootstrapCompletion: Boolean,
    ): AppActorOperationSnapshot

    suspend fun syncCurrentPurchases(snapshot: AppActorOperationSnapshot): AppActorCustomerInfo?

    suspend fun retryDeadLetteredItems(runtimeState: AppActorRuntimeState)

    suspend fun fetchOfferings(runtimeState: AppActorRuntimeState): AppActorDiagnosticsDataSource?

    suspend fun fetchCustomerInfo(
        snapshot: AppActorOperationSnapshot,
    ): Pair<AppActorCustomerInfo, AppActorDiagnosticsDataSource?>

    suspend fun persistCustomerInfoIfCurrent(
        snapshot: AppActorOperationSnapshot,
        info: AppActorCustomerInfo,
    ): Boolean

    suspend fun publishCustomerInfoIfCurrent(
        snapshot: AppActorOperationSnapshot,
        info: AppActorCustomerInfo,
        source: AppActorDiagnosticsDataSource? = null,
    ): Boolean

    suspend fun persistOfferingsSource(
        runtimeSessionId: Long,
        source: AppActorDiagnosticsDataSource?,
    )

    suspend fun processPurchaseUpdates(
        runtimeState: AppActorRuntimeState,
        snapshot: AppActorOperationSnapshot,
        purchases: List<AppActorStorePurchase>,
    ): AppActorCustomerInfo?

    fun deliverOnMain(block: () -> Unit)

    fun emitDebugEvent(
        runtimeSessionId: Long,
        category: AppActorDebugCategory,
        level: AppActorLogLevel,
        name: String,
        message: String,
        requestId: String? = null,
        attributes: Map<String, String> = emptyMap(),
    )
}

internal class AppActorStartupCoordinator(
    private val host: AppActorStartupCoordinatorHost,
) {

    fun start(runtimeState: AppActorRuntimeState): AppActorStartupHandles {
        runtimeState.scope.launch {
            warmStoreState(runtimeState)
        }

        val identityReadyJob = runtimeState.scope.launch {
            if (runtimeState.configuration.options.shouldStartBootstrap()) {
                runStartupSequence(runtimeState)
            } else {
                host.confirmIdentity(runtimeState)
            }
        }

        val bootstrapCompletionJob = runtimeState.scope.launch {
            try {
                awaitIdentityReadyIfNeeded(identityReadyJob)
            } catch (_: CancellationException) {
                return@launch
            }
            if (runtimeState.configuration.options.shouldStartBootstrap()) {
                runDeferredBootstrapPhases(runtimeState)
            }
        }

        val purchaseUpdates = runtimeState.storeAdapter.purchaseUpdates()
        val purchaseUpdatesJob = runtimeState.scope.launch {
            try {
                awaitIdentityReadyIfNeeded(identityReadyJob)
            } catch (_: CancellationException) {
                return@launch
            }
            purchaseUpdates.collect { purchases ->
                val snapshot = host.captureOperationSnapshot(
                    resolveAppUserId = true,
                    awaitBootstrapCompletion = false,
                )
                runCatching {
                    host.processPurchaseUpdates(runtimeState, snapshot, purchases)
                }.onSuccess { updatedCustomer ->
                    if (updatedCustomer != null && host.persistCustomerInfoIfCurrent(snapshot, updatedCustomer)) {
                        host.publishCustomerInfoIfCurrent(
                            snapshot = snapshot,
                            info = updatedCustomer,
                            source = AppActorDiagnosticsDataSource.Network,
                        )
                    }
                }.onFailure { throwable ->
                    throwIfCancellation(throwable)
                    AppActorLogger.debug("Purchase update processing failed: ${throwable.message}")
                    host.emitDebugEvent(
                        runtimeSessionId = runtimeState.sessionId,
                        category = AppActorDebugCategory.Purchase,
                        level = AppActorLogLevel.Warn,
                        name = "purchase_update_failed",
                        message = "Purchase update processing failed.",
                        attributes = debugAttributes("reason" to throwable.message),
                    )
                }
            }
        }

        return AppActorStartupHandles(
            identityReadyJob = identityReadyJob,
            bootstrapCompletionJob = bootstrapCompletionJob,
            purchaseUpdatesJob = purchaseUpdatesJob,
        )
    }

    suspend fun awaitStartupIfNeeded(currentRuntime: AppActorRuntimeState) {
        awaitBootstrapCompletionIfNeeded(currentRuntime.bootstrapCompletionJob)
    }

    private suspend fun runStartupSequence(runtimeState: AppActorRuntimeState) {
        var callback: Pair<((AppActorCustomerInfo) -> Unit)?, AppActorCustomerInfo>? = null

        timedPhase(runtimeState, "identify", AppActorDebugCategory.Network) {
            callback = host.performStartupIdentify(runtimeState)
        }

        if (callback != null) {
            host.confirmIdentity(runtimeState)
        }

        callback?.let { resolved ->
            host.deliverOnMain {
                resolved.first?.invoke(resolved.second)
            }
        }
    }

    private suspend fun runDeferredBootstrapPhases(runtimeState: AppActorRuntimeState) = coroutineScope {
        // Offerings prefetch runs in parallel with purchase sync + customer refresh.
        // Install referrer is handled separately via AppActor.enableInstallReferrer().
        val offeringsJob = launch { runStartupOfferingsFetch(runtimeState) }

        runStartupPurchaseSync(runtimeState)
        runStartupDeadLetterRetry(runtimeState)
        runStartupCustomerRefresh(runtimeState)

        offeringsJob.join()
    }

    private suspend fun timedPhase(
        runtimeState: AppActorRuntimeState,
        phaseName: String,
        failCategory: AppActorDebugCategory,
        block: suspend () -> Unit,
    ) {
        val startNanos = System.nanoTime()
        try {
            block()
        } catch (throwable: Throwable) {
            throwIfCancellation(throwable)
            AppActorLogger.debug("Startup $phaseName failed: ${throwable.message}")
            host.emitDebugEvent(
                runtimeSessionId = runtimeState.sessionId,
                category = failCategory,
                level = AppActorLogLevel.Warn,
                name = "startup_${phaseName}_failed",
                message = "Startup $phaseName failed.",
                attributes = debugAttributes("reason" to throwable.message),
            )
        } finally {
            host.emitDebugEvent(
                runtimeSessionId = runtimeState.sessionId,
                category = AppActorDebugCategory.Lifecycle,
                level = AppActorLogLevel.Info,
                name = "startup_phase_$phaseName",
                message = "Startup $phaseName completed.",
                attributes = debugAttributes("duration_ms" to elapsedMs(startNanos).toString()),
            )
        }
    }

    private suspend fun runStartupOfferingsFetch(runtimeState: AppActorRuntimeState) {
        timedPhase(runtimeState, "offerings", AppActorDebugCategory.Network) {
            val source = host.fetchOfferings(runtimeState)
            host.persistOfferingsSource(runtimeState.sessionId, source)
        }
    }

    private suspend fun runStartupPurchaseSync(runtimeState: AppActorRuntimeState) {
        timedPhase(runtimeState, "purchase_sync", AppActorDebugCategory.Purchase) {
            val snapshot = host.captureOperationSnapshot(
                resolveAppUserId = true,
                awaitBootstrapCompletion = false,
            )
            if (snapshot.runtime.sessionId != runtimeState.sessionId) {
                return@timedPhase
            }
            val synced = host.syncCurrentPurchases(snapshot) ?: return@timedPhase
            if (host.persistCustomerInfoIfCurrent(snapshot, synced)) {
                host.publishCustomerInfoIfCurrent(
                    snapshot = snapshot,
                    info = synced,
                    source = AppActorDiagnosticsDataSource.Network,
                )
            }
        }
    }

    private suspend fun runStartupDeadLetterRetry(runtimeState: AppActorRuntimeState) {
        timedPhase(runtimeState, "dead_letter_retry", AppActorDebugCategory.Purchase) {
            host.retryDeadLetteredItems(runtimeState)
        }
    }

    private suspend fun runStartupCustomerRefresh(runtimeState: AppActorRuntimeState) {
        timedPhase(runtimeState, "customer_refresh", AppActorDebugCategory.Network) {
            val snapshot = host.captureOperationSnapshot(
                resolveAppUserId = true,
                awaitBootstrapCompletion = false,
            )
            if (snapshot.runtime.sessionId != runtimeState.sessionId) {
                return@timedPhase
            }
            val (info, source) = host.fetchCustomerInfo(snapshot)
            if (host.persistCustomerInfoIfCurrent(snapshot, info)) {
                host.publishCustomerInfoIfCurrent(
                    snapshot = snapshot,
                    info = info,
                    source = source,
                )
            }
        }
    }

    private suspend fun warmStoreState(runtimeState: AppActorRuntimeState) {
        val startNanos = System.nanoTime()
        runCatching { runtimeState.storeAdapter.connect() }
            .onSuccess {
                host.emitDebugEvent(
                    runtimeSessionId = runtimeState.sessionId,
                    category = AppActorDebugCategory.Billing,
                    level = AppActorLogLevel.Info,
                    name = "billing_connected",
                    message = "Billing store state warmed.",
                    attributes = debugAttributes(
                        "capabilities" to runtimeState.storeAdapter.currentCapabilities().joinToString(",") { it.name },
                        "storefront" to runtimeState.storeAdapter.currentStorefront()?.countryCode,
                        "duration_ms" to elapsedMs(startNanos).toString(),
                    ),
                )
            }
            .onFailure { throwable ->
                throwIfCancellation(throwable)
                AppActorLogger.debug("Initial billing warmup failed: ${throwable.message}")
                host.emitDebugEvent(
                    runtimeSessionId = runtimeState.sessionId,
                    category = AppActorDebugCategory.Billing,
                    level = AppActorLogLevel.Warn,
                    name = "billing_warmup_failed",
                    message = "Initial billing warmup failed.",
                    attributes = debugAttributes(
                        "reason" to throwable.message,
                        "duration_ms" to elapsedMs(startNanos).toString(),
                    ),
                )
            }
    }

    private suspend fun awaitIdentityReadyIfNeeded(identityReadyJob: Job?) {
        awaitJobIfNeeded(identityReadyJob)
    }

    private suspend fun awaitBootstrapCompletionIfNeeded(bootstrapCompletionJob: Job?) {
        awaitJobIfNeeded(bootstrapCompletionJob)
    }

    private suspend fun awaitJobIfNeeded(job: Job?) {
        if (job == null) {
            return
        }
        if (currentCoroutineContext()[Job] !== job) {
            job.join()
        }
    }

    private fun elapsedMs(startNanos: Long): Long =
        maxOf(0L, (System.nanoTime() - startNanos) / 1_000_000)

}
