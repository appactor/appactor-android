package com.appactor.android.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.dto.AppActorAttributionRequestDTO
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppActorAttributeQueueStoreTests {

    private lateinit var store: AppActorSharedPrefsAttributeQueueStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = AppActorSharedPrefsAttributeQueueStore(context)
        store.clearAll()
    }

    @Test
    fun `attribution snapshots are trimmed with the pending user limit`() {
        repeat(9) { index ->
            store.saveAttributionSnapshot(
                appUserId = "user_%02d".format(index),
                attribution = AppActorAttributionRequestDTO(
                    provider = "custom",
                    campaign = "campaign_$index",
                ),
            )
        }

        assertNull(store.loadAttributionSnapshot("user_00"))
        (1..8).forEach { index ->
            assertNotNull(store.loadAttributionSnapshot("user_%02d".format(index)))
        }
    }
}
