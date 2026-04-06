package com.appactor.android.models

public sealed class AppActorError(
    override val message: String,
    override val cause: Throwable? = null,
) : IllegalStateException(message, cause) {

    public data object NotConfigured : AppActorError(
        message = "AppActor is not configured. Call AppActor.configure(...) first."
    )

    public data object AlreadyConfigured : AppActorError(
        message = "AppActor is already configured. Call reset() before configuring again."
    )

    public data class InvalidConfiguration(
        val reason: String,
    ) : AppActorError(message = reason)

    public data class NotImplementedYet(
        val operation: String,
    ) : AppActorError(message = "$operation is not implemented yet.")

    public data class Network(
        val description: String,
        val throwable: Throwable? = null,
    ) : AppActorError(message = description, cause = throwable)

    public data class Server(
        val description: String,
        val statusCode: Int? = null,
        val throwable: Throwable? = null,
    ) : AppActorError(message = description, cause = throwable)

    public data class PurchaseFailed(
        val description: String,
        val throwable: Throwable? = null,
    ) : AppActorError(message = description, cause = throwable)

    public data class ReceiptPostFailed(
        val description: String,
        val throwable: Throwable? = null,
    ) : AppActorError(message = description, cause = throwable)

    public data class Decoding(
        val description: String,
        val throwable: Throwable? = null,
    ) : AppActorError(message = description, cause = throwable)

    public data class StoreProductsMissing(
        val description: String,
    ) : AppActorError(message = description)

    public data class CustomerNotFound(
        val appUserId: String,
        val description: String = "Customer not found: $appUserId",
    ) : AppActorError(message = description)

    public data class ReceiptQueuedForRetry(
        val description: String,
        val throwable: Throwable? = null,
    ) : AppActorError(message = description, cause = throwable)

    public data object PurchaseAlreadyInProgress : AppActorError(
        message = "A purchase is already in progress."
    )

    public data class ProductNotAvailable(
        val description: String,
    ) : AppActorError(message = description)

    public data class InvalidOffer(
        val description: String,
        override val cause: Throwable? = null,
    ) : AppActorError(message = description, cause = cause)

    public data class PurchaseIneligible(
        val description: String,
        override val cause: Throwable? = null,
    ) : AppActorError(message = description, cause = cause)

    public data class SignatureVerificationFailed(
        val description: String,
        val throwable: Throwable? = null,
    ) : AppActorError(message = description, cause = throwable)

    public data class SignatureTimestampOutOfRange(
        val description: String,
        override val cause: Throwable? = null,
    ) : AppActorError(message = description, cause = cause)

    public data class SignatureMissing(
        val description: String,
        override val cause: Throwable? = null,
    ) : AppActorError(message = description, cause = cause)

    public data class NonceMismatch(
        val description: String,
        override val cause: Throwable? = null,
    ) : AppActorError(message = description, cause = cause)

    public data class IntermediateCertInvalid(
        val description: String,
        override val cause: Throwable? = null,
    ) : AppActorError(message = description, cause = cause)

    public data class IntermediateKeyExpired(
        val description: String,
        override val cause: Throwable? = null,
    ) : AppActorError(message = description, cause = cause)

    public data class Unknown(
        val description: String,
        val throwable: Throwable? = null,
    ) : AppActorError(message = description, cause = throwable)

    public val isTransient: Boolean
        get() = when (this) {
            is Network, is ReceiptQueuedForRetry -> true
            is Server -> statusCode?.let { it == 429 || it >= 500 } ?: false
            else -> false
        }
}
