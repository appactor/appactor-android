package com.appactor.plugin.infrastructure

import com.appactor.plugin.requests.*
import java.util.concurrent.ConcurrentHashMap

internal object PluginRequestRouter {

    private val registry: MutableMap<String, PluginRequestFactory> = ConcurrentHashMap()

    fun registerDefaults() {
        val defaults: List<PluginRequestFactory> = listOf(
            ConfigureRequest,
            LogInRequest,
            LogOutRequest,
            PurchasePackageRequest,
            RestorePurchasesRequest,
            SyncPurchasesRequest,
            QuietSyncPurchasesRequest,
            DrainReceiptQueueAndRefreshCustomerRequest,
            GetCustomerInfoRequest,
            ActiveEntitlementsOfflineRequest,
            GetOfferingsRequest,
            GetRemoteConfigsRequest,
            GetExperimentAssignmentRequest,
            ResetRequest,
            SetLogLevelRequest,
            GetSdkVersionRequest,
            GetAppUserIdRequest,
            GetIsAnonymousRequest,
            GetCachedOfferingsRequest,
            GetCachedRemoteConfigsRequest,
            GetCachedCustomerInfoRequest,
            GetRemoteConfigRequest,
            EnableInstallReferrerRequest,
            SetFallbackOfferingsRequest,
            CanMakePurchasesRequest,
            GetStorefrontRequest,
            GetStoreCapabilitiesRequest,
            SetAttributesRequest,
            SetAttributeRequest,
            UnsetAttributeRequest,
            SetEmailRequest,
            SetDisplayNameRequest,
            SetPhoneNumberRequest,
            SetPushTokenRequest,
            CollectDeviceIdentifiersRequest,
            SetIntegrationIdentifierRequest,
            UpdateAttributionRequest,
            SetMediaSourceRequest,
            SetCampaignRequest,
            SetAdGroupRequest,
            SetAdRequest,
            SetKeywordRequest,
            SetCreativeRequest,
        )
        defaults.forEach { registry[it.method] = it }
    }

    fun register(factories: List<PluginRequestFactory>) {
        factories.forEach { registry[it.method] = it }
    }

    fun remove(methods: List<String>) {
        methods.forEach { registry.remove(it) }
    }

    val availableMethods: List<String> get() = registry.keys.sorted()

    suspend fun route(method: String, json: String): PluginResult {
        val factory = registry[method]
            ?: return PluginResult.Error(
                PluginError(
                    code = PluginError.UNKNOWN_METHOD,
                    message = "Unknown method: '$method'",
                    detail = "Available: ${availableMethods.joinToString(", ")}",
                )
            )

        val request: PluginRequest = try {
            factory.create(json)
        } catch (e: Exception) {
            return PluginResult.Error(
                PluginError(
                    code = PluginError.DECODING_FAILED,
                    message = "Failed to decode params for '$method'",
                    detail = e.message ?: "",
                )
            )
        }

        return try {
            request.execute()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PluginResult.Error(PluginError.fromException(e))
        }
    }
}
