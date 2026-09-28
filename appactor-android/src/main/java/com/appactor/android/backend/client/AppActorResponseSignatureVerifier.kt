package com.appactor.android.backend.client

import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.util.Bytes
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max

internal object AppActorResponseSignatureVerifier {

    internal const val v1PublicKeyBase64: String = "ucf5p+d5KfS0hZDKe/GFsDMumPpJtwdDHQFB9ymfMlA="
    internal const val rootPublicKeyBase64: String = "T7+gp+5ABLXlyTpnrWWVanJJcpuijExFBn5n/Ek/I1Q="

    private const val maxTimestampDriftSeconds: Double = 300.0
    private const val v2BlobSize: Int = 180
    private const val v1SignatureSize: Int = 64
    private const val certHeaderSize: Int = 52
    private val certPrefix: ByteArray = "appactor-cert-v1".toByteArray(Charsets.UTF_8)

    internal enum class VerificationResult {
        Success,
        SignatureMissing,
        SigningNotSupported,
        SignatureInvalid,
        TimestampOutOfRange,
        NonceMismatch,
        PublicKeyUnavailable,
        IntermediateCertInvalid,
        IntermediateKeyExpired,
    }

    /**
     * [requestBinding] is the request's [requestBinding] when it sent
     * `X-AppActor-Signature-Binding: request`, and null when it didn't.
     */
    internal fun verify(
        headers: AppActorResponseSignatureHeaders?,
        body: String,
        sentNonce: String?,
        apiKey: String,
        requestPath: String,
        eTag: String,
        requestBinding: String?,
    ): VerificationResult {
        return verify(
            headers = headers,
            body = body,
            sentNonce = sentNonce,
            apiKey = apiKey,
            requestPath = requestPath,
            eTag = eTag,
            requestBinding = requestBinding,
            v1PublicKey = decodeBase64(v1PublicKeyBase64),
            rootPublicKey = decodeBase64(rootPublicKeyBase64),
            nowEpochSeconds = System.currentTimeMillis() / 1000.0,
        )
    }

    internal fun verify(
        headers: AppActorResponseSignatureHeaders?,
        body: String,
        sentNonce: String?,
        apiKey: String,
        requestPath: String,
        eTag: String,
        v1PublicKey: ByteArray?,
        rootPublicKey: ByteArray?,
        nowEpochSeconds: Double,
        requestBinding: String? = null,
    ): VerificationResult {
        if (sentNonce != null) {
            return verifyNonceBased(headers, body, sentNonce, apiKey, requestBinding, v1PublicKey, rootPublicKey, nowEpochSeconds)
        }
        return verifySaltBased(headers, body, apiKey, requestPath, eTag, v1PublicKey, rootPublicKey, nowEpochSeconds)
    }

    // ── Nonce-based verification ──

    private fun verifyNonceBased(
        headers: AppActorResponseSignatureHeaders?,
        body: String,
        sentNonce: String,
        apiKey: String,
        requestBinding: String?,
        v1PublicKey: ByteArray?,
        rootPublicKey: ByteArray?,
        nowEpochSeconds: Double,
    ): VerificationResult {
        val echoedNonce = headers?.requestNonce ?: return VerificationResult.SigningNotSupported
        val signatureBase64 = headers.signature ?: return VerificationResult.SignatureMissing
        val timestamp = headers.signatureTimestamp ?: return VerificationResult.SignatureMissing

        if (echoedNonce != sentNonce) {
            return VerificationResult.NonceMismatch
        }

        val payload = noncePayloadBytes(sentNonce, timestamp, apiKey, requestBinding, body)
        return validateTimestampAndDispatch(signatureBase64, timestamp, payload, v1PublicKey, rootPublicKey, nowEpochSeconds)
    }

    private fun verifySaltBased(
        headers: AppActorResponseSignatureHeaders?,
        body: String,
        apiKey: String,
        requestPath: String,
        eTag: String,
        v1PublicKey: ByteArray?,
        rootPublicKey: ByteArray?,
        nowEpochSeconds: Double,
    ): VerificationResult {
        val salt = headers?.salt ?: return VerificationResult.SigningNotSupported
        val signatureBase64 = headers.signature ?: return VerificationResult.SignatureMissing
        val timestamp = headers.signatureTimestamp ?: return VerificationResult.SignatureMissing

        val payload = saltPayloadBytes(salt, apiKey, requestPath, timestamp, eTag, body)
        return validateTimestampAndDispatch(signatureBase64, timestamp, payload, v1PublicKey, rootPublicKey, nowEpochSeconds)
    }

    private fun validateTimestampAndDispatch(
        signatureBase64: String,
        timestamp: String,
        payload: ByteArray,
        v1PublicKey: ByteArray?,
        rootPublicKey: ByteArray?,
        nowEpochSeconds: Double,
    ): VerificationResult {
        val timestampValue = timestamp.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return VerificationResult.SignatureMissing

        if (abs(nowEpochSeconds - timestampValue) > maxTimestampDriftSeconds) {
            return VerificationResult.TimestampOutOfRange
        }

        val signatureBlob = decodeBase64(signatureBase64)
            ?: return VerificationResult.SignatureInvalid

        return when (signatureBlob.size) {
            v1SignatureSize -> verifyV1(
                signature = signatureBlob,
                payload = payload,
                publicKey = v1PublicKey,
            )

            v2BlobSize -> verifyV2(
                blob = signatureBlob,
                payload = payload,
                rootPublicKey = rootPublicKey,
                nowEpochSeconds = nowEpochSeconds,
            )

            else -> VerificationResult.SignatureInvalid
        }
    }

    private fun verifyV1(
        signature: ByteArray,
        payload: ByteArray,
        publicKey: ByteArray?,
    ): VerificationResult {
        val key = publicKey ?: return VerificationResult.PublicKeyUnavailable
        val isValid = verifyEd25519(
            publicKey = key,
            signature = signature,
            payload = payload,
        )
        return if (isValid) VerificationResult.Success else VerificationResult.SignatureInvalid
    }

    private fun verifyV2(
        blob: ByteArray,
        payload: ByteArray,
        rootPublicKey: ByteArray?,
        nowEpochSeconds: Double,
    ): VerificationResult {
        val rootKey = rootPublicKey ?: return VerificationResult.PublicKeyUnavailable
        if (blob[0].toInt() != 0x02 || blob[1].toInt() != 0x00) {
            return VerificationResult.SignatureInvalid
        }

        val certHeader = blob.copyOfRange(0, certHeaderSize)
        val rootCertSignature = blob.copyOfRange(52, 116)
        val payloadSignature = blob.copyOfRange(116, 180)

        val issuedAt = readUInt64BE(certHeader, 4)
        val expiresAt = readUInt64BE(certHeader, 12)
        val nowSeconds = max(0.0, nowEpochSeconds).toLong().toULong()

        if (nowSeconds < issuedAt) {
            return VerificationResult.IntermediateCertInvalid
        }
        if (nowSeconds >= expiresAt) {
            return VerificationResult.IntermediateKeyExpired
        }

        val certPayload = ByteArray(certPrefix.size + certHeader.size).also { combined ->
            certPrefix.copyInto(combined, destinationOffset = 0)
            certHeader.copyInto(combined, destinationOffset = certPrefix.size)
        }

        val rootSignatureValid = verifyEd25519(
            publicKey = rootKey,
            signature = rootCertSignature,
            payload = certPayload,
        )
        if (!rootSignatureValid) {
            return VerificationResult.IntermediateCertInvalid
        }

        val intermediatePublicKey = certHeader.copyOfRange(20, 52)
        val payloadValid = verifyEd25519(
            publicKey = intermediatePublicKey,
            signature = payloadSignature,
            payload = payload,
        )
        return if (payloadValid) VerificationResult.Success else VerificationResult.SignatureInvalid
    }

    // The API key as well: every nonce request sends `X-AppActor-Signature-Api-Key: include`.
    // Every project is signed with the same key, so without it another project's signed answer
    // would pass as this app's.
    private fun noncePayloadBytes(
        sentNonce: String,
        timestamp: String,
        apiKey: String,
        requestBinding: String?,
        body: String,
    ): ByteArray {
        val bound = requestBinding?.let { "$it\n" }.orEmpty()
        return "$sentNonce\n$timestamp\n$apiKey\n$bound$body".toByteArray(Charsets.UTF_8)
    }

    /**
     * What the backend signs next to the nonce for a request bound with
     * `X-AppActor-Signature-Binding: request`: the method, path + query, and the lowercase hex
     * SHA-256 of the body (of no bytes when there is none). As on iOS.
     */
    internal fun requestBinding(method: String, target: String, body: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
        val hex = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "$method\n$target\n$hex"
    }

    private fun saltPayloadBytes(
        salt: String,
        apiKey: String,
        requestPath: String,
        timestamp: String,
        eTag: String,
        body: String,
    ): ByteArray {
        return "$salt\n$apiKey\n$requestPath\n$timestamp\n$eTag\n$body".toByteArray(Charsets.UTF_8)
    }

    // Tink uses the platform's Ed25519 (Conscrypt) where it has one and its own pure Java
    // implementation elsewhere. The first call loads the classes, so it stays off the main thread:
    // every caller verifies inside the backend client's IO dispatcher.
    private fun verifyEd25519(
        publicKey: ByteArray,
        signature: ByteArray,
        payload: ByteArray,
    ): Boolean {
        return runCatching {
            Ed25519Verify.create(Ed25519PublicKey.create(Bytes.copyFrom(publicKey))).verify(signature, payload)
            true
        }.getOrDefault(false)
    }

    // Standard alphabet, as the backend encodes with Node's toString('base64').
    private fun decodeBase64(base64: String): ByteArray? {
        return runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
    }

    private fun readUInt64BE(
        bytes: ByteArray,
        offset: Int,
    ): ULong {
        var value = 0UL
        for (index in 0 until 8) {
            value = (value shl 8) or bytes[offset + index].toUByte().toULong()
        }
        return value
    }
}
