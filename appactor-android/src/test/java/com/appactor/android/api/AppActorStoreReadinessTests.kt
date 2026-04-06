package com.appactor.android.api

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class AppActorStoreReadinessTests {

    @Before
    fun setUp() {
        resetApiTestState()
    }

    @Test
    fun `store readiness surface returns cached storefront capabilities and readiness`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedStorefront = AppActorStorefront(
            store = AppActorStore.PlayStore,
            countryCode = "US",
        )
        val fakeStoreAdapter = FakeStoreAdapter(
            storefront = expectedStorefront,
            capabilities = linkedSetOf(
                AppActorStoreCapability.Purchases,
                AppActorStoreCapability.Subscriptions,
                AppActorStoreCapability.Storefront,
            ),
        )
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        AppActor.configure(
            AppActorConfiguration(
                context = context,
                apiKey = "pk_test_123",
                options = startupDisabledOptions(),
            )
        )

        assertEquals(expectedStorefront, AppActor.getStorefront())
        assertEquals(fakeStoreAdapter.capabilities, AppActor.getStoreCapabilities())
        assertTrue(AppActor.canMakePurchases(setOf(AppActorStoreCapability.Purchases)))
        assertFalse(AppActor.canMakePurchases(setOf(AppActorStoreCapability.PurchaseHistory)))
    }

    @Test
    fun `store readiness surface clears on disconnect and refreshes after reconnect`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val initialStorefront = AppActorStorefront(
            store = AppActorStore.PlayStore,
            countryCode = "US",
        )
        val fakeStoreAdapter = FakeStoreAdapter(
            storefront = initialStorefront,
            capabilities = linkedSetOf(
                AppActorStoreCapability.Purchases,
                AppActorStoreCapability.Storefront,
            ),
        )
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        AppActor.configure(
            AppActorConfiguration(
                context = context,
                apiKey = "pk_test_123",
                options = startupDisabledOptions(),
            )
        )

        assertEquals(initialStorefront, AppActor.getStorefront())
        assertTrue(AppActor.canMakePurchases(setOf(AppActorStoreCapability.Purchases)))

        fakeStoreAdapter.shutdown()

        assertNull(AppActor.getStorefront())
        assertTrue(AppActor.getStoreCapabilities().isEmpty())
        assertFalse(AppActor.canMakePurchases(setOf(AppActorStoreCapability.Purchases)))

        fakeStoreAdapter.connect()

        assertEquals(initialStorefront, AppActor.getStorefront())
        assertEquals(fakeStoreAdapter.capabilities, AppActor.getStoreCapabilities())
        assertTrue(AppActor.canMakePurchases(setOf(AppActorStoreCapability.Purchases)))
    }

    @Test
    fun `java store readiness surface delivers callbacks on main thread`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedStorefront = AppActorStorefront(
            store = AppActorStore.PlayStore,
            countryCode = "TR",
        )
        val fakeStoreAdapter = FakeStoreAdapter(
            storefront = expectedStorefront,
            capabilities = linkedSetOf(
                AppActorStoreCapability.Purchases,
                AppActorStoreCapability.Storefront,
            ),
        )
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        AppActor.configure(
            AppActorConfiguration(
                context = context,
                apiKey = "pk_test_123",
                options = startupDisabledOptions(),
            )
        )
        fakeStoreAdapter.connect()

        val storefrontLatch = CountDownLatch(1)
        val capabilitiesLatch = CountDownLatch(1)
        val storefrontOnMain = AtomicReference<Boolean?>()
        val capabilitiesOnMain = AtomicReference<Boolean?>()
        val storefrontCountryCode = AtomicReference<String?>()
        val capabilityNames = AtomicReference<List<String>>()
        val storefrontError = AtomicReference<AppActorError?>()
        val capabilitiesError = AtomicReference<AppActorError?>()

        AppActorJava.getStorefrontAsync(
            onSuccess = com.appactor.android.models.AppActorSuccessCallback { storefront ->
                storefrontOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                storefrontCountryCode.set(storefront?.countryCode)
                storefrontLatch.countDown()
            },
            onError = com.appactor.android.models.AppActorErrorCallback { error ->
                storefrontError.set(error)
                storefrontLatch.countDown()
            },
        )

        AppActorJava.getStoreCapabilitiesAsync(
            onSuccess = com.appactor.android.models.AppActorSuccessCallback { capabilities ->
                capabilitiesOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                capabilityNames.set(capabilities.map { it.name }.sorted())
                capabilitiesLatch.countDown()
            },
            onError = com.appactor.android.models.AppActorErrorCallback { error ->
                capabilitiesError.set(error)
                capabilitiesLatch.countDown()
            },
        )

        assertTrue(awaitMainThreadCallback(storefrontLatch))
        assertTrue(awaitMainThreadCallback(capabilitiesLatch))
        assertNull(storefrontError.get())
        assertNull(capabilitiesError.get())
        assertEquals(true, storefrontOnMain.get())
        assertEquals(true, capabilitiesOnMain.get())
        assertEquals("TR", storefrontCountryCode.get())
        assertEquals(listOf("Purchases", "Storefront"), capabilityNames.get())
        assertTrue(AppActorJava.canMakePurchases())
        assertFalse(AppActorJava.canMakePurchases(setOf(AppActorStoreCapability.Subscriptions)))
    }
}
