package com.appactor.android.models

import java.time.Instant

// The *Instant helpers return java.time.Instant, which Android has only from API 26. An app with
// minSdk 24 or 25 that calls them needs core library desugaring. Nothing else in the SDK uses
// java.time, so an app that doesn't call them runs on API 24 without it.
private fun String?.toInstantOrNull(): Instant? {
    val value = this?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

public val AppActorCustomerInfo.snapshotInstant: Instant?
    get() = snapshotDate.toInstantOrNull()

public val AppActorCustomerInfo.requestInstant: Instant?
    get() = requestDate.toInstantOrNull()

public val AppActorCustomerInfo.firstSeenInstant: Instant?
    get() = firstSeen.toInstantOrNull()

public val AppActorCustomerInfo.lastSeenInstant: Instant?
    get() = lastSeen.toInstantOrNull()

public val AppActorPurchaseInfo.purchaseInstant: Instant?
    get() = purchaseDate.toInstantOrNull()

public val AppActorEntitlementInfo.id: String
    get() = identifier

public val AppActorEntitlementInfo.productId: String?
    get() = productIdentifier

public val AppActorEntitlementInfo.storeOrNull: AppActorStore?
    get() = store.takeUnless { it == AppActorStore.Unknown }

public val AppActorEntitlementInfo.purchaseInstant: Instant?
    get() = purchaseDate.toInstantOrNull()

public val AppActorEntitlementInfo.startsAtInstant: Instant?
    get() = startsAt.toInstantOrNull()

public val AppActorEntitlementInfo.latestPurchaseInstant: Instant?
    get() = latestPurchaseDate.toInstantOrNull()

public val AppActorEntitlementInfo.originalPurchaseInstant: Instant?
    get() = originalPurchaseDate.toInstantOrNull()

public val AppActorEntitlementInfo.expirationInstant: Instant?
    get() = expirationDate.toInstantOrNull()

public val AppActorEntitlementInfo.gracePeriodExpiresInstant: Instant?
    get() = gracePeriodExpiresAt.toInstantOrNull()

public val AppActorEntitlementInfo.billingIssueDetectedInstant: Instant?
    get() = billingIssueDetectedAt.toInstantOrNull()

public val AppActorEntitlementInfo.unsubscribeDetectedInstant: Instant?
    get() = unsubscribeDetectedAt.toInstantOrNull()

public val AppActorEntitlementInfo.renewedInstant: Instant?
    get() = renewedAt.toInstantOrNull()
