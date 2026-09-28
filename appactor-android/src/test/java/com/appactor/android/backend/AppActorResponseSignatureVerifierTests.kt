package com.appactor.android.backend

import com.appactor.android.backend.client.AppActorResponseSignatureHeaders
import com.appactor.android.backend.client.AppActorResponseSignatureVerifier
import com.google.crypto.tink.subtle.Ed25519Sign
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class AppActorResponseSignatureVerifierTests {

    // ── Nonce-based tests ──

    @Test
    fun `v1 nonce based signature verifies successfully`() {
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val nonce = "nonce_123"
        val timestamp = "1710000000"
        val body = """{"hello":"world"}"""
        val payload = "$nonce\n$timestamp\npk_test_123\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(keyPair, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                requestNonce = nonce,
                signature = Base64.getEncoder().encodeToString(signature),
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
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val timestamp = "1710000000"
        val body = """{"hello":"world"}"""
        val payload = "sent_nonce\n$timestamp\npk_test_123\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(keyPair, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                requestNonce = "echoed_nonce",
                signature = Base64.getEncoder().encodeToString(signature),
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
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val salt = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val apiKey = "pk_test_123"
        val path = "/v1/payment/offerings"
        val timestamp = "1710000000"
        val eTag = "W/\"abc123\""
        val body = """{"data":{"offerings":[]}}"""
        val payload = "$salt\n$apiKey\n$path\n$timestamp\n$eTag\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(keyPair, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.getEncoder().encodeToString(signature),
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
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val salt = Base64.getEncoder().encodeToString(ByteArray(16) { 0x42 })
        val apiKey = "pk_test_123"
        val path = "/v1/remote-config"
        val timestamp = "1710000000"
        val eTag = ""
        val body = """{"data":[]}"""
        val payload = "$salt\n$apiKey\n$path\n$timestamp\n$eTag\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(keyPair, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.getEncoder().encodeToString(signature),
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
                signature = Base64.getEncoder().encodeToString(ByteArray(64)),
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
                salt = Base64.getEncoder().encodeToString(ByteArray(16)),
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
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val salt = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val timestamp = "1710000000"
        val body = """{"data":{}}"""
        val payload = "$salt\npk_correct\n/v1/payment/offerings\n$timestamp\n\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(keyPair, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.getEncoder().encodeToString(signature),
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
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val salt = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val apiKey = "pk_test_123"
        val timestamp = "1710000000"
        val body = """{"data":{}}"""
        val payload = "$salt\n$apiKey\n/v1/payment/offerings\n$timestamp\n\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(keyPair, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.getEncoder().encodeToString(signature),
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
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val salt = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val apiKey = "pk_test_123"
        val timestamp = "1710000000"
        val body = """{"data":{}}"""
        val payload = "$salt\n$apiKey\n/v1/payment/offerings\n$timestamp\n\n$body".toByteArray(Charsets.UTF_8)
        val signature = signPayload(keyPair, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.getEncoder().encodeToString(signature),
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
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val salt = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val apiKey = "pk_test_123"
        val path = "/v1/payment/offerings"
        val timestamp = "1710000000"
        val originalBody = """{"data":{"offerings":[]}}"""
        val payload = "$salt\n$apiKey\n$path\n$timestamp\n\n$originalBody".toByteArray(Charsets.UTF_8)
        val signature = signPayload(keyPair, payload)

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                salt = salt,
                signature = Base64.getEncoder().encodeToString(signature),
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

    @Test
    fun `nonce signature bound to the request verifies only for that request`() {
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val nonce = "nonce_123"
        val timestamp = "1710000000"
        val body = """{"customer":{}}"""
        val binding = AppActorResponseSignatureVerifier.requestBinding(
            method = "GET",
            target = "/v1/customers/user_b",
            body = ByteArray(0),
        )
        // The backend's sha256 of no bytes.
        assertEquals(
            "GET\n/v1/customers/user_b\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            binding,
        )
        val signature = signPayload(keyPair, "$nonce\n$timestamp\npk_test_123\n$binding\n$body".toByteArray(Charsets.UTF_8))

        fun verifyFor(target: String) = verifyBoundNonce(
            signature = signature,
            publicKey = publicKey,
            nonce = nonce,
            timestamp = timestamp,
            body = body,
            apiKey = "pk_test_123",
            target = target,
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.Success, verifyFor("/v1/customers/user_b"))
        // User B's signed response relayed onto user A's request.
        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.SignatureInvalid, verifyFor("/v1/customers/user_a"))
    }

    @Test
    fun `nonce signature verifies only for the api key the sdk sent`() {
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val publicKey = keyPair.publicKey
        val nonce = "nonce_123"
        val timestamp = "1710000000"
        val body = """{"customer":{"entitlements":{"premium":{}}}}"""
        val target = "/v1/customers/user_a"
        val binding = AppActorResponseSignatureVerifier.requestBinding("GET", target, ByteArray(0))

        fun verifySigned(payload: String) = verifyBoundNonce(
            signature = signPayload(keyPair, payload.toByteArray(Charsets.UTF_8)),
            publicKey = publicKey,
            nonce = nonce,
            timestamp = timestamp,
            body = body,
            apiKey = "pk_app",
            target = target,
        )

        assertEquals(
            AppActorResponseSignatureVerifier.VerificationResult.Success,
            verifySigned("$nonce\n$timestamp\npk_app\n$binding\n$body"),
        )
        // Another project's answer, signed for the key a proxy swapped in.
        assertEquals(
            AppActorResponseSignatureVerifier.VerificationResult.SignatureInvalid,
            verifySigned("$nonce\n$timestamp\npk_attacker\n$binding\n$body"),
        )
        // The payload without the key, which a proxy would get by stripping the header.
        assertEquals(
            AppActorResponseSignatureVerifier.VerificationResult.SignatureInvalid,
            verifySigned("$nonce\n$timestamp\n$binding\n$body"),
        )
    }

    @Test
    fun `nonce signature made the way the backend signs verifies`() {
        // Signed with Node's crypto.sign(null, payload, key), as response-signing.ts does, over
        // nonce, timestamp, API key and a non-ASCII body. The other tests sign with Tink itself.
        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                requestNonce = "nonce_kat_1",
                signature = "M4GP5m6QiBo1Pr+sjmYwErAHx1ZmOQ77oHH0ipzdard4gdzo55tSdAcd4QEyFXRqVeXHe6aEZeyznWscu2MMAg==",
                signatureTimestamp = "1710000000",
            ),
            body = """{"appUserId":"user_ü","entitlements":{}}""",
            sentNonce = "nonce_kat_1",
            apiKey = "pk_test_kat",
            requestPath = "/v1/payment/identify",
            eTag = "",
            v1PublicKey = Base64.getDecoder().decode("H2vrnxbDbsImzc6iX4UcT1fNL47AbfWp4QnNGf/9NoA="),
            rootPublicKey = null,
            nowEpochSeconds = 1710000000.0,
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.Success, result)
    }

    // ── Helper ──

    private fun verifyBoundNonce(
        signature: ByteArray,
        publicKey: ByteArray,
        nonce: String,
        timestamp: String,
        body: String,
        apiKey: String,
        target: String,
    ) = AppActorResponseSignatureVerifier.verify(
        headers = AppActorResponseSignatureHeaders(
            requestNonce = nonce,
            signature = Base64.getEncoder().encodeToString(signature),
            signatureTimestamp = timestamp,
        ),
        body = body,
        sentNonce = nonce,
        apiKey = apiKey,
        requestPath = target,
        eTag = "",
        v1PublicKey = publicKey,
        rootPublicKey = null,
        nowEpochSeconds = timestamp.toDouble(),
        requestBinding = AppActorResponseSignatureVerifier.requestBinding("GET", target, ByteArray(0)),
    )

    private fun signPayload(keyPair: Ed25519Sign.KeyPair, payload: ByteArray): ByteArray {
        return Ed25519Sign(keyPair.privateKey).sign(payload)
    }
}
