package com.appactor.android.backend.client

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
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

    internal fun verify(
        headers: AppActorResponseSignatureHeaders?,
        body: String,
        sentNonce: String,
    ): VerificationResult {
        return verify(
            headers = headers,
            body = body,
            sentNonce = sentNonce,
            v1PublicKey = decodeKey(v1PublicKeyBase64),
            rootPublicKey = decodeKey(rootPublicKeyBase64),
            nowEpochSeconds = System.currentTimeMillis() / 1000.0,
        )
    }

    internal fun verify(
        headers: AppActorResponseSignatureHeaders?,
        body: String,
        sentNonce: String,
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

        val timestampValue = timestamp.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return VerificationResult.SignatureMissing

        if (abs(nowEpochSeconds - timestampValue) > maxTimestampDriftSeconds) {
            return VerificationResult.TimestampOutOfRange
        }

        val signatureBlob = runCatching { Base64.decode(signatureBase64) }.getOrNull()
            ?: return VerificationResult.SignatureInvalid

        return when (signatureBlob.size) {
            v1SignatureSize -> verifyV1(
                signature = signatureBlob,
                sentNonce = sentNonce,
                timestamp = timestamp,
                body = body,
                publicKey = v1PublicKey,
            )

            v2BlobSize -> verifyV2(
                blob = signatureBlob,
                sentNonce = sentNonce,
                timestamp = timestamp,
                body = body,
                rootPublicKey = rootPublicKey,
                nowEpochSeconds = nowEpochSeconds,
            )

            else -> VerificationResult.SignatureInvalid
        }
    }

    private fun verifyV1(
        signature: ByteArray,
        sentNonce: String,
        timestamp: String,
        body: String,
        publicKey: ByteArray?,
    ): VerificationResult {
        val key = publicKey ?: return VerificationResult.PublicKeyUnavailable
        val payload = payloadBytes(sentNonce, timestamp, body)
        val isValid = verifyEd25519(
            publicKey = key,
            signature = signature,
            payload = payload,
        )
        return if (isValid) VerificationResult.Success else VerificationResult.SignatureInvalid
    }

    private fun verifyV2(
        blob: ByteArray,
        sentNonce: String,
        timestamp: String,
        body: String,
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
            payload = payloadBytes(sentNonce, timestamp, body),
        )
        return if (payloadValid) VerificationResult.Success else VerificationResult.SignatureInvalid
    }

    private fun payloadBytes(
        sentNonce: String,
        timestamp: String,
        body: String,
    ): ByteArray {
        return "$sentNonce\n$timestamp\n$body".toByteArray(Charsets.UTF_8)
    }

    private fun verifyEd25519(
        publicKey: ByteArray,
        signature: ByteArray,
        payload: ByteArray,
    ): Boolean {
        return runCatching {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(payload, 0, payload.size)
            signer.verifySignature(signature)
        }.getOrDefault(false)
    }

    private fun decodeKey(base64: String): ByteArray? {
        return runCatching { Base64.decode(base64) }.getOrNull()
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
