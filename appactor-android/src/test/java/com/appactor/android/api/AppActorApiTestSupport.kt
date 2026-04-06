package com.appactor.android.api

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStoreProduct
import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseHistoryRecord
import com.appactor.android.billing.AppActorStorePurchaseLaunchResult
import com.appactor.android.billing.GooglePlayStoreAdapter
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import com.appactor.android.storage.AppActorReceiptQueueItem
import com.appactor.android.storage.AppActorReceiptQueuePhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun resetApiTestState() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE).edit().clear().apply()
    context.getSharedPreferences("com.appactor.android.pending_purchases", Context.MODE_PRIVATE).edit().clear().apply()
    val httpCache = File(context.cacheDir, "appactor/http-cache")
    if (httpCache.exists()) httpCache.deleteRecursively()
    val appactorDir = File(context.filesDir, "appactor")
    if (appactorDir.exists()) appactorDir.deleteRecursively()
    AppActor.storeAdapterFactory = { appContext -> GooglePlayStoreAdapter(appContext) }
    AppActor.reset()
}

internal fun queueItem(
    key: String = AppActorReceiptQueueItem.makeKey(
        purchaseToken = "token_123",
        productId = "com.appactor.pro.monthly",
        basePlanId = "monthly001",
    ),
    purchaseToken: String = "token_123",
    productId: String = "com.appactor.pro.monthly",
    basePlanId: String? = "monthly001",
    phase: AppActorReceiptQueuePhase = AppActorReceiptQueuePhase.NeedsPost,
    lastUpdatedAtMillis: Long = System.currentTimeMillis(),
): AppActorReceiptQueueItem {
    return AppActorReceiptQueueItem(
        key = key,
        appUserId = "user_android_123",
        packageName = "com.appactor.android",
        environment = "production",
        productId = productId,
        productType = "subscription",
        purchaseToken = purchaseToken,
        purchaseTime = "1710000000000",
        purchaseState = "PURCHASED",
        basePlanId = basePlanId,
        idempotencyKey = "google:purchase:$purchaseToken",
        createdAtMillis = lastUpdatedAtMillis,
        lastUpdatedAtMillis = lastUpdatedAtMillis,
        phase = phase,
    )
}

internal fun startupDisabledOptions(): AppActorConfiguration.Options {
    return AppActorConfiguration.Options(
        verifyResponseSignatures = true,
        requireResponseSignatures = true,
    )
}

internal fun testOptionsForLocalBackend(): AppActorConfiguration.Options {
    return AppActorConfiguration.Options(
        verifyResponseSignatures = false,
        requireResponseSignatures = false,
    )
}

internal fun awaitMainThreadCallback(
    latch: CountDownLatch,
    timeoutMillis: Long = 5_000L,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        if (latch.await(100, TimeUnit.MILLISECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
            return true
        }
    }
    shadowOf(Looper.getMainLooper()).idle()
    return latch.count == 0L
}

internal fun currentRuntimeSessionId(): Long {
    val runtimeField = AppActor::class.java.getDeclaredField("runtime").apply {
        isAccessible = true
    }
    val runtime = requireNotNull(runtimeField.get(AppActor)) {
        "Runtime must be configured for this test."
    }
    val sessionField = runtime.javaClass.getDeclaredField("sessionId").apply {
        isAccessible = true
    }
    return sessionField.getLong(runtime)
}

internal class TestBackendServer(
    private val handler: (RecordedRequest) -> MockResponse,
) : AutoCloseable {
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return handler(request)
            }
        }
        start()
    }

    val baseUrl: String
        get() = server.url("/").toString()

    override fun close() {
        server.shutdown()
    }
}

internal class FakeStoreAdapter(
    private val connectStarted: CountDownLatch? = null,
    private val connectCompleted: CountDownLatch? = null,
    private val releaseConnect: CountDownLatch? = null,
    private val activePurchases: List<AppActorStorePurchase> = emptyList(),
    val storefront: AppActorStorefront? = null,
    val capabilities: Set<AppActorStoreCapability> = setOf(AppActorStoreCapability.Purchases),
) : AppActorStoreAdapter {

    @Volatile
    private var connected: Boolean = false

    override suspend fun connect() {
        if (connected) return
        connectStarted?.countDown()
        releaseConnect?.await(5, TimeUnit.SECONDS)
        connected = true
        connectCompleted?.countDown()
    }

    override fun shutdown() {
        connected = false
    }

    override fun isConnected(): Boolean = connected

    override fun currentStorefront(): AppActorStorefront? {
        return if (connected) storefront else null
    }

    override fun currentCapabilities(): Set<AppActorStoreCapability> {
        return if (connected) capabilities else emptySet()
    }

    override suspend fun queryProductDetails(
        requests: List<AppActorStoreProductRequest>,
    ): List<AppActorStoreProduct> {
        connect()
        return emptyList()
    }

    override suspend fun launchPurchase(
        activity: Activity,
        request: AppActorStoreProductRequest,
    ): AppActorStorePurchaseLaunchResult = AppActorStorePurchaseLaunchResult.Cancelled

    override fun purchaseUpdates(): Flow<List<AppActorStorePurchase>> = emptyFlow()

    override suspend fun resolveDirectPurchaseRequest(
        productId: String,
        obfuscatedAccountId: String?,
    ): AppActorStoreProductRequest {
        connect()
        return AppActorStoreProductRequest(
            productId = productId,
            productType = com.appactor.android.models.AppActorProductType.Unknown,
            obfuscatedAccountId = obfuscatedAccountId,
        )
    }

    override suspend fun queryActivePurchases(): List<AppActorStorePurchase> {
        connect()
        return activePurchases
    }

    override suspend fun queryPurchaseHistory(): List<AppActorStorePurchaseHistoryRecord> {
        connect()
        return emptyList()
    }

    override suspend fun acknowledgePurchase(purchaseToken: String) = Unit

    override suspend fun consumePurchase(purchaseToken: String) = Unit
}

internal fun jsonResponse(
    body: String,
    statusCode: Int = 200,
): MockResponse {
    return MockResponse()
        .setResponseCode(statusCode)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}

internal fun customerEnvelope(
    requestId: String,
    appUserId: String,
): String {
    return """
        {
          "requestId": "$requestId",
          "appUserId": "$appUserId",
          "customer": {
            "entitlements": {},
            "subscriptions": {},
            "nonSubscriptions": {}
          }
        }
    """.trimIndent()
}

internal fun googleReceiptEnvelope(
    requestId: String,
    appUserId: String,
): String {
    return """
        {
          "status": "ok",
          "requestId": "$requestId",
          "customer": {
            "entitlements": {},
            "subscriptions": {},
            "nonSubscriptions": {}
          },
          "acknowledgePurchase": true,
          "consumePurchase": false
        }
    """.trimIndent()
}

internal fun logoutEnvelope(
    requestId: String,
    success: Boolean = true,
): String {
    return """
        {
          "requestId": "$requestId",
          "success": $success
        }
    """.trimIndent()
}

internal fun googleRestoreEnvelope(
    requestId: String,
): String {
    return """
        {
          "customer": {
            "entitlements": {},
            "subscriptions": {},
            "nonSubscriptions": {}
          },
          "restoredCount": 1,
          "transferred": false,
          "requestId": "$requestId"
        }
    """.trimIndent()
}

internal fun loginEnvelope(
    requestId: String,
    appUserId: String,
): String {
    return """
        {
          "requestId": "$requestId",
          "appUserId": "$appUserId",
          "serverUserId": "$appUserId",
          "customer": {
            "entitlements": {},
            "subscriptions": {},
            "nonSubscriptions": {}
          }
        }
    """.trimIndent()
}

internal fun remoteConfigEnvelope(
    requestId: String,
    key: String,
    value: String,
): String {
    return """
        {
          "requestId": "$requestId",
          "data": [
            { "key": "$key", "value": "$value", "valueType": "string" }
          ]
        }
    """.trimIndent()
}
