package com.appactor.android.internal.runtime

import com.appactor.android.api.AppActorLifecycleCallbacks
import com.appactor.android.backend.client.AppActorHttpBackendClient
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.cache.AppActorCustomerCacheStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorExperimentCacheStore
import com.appactor.android.cache.AppActorOfflineProductCatalogStore
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.cache.AppActorRemoteConfigsCacheStore
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.managers.AppActorAttributesManager
import com.appactor.android.managers.AppActorExperimentManager
import com.appactor.android.managers.AppActorOfferingsManager
import com.appactor.android.managers.AppActorRemoteConfigManager
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.pipeline.AppActorPaymentProcessor
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorPostedLedgerStore
import com.appactor.android.storage.AppActorReceiptQueueStore
import com.appactor.android.models.AppActorError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AppActorRuntimeState(
    val sessionId: Long,
    val configuration: AppActorConfiguration,
    val identityStore: AppActorIdentityStore,
    val eTagManager: AppActorETagManager,
    val backendClient: AppActorHttpBackendClient,
    val storeAdapter: AppActorStoreAdapter,
    val offeringsCacheStore: AppActorOfferingsCacheStore,
    val offlineProductCatalogStore: AppActorOfflineProductCatalogStore,
    val customerCacheStore: AppActorCustomerCacheStore,
    val receiptQueueStore: AppActorReceiptQueueStore,
    val postedLedgerStore: AppActorPostedLedgerStore,
    val offeringsManager: AppActorOfferingsManager,
    val customerManager: AppActorCustomerManager,
    val attributesManager: AppActorAttributesManager,
    val paymentProcessor: AppActorPaymentProcessor,
    val scope: CoroutineScope,
    val bootstrapCompletionJob: kotlinx.coroutines.Job? = null,
    val purchaseUpdatesJob: kotlinx.coroutines.Job? = null,
    val lifecycleCallbacks: AppActorLifecycleCallbacks? = null,
    val remoteConfigManager: AppActorRemoteConfigManager,
    val experimentManager: AppActorExperimentManager,
    val onCustomerInfoChanged: ((AppActorCustomerInfo) -> Unit)? = null,
    val onReceiptPipelineEvent: ((AppActorReceiptPipelineEvent) -> Unit)? = null,
    val onDeferredPurchaseResolved: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)? = null,
    val lastCustomerInfo: AppActorCustomerInfo = AppActorCustomerInfo.empty,
    // The last info handed to onCustomerInfoChanged since the identity last changed.
    val notifiedCustomerInfo: AppActorCustomerInfo? = null,
    val lastCustomerInfoSource: AppActorDiagnosticsDataSource? = null,
    val lastOfferingsSource: AppActorDiagnosticsDataSource? = null,
    val lastRemoteConfigSource: AppActorDiagnosticsDataSource? = null,
)

internal data class AppActorOperationSnapshot(
    val runtime: AppActorRuntimeState,
    val epoch: Long,
    val appUserId: String,
)

internal data class AppActorCallbackState(
    val onCustomerInfoChanged: ((AppActorCustomerInfo) -> Unit)? = null,
    val onReceiptPipelineEvent: ((AppActorReceiptPipelineEvent) -> Unit)? = null,
    val onDeferredPurchaseResolved: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)? = null,
)

internal data class AppActorStartupHandles(
    val bootstrapCompletionJob: kotlinx.coroutines.Job? = null,
    val purchaseUpdatesJob: kotlinx.coroutines.Job? = null,
)

/**
 * Backstop for the SDK's own background scopes: an exception that escapes a launch is logged
 * instead of crashing the host app. Each launch should still catch what it expects.
 */
internal val appActorBackgroundExceptionHandler: CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        AppActorLogger.error("Unexpected error in an AppActor background task: $throwable", throwable)
    }

/**
 * Runs a fetch several callers share in this scope, completes [request] with its outcome, then
 * runs [cleanup]. Not in the first caller's coroutine: that caller being cancelled would fail the
 * others, or answer the cancelled caller from a cache. Started ATOMIC so [request] completes even
 * when reset() cancelled the scope before the launch began; a cancelled scope fails it with
 * NotConfigured, since the callers awaiting it were not cancelled themselves.
 */
internal fun <T> CoroutineScope.launchSharedRequest(
    request: CompletableDeferred<T>,
    cleanup: suspend () -> Unit,
    block: suspend () -> T,
) {
    launch(start = CoroutineStart.ATOMIC) {
        val result = try {
            Result.success(block())
        } catch (throwable: Throwable) {
            Result.failure(if (throwable is CancellationException) AppActorError.NotConfigured else throwable)
        }
        // Cleaned up first, so a caller that calls again once this returns starts a new request.
        try {
            withContext(NonCancellable) { cleanup() }
        } finally {
            request.completeWith(result)
        }
    }
}

internal fun throwIfCancellation(throwable: Throwable) {
    if (throwable is kotlinx.coroutines.CancellationException) {
        throw throwable
    }
}

internal fun debugAttributes(vararg pairs: Pair<String, String?>): Map<String, String> {
    return pairs.mapNotNull { (key, value) ->
        value?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap(linkedMapOf())
}
