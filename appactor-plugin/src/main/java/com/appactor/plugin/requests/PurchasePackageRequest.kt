package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorSubscriptionReplacementMode
import com.appactor.plugin.AppActorPlugin
import com.appactor.plugin.encoding.PurchaseResultSurrogate
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class PurchasePackageRequest private constructor(
    private val packageId: String,
    private val offeringId: String?,
    private val oldPurchaseToken: String?,
    private val replacementMode: String?,
    private val placement: String?,
    private val quantity: Int?,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        if (quantity != null && quantity < 1) {
            return PluginResult.Error(PluginError(PluginError.SDK_VALIDATION, "Purchase quantity must be at least 1."))
        }
        if (quantity != null && quantity != 1) {
            return PluginResult.Error(PluginError(PluginError.SDK_VALIDATION, "Android purchase quantity greater than 1 is not supported."))
        }

        val activity = AppActorPlugin.activityRef?.get()
            ?: return PluginResult.Error(PluginError(PluginError.MISSING_ACTIVITY, "Activity not set."))

        val offerings = AppActor.cachedOfferings
            ?: return PluginResult.Error(PluginError(PluginError.SDK_VALIDATION, "Offerings not loaded. Call get_offerings first."))

        val pkg = if (offeringId != null) {
            offerings.offering(offeringId)?.packages?.firstOrNull { it.id == packageId }
        } else {
            offerings.all.values.flatMap { it.packages }.firstOrNull { it.id == packageId }
        }
            ?: return PluginResult.Error(PluginError(PluginError.SDK_VALIDATION, "Package '$packageId' not found in cached offerings"))

        val finalPkg = if (oldPurchaseToken != null || replacementMode != null) {
            pkg.copy(
                oldPurchaseToken = oldPurchaseToken,
                replacementMode = parseReplacementMode(replacementMode),
            )
        } else {
            pkg
        }

        val result = AppActor.purchase(activity, finalPkg, placement)
        return PluginResult.encoding(PurchaseResultSurrogate.serializer(), PurchaseResultSurrogate(result))
    }

    companion object : PluginRequestFactory {
        override val method: String = "purchase_package"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return PurchasePackageRequest(
                p.packageId,
                p.offeringId,
                p.oldPurchaseToken,
                p.replacementMode,
                p.placement,
                p.quantity,
            )
        }

        private fun parseReplacementMode(value: String?): AppActorSubscriptionReplacementMode? {
            return when (value) {
                "with_time_proration" -> AppActorSubscriptionReplacementMode.WithTimeProration
                "charge_prorated" -> AppActorSubscriptionReplacementMode.ChargeProrated
                "without_proration" -> AppActorSubscriptionReplacementMode.WithoutProration
                "charge_full_price" -> AppActorSubscriptionReplacementMode.ChargeFullPrice
                "deferred" -> AppActorSubscriptionReplacementMode.Deferred
                else -> null
            }
        }

        @Serializable
        private data class Params(
            @SerialName("package_id") val packageId: String,
            @SerialName("offering_id") val offeringId: String? = null,
            @SerialName("old_purchase_token") val oldPurchaseToken: String? = null,
            @SerialName("replacement_mode") val replacementMode: String? = null,
            @SerialName("placement") val placement: String? = null,
            @SerialName("quantity") val quantity: Int? = null,
        )
    }
}
