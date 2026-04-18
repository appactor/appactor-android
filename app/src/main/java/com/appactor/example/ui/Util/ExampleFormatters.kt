package com.appactor.example.ui

import com.appactor.android.models.AppActorConfigValue
import com.appactor.android.models.AppActorReceiptPipelineEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun AppActorConfigValue.prettyDisplay(): String {
    return when {
        boolValue != null -> boolValue.toString()
        stringValue != null -> stringValue.orEmpty()
        intValue != null -> intValue.toString()
        doubleValue != null -> doubleValue.toString()
        listValue != null -> listValue!!.joinToString(prefix = "[", postfix = "]") { it.prettyDisplay() }
        mapValue != null -> mapValue!!.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=${value.prettyDisplay()}" }
        isNull -> "null"
        else -> toString()
    }
}

fun receiptEventCopy(event: AppActorReceiptPipelineEvent): String {
    return when (event) {
        is AppActorReceiptPipelineEvent.PostedOk ->
            "receipt posted -> ${event.productId} (${event.requestId ?: "no-request-id"})"

        is AppActorReceiptPipelineEvent.DeferredWaitingForIdentity ->
            "receipt waiting for identity -> ${event.productId}"

        is AppActorReceiptPipelineEvent.RetryScheduled ->
            "receipt retry -> ${event.productId} (#${event.retryCount})"

        is AppActorReceiptPipelineEvent.PermanentlyRejected ->
            "receipt rejected -> ${event.productId} (${event.code ?: "unknown"})"

        is AppActorReceiptPipelineEvent.DeadLettered ->
            "receipt dead-lettered -> ${event.productId} (#${event.retryCount})"

        is AppActorReceiptPipelineEvent.DuplicateSkipped ->
            "receipt duplicate skipped -> ${event.productId}"
    }
}

fun receiptEventTone(event: AppActorReceiptPipelineEvent): ExampleLogTone {
    return when (event) {
        is AppActorReceiptPipelineEvent.PostedOk -> ExampleLogTone.Success
        is AppActorReceiptPipelineEvent.DeferredWaitingForIdentity -> ExampleLogTone.Warn
        is AppActorReceiptPipelineEvent.RetryScheduled -> ExampleLogTone.Warn
        is AppActorReceiptPipelineEvent.PermanentlyRejected -> ExampleLogTone.Error
        is AppActorReceiptPipelineEvent.DeadLettered -> ExampleLogTone.Error
        is AppActorReceiptPipelineEvent.DuplicateSkipped -> ExampleLogTone.Info
    }
}

fun timestamp(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
