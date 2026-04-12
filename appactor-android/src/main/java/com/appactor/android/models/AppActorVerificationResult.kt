package com.appactor.android.models

import kotlinx.serialization.Serializable

/**
 * The result of verifying a server response's cryptographic signature.
 *
 * Exposed on [AppActorCustomerInfo] and [AppActorOfferings] so the app
 * can react to verification failures.
 */
@Serializable
public enum class AppActorVerificationResult(public val wireValue: String) {
    /** Verification was not performed (signing disabled or transitional). */
    NotRequested("notRequested"),

    /** Response signature was successfully verified. */
    Verified("verified"),

    /** Response signature verification failed — possible tampering. */
    Failed("failed"),
    ;

    /** True when the response has been cryptographically verified. */
    public val isVerified: Boolean get() = this == Verified

    public companion object {
        /**
         * Maps the internal `signatureVerified` boolean to a public result.
         *
         * `true` → [Verified], `false` → [NotRequested] (never [Failed] — failures throw).
         */
        public fun from(signatureVerified: Boolean): AppActorVerificationResult =
            if (signatureVerified) Verified else NotRequested
    }
}
