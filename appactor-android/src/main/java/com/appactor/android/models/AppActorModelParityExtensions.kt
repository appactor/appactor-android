package com.appactor.android.models

import java.time.Instant

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
