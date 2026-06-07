package com.appactor.android.pipeline

import com.appactor.android.models.AppActorConfiguration

/**
 * Owns the pending-purchase and recent-foreground-purchase bookkeeping for the
 * payment pipeline:
 *  - [pendingPurchaseTokens]: purchaseToken -> serialized [PendingPurchaseEntry]
 *    used to correlate deferred/transaction-update purchases back to the
 *    originating purchase attempt, durably persisted across sessions via
 *    [SharedPreferences].
 *  - [foregroundPurchaseProductExpiries] / [foregroundPurchaseProductContexts]:
 *    short-lived in-memory markers tying a freshly launched foreground purchase
 *    to the [AppActorClientPurchaseContext] that started it.
 *
 * All three maps are [java.util.concurrent.ConcurrentHashMap] and therefore
 * individually thread-safe; this registry intentionally adds no lock of its own.
 * Every method here is synchronous and lock-free, so callers may invoke them
 * while already holding the pipeline mutex without risk of re-entrancy or a new
 * suspension point.
 */
internal class AppActorPendingPurchaseRegistry(
    private val configuration: AppActorConfiguration,
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
) {

    // Key: purchaseToken, Value: legacy "productId|timestampMillis" or
    // "productId|recordedAtMillis|attemptStartedAtMillis|attemptId".
    private val pendingPurchaseTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val foregroundPurchaseProductExpiries = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val foregroundPurchaseProductContexts = java.util.concurrent.ConcurrentHashMap<String, AppActorClientPurchaseContext>()
    private val pendingPrefs = configuration.applicationContext.getSharedPreferences(
        PENDING_PREFS_NAME, android.content.Context.MODE_PRIVATE,
    )

    init {
        // Restore persisted pending purchase state from previous session
        val now = dateProviderMillis()
        pendingPrefs.all.forEach { (token, value) ->
            val entry = value as? String ?: return@forEach
            val pendingEntry = PendingPurchaseEntry.parse(entry) ?: return@forEach
            if (now - pendingEntry.recordedAtMillis < PENDING_EXPIRY_MILLIS) {
                pendingPurchaseTokens[token] = entry
            }
        }
    }

    fun hasPendingTokens(): Boolean = pendingPurchaseTokens.isNotEmpty()

    fun putPendingEntry(purchaseToken: String, entry: String) {
        pendingPurchaseTokens[purchaseToken] = entry
    }

    fun persist() {
        pendingPrefs.edit().apply {
            clear()
            pendingPurchaseTokens.forEach { (token, entry) -> putString(token, entry) }
            apply()
        }
    }

    /**
     * Atomically resolves the pending entry for [purchaseToken]: removes it if
     * present (whether expired or matched) and returns its productId when the
     * entry was still valid, or null otherwise. Persists only when a resolution
     * actually occurred. Mirrors the original [java.util.concurrent.ConcurrentHashMap.compute]
     * so the read + expiry-check + remove stay a single atomic operation.
     */
    fun resolveDeferredEntry(purchaseToken: String): String? {
        var resolvedProductId: String? = null
        pendingPurchaseTokens.compute(purchaseToken) { _, entry ->
            if (entry == null) return@compute null
            val pendingEntry = PendingPurchaseEntry.parse(entry) ?: return@compute null
            if (dateProviderMillis() - pendingEntry.recordedAtMillis > PENDING_EXPIRY_MILLIS) {
                null // Expired — stale entry from abandoned pending purchase
            } else {
                resolvedProductId = pendingEntry.productId
                null // Remove — resolved
            }
        }
        if (resolvedProductId != null) {
            persist()
        }
        return resolvedProductId
    }

    /**
     * Returns the still-valid [PendingPurchaseEntry] for [purchaseToken] paired
     * with the clock sample used for its expiry check, so the caller can reuse the
     * exact same `now` for the resulting context's observedAt. Mirrors the
     * original ordering precisely: the clock is read only when a raw entry is
     * present (zero samples when the token is absent). When the entry is absent or
     * expired, removes it, persists, and returns null.
     */
    fun takePendingEntryIfValid(purchaseToken: String): Pair<PendingPurchaseEntry, Long>? {
        val rawEntry = pendingPurchaseTokens[purchaseToken] ?: return null
        val entry = PendingPurchaseEntry.parse(rawEntry)
        val now = dateProviderMillis()
        if (entry != null && now - entry.recordedAtMillis <= PENDING_EXPIRY_MILLIS) {
            return entry to now
        }
        pendingPurchaseTokens.remove(purchaseToken)
        persist()
        return null
    }

    fun markForegroundPurchaseProduct(
        productId: String,
        ttlMillis: Long = FOREGROUND_PURCHASE_EXPIRY_MILLIS,
        clientPurchaseContext: AppActorClientPurchaseContext? = null,
    ) {
        val now = dateProviderMillis()
        foregroundPurchaseProductExpiries.entries.removeIf { it.value <= now }
        foregroundPurchaseProductExpiries[productId] = now + ttlMillis
        if (clientPurchaseContext != null) {
            foregroundPurchaseProductContexts[productId] = clientPurchaseContext
        }
    }

    fun clearForegroundPurchaseProduct(productId: String) {
        foregroundPurchaseProductExpiries.remove(productId)
        foregroundPurchaseProductContexts.remove(productId)
    }

    fun consumeRecentForegroundPurchaseContext(productId: String): AppActorClientPurchaseContext? {
        val expiresAt = foregroundPurchaseProductExpiries[productId] ?: return null
        if (dateProviderMillis() >= expiresAt) {
            foregroundPurchaseProductExpiries.remove(productId)
            foregroundPurchaseProductContexts.remove(productId)
            return null
        }
        foregroundPurchaseProductExpiries.remove(productId)
        return foregroundPurchaseProductContexts.remove(productId)
    }

    private companion object {
        const val PENDING_PREFS_NAME = "com.appactor.android.pending_purchases"
        const val PENDING_EXPIRY_MILLIS: Long = 7 * 24 * 60 * 60 * 1_000L // 7 days
        const val FOREGROUND_PURCHASE_EXPIRY_MILLIS: Long = 10 * 60 * 1_000L // 10 minutes
    }
}
