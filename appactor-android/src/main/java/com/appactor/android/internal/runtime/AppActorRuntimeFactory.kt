package com.appactor.android.internal.runtime

import android.content.Context
import com.appactor.android.backend.client.AppActorHttpBackendClient
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorCustomerCacheStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorExperimentCacheStore
import com.appactor.android.cache.AppActorOfflineProductCatalogStore
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.cache.AppActorRemoteConfigsCacheStore
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.managers.AppActorAttributesManager
import com.appactor.android.managers.AppActorExperimentManager
import com.appactor.android.managers.AppActorOfferingsManager
import com.appactor.android.managers.AppActorRemoteConfigManager
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.pipeline.AppActorPaymentProcessor
import com.appactor.android.storage.AppActorAtomicJsonPostedLedgerStore
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import com.appactor.android.storage.AppActorSharedPrefsAttributeQueueStore
import com.appactor.android.storage.AppActorSharedPrefsIdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

internal class AppActorRuntimeFactory(
    private val storeAdapterFactory: (Context) -> AppActorStoreAdapter,
    private val appVersionProvider: (Context) -> String?,
    private val countryProvider: () -> String?,
) {

    fun create(
        configuration: AppActorConfiguration,
        sessionId: Long,
        callbackState: AppActorCallbackState,
        onPipelineEvent: (AppActorReceiptPipelineEvent) -> Unit,
    ): AppActorRuntimeState {
        val identityStore = AppActorSharedPrefsIdentityStore(configuration.applicationContext)
        val cacheDiskStore = AppActorCacheDiskStore(configuration.applicationContext)
        val eTagManager = AppActorETagManager(
            diskStore = cacheDiskStore,
            responseVerificationEnabled = configuration.options.verifyResponseSignatures ||
                configuration.options.requireResponseSignatures,
        )
        eTagManager.clearUnverifiedIfNeeded()

        identityStore.installId
        identityStore.resolveAppUserId(configuration.appUserId)
        identityStore.clearLegacyIdentityState()

        val backendClient = AppActorHttpBackendClient(configuration)
        val storeAdapter = storeAdapterFactory(configuration.applicationContext)
        val offeringsCacheStore = AppActorOfferingsCacheStore(eTagManager)
        val offlineProductCatalogStore = AppActorOfflineProductCatalogStore(eTagManager)
        val customerCacheStore = AppActorCustomerCacheStore(eTagManager)
        val remoteConfigsCacheStore = AppActorRemoteConfigsCacheStore(eTagManager)
        val experimentCacheStore = AppActorExperimentCacheStore(eTagManager)
        val attributeQueueStore = AppActorSharedPrefsAttributeQueueStore(configuration.applicationContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val offeringsManager = AppActorOfferingsManager(
            backendClient = backendClient,
            cacheStore = offeringsCacheStore,
            offlineProductCatalogStore = offlineProductCatalogStore,
            storeAdapter = storeAdapter,
            backgroundScope = scope,
        )
        val customerManager = AppActorCustomerManager(
            configuration = configuration,
            backendClient = backendClient,
            cacheStore = customerCacheStore,
            identityStore = identityStore,
            offeringsManager = offeringsManager,
            offlineProductCatalogStore = offlineProductCatalogStore,
            storeAdapter = storeAdapter,
        )
        val remoteConfigManager = AppActorRemoteConfigManager(
            backendClient = backendClient,
            cacheStore = remoteConfigsCacheStore,
            appVersionProvider = { appVersionProvider(configuration.applicationContext) },
            countryProvider = countryProvider,
        )
        val experimentManager = AppActorExperimentManager(
            backendClient = backendClient,
            cacheStore = experimentCacheStore,
            appVersionProvider = { appVersionProvider(configuration.applicationContext) },
            countryProvider = countryProvider,
        )
        val receiptQueueStore = AppActorAtomicJsonReceiptQueueStore(configuration.applicationContext)
        val postedLedgerStore = AppActorAtomicJsonPostedLedgerStore(configuration.applicationContext)
        val attributesManager = AppActorAttributesManager(
            backendClient = backendClient,
            queueStore = attributeQueueStore,
            identityStore = identityStore,
            packageName = configuration.applicationContext.packageName,
            appVersionProvider = { appVersionProvider(configuration.applicationContext) },
            platformInfoProvider = { configuration.options.platformInfo },
            countryProvider = countryProvider,
        )
        val cachedCustomerInfo = identityStore.currentAppUserId
            ?.let(customerManager::cachedInfo)
            ?: AppActorCustomerInfo.empty

        return AppActorRuntimeState(
            sessionId = sessionId,
            configuration = configuration,
            identityStore = identityStore,
            eTagManager = eTagManager,
            backendClient = backendClient,
            storeAdapter = storeAdapter,
            offeringsCacheStore = offeringsCacheStore,
            offlineProductCatalogStore = offlineProductCatalogStore,
            customerCacheStore = customerCacheStore,
            receiptQueueStore = receiptQueueStore,
            postedLedgerStore = postedLedgerStore,
            offeringsManager = offeringsManager,
            customerManager = customerManager,
            attributesManager = attributesManager,
            paymentProcessor = AppActorPaymentProcessor(
                configuration = configuration,
                backendClient = backendClient,
                storeAdapter = storeAdapter,
                queueStore = receiptQueueStore,
                postedLedgerStore = postedLedgerStore,
                customerManager = customerManager,
                identityStore = identityStore,
                offeringsManager = offeringsManager,
                offlineProductCatalogStore = offlineProductCatalogStore,
                packageName = configuration.applicationContext.packageName,
                onPipelineEvent = onPipelineEvent,
                backgroundScope = scope,
            ),
            scope = scope,
            remoteConfigManager = remoteConfigManager,
            experimentManager = experimentManager,
            onCustomerInfoChanged = callbackState.onCustomerInfoChanged,
            onReceiptPipelineEvent = callbackState.onReceiptPipelineEvent,
            customerInfoStateFlow = MutableStateFlow(cachedCustomerInfo),
            lastCustomerInfo = cachedCustomerInfo,
        )
    }
}
