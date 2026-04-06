package com.appactor.plugin.events

import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.models.toBridgeEvent
import com.appactor.plugin.AppActorPlugin
import com.appactor.plugin.encoding.BridgeReceiptEventSurrogate
import com.appactor.plugin.encoding.CustomerInfoSurrogate
import com.appactor.plugin.encoding.DeferredPurchaseResolvedSurrogate
import com.appactor.plugin.encoding.SdkLogSurrogate
import com.appactor.plugin.infrastructure.PluginCoder
import kotlinx.serialization.KSerializer

internal object PluginEventBridge {

    private var listening = false
    private var previousCustomerInfoCallback: ((AppActorCustomerInfo) -> Unit)? = null
    private var previousReceiptPipelineCallback: ((AppActorReceiptPipelineEvent) -> Unit)? = null
    private var previousDeferredPurchaseCallback: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)? = null

    fun startListening() {
        if (listening) return
        listening = true

        previousCustomerInfoCallback = AppActor.onCustomerInfoChanged
        previousReceiptPipelineCallback = AppActor.onReceiptPipelineEvent

        AppActor.onCustomerInfoChanged = { customerInfo ->
            previousCustomerInfoCallback?.invoke(customerInfo)
            emit("customer_info_updated", CustomerInfoSurrogate.serializer(), CustomerInfoSurrogate(customerInfo))
        }

        AppActor.onReceiptPipelineEvent = { event ->
            previousReceiptPipelineCallback?.invoke(event)
            val bridgeEvent = event.toBridgeEvent()
            emit("receipt_pipeline_event", BridgeReceiptEventSurrogate.serializer(), BridgeReceiptEventSurrogate(bridgeEvent))
        }

        previousDeferredPurchaseCallback = AppActor.onDeferredPurchaseResolved
        AppActor.onDeferredPurchaseResolved = { productId, customerInfo ->
            previousDeferredPurchaseCallback?.invoke(productId, customerInfo)
            emit(
                "deferred_purchase_resolved",
                DeferredPurchaseResolvedSurrogate.serializer(),
                DeferredPurchaseResolvedSurrogate(productId = productId, customerInfo = CustomerInfoSurrogate(customerInfo)),
            )
        }

        // Note: Unlike onCustomerInfoChanged/onReceiptPipelineEvent, the log handler
        // has no public getter, so we cannot save/restore a previous handler. The plugin
        // is expected to be the sole consumer of setLogHandler.
        AppActor.setLogHandler { level, message, category, timestamp ->
            emit("sdk_log", SdkLogSurrogate.serializer(), SdkLogSurrogate(level, message, category, timestamp))
        }
    }

    fun stopListening() {
        if (!listening) return
        listening = false

        AppActor.setLogHandler(null)
        AppActor.onCustomerInfoChanged = previousCustomerInfoCallback
        AppActor.onReceiptPipelineEvent = previousReceiptPipelineCallback
        AppActor.onDeferredPurchaseResolved = previousDeferredPurchaseCallback
        previousCustomerInfoCallback = null
        previousReceiptPipelineCallback = null
        previousDeferredPurchaseCallback = null
    }

    private fun <T> emit(eventName: String, serializer: KSerializer<T>, value: T) {
        val json = try {
            PluginCoder.json.encodeToString(serializer, value)
        } catch (_: Exception) {
            "{}"
        }
        AppActorPlugin.eventListener?.onEvent(eventName, json)
    }
}
