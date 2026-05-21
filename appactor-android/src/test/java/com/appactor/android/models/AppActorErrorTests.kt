package com.appactor.android.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActorErrorTests {

    @Test
    fun `Network is transient`() {
        assertTrue(AppActorError.Network("timeout").isTransient)
    }

    @Test
    fun `Server is transient`() {
        assertTrue(AppActorError.Server("internal error", statusCode = 500).isTransient)
    }

    @Test
    fun `Server with 429 is transient`() {
        assertTrue(AppActorError.Server("rate limited", statusCode = 429).isTransient)
    }

    @Test
    fun `Server with 503 is transient`() {
        assertTrue(AppActorError.Server("service unavailable", statusCode = 503).isTransient)
    }

    @Test
    fun `Server without status code is not transient`() {
        assertFalse(AppActorError.Server("unknown server error").isTransient)
    }

    @Test
    fun `Server with 400 is not transient`() {
        assertFalse(AppActorError.Server("bad request", statusCode = 400).isTransient)
    }

    @Test
    fun `Server with 404 is not transient`() {
        assertFalse(AppActorError.Server("not found", statusCode = 404).isTransient)
    }

    @Test
    fun `PurchaseFailed is not transient`() {
        assertFalse(AppActorError.PurchaseFailed("billing unavailable").isTransient)
    }

    @Test
    fun `ReceiptPostFailed is not transient`() {
        assertFalse(AppActorError.ReceiptPostFailed("post failed").isTransient)
    }

    @Test
    fun `NotConfigured is not transient`() {
        assertFalse(AppActorError.NotConfigured.isTransient)
    }

    @Test
    fun `AlreadyConfigured is not transient`() {
        assertFalse(AppActorError.AlreadyConfigured.isTransient)
    }

    @Test
    fun `InvalidConfiguration is not transient`() {
        assertFalse(AppActorError.InvalidConfiguration("bad key").isTransient)
    }

    @Test
    fun `Unknown is not transient`() {
        assertFalse(AppActorError.Unknown("something happened").isTransient)
    }

    @Test
    fun `NotImplementedYet is not transient`() {
        assertFalse(AppActorError.NotImplementedYet("feature").isTransient)
    }
}
