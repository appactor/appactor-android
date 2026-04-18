package com.appactor.android.models

/**
 * Controls how [com.appactor.android.api.AppActor.offerings] uses cached offerings data.
 */
public enum class AppActorOfferingsFetchPolicy(public val wireValue: String) {
    /**
     * Return fresh cache immediately. If cache is stale or missing, wait for a
     * fresh network response before returning.
     */
    FreshIfStale("freshIfStale"),

    /**
     * Return suitable cached data immediately when available and refresh in the
     * background.
     */
    ReturnCachedThenRefresh("returnCachedThenRefresh"),

    /**
     * Return only suitable cached data. Throw when no locale-compatible cache exists.
     */
    CacheOnly("cacheOnly"),
    ;

    public companion object {
        public fun fromWireValue(value: String?): AppActorOfferingsFetchPolicy? {
            return entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) }
        }
    }
}
