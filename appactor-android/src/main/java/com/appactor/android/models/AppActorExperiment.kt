package com.appactor.android.models

import kotlinx.serialization.json.JsonNull

/**
 * A user's standing in one experiment — always returned, also when the user is not in it,
 * so callers never null-check. Use `AppActor.getExperiment(experimentKey)` to get one (examples there).
 */
public data class AppActorExperiment(
    /** The developer-defined experiment key this was resolved for. */
    val experimentKey: String,
    /** The raw assignment; `null` when the user is not in the experiment (not targeted, not running, …). */
    val assignment: AppActorExperimentAssignment?,
) {
    /** `true` when the user has a variant in this experiment. */
    public val isEnrolled: Boolean
        get() = assignment != null

    /** The assigned variant's key (e.g. `"control"`), or `null` when not enrolled. */
    public val variantKey: String?
        get() = assignment?.variantKey

    /** The variant's payload; a JSON `null` value when not enrolled. */
    public val payload: AppActorConfigValue
        get() = assignment?.payload ?: NULL_VALUE

    /** `true` when the user is enrolled in the variant with this key. */
    public fun isVariant(variantKey: String): Boolean = assignment?.variantKey == variantKey

    /** The payload as a `Boolean`, or [defaultValue] when not enrolled or not a boolean. */
    public fun boolValue(defaultValue: Boolean): Boolean = payload.boolValue ?: defaultValue

    /** The payload as a `String`, or [defaultValue] when not enrolled or not a string. */
    public fun stringValue(defaultValue: String): String = payload.stringValue ?: defaultValue

    /** The payload as an `Int`, or [defaultValue] when not enrolled or not a whole number. */
    public fun intValue(defaultValue: Int): Int = payload.intValue ?: defaultValue

    /** The payload as a `Double`, or [defaultValue] when not enrolled or not a number. */
    public fun doubleValue(defaultValue: Double): Double = payload.doubleValue ?: defaultValue

    /** A key of a JSON payload: `experiment["title"]?.stringValue`. */
    public operator fun get(key: String): AppActorConfigValue? = payload[key]

    private companion object {
        val NULL_VALUE = AppActorConfigValue(JsonNull)
    }
}
