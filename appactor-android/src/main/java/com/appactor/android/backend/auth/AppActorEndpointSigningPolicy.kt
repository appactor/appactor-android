package com.appactor.android.backend.auth

/**
 * Determines whether an endpoint requires a nonce for response signing.
 *
 * Nonce-required endpoints get per-request replay protection.
 * Nonce-free endpoints get salt-based signing, enabling CDN caching.
 */
internal enum class AppActorEndpointSigningPolicy {
    NonceRequired,
    NonceFree,
    ;

    val needsNonce: Boolean get() = this == NonceRequired

    internal companion object {
        private val nonceFreePathSuffixes: List<String> = listOf(
            "/v1/payment/offerings",
            "/v1/remote-config",
        )

        fun forPath(path: String): AppActorEndpointSigningPolicy {
            return if (nonceFreePathSuffixes.any { path.endsWith(it) }) NonceFree else NonceRequired
        }
    }
}
