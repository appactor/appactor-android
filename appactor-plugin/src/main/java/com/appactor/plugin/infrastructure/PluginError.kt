package com.appactor.plugin.infrastructure

import com.appactor.android.models.AppActorError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PluginError(
    val code: Int,
    val message: String,
    val detail: String = "",
    @SerialName("request_id") val requestId: String? = null,
    val scope: String? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Double? = null,
) {
    internal companion object {
        // Plugin internal codes (1xxx)
        const val ENCODING_FAILED: Int = 1001
        const val DECODING_FAILED: Int = 1002
        const val UNKNOWN_METHOD: Int = 1003
        const val MISSING_CONTEXT: Int = 1004
        const val MISSING_ACTIVITY: Int = 1005

        // SDK codes (2xxx) — matches iOS AppActorPluginError code space
        const val SDK_NOT_CONFIGURED: Int = 2001
        const val SDK_ALREADY_CONFIGURED: Int = 2002
        const val SDK_VALIDATION: Int = 2003
        const val SDK_NOT_AVAILABLE: Int = 2004
        const val SDK_NETWORK: Int = 2005
        const val SDK_DECODING: Int = 2006
        const val SDK_SERVER: Int = 2007
        const val SDK_STORE_PRODUCTS_MISSING: Int = 2008
        const val SDK_CUSTOMER_NOT_FOUND: Int = 2009
        const val SDK_PURCHASE_FAILED: Int = 2010
        const val SDK_RECEIPT_POST_FAILED: Int = 2011
        const val SDK_RECEIPT_QUEUED_FOR_RETRY: Int = 2012
        const val SDK_PURCHASE_IN_PROGRESS: Int = 2013
        const val SDK_PRODUCT_NOT_AVAILABLE: Int = 2014
        const val SDK_SIGNATURE_VERIFICATION: Int = 2015
        const val SDK_INVALID_OFFER: Int = 2016
        const val SDK_PURCHASE_INELIGIBLE: Int = 2017
        const val SDK_UNKNOWN: Int = 2099

        fun fromException(e: Exception): PluginError {
            val error = e as? AppActorError ?: return PluginError(
                code = SDK_UNKNOWN,
                message = e.message ?: "Unknown error",
                detail = e.javaClass.simpleName,
            )
            return error.toPluginError()
        }
    }
}

internal fun AppActorError.toPluginError(): PluginError {
    val code = when (this) {
        is AppActorError.NotConfigured -> PluginError.SDK_NOT_CONFIGURED
        is AppActorError.AlreadyConfigured -> PluginError.SDK_ALREADY_CONFIGURED
        is AppActorError.InvalidConfiguration -> PluginError.SDK_VALIDATION
        is AppActorError.NotImplementedYet -> PluginError.SDK_NOT_AVAILABLE
        is AppActorError.Network -> PluginError.SDK_NETWORK
        is AppActorError.Decoding -> PluginError.SDK_DECODING
        is AppActorError.Server -> PluginError.SDK_SERVER
        is AppActorError.StoreProductsMissing -> PluginError.SDK_STORE_PRODUCTS_MISSING
        is AppActorError.CustomerNotFound -> PluginError.SDK_CUSTOMER_NOT_FOUND
        is AppActorError.PurchaseFailed -> PluginError.SDK_PURCHASE_FAILED
        is AppActorError.ReceiptPostFailed -> PluginError.SDK_RECEIPT_POST_FAILED
        is AppActorError.ReceiptQueuedForRetry -> PluginError.SDK_RECEIPT_QUEUED_FOR_RETRY
        is AppActorError.PurchaseAlreadyInProgress -> PluginError.SDK_PURCHASE_IN_PROGRESS
        is AppActorError.ProductNotAvailable -> PluginError.SDK_PRODUCT_NOT_AVAILABLE
        is AppActorError.InvalidOffer -> PluginError.SDK_INVALID_OFFER
        is AppActorError.PurchaseIneligible -> PluginError.SDK_PURCHASE_INELIGIBLE
        is AppActorError.SignatureVerificationFailed,
        is AppActorError.SignatureTimestampOutOfRange,
        is AppActorError.SignatureMissing,
        is AppActorError.NonceMismatch,
        is AppActorError.IntermediateCertInvalid,
        is AppActorError.IntermediateKeyExpired -> PluginError.SDK_SIGNATURE_VERIFICATION
        is AppActorError.Unknown -> PluginError.SDK_UNKNOWN
    }
    val detailParts = buildList {
        cause?.message?.let { add(it) }
        if (this@toPluginError is AppActorError.Server) {
            (this@toPluginError as AppActorError.Server).statusCode?.let { add("httpStatus=$it") }
        }
        if (isTransient) add("transient=true")
    }
    return PluginError(code = code, message = message, detail = detailParts.joinToString(", "))
}
