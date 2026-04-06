package com.appactor.android.models

public data class AppActorBridgeError(
    public val code: String,
    public val message: String,
    public val isTransient: Boolean,
    public val statusCode: Int? = null,
    public val debugMessage: String? = null,
) {
    public companion object {
        public const val CODE_NOT_CONFIGURED: String = "NOT_CONFIGURED"
        public const val CODE_ALREADY_CONFIGURED: String = "ALREADY_CONFIGURED"
        public const val CODE_VALIDATION: String = "VALIDATION"
        public const val CODE_NOT_AVAILABLE: String = "NOT_AVAILABLE"
        public const val CODE_NETWORK: String = "NETWORK"
        public const val CODE_DECODING: String = "DECODING"
        public const val CODE_SERVER: String = "SERVER"
        public const val CODE_STORE_PRODUCTS_MISSING: String = "STORE_PRODUCTS_MISSING"
        public const val CODE_CUSTOMER_NOT_FOUND: String = "CUSTOMER_NOT_FOUND"
        public const val CODE_PURCHASE_FAILED: String = "PURCHASE_FAILED"
        public const val CODE_RECEIPT_POST_FAILED: String = "RECEIPT_POST_FAILED"
        public const val CODE_RECEIPT_QUEUED_FOR_RETRY: String = "RECEIPT_QUEUED_FOR_RETRY"
        public const val CODE_PURCHASE_ALREADY_IN_PROGRESS: String = "PURCHASE_ALREADY_IN_PROGRESS"
        public const val CODE_PRODUCT_NOT_AVAILABLE: String = "PRODUCT_NOT_AVAILABLE"
        public const val CODE_SIGNATURE_VERIFICATION_FAILED: String = "SIGNATURE_VERIFICATION_FAILED"
        public const val CODE_INVALID_OFFER: String = "INVALID_OFFER"
        public const val CODE_PURCHASE_INELIGIBLE: String = "PURCHASE_INELIGIBLE"
        public const val CODE_UNKNOWN: String = "UNKNOWN"
    }
}

public fun interface AppActorBridgeErrorCallback {
    public fun onError(error: AppActorBridgeError)
}

internal fun AppActorError.toBridgeError(): AppActorBridgeError {
    val debugMessage = cause?.message
    return when (this) {
        AppActorError.NotConfigured -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_NOT_CONFIGURED,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        AppActorError.AlreadyConfigured -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_ALREADY_CONFIGURED,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.InvalidConfiguration -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_VALIDATION,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.NotImplementedYet -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_NOT_AVAILABLE,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.Network -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_NETWORK,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.Server -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_SERVER,
            message = message,
            isTransient = isTransient,
            statusCode = statusCode,
            debugMessage = debugMessage,
        )

        is AppActorError.PurchaseFailed -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_PURCHASE_FAILED,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.Decoding -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_DECODING,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.StoreProductsMissing -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_STORE_PRODUCTS_MISSING,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.CustomerNotFound -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_CUSTOMER_NOT_FOUND,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.ReceiptPostFailed -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_RECEIPT_POST_FAILED,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.ReceiptQueuedForRetry -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_RECEIPT_QUEUED_FOR_RETRY,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        AppActorError.PurchaseAlreadyInProgress -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_PURCHASE_ALREADY_IN_PROGRESS,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.ProductNotAvailable -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_PRODUCT_NOT_AVAILABLE,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.InvalidOffer -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_INVALID_OFFER,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.PurchaseIneligible -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_PURCHASE_INELIGIBLE,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.SignatureVerificationFailed,
        is AppActorError.SignatureTimestampOutOfRange,
        is AppActorError.SignatureMissing,
        is AppActorError.NonceMismatch,
        is AppActorError.IntermediateCertInvalid,
        is AppActorError.IntermediateKeyExpired -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_SIGNATURE_VERIFICATION_FAILED,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )

        is AppActorError.Unknown -> AppActorBridgeError(
            code = AppActorBridgeError.CODE_UNKNOWN,
            message = message,
            isTransient = isTransient,
            debugMessage = debugMessage,
        )
    }
}

internal fun AppActorBridgeError.toAppActorError(): AppActorError {
    return when (code) {
        AppActorBridgeError.CODE_NOT_CONFIGURED -> AppActorError.NotConfigured
        AppActorBridgeError.CODE_ALREADY_CONFIGURED -> AppActorError.AlreadyConfigured
        AppActorBridgeError.CODE_VALIDATION -> AppActorError.InvalidConfiguration(message)
        AppActorBridgeError.CODE_NOT_AVAILABLE -> AppActorError.NotImplementedYet(message)
        AppActorBridgeError.CODE_NETWORK -> AppActorError.Network(message)
        AppActorBridgeError.CODE_DECODING -> AppActorError.Decoding(message)
        AppActorBridgeError.CODE_SERVER -> AppActorError.Server(message, statusCode)
        AppActorBridgeError.CODE_STORE_PRODUCTS_MISSING -> AppActorError.StoreProductsMissing(message)
        AppActorBridgeError.CODE_CUSTOMER_NOT_FOUND -> AppActorError.CustomerNotFound(appUserId = "", description = message)
        AppActorBridgeError.CODE_PURCHASE_FAILED -> AppActorError.PurchaseFailed(message)
        AppActorBridgeError.CODE_RECEIPT_POST_FAILED -> AppActorError.ReceiptPostFailed(message)
        AppActorBridgeError.CODE_RECEIPT_QUEUED_FOR_RETRY -> AppActorError.ReceiptQueuedForRetry(message)
        AppActorBridgeError.CODE_PURCHASE_ALREADY_IN_PROGRESS -> AppActorError.PurchaseAlreadyInProgress
        AppActorBridgeError.CODE_PRODUCT_NOT_AVAILABLE -> AppActorError.ProductNotAvailable(message)
        AppActorBridgeError.CODE_INVALID_OFFER -> AppActorError.InvalidOffer(message)
        AppActorBridgeError.CODE_PURCHASE_INELIGIBLE -> AppActorError.PurchaseIneligible(message)
        AppActorBridgeError.CODE_SIGNATURE_VERIFICATION_FAILED -> AppActorError.SignatureVerificationFailed(message)
        else -> AppActorError.Unknown(message)
    }
}
