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

    @Test
    fun `v1 signature verifies successfully`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val nonce = "nonce_123"
        val timestamp = "1710000000"
        val body = """{"hello":"world"}"""
        val payload = "$nonce\n$timestamp\n$body".toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(payload, 0, payload.size)
        val signature = signer.generateSignature()

        val result = AppActorResponseSignatureVerifier.verify(
            headers = AppActorResponseSignatureHeaders(
                requestNonce = nonce,
                signature = Base64.toBase64String(signature),
                signatureTimestamp = timestamp,
            ),
            body = body,
            sentNonce = nonce,
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
            v1PublicKey = ByteArray(32),
            rootPublicKey = null,
            nowEpochSeconds = 1710000000.0,
        )

        assertEquals(AppActorResponseSignatureVerifier.VerificationResult.SignatureMissing, result)
    }
}
