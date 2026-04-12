package com.appactor.android.backend

import com.appactor.android.backend.client.AppActorResponseSignatureHeaders
import com.appactor.android.backend.client.AppActorResponseSignatureVerifier
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

class AppActorResponseSignatureVerifierTests {

    // ── Nonce-based tests ──

    @Test
    fun `v1 nonce based signature verifies successfully`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val nonce = "nonce_123"
        val timestamp = "1710000000"
        val body = """{"hello":"world"}"""
        val payload = "$nonce\n$timestamp\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(privateKey, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                requestNonce = nonce,
                signature = Base64.toBase64String(signature),
                signatureTimestamp = timestamp,
            ),
            body = body,
            sentNonce = nonce,
            apiKey = "pk_test_123",
            requestPath = "/v1/payment/identify",
            eTag = "",
            v1PublicKey = publicKey,
            rootPublicKey = null,
            nowEpochSeconds = timestamp.toDouble(),
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.Success, result)
    }

    @Test
    fun `missing signature returns signature missing`() {
        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                requestNonce = "nonce_123",
                signature = null,
                signatureTimestamp = "1710000000",
            ),
            body = """{"hello":"world"}""",
            sentNonce = "nonce_123",
            apiKey = "pk_test_123",
            requestPath = "/v1/payment/identify",
            eTag = "",
            v1PublicKey = ByteArray(32),
            rootPublicKey = null,
            nowEpochSeconds = 1710000000.0,
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.SignatureMissing, result)
    }

    @Test
    fun `nonce mismatch returns nonce mismatch`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val timestamp = "1710000000"
        val body = """{"hello":"world"}"""
        val payload = "sent_nonce\n$timestamp\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(privateKey, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                requestNonce = "echoed_nonce",
                signature = Base64.toBase64String(signature),
                signatureTimestamp = timestamp,
            ),
            body = body,
            sentNonce = "sent_nonce",
            apiKey = "pk_test_123",
            requestPath = "/v1/payment/identify",
            eTag = "",
            v1PublicKey = publicKey,
            rootPublicKey = null,
            nowEpochSeconds = timestamp.toDouble(),
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.NonceMismatch, result)
    }

    // ── Salt-based tests ──

    @Test
    fun `v1 salt based signature verifies successfully`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val salt = Base64.toBase64String(ByteArray(16) { it.toByte() })
        val apiKey = "pk_test_123"
        val path = "/v1/payment/offerings"
        val timestamp = "1710000000"
        val eTag = "W/\"abc123\""
        val body = """{"data":{"offerings":[]}}"""
        val payload = "$salt\n$apiKey\n$path\n$timestamp\n$eTag\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(privateKey, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.toBase64String(signature),
                signatureTimestamp = timestamp,
            ),
            body = body,
            sentNonce = null,
            apiKey = apiKey,
            requestPath = path,
            eTag = eTag,
            v1PublicKey = publicKey,
            rootPublicKey = null,
            nowEpochSeconds = timestamp.toDouble(),
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.Success, result)
    }

    @Test
    fun `salt based with empty etag verifies successfully`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val salt = Base64.toBase64String(ByteArray(16) { 0x42 })
        val apiKey = "pk_test_123"
        val path = "/v1/remote-config"
        val timestamp = "1710000000"
        val eTag = ""
        val body = """{"data":[]}"""
        val payload = "$salt\n$apiKey\n$path\n$timestamp\n$eTag\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(privateKey, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.toBase64String(signature),
                signatureTimestamp = timestamp,
            ),
            body = body,
            sentNonce = null,
            apiKey = apiKey,
            requestPath = path,
            eTag = eTag,
            v1PublicKey = publicKey,
            rootPublicKey = null,
            nowEpochSeconds = timestamp.toDouble(),
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.Success, result)
    }

    @Test
    fun `salt based missing salt header returns signing not supported`() {
        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                signature = Base64.toBase64String(ByteArray(64)),
                signatureTimestamp = "1710000000",
            ),
            body = """{"data":{}}""",
            sentNonce = null,
            apiKey = "pk_test_123",
            requestPath = "/v1/payment/offerings",
            eTag = "",
            v1PublicKey = ByteArray(32),
            rootPublicKey = null,
            nowEpochSeconds = 1710000000.0,
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.SigningNotSupported, result)
    }

    @Test
    fun `salt based missing signature returns signature missing`() {
        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = Base64.toBase64String(ByteArray(16)),
                signature = null,
                signatureTimestamp = "1710000000",
            ),
            body = """{"data":{}}""",
            sentNonce = null,
            apiKey = "pk_test_123",
            requestPath = "/v1/payment/offerings",
            eTag = "",
            v1PublicKey = ByteArray(32),
            rootPublicKey = null,
            nowEpochSeconds = 1710000000.0,
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.SignatureMissing, result)
    }

    @Test
    fun `salt based wrong api key returns signature invalid`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val salt = Base64.toBase64String(ByteArray(16) { it.toByte() })
        val timestamp = "1710000000"
        val body = """{"data":{}}"""
        val payload = "$salt\npk_correct\n/v1/payment/offerings\n$timestamp\n\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(privateKey, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.toBase64String(signature),
                signatureTimestamp = timestamp,
            ),
            body = body,
            sentNonce = null,
            apiKey = "pk_WRONG",
            requestPath = "/v1/payment/offerings",
            eTag = "",
            v1PublicKey = publicKey,
            rootPublicKey = null,
            nowEpochSeconds = timestamp.toDouble(),
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.SignatureInvalid, result)
    }

    @Test
    fun `salt based wrong path returns signature invalid`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val salt = Base64.toBase64String(ByteArray(16) { it.toByte() })
        val apiKey = "pk_test_123"
        val timestamp = "1710000000"
        val body = """{"data":{}}"""
        val payload = "$salt\n$apiKey\n/v1/payment/offerings\n$timestamp\n\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(privateKey, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.toBase64String(signature),
                signatureTimestamp = timestamp,
            ),
            body = body,
            sentNonce = null,
            apiKey = apiKey,
            requestPath = "/v1/remote-config",
            eTag = "",
            v1PublicKey = publicKey,
            rootPublicKey = null,
            nowEpochSeconds = timestamp.toDouble(),
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.SignatureInvalid, result)
    }

    @Test
    fun `salt based timestamp drift beyond 300s returns timestamp out of range`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val salt = Base64.toBase64String(ByteArray(16) { it.toByte() })
        val apiKey = "pk_test_123"
        val timestamp = "1710000000"
        val body = """{"data":{}}"""
        val payload = "$salt\n$apiKey\n/v1/payment/offerings\n$timestamp\n\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(privateKey, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.toBase64String(signature),
                signatureTimestamp = timestamp,
            ),
            body = body,
            sentNonce = null,
            apiKey = apiKey,
            requestPath = "/v1/payment/offerings",
            eTag = "",
            v1PublicKey = publicKey,
            rootPublicKey = null,
            nowEpochSeconds = 1710000000.0 + 301.0,
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.TimestampOutOfRange, result)
    }

    @Test
    fun `salt based tampered body returns signature invalid`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val salt = Base64.toBase64String(ByteArray(16) { it.toByte() })
        val apiKey = "pk_test_123"
        val path = "/v1/payment/offerings"
        val timestamp = "1710000000"
        val originalBody = """{"data":{"offerings":[]}}"""
        val payload = "$salt\n$apiKey\n$path\n$timestamp\n\n$originalBody".toByteArray(Charsets.UTF_8)
        val signature = signPayload(privateKey, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.toBase64String(signature),
                signatureTimestamp = timestamp,
            ),
            body = """{"data":{"offerings":["TAMPERED"]}}""",
            sentNonce = null,
            apiKey = apiKey,
            requestPath = path,
            eTag = "",
            v1PublicKey = publicKey,
            rootPublicKey = null,
            nowEpochSeconds = timestamp.toDouble(),
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.SignatureInvalid, result)
    }

    @Test
    fun `null headers with null sentNonce returns signing not supported`() {
        val result = AppActorResponseSignatureVerifier.verify(
            headers = null,
            body = """{"data":{}}""",
            sentNonce = null,
            apiKey = "pk_test_123",
            requestPath = "/v1/payment/offerings",
            eTag = "",
            v1PublicKey = ByteArray(32),
            rootPublicKey = null,
            nowEpochSeconds = 1710000000.0,
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.SigningNotSupported, result)
    }

    // ── Helper ──

    private fun signPayload(privateKey: Ed25519PrivateKeyParameters, payload: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(payload, 0, payload.size)
        return signer.generateSignature()
    }
}
