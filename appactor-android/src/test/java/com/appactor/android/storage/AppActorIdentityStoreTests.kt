package com.appactor.android.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppActorIdentityStoreTests {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `identity store persists install and user ids`() {
        val firstStore = AppActorSharedPrefsIdentityStore(context)
        val installId = firstStore.installId
        val appUserId = firstStore.ensureAppUserId()
        firstStore.setLastRequestId("req_123")

        val secondStore = AppActorSharedPrefsIdentityStore(context)

        assertEquals(installId, secondStore.installId)
        assertEquals(appUserId, secondStore.currentAppUserId)
        assertEquals("req_123", secondStore.lastRequestId)
    }

    @Test
    fun `ensure app user id is stable once generated`() {
        val store = AppActorSharedPrefsIdentityStore(context)

        val first = store.ensureAppUserId()
        val second = store.ensureAppUserId()

        assertEquals(first, second)
        assertTrue(first.startsWith("appactor-anon-"))
    }

    @Test
    fun `resolve app user id preserves explicit non empty formatting`() {
        val store = AppActorSharedPrefsIdentityStore(context)

        val resolved = store.resolveAppUserId(" user_android_123 ")

        assertEquals(" user_android_123 ", resolved)
        assertEquals(" user_android_123 ", store.currentAppUserId)
    }

    @Test
    fun `clear identity preserves install id`() {
        val store = AppActorSharedPrefsIdentityStore(context)
        val installId = store.installId
        store.ensureAppUserId()

        store.clearIdentity()

        val reloaded = AppActorSharedPrefsIdentityStore(context)
        assertEquals(installId, reloaded.installId)
        assertEquals(null, reloaded.currentAppUserId)
        assertNotEquals("", installId)
    }

    @Test
    fun `install referrer persists and survives reload`() {
        val store = AppActorSharedPrefsIdentityStore(context)
        assertNull(store.installReferrer)

        store.setInstallReferrer("utm_source=google&utm_medium=cpc")

        assertEquals("utm_source=google&utm_medium=cpc", store.installReferrer)

        val reloaded = AppActorSharedPrefsIdentityStore(context)
        assertEquals("utm_source=google&utm_medium=cpc", reloaded.installReferrer)
    }

    @Test
    fun `install referrer is not cleared by clearIdentity`() {
        val store = AppActorSharedPrefsIdentityStore(context)
        store.setInstallReferrer("utm_source=test")
        store.ensureAppUserId()

        store.clearIdentity()

        assertEquals("utm_source=test", store.installReferrer)
        assertNull(store.currentAppUserId)
    }
}
