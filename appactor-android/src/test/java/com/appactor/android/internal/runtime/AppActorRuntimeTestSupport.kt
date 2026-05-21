package com.appactor.android.internal.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseLaunchResult
import com.appactor.android.billing.AppActorStorePurchaseState
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import com.appactor.android.models.appActorGoogleObfuscatedAccountId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun runtimeTestContext(): Context {
    return ApplicationProvider.getApplicationContext()
}

internal fun clearRuntimeTestStorage(context: Context = runtimeTestContext()) {
    context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE).edit().clear().commit()
    File(context.cacheDir, "appactor/http-cache").deleteRecursively()
    File(context.filesDir, "appactor").deleteRecursively()
}

internal fun runtimeTestOptions(): AppActorConfiguration.Options {
    return AppActorConfiguration.Options(
        verifyResponseSignatures = false,
        requireResponseSignatures = false,
    )
}

internal fun runtimeTestConfiguration(
    context: Context = runtimeTestContext(),
    appUserId: String? = null,
    options: AppActorConfiguration.Options = runtimeTestOptions(),
): AppActorConfiguration {
    return AppActorConfiguration(
        context = context,
        apiKey = "pk_test_runtime",
        appUserId = appUserId,
        baseUrl = "https://example.com",
        options = options,
    )
}

internal fun createMockStoreAdapter(
    purchaseUpdatesFlow: Flow<List<AppActorStorePurchase>> = emptyFlow(),
    connectStarted: CountDownLatch? = null,
    releaseConnect: CountDownLatch? = null,
    activePurchases: List<AppActorStorePurchase> = emptyList(),
    storefront: AppActorStorefront? = null,
    capabilities: Set<AppActorStoreCapability> = setOf(AppActorStoreCapability.Purchases),
): AppActorStoreAdapter {
    var connected = false

    val mock = mockk<AppActorStoreAdapter>(relaxed = true)

    coEvery { mock.connect() } coAnswers {
        if (!connected) {
            connectStarted?.countDown()
            releaseConnect?.await(5, TimeUnit.SECONDS)
            connected = true
        }
    }

    every { mock.shutdown() } answers {
        connected = false
    }

    every { mock.isConnected() } answers { connected }

    every { mock.currentStorefront() } answers {
        if (connected) storefront else null
    }

    every { mock.currentCapabilities() } answers {
        if (connected) capabilities else emptySet()
    }

    coEvery { mock.queryProductDetails(any()) } coAnswers {
        if (!connected) {
            connectStarted?.countDown()
            releaseConnect?.await(5, TimeUnit.SECONDS)
            connected = true
        }
        emptyList()
    }

    coEvery { mock.launchPurchase(any(), any()) } returns AppActorStorePurchaseLaunchResult.Cancelled

    every { mock.purchaseUpdates() } returns purchaseUpdatesFlow

    coEvery { mock.resolveDirectPurchaseRequest(any()) } coAnswers {
        val request = firstArg<AppActorStoreProductRequest>()
        if (!connected) {
            connectStarted?.countDown()
            releaseConnect?.await(5, TimeUnit.SECONDS)
            connected = true
        }
        request
    }

    coEvery { mock.queryActivePurchases() } coAnswers {
        if (!connected) {
            connectStarted?.countDown()
            releaseConnect?.await(5, TimeUnit.SECONDS)
            connected = true
        }
        activePurchases
    }

    coEvery { mock.queryPurchaseHistory() } coAnswers {
        if (!connected) {
            connectStarted?.countDown()
            releaseConnect?.await(5, TimeUnit.SECONDS)
            connected = true
        }
        emptyList()
    }

    return mock
}

internal fun createRuntimeState(
    storeAdapter: AppActorStoreAdapter = createMockStoreAdapter(),
    sessionId: Long = 1L,
    appUserId: String? = null,
    options: AppActorConfiguration.Options = runtimeTestOptions(),
    callbackState: AppActorCallbackState = AppActorCallbackState(),
): AppActorRuntimeState {
    val factory = AppActorRuntimeFactory(
        storeAdapterFactory = { storeAdapter },
        appVersionProvider = { "1.0.0" },
        countryProvider = { "TR" },
    )
    return factory.create(
        configuration = runtimeTestConfiguration(
            appUserId = appUserId,
            options = options,
        ),
        sessionId = sessionId,
        callbackState = callbackState,
        onPipelineEvent = {},
    )
}

internal fun runtimeTestPurchase(
    productId: String = "com.appactor.pro.monthly",
    purchaseToken: String = "token_runtime_123",
    obfuscatedAccountId: String? = appActorGoogleObfuscatedAccountId("user_runtime_123"),
): AppActorStorePurchase {
    return AppActorStorePurchase(
        productId = productId,
        productType = AppActorProductType.Subscription,
        purchaseToken = purchaseToken,
        orderId = "GPA.runtime.123",
        purchaseTimeMillis = 1_710_000_000_000,
        purchaseState = AppActorStorePurchaseState.Purchased,
        basePlanId = "monthly001",
        offerId = "intro7d",
        isAcknowledged = false,
        isAutoRenewing = true,
        obfuscatedAccountId = obfuscatedAccountId,
        rawPurchaseData = "{\"purchaseToken\":\"$purchaseToken\"}",
        purchaseSignature = "signature_$purchaseToken",
    )
}
