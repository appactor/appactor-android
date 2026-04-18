package com.appactor.android.billing

import android.app.Activity
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ProductDetails
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import com.appactor.android.models.AppActorSubscriptionReplacementMode
import kotlinx.coroutines.flow.Flow

internal interface AppActorStoreAdapter {
    suspend fun connect()
    fun shutdown()
    fun isConnected(): Boolean = false
    fun currentStorefront(): AppActorStorefront? = null
    fun currentCapabilities(): Set<AppActorStoreCapability> = emptySet()
    fun canMakePurchases(requiredCapabilities: Set<AppActorStoreCapability> = emptySet()): Boolean {
        return isConnected() && currentCapabilities().containsAll(requiredCapabilities)
    }
    suspend fun queryProductDetails(requests: List<AppActorStoreProductRequest>): List<AppActorStoreProduct>
    suspend fun launchPurchase(
        activity: Activity,
        request: AppActorStoreProductRequest,
    ): AppActorStorePurchaseLaunchResult

    fun purchaseUpdates(): Flow<List<AppActorStorePurchase>>
    suspend fun resolveDirectPurchaseRequest(
        request: AppActorStoreProductRequest,
    ): AppActorStoreProductRequest
    suspend fun queryActivePurchases(): List<AppActorStorePurchase>
    suspend fun queryPurchaseHistory(): List<AppActorStorePurchaseHistoryRecord>
    suspend fun acknowledgePurchase(purchaseToken: String)
    suspend fun consumePurchase(purchaseToken: String)
}

internal data class AppActorStoreProductRequest(
    val productId: String,
    val productType: AppActorProductType,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val obfuscatedAccountId: String? = null,
    val oldPurchaseToken: String? = null,
    val replacementMode: Int? = null,
)

internal data class AppActorStorePricing(
    val formattedPrice: String? = null,
    val priceAmountMicros: Long? = null,
    val currencyCode: String? = null,
)

internal data class AppActorStoreProduct(
    val productId: String,
    val productType: AppActorProductType,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val localizedPrice: String? = null,
    val priceAmountMicros: Long? = null,
    val currencyCode: String? = null,
    val title: String? = null,
    val displayName: String? = null,
    val description: String? = null,
)

internal enum class AppActorStorePurchaseState {
    Purchased,
    Pending,
    Unknown,
}

internal data class AppActorStorePurchase(
    val productId: String,
    val productType: AppActorProductType,
    val purchaseToken: String,
    val orderId: String? = null,
    val purchaseTimeMillis: Long,
    val purchaseState: AppActorStorePurchaseState,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val isAcknowledged: Boolean = false,
    val isAutoRenewing: Boolean? = null,
    val obfuscatedAccountId: String? = null,
    val rawPurchaseData: String? = null,
    val purchaseSignature: String? = null,
)

internal data class AppActorStorePurchaseHistoryRecord(
    val productId: String,
    val productType: AppActorProductType,
    val purchaseToken: String,
    val orderId: String? = null,
    val purchaseTimeMillis: Long,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val isAutoRenewing: Boolean? = null,
    val obfuscatedAccountId: String? = null,
    val rawPurchaseData: String? = null,
    val purchaseSignature: String? = null,
)

internal sealed interface AppActorStorePurchaseLaunchResult {
    data class Purchased(
        val purchases: List<AppActorStorePurchase>,
    ) : AppActorStorePurchaseLaunchResult

    data class Pending(
        val purchases: List<AppActorStorePurchase>,
    ) : AppActorStorePurchaseLaunchResult

    data object Cancelled : AppActorStorePurchaseLaunchResult

    data class Failed(
        val error: Throwable,
    ) : AppActorStorePurchaseLaunchResult
}

internal data class AppActorBillingQueryProduct(
    val productId: String,
    val productType: AppActorProductType,
)

internal data class AppActorBillingSubscriptionOfferPayload(
    val basePlanId: String? = null,
    val offerId: String? = null,
    val offerToken: String? = null,
    val pricing: AppActorStorePricing? = null,
)

internal data class AppActorBillingProductDetailsPayload(
    val productId: String,
    val productType: AppActorProductType,
    val title: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val oneTimeOffer: AppActorStorePricing? = null,
    val subscriptionOffers: List<AppActorBillingSubscriptionOfferPayload> = emptyList(),
    val nativeProductDetails: ProductDetails? = null,
)

internal data class AppActorBillingPurchasePayload(
    val products: List<String>,
    val productType: AppActorProductType,
    val purchaseToken: String,
    val orderId: String? = null,
    val purchaseTimeMillis: Long,
    val purchaseState: AppActorStorePurchaseState,
    val isAcknowledged: Boolean,
    val isAutoRenewing: Boolean? = null,
    val obfuscatedAccountId: String? = null,
    val rawPurchaseData: String? = null,
    val purchaseSignature: String? = null,
)

internal fun AppActorBillingPurchasePayload.toHistoryPayload(): AppActorBillingPurchaseHistoryPayload =
    AppActorBillingPurchaseHistoryPayload(
        products = products,
        productType = productType,
        purchaseToken = purchaseToken,
        orderId = orderId,
        purchaseTimeMillis = purchaseTimeMillis,
        isAutoRenewing = isAutoRenewing,
        obfuscatedAccountId = obfuscatedAccountId,
        rawPurchaseData = rawPurchaseData,
        purchaseSignature = purchaseSignature,
    )

internal data class AppActorBillingPurchaseHistoryPayload(
    val products: List<String>,
    val productType: AppActorProductType,
    val purchaseToken: String,
    val orderId: String? = null,
    val purchaseTimeMillis: Long,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val isAutoRenewing: Boolean? = null,
    val obfuscatedAccountId: String? = null,
    val rawPurchaseData: String? = null,
    val purchaseSignature: String? = null,
)

internal data class AppActorBillingPurchaseUpdate(
    val purchaseTokens: Set<String>,
    val purchases: List<AppActorBillingPurchasePayload>,
)

internal sealed interface AppActorBillingLaunchResult {
    data class Purchased(
        val purchases: List<AppActorBillingPurchasePayload>,
    ) : AppActorBillingLaunchResult

    data class Pending(
        val purchases: List<AppActorBillingPurchasePayload>,
    ) : AppActorBillingLaunchResult

    data object Cancelled : AppActorBillingLaunchResult

    data class Failed(
        val error: Throwable,
    ) : AppActorBillingLaunchResult
}

internal interface AppActorGoogleBillingClient {
    suspend fun connect()
    fun shutdown()
    fun isConnected(): Boolean = false
    fun currentStorefront(): AppActorStorefront? = null
    fun currentCapabilities(): Set<AppActorStoreCapability> = emptySet()
    suspend fun queryProductDetails(products: List<AppActorBillingQueryProduct>): List<AppActorBillingProductDetailsPayload>
    suspend fun launchPurchase(
        activity: Activity,
        productDetails: ProductDetails?,
        productType: AppActorProductType,
        offerToken: String?,
        obfuscatedAccountId: String? = null,
        oldPurchaseToken: String? = null,
        replacementMode: Int? = null,
    ): AppActorBillingLaunchResult

    fun purchaseUpdates(): Flow<AppActorBillingPurchaseUpdate>
    suspend fun queryPurchases(productType: AppActorProductType): List<AppActorBillingPurchasePayload>
    suspend fun queryPurchaseHistory(productType: AppActorProductType): List<AppActorBillingPurchaseHistoryPayload>
    suspend fun acknowledgePurchase(purchaseToken: String)
    suspend fun consumePurchase(purchaseToken: String)
}

internal fun AppActorSubscriptionReplacementMode.toBillingReplacementMode(): Int {
    return when (this) {
        AppActorSubscriptionReplacementMode.WithTimeProration ->
            BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION
        AppActorSubscriptionReplacementMode.ChargeProrated ->
            BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
        AppActorSubscriptionReplacementMode.WithoutProration ->
            BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION
        AppActorSubscriptionReplacementMode.ChargeFullPrice ->
            BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE
        AppActorSubscriptionReplacementMode.Deferred ->
            BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED
    }
}
