package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

internal class CanMakePurchasesRequest private constructor(
    private val requiredCapabilities: Set<AppActorStoreCapability>,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        return PluginResult.encoding(Boolean.serializer(), AppActor.canMakePurchases(requiredCapabilities))
    }

    companion object : PluginRequestFactory {
        override val method: String = "can_make_purchases"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            val capabilities = p.capabilities?.mapNotNull { parseCapability(it) }?.toSet() ?: emptySet()
            return CanMakePurchasesRequest(capabilities)
        }

        private fun parseCapability(value: String): AppActorStoreCapability? {
            return when (value) {
                "purchases" -> AppActorStoreCapability.Purchases
                "subscriptions" -> AppActorStoreCapability.Subscriptions
                "in_app_products" -> AppActorStoreCapability.InAppProducts
                "purchase_history" -> AppActorStoreCapability.PurchaseHistory
                "storefront" -> AppActorStoreCapability.Storefront
                else -> null
            }
        }

        @Serializable
        private data class Params(
            @SerialName("required_capabilities") val capabilities: List<String>? = null,
        )
    }
}
