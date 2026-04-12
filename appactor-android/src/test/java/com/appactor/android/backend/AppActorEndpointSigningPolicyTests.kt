package com.appactor.android.backend

import com.appactor.android.backend.auth.AppActorEndpointSigningPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class AppActorEndpointSigningPolicyTests {

    @Test
    fun `offerings path is nonce free`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceFree,
            AppActorEndpointSigningPolicy.forPath("/v1/payment/offerings"),
        )
    }

    @Test
    fun `remote config path is nonce free`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceFree,
            AppActorEndpointSigningPolicy.forPath("/v1/remote-config"),
        )
    }

    @Test
    fun `identify path requires nonce`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/v1/payment/identify"),
        )
    }

    @Test
    fun `login path requires nonce`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/v1/payment/login"),
        )
    }

    @Test
    fun `logout path requires nonce`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/v1/payment/logout"),
        )
    }

    @Test
    fun `customer path requires nonce`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/v1/customers/user_123"),
        )
    }

    @Test
    fun `google receipt path requires nonce`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/v1/payment/receipts/google"),
        )
    }

    @Test
    fun `google restore path requires nonce`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/v1/payment/restore/google"),
        )
    }

    @Test
    fun `google sync path requires nonce`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/v1/payment/sync/google"),
        )
    }

    @Test
    fun `experiment assignment path requires nonce`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/v1/experiments/paywall_copy/assignments"),
        )
    }

    @Test
    fun `unknown path defaults to nonce required`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/v1/unknown/path"),
        )
    }

    @Test
    fun `offerings path with base-path prefix is nonce free`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceFree,
            AppActorEndpointSigningPolicy.forPath("/proxy/v1/payment/offerings"),
        )
    }

    @Test
    fun `remote config path with base-path prefix is nonce free`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceFree,
            AppActorEndpointSigningPolicy.forPath("/gateway/api/v1/remote-config"),
        )
    }

    @Test
    fun `nonce-required path with base-path prefix still requires nonce`() {
        assertEquals(
            AppActorEndpointSigningPolicy.NonceRequired,
            AppActorEndpointSigningPolicy.forPath("/proxy/v1/payment/identify"),
        )
    }
}
