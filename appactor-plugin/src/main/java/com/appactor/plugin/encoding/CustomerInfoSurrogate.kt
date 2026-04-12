package com.appactor.plugin.encoding

import com.appactor.android.models.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class CustomerInfoSurrogate(
    @SerialName("entitlements") val entitlements: Map<String, EntitlementInfoSurrogate> = emptyMap(),
    @SerialName("subscriptions") val subscriptions: Map<String, SubscriptionInfoSurrogate> = emptyMap(),
    @SerialName("non_subscriptions") val nonSubscriptions: Map<String, List<NonSubscriptionSurrogate>> = emptyMap(),
    @SerialName("consumable_balances") val consumableBalances: Map<String, Int>? = null,
    @SerialName("token_balance") val tokenBalance: TokenBalanceSurrogate? = null,
    @SerialName("snapshot_date") val snapshotDate: String? = null,
    @SerialName("app_user_id") val appUserId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("request_date") val requestDate: String? = null,
    @SerialName("first_seen") val firstSeen: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("management_url") val managementUrl: String? = null,
    @SerialName("is_computed_offline") val isComputedOffline: Boolean = false,
    @SerialName("product_entitlements") val productEntitlements: Map<String, List<String>> = emptyMap(),
    @SerialName("active_entitlement_keys") val activeEntitlementKeys: Set<String> = emptySet(),
    @SerialName("verification") val verification: String = "notRequested",
) {
    constructor(from: AppActorCustomerInfo) : this(
        entitlements = from.entitlements.mapValues { EntitlementInfoSurrogate(it.value) },
        subscriptions = from.subscriptions.mapValues { SubscriptionInfoSurrogate(it.value) },
        nonSubscriptions = from.nonSubscriptions.mapValues { (_, list) -> list.map { NonSubscriptionSurrogate(it) } },
        consumableBalances = from.consumableBalances,
        tokenBalance = from.tokenBalance?.let { TokenBalanceSurrogate(it) },
        snapshotDate = from.snapshotDate,
        appUserId = from.appUserId,
        requestId = from.requestId,
        requestDate = from.requestDate,
        firstSeen = from.firstSeen,
        lastSeen = from.lastSeen,
        managementUrl = from.managementUrl,
        isComputedOffline = from.isComputedOffline,
        productEntitlements = from.productEntitlements,
        activeEntitlementKeys = from.activeEntitlementKeys,
        verification = from.verification.wireValue,
    )
}

@Serializable
internal data class EntitlementInfoSurrogate(
    val identifier: String,
    @SerialName("is_active") val isActive: Boolean,
    val status: String? = null,
    @SerialName("product_identifier") val productIdentifier: String? = null,
    @SerialName("granted_by") val grantedBy: String? = null,
    @SerialName("ownership_type") val ownershipType: String,
    @SerialName("period_type") val periodType: String,
    @SerialName("will_renew") val willRenew: Boolean = false,
    @SerialName("subscription_status") val subscriptionStatus: String? = null,
    val store: String,
    @SerialName("base_plan_id") val basePlanId: String? = null,
    @SerialName("offer_id") val offerId: String? = null,
    @SerialName("is_sandbox") val isSandbox: Boolean? = null,
    @SerialName("cancellation_reason") val cancellationReason: String? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("latest_purchase_date") val latestPurchaseDate: String? = null,
    @SerialName("original_purchase_date") val originalPurchaseDate: String? = null,
    @SerialName("expiration_date") val expirationDate: String? = null,
    @SerialName("grace_period_expires_at") val gracePeriodExpiresAt: String? = null,
    @SerialName("billing_issue_detected_at") val billingIssueDetectedAt: String? = null,
    @SerialName("unsubscribe_detected_at") val unsubscribeDetectedAt: String? = null,
    @SerialName("renewed_at") val renewedAt: String? = null,
    @SerialName("active_promotional_offer_type") val activePromotionalOfferType: String? = null,
    @SerialName("active_promotional_offer_id") val activePromotionalOfferId: String? = null,
) {
    constructor(from: AppActorEntitlementInfo) : this(
        identifier = from.identifier,
        isActive = from.isActive,
        status = from.status,
        productIdentifier = from.productIdentifier,
        grantedBy = from.grantedBy,
        ownershipType = from.ownershipType.wireValue,
        periodType = from.periodType.wireValue,
        willRenew = from.willRenew,
        subscriptionStatus = from.subscriptionStatus?.wireValue,
        store = from.store.wireValue,
        basePlanId = from.basePlanId,
        offerId = from.offerId,
        isSandbox = from.isSandbox,
        cancellationReason = from.cancellationReason?.wireValue,
        purchaseDate = from.purchaseDate,
        startsAt = from.startsAt,
        latestPurchaseDate = from.latestPurchaseDate,
        originalPurchaseDate = from.originalPurchaseDate,
        expirationDate = from.expirationDate,
        gracePeriodExpiresAt = from.gracePeriodExpiresAt,
        billingIssueDetectedAt = from.billingIssueDetectedAt,
        unsubscribeDetectedAt = from.unsubscribeDetectedAt,
        renewedAt = from.renewedAt,
        activePromotionalOfferType = from.activePromotionalOfferType,
        activePromotionalOfferId = from.activePromotionalOfferId,
    )
}

@Serializable
internal data class SubscriptionInfoSurrogate(
    @SerialName("subscription_key") val subscriptionKey: String,
    @SerialName("product_identifier") val productIdentifier: String,
    val store: String,
    @SerialName("base_plan_id") val basePlanId: String? = null,
    @SerialName("offer_id") val offerId: String? = null,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("expires_date") val expiresDate: String? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("period_type") val periodType: String? = null,
    val status: String? = null,
    @SerialName("auto_renew") val autoRenew: Boolean? = null,
    @SerialName("is_sandbox") val isSandbox: Boolean? = null,
    @SerialName("grace_period_expires_at") val gracePeriodExpiresAt: String? = null,
    @SerialName("unsubscribe_detected_at") val unsubscribeDetectedAt: String? = null,
    @SerialName("cancellation_reason") val cancellationReason: String? = null,
    @SerialName("renewed_at") val renewedAt: String? = null,
    @SerialName("original_transaction_id") val originalTransactionId: String? = null,
    @SerialName("latest_transaction_id") val latestTransactionId: String? = null,
    @SerialName("active_promotional_offer_type") val activePromotionalOfferType: String? = null,
    @SerialName("active_promotional_offer_id") val activePromotionalOfferId: String? = null,
) {
    constructor(from: AppActorSubscriptionInfo) : this(
        subscriptionKey = from.subscriptionKey,
        productIdentifier = from.productIdentifier,
        store = from.store.wireValue,
        basePlanId = from.basePlanId,
        offerId = from.offerId,
        isActive = from.isActive,
        expiresDate = from.expiresDate,
        purchaseDate = from.purchaseDate,
        startsAt = from.startsAt,
        periodType = from.periodType?.wireValue,
        status = from.status,
        autoRenew = from.autoRenew,
        isSandbox = from.isSandbox,
        gracePeriodExpiresAt = from.gracePeriodExpiresAt,
        unsubscribeDetectedAt = from.unsubscribeDetectedAt,
        cancellationReason = from.cancellationReason?.wireValue,
        renewedAt = from.renewedAt,
        originalTransactionId = from.originalTransactionId,
        latestTransactionId = from.latestTransactionId,
        activePromotionalOfferType = from.activePromotionalOfferType,
        activePromotionalOfferId = from.activePromotionalOfferId,
    )
}

@Serializable
internal data class NonSubscriptionSurrogate(
    @SerialName("product_identifier") val productIdentifier: String,
    val store: String,
    @SerialName("base_plan_id") val basePlanId: String? = null,
    @SerialName("offer_id") val offerId: String? = null,
    @SerialName("original_transaction_identifier") val originalTransactionIdentifier: String? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("store_transaction_identifier") val storeTransactionIdentifier: String? = null,
    @SerialName("is_sandbox") val isSandbox: Boolean? = null,
    @SerialName("is_consumable") val isConsumable: Boolean? = null,
    @SerialName("is_refund") val isRefund: Boolean? = null,
) {
    constructor(from: AppActorNonSubscription) : this(
        productIdentifier = from.productIdentifier,
        store = from.store.wireValue,
        basePlanId = from.basePlanId,
        offerId = from.offerId,
        originalTransactionIdentifier = from.originalTransactionIdentifier,
        purchaseDate = from.purchaseDate,
        storeTransactionIdentifier = from.storeTransactionIdentifier,
        isSandbox = from.isSandbox,
        isConsumable = from.isConsumable,
        isRefund = from.isRefund,
    )
}

@Serializable
internal data class TokenBalanceSurrogate(
    val renewable: Int,
    @SerialName("non_renewable") val nonRenewable: Int,
    val total: Int,
) {
    constructor(from: AppActorTokenBalance) : this(
        renewable = from.renewable,
        nonRenewable = from.nonRenewable,
        total = from.total,
    )
}
