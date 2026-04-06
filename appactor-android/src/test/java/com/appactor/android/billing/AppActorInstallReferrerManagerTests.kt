package com.appactor.android.billing

import com.appactor.android.storage.AppActorIdentityStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppActorInstallReferrerManagerTests {

    @Test
    fun `fetchReferrerOnce returns cached referrer without connecting`() = runBlocking {
        val store = mockk<AppActorIdentityStore>(relaxed = true)
        every { store.installReferrer } returns "utm_source=google&utm_medium=cpc"
        every { store.currentAppUserId } returns "user_123"
        every { store.installId } returns "install_123"

        val manager = AppActorInstallReferrerManager(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            identityStore = store,
        )

        val result = manager.fetchReferrerOnce()

        assertEquals("utm_source=google&utm_medium=cpc", result)
        verify(exactly = 0) { store.setInstallReferrer(any()) }
    }

    @Test
    fun `fetchReferrerOnce skips fetch when referrer already persisted`() = runBlocking {
        val store = mockk<AppActorIdentityStore>(relaxed = true)
        every { store.installReferrer } returns "existing_referrer"
        every { store.currentAppUserId } returns "user_123"
        every { store.installId } returns "install_123"

        val manager = AppActorInstallReferrerManager(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            identityStore = store,
        )

        val result = manager.fetchReferrerOnce()

        assertEquals("existing_referrer", result)
    }
}
