package com.appactor.android.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AlternativeBillingOnlyAvailabilityListener
import com.android.billingclient.api.AlternativeBillingOnlyInformationDialogListener
import com.android.billingclient.api.AlternativeBillingOnlyReportingDetailsListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingConfig
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingProgramAvailabilityListener
import com.android.billingclient.api.BillingProgramReportingDetailsListener
import com.android.billingclient.api.BillingProgramReportingDetailsParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ExternalOfferAvailabilityListener
import com.android.billingclient.api.ExternalOfferInformationDialogListener
import com.android.billingclient.api.ExternalOfferReportingDetailsListener
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResponseListener
import com.android.billingclient.api.LaunchExternalLinkParams
import com.android.billingclient.api.LaunchExternalLinkResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.UnfetchedProduct
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorProductType
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class GooglePlayBillingClientBridgeTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `connect refreshes storefront on subsequent connects`() = runBlocking {
        val fakeBillingClient = FakeBillingClient(
            storefrontCountryCodes = ArrayDeque(listOf("US", "TR")),
        )
        val bridge = GooglePlayBillingClientBridge(
            context = context,
            billingClientFactory = { _, _ -> fakeBillingClient },
        )

        bridge.connect()
        assertEquals("US", bridge.currentStorefront()?.countryCode)

        bridge.connect()
        assertEquals("TR", bridge.currentStorefront()?.countryCode)
    }

    @Test
    fun `service disconnect clears storefront until the next successful refresh`() = runBlocking {
        val fakeBillingClient = FakeBillingClient(
            storefrontCountryCodes = ArrayDeque(listOf("US", "TR")),
            initialReady = false,
            connectResults = ArrayDeque(
                listOf(
                    BillingClient.BillingResponseCode.OK,
                    BillingClient.BillingResponseCode.OK,
                )
            ),
        )
        val bridge = GooglePlayBillingClientBridge(
            context = context,
            billingClientFactory = { _, _ -> fakeBillingClient },
        )

        bridge.connect()
        assertEquals("US", bridge.currentStorefront()?.countryCode)

        fakeBillingClient.simulateServiceDisconnect()
        assertNull(bridge.currentStorefront())

        bridge.connect()
        assertEquals("TR", bridge.currentStorefront()?.countryCode)
    }

    @Test
    fun `queued requests wait for connection then execute in order`() = runBlocking {
        val fakeBillingClient = FakeBillingClient(
            initialReady = false,
            connectResults = ArrayDeque(listOf(BillingClient.BillingResponseCode.OK)),
            connectRelease = CountDownLatch(1),
        )
        val bridge = GooglePlayBillingClientBridge(
            context = context,
            billingClientFactory = { _, _ -> fakeBillingClient },
        )

        val deferred = async(Dispatchers.Default) {
            bridge.queryProductDetails(
                listOf(
                    AppActorBillingQueryProduct(
                        productId = "monthly",
                        productType = AppActorProductType.Subscription,
                    )
                )
            )
        }

        assertTrue(fakeBillingClient.connectionStarted.await(5, TimeUnit.SECONDS))
        assertEquals(0, fakeBillingClient.queryProductDetailsCalls)
        assertFalse(deferred.isCompleted)

        fakeBillingClient.connectRelease?.countDown()

        deferred.await()

        assertEquals(1, fakeBillingClient.startConnectionCalls)
        assertEquals(1, fakeBillingClient.queryProductDetailsCalls)
    }

    @Test
    fun `failed connection keeps queued requests until a later successful connect`() = runBlocking {
        val fakeBillingClient = FakeBillingClient(
            initialReady = false,
            connectResults = ArrayDeque(
                listOf(
                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                    BillingClient.BillingResponseCode.OK,
                )
            ),
        )
        val bridge = GooglePlayBillingClientBridge(
            context = context,
            billingClientFactory = { _, _ -> fakeBillingClient },
        )

        val deferred = async(Dispatchers.Default) {
            bridge.queryProductDetails(
                listOf(
                    AppActorBillingQueryProduct(
                        productId = "monthly",
                        productType = AppActorProductType.Subscription,
                    )
                )
            )
        }

        assertTrue(fakeBillingClient.connectionStarted.await(5, TimeUnit.SECONDS))
        delay(100)
        assertFalse(deferred.isCompleted)

        bridge.connect()
        deferred.await()

        assertTrue(fakeBillingClient.startConnectionCalls >= 2)
        assertEquals(1, fakeBillingClient.queryProductDetailsCalls)
    }

    @Test
    fun `service disconnect schedules only one reconnect job`() = runBlocking {
        val fakeBillingClient = FakeBillingClient(
            initialReady = false,
            connectResults = ArrayDeque(
                listOf(
                    BillingClient.BillingResponseCode.OK,
                    BillingClient.BillingResponseCode.OK,
                )
            ),
        )
        val bridge = GooglePlayBillingClientBridge(
            context = context,
            billingClientFactory = { _, _ -> fakeBillingClient },
        )

        bridge.connect()
        fakeBillingClient.simulateServiceDisconnect()
        fakeBillingClient.simulateServiceDisconnect()

        delay(1_300L)

        assertEquals(2, fakeBillingClient.startConnectionCalls)
    }

    @Test
    fun `query product details logs billing debug message`() = runBlocking {
        val previousHandler = AppActorLogger.logHandler
        val messages = mutableListOf<String>()
        AppActorLogger.logHandler = { _, message, _, _ -> messages += message }
        try {
            val fakeBillingClient = FakeBillingClient(
                queryProductDetailsBillingResult = billingResult(
                    BillingClient.BillingResponseCode.OK,
                    debugMessage = "Play debug details",
                ),
            )
            val bridge = GooglePlayBillingClientBridge(
                context = context,
                billingClientFactory = { _, _ -> fakeBillingClient },
            )

            bridge.queryProductDetails(
                listOf(
                    AppActorBillingQueryProduct(
                        productId = "monthly",
                        productType = AppActorProductType.Subscription,
                    )
                )
            )

            assertTrue(messages.any { it.contains("queryProductDetails") && it.contains("debugMessage=Play debug details") })
        } finally {
            AppActorLogger.logHandler = previousHandler
        }
    }

    @Test
    fun `query product details logs unfetched product entries`() = runBlocking {
        val previousHandler = AppActorLogger.logHandler
        val messages = mutableListOf<String>()
        AppActorLogger.logHandler = { _, message, _, _ -> messages += message }
        try {
            val fakeBillingClient = FakeBillingClient(
                queryProductDetailsResult = QueryProductDetailsResult.create(
                    emptyList(),
                    listOf(
                        unfetchedProduct(
                            productId = "missing.monthly",
                            productType = BillingClient.ProductType.SUBS,
                            statusCode = 7,
                            serializedDocid = "doc-123",
                        )
                    ),
                ),
            )
            val bridge = GooglePlayBillingClientBridge(
                context = context,
                billingClientFactory = { _, _ -> fakeBillingClient },
            )

            bridge.queryProductDetails(
                listOf(
                    AppActorBillingQueryProduct(
                        productId = "missing.monthly",
                        productType = AppActorProductType.Subscription,
                    )
                )
            )

            assertTrue(messages.any { it.contains("unfetched productId=missing.monthly") && it.contains("serializedDocid=doc-123") })
        } finally {
            AppActorLogger.logHandler = previousHandler
        }
    }

    private class FakeBillingClient(
        private val storefrontCountryCodes: ArrayDeque<String?> = ArrayDeque(),
        private val supportedFeatures: Set<String> = setOf(
            BillingClient.FeatureType.BILLING_CONFIG,
            BillingClient.FeatureType.SUBSCRIPTIONS,
        ),
        private val connectResults: ArrayDeque<Int> = ArrayDeque(listOf(BillingClient.BillingResponseCode.OK)),
        private val queryProductDetailsBillingResult: BillingResult = billingResult(BillingClient.BillingResponseCode.OK),
        private val queryProductDetailsResult: QueryProductDetailsResult = QueryProductDetailsResult.create(
            emptyList(),
            emptyList(),
        ),
        val connectRelease: CountDownLatch? = null,
        initialReady: Boolean = true,
    ) : BillingClient() {

        var ready: Boolean = initialReady
        var startConnectionCalls: Int = 0
        var queryProductDetailsCalls: Int = 0
        val connectionStarted = CountDownLatch(1)
        private var stateListener: com.android.billingclient.api.BillingClientStateListener? = null

        override fun getConnectionState(): Int = if (ready) 2 else 0

        override fun isFeatureSupported(feature: String): BillingResult {
            val responseCode = if (supportedFeatures.contains(feature)) {
                BillingClient.BillingResponseCode.OK
            } else {
                BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED
            }
            return billingResult(responseCode)
        }

        override fun launchBillingFlow(activity: android.app.Activity, params: BillingFlowParams): BillingResult {
            return billingResult(BillingClient.BillingResponseCode.OK)
        }

        override fun showAlternativeBillingOnlyInformationDialog(
            activity: android.app.Activity,
            listener: AlternativeBillingOnlyInformationDialogListener,
        ): BillingResult = billingResult(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED)

        override fun showExternalOfferInformationDialog(
            activity: android.app.Activity,
            listener: ExternalOfferInformationDialogListener,
        ): BillingResult = billingResult(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED)

        override fun showInAppMessages(
            activity: android.app.Activity,
            params: InAppMessageParams,
            listener: InAppMessageResponseListener,
        ): BillingResult = billingResult(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED)

        override fun acknowledgePurchase(
            params: AcknowledgePurchaseParams,
            listener: com.android.billingclient.api.AcknowledgePurchaseResponseListener,
        ) {
            listener.onAcknowledgePurchaseResponse(billingResult(BillingClient.BillingResponseCode.OK))
        }

        override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) {
            listener.onConsumeResponse(billingResult(BillingClient.BillingResponseCode.OK), params.purchaseToken)
        }

        override fun createAlternativeBillingOnlyReportingDetailsAsync(
            listener: AlternativeBillingOnlyReportingDetailsListener,
        ) = Unit

        override fun createBillingProgramReportingDetailsAsync(
            params: BillingProgramReportingDetailsParams,
            listener: BillingProgramReportingDetailsListener,
        ) = Unit

        override fun createExternalOfferReportingDetailsAsync(
            listener: ExternalOfferReportingDetailsListener,
        ) = Unit

        override fun endConnection() {
            ready = false
        }

        override fun getBillingConfigAsync(
            params: GetBillingConfigParams,
            listener: com.android.billingclient.api.BillingConfigResponseListener,
        ) {
            val countryCode = storefrontCountryCodes.pollFirst()
            listener.onBillingConfigResponse(
                billingResult(BillingClient.BillingResponseCode.OK),
                countryCode?.let { billingConfigForCountryCode(it) },
            )
        }

        override fun isAlternativeBillingOnlyAvailableAsync(listener: AlternativeBillingOnlyAvailabilityListener) = Unit

        override fun isBillingProgramAvailableAsync(
            program: Int,
            listener: BillingProgramAvailabilityListener,
        ) = Unit

        override fun isExternalOfferAvailableAsync(listener: ExternalOfferAvailabilityListener) = Unit

        override fun launchExternalLink(
            activity: android.app.Activity,
            params: LaunchExternalLinkParams,
            listener: LaunchExternalLinkResponseListener,
        ) = Unit

        override fun queryProductDetailsAsync(
            params: QueryProductDetailsParams,
            listener: ProductDetailsResponseListener,
        ) {
            queryProductDetailsCalls += 1
            listener.onProductDetailsResponse(
                queryProductDetailsBillingResult,
                queryProductDetailsResult,
            )
        }

        override fun queryPurchasesAsync(
            params: QueryPurchasesParams,
            listener: PurchasesResponseListener,
        ) {
            listener.onQueryPurchasesResponse(billingResult(BillingClient.BillingResponseCode.OK), emptyList())
        }

        override fun startConnection(listener: com.android.billingclient.api.BillingClientStateListener) {
            startConnectionCalls += 1
            connectionStarted.countDown()
            stateListener = listener
            connectRelease?.await(5, TimeUnit.SECONDS)
            val responseCode = if (connectResults.isEmpty()) {
                BillingClient.BillingResponseCode.OK
            } else {
                connectResults.removeFirst()
            }
            ready = responseCode == BillingClient.BillingResponseCode.OK
            listener.onBillingSetupFinished(billingResult(responseCode))
        }

        override fun isReady(): Boolean = ready

        fun simulateServiceDisconnect() {
            ready = false
            stateListener?.onBillingServiceDisconnected()
        }
    }
}

private fun billingResult(responseCode: Int, debugMessage: String = "test"): BillingResult {
    return BillingResult.newBuilder()
        .setResponseCode(responseCode)
        .setDebugMessage(debugMessage)
        .build()
}

private fun billingConfigForCountryCode(countryCode: String): BillingConfig {
    val factory = BillingConfig::class.java.getDeclaredMethod("forCountryCode", String::class.java)
    factory.isAccessible = true
    return factory.invoke(null, countryCode) as BillingConfig
}

private fun unfetchedProduct(
    productId: String,
    productType: String,
    statusCode: Int,
    serializedDocid: String?,
): UnfetchedProduct {
    val json = buildString {
        append("{")
        append("\"productId\":\"").append(productId).append("\",")
        append("\"type\":\"").append(productType).append("\",")
        append("\"statusCode\":").append(statusCode)
        if (serializedDocid != null) {
            append(",\"serializedDocid\":\"").append(serializedDocid).append("\"")
        }
        append("}")
    }
    val factory = UnfetchedProduct::class.java.getDeclaredMethod("fromJson", String::class.java)
    factory.isAccessible = true
    return factory.invoke(null, json) as UnfetchedProduct
}
