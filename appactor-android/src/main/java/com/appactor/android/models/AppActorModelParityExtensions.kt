package com.appactor.android.models

import java.time.Instant

// Nothing else in the SDK uses java.time, so an app that doesn't call the *Instant helpers runs on
// API 24 without core library desugaring.
private fun String?.toInstantOrNull(): Instant? {
    val value = this?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorCustomerInfo.snapshotInstant: Instant?
    get() = snapshotDate.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorCustomerInfo.requestInstant: Instant?
    get() = requestDate.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorCustomerInfo.firstSeenInstant: Instant?
    get() = firstSeen.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorCustomerInfo.lastSeenInstant: Instant?
    get() = lastSeen.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorPurchaseInfo.purchaseInstant: Instant?
    get() = purchaseDate.toInstantOrNull()

public val AppActorEntitlementInfo.id: String
    get() = identifier

public val AppActorEntitlementInfo.productId: String?
    get() = productIdentifier

public val AppActorEntitlementInfo.storeOrNull: AppActorStore?
    get() = store.takeUnless { it == AppActorStore.Unknown }

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorEntitlementInfo.purchaseInstant: Instant?
    get() = purchaseDate.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorEntitlementInfo.startsAtInstant: Instant?
    get() = startsAt.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorEntitlementInfo.latestPurchaseInstant: Instant?
    get() = latestPurchaseDate.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorEntitlementInfo.originalPurchaseInstant: Instant?
    get() = originalPurchaseDate.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorEntitlementInfo.expirationInstant: Instant?
    get() = expirationDate.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorEntitlementInfo.gracePeriodExpiresInstant: Instant?
    get() = gracePeriodExpiresAt.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorEntitlementInfo.billingIssueDetectedInstant: Instant?
    get() = billingIssueDetectedAt.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorEntitlementInfo.unsubscribeDetectedInstant: Instant?
    get() = unsubscribeDetectedAt.toInstantOrNull()

/** Returns [java.time.Instant], which needs API 26 or core library desugaring. */
public val AppActorEntitlementInfo.renewedInstant: Instant?
    get() = renewedAt.toInstantOrNull()
