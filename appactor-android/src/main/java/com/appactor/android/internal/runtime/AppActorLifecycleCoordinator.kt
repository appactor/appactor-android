package com.appactor.android.internal.runtime

import android.app.Application
import com.appactor.android.api.AppActorLifecycleCallbacks
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorDebugCategory
import com.appactor.android.models.AppActorLogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface AppActorLifecycleCoordinatorHost {
    fun currentRuntimeSnapshot(): AppActorRuntimeState?

    suspend fun awaitStartupIfNeeded(runtimeState: AppActorRuntimeState)

    suspend fun drainReceipts(runtimeState: AppActorRuntimeState)

    suspend fun flushPendingAttributes(runtimeState: AppActorRuntimeState)

    suspend fun refreshCustomerInfoIfNeeded(runtimeState: AppActorRuntimeState)

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

internal class AppActorLifecycleCoordinator(
    private val host: AppActorLifecycleCoordinatorHost,
) {

    private companion object {
        const val STALENESS_CHECK_INTERVAL_MS = 5L * 60 * 1_000
    }

    private val stalenessTimerLock = Any()
    private var stalenessTimerJob: Job? = null

    fun registerLifecycleCallbacksIfNeeded(runtimeState: AppActorRuntimeState): AppActorLifecycleCallbacks? {
        val application = runtimeState.configuration.applicationContext as? Application ?: return null
        if (!runtimeState.configuration.options.shouldObserveLifecycle()) {
            return null
        }

        runtimeState.offeringsManager.setBackground(false)
        val callbacks = AppActorLifecycleCallbacks(
            onForeground = ::handleForegroundTransition,
            onBackground = ::handleBackgroundTransition,
        )
        application.registerActivityLifecycleCallbacks(callbacks)
        return callbacks
    }

    private fun handleForegroundTransition() {
        val currentRuntime = host.currentRuntimeSnapshot() ?: return
        currentRuntime.offeringsManager.setBackground(false)
        host.emitDebugEvent(
            runtimeSessionId = currentRuntime.sessionId,
            category = AppActorDebugCategory.Lifecycle,
            level = AppActorLogLevel.Debug,
            name = "foreground",
            message = "Application entered foreground.",
        )
        currentRuntime.scope.launch {
            try {
                host.awaitStartupIfNeeded(currentRuntime)
            } catch (_: CancellationException) {
                return@launch
            }
            try {
                host.drainReceipts(currentRuntime)
            } catch (throwable: Throwable) {
                throwIfCancellation(throwable)
                AppActorLogger.debug("Foreground drain failed: ${throwable.message}")
                host.emitDebugEvent(
                    runtimeSessionId = currentRuntime.sessionId,
                    category = AppActorDebugCategory.Purchase,
                    level = AppActorLogLevel.Warn,
                    name = "foreground_drain_failed",
                    message = "Foreground receipt drain failed.",
                    attributes = debugAttributes("reason" to throwable.message),
                )
            }
            try {
                host.flushPendingAttributes(currentRuntime)
            } catch (throwable: Throwable) {
                throwIfCancellation(throwable)
                AppActorLogger.debug("Foreground attribute flush failed: ${throwable.message}")
                host.emitDebugEvent(
                    runtimeSessionId = currentRuntime.sessionId,
                    category = AppActorDebugCategory.Network,
                    level = AppActorLogLevel.Warn,
                    name = "foreground_attribute_flush_failed",
                    message = "Foreground customer attribute flush failed.",
                    attributes = debugAttributes("reason" to throwable.message),
                )
            }
            try {
                host.refreshCustomerInfoIfNeeded(currentRuntime)
            } catch (throwable: Throwable) {
                throwIfCancellation(throwable)
                AppActorLogger.debug("Foreground customer refresh failed: ${throwable.message}")
                host.emitDebugEvent(
                    runtimeSessionId = currentRuntime.sessionId,
                    category = AppActorDebugCategory.Network,
                    level = AppActorLogLevel.Warn,
                    name = "foreground_customer_refresh_failed",
                    message = "Foreground customer refresh failed.",
                    attributes = debugAttributes("reason" to throwable.message),
                )
            }
        }

        synchronized(stalenessTimerLock) {
            stalenessTimerJob?.cancel()
            stalenessTimerJob = currentRuntime.scope.launch {
                while (isActive) {
                    delay(STALENESS_CHECK_INTERVAL_MS)
                    try {
                        host.refreshCustomerInfoIfNeeded(currentRuntime)
                    } catch (throwable: Throwable) {
                        throwIfCancellation(throwable)
                        AppActorLogger.debug("Staleness refresh failed: ${throwable.message}")
                    }
                }
            }
        }
    }

    private fun handleBackgroundTransition() {
        synchronized(stalenessTimerLock) {
            stalenessTimerJob?.cancel()
            stalenessTimerJob = null
        }
        host.currentRuntimeSnapshot()?.let { currentRuntime ->
            currentRuntime.offeringsManager.setBackground(true)
            host.emitDebugEvent(
                runtimeSessionId = currentRuntime.sessionId,
                category = AppActorDebugCategory.Lifecycle,
                level = AppActorLogLevel.Debug,
                name = "background",
                message = "Application entered background.",
            )
        }
    }

}
