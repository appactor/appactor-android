package com.appactor.android.backend.client

import com.appactor.android.backend.dto.AppActorBackendErrorDTO
import kotlinx.serialization.json.Json

internal object AppActorBackendJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }
}

internal data class AppActorResponseSignatureHeaders(
    val requestNonce: String? = null,
    val signature: String? = null,
    val signatureTimestamp: String? = null,
) {
    companion object {
        fun fromHeaders(headers: okhttp3.Headers): AppActorResponseSignatureHeaders? {
            val requestNonce = headers["X-AppActor-Request-Nonce"]
            val signature = headers["X-AppActor-Signature"]
            val signatureTimestamp = headers["X-AppActor-Signature-Timestamp"]
            if (requestNonce == null && signature == null && signatureTimestamp == null) {
                return null
            }
            return AppActorResponseSignatureHeaders(
                requestNonce = requestNonce,
                signature = signature,
                signatureTimestamp = signatureTimestamp,
            )
        }
    }
}

internal data class AppActorBackendHttpResponse<T>(
    val body: T?,
    val statusCode: Int,
    val requestId: String? = null,
    val eTag: String? = null,
    val isNotModified: Boolean = false,
    val signatureHeaders: AppActorResponseSignatureHeaders? = null,
    val signatureVerified: Boolean = false,
)

internal sealed class AppActorBackendException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    data class Network(
        val description: String,
        val throwable: Throwable? = null,
    ) : AppActorBackendException(description, throwable)

    data class Decoding(
        val description: String,
        val requestId: String? = null,
        val throwable: Throwable? = null,
    ) : AppActorBackendException(description, throwable)

    data class Signature(
        val result: AppActorResponseSignatureVerifier.VerificationResult,
        val requestId: String? = null,
    ) : AppActorBackendException(
        message = "Response signature verification failed: $result",
    )

    data class Http(
        val statusCode: Int,
        val requestId: String? = null,
        val error: AppActorBackendErrorDTO? = null,
        val rawBodyLength: Int? = null,
        val retryAfterSeconds: Double? = null,
    ) : AppActorBackendException(
        message = buildString {
            append("HTTP ").append(statusCode)
            error?.code?.let { append(" (").append(it).append(")") }
            error?.message?.let { append(": ").append(it) }
        }
    )

    data class CustomerNotFound(
        val appUserId: String,
        val requestId: String? = null,
    ) : AppActorBackendException(
        message = "Customer not found for appUserId=$appUserId",
    )
}
