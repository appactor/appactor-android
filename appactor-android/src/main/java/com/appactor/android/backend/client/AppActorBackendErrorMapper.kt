package com.appactor.android.backend.client

import com.appactor.android.models.AppActorError

internal fun Throwable.toAppActorError(
    defaultMessage: String = "AppActor request failed.",
): AppActorError {
    return when (this) {
        is AppActorError -> this
        is AppActorBackendException.Network -> AppActorError.Network(description, throwable)
        is AppActorBackendException.Decoding -> AppActorError.Unknown(description, throwable)
        is AppActorBackendException.Signature -> when (result) {
            AppActorResponseSignatureVerifier.VerificationResult.SignatureMissing ->
                AppActorError.SignatureMissing(message ?: defaultMessage, this)
            AppActorResponseSignatureVerifier.VerificationResult.TimestampOutOfRange ->
                AppActorError.SignatureTimestampOutOfRange(message ?: defaultMessage, this)
            AppActorResponseSignatureVerifier.VerificationResult.NonceMismatch ->
                AppActorError.NonceMismatch(message ?: defaultMessage, this)
            AppActorResponseSignatureVerifier.VerificationResult.IntermediateCertInvalid ->
                AppActorError.IntermediateCertInvalid(message ?: defaultMessage, this)
            AppActorResponseSignatureVerifier.VerificationResult.IntermediateKeyExpired ->
                AppActorError.IntermediateKeyExpired(message ?: defaultMessage, this)
            else -> AppActorError.SignatureVerificationFailed(message ?: defaultMessage, this)
        }
        is AppActorBackendException.CustomerNotFound -> AppActorError.CustomerNotFound(
            appUserId = appUserId,
            description = message ?: defaultMessage,
        )
        is AppActorBackendException.Http -> {
            if (statusCode >= 500 || statusCode == 429) {
                AppActorError.Server(
                    description = message ?: defaultMessage,
                    statusCode = statusCode,
                    scope = error?.scope,
                    retryAfterSeconds = retryAfterSeconds,
                    throwable = this,
                )
            } else {
                AppActorError.Unknown(message ?: defaultMessage, this)
            }
        }

        else -> AppActorError.Unknown(message ?: defaultMessage, this)
    }
}
