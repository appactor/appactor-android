package com.appactor.android.internal.runtime

import com.appactor.android.api.AppActorLifecycleCallbacks
import com.appactor.android.backend.client.AppActorHttpBackendClient
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.cache.AppActorCustomerCacheStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorExperimentCacheStore
import com.appactor.android.cache.AppActorOfflineProductCatalogStore
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.cache.AppActorRemoteConfigsCacheStore
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.managers.AppActorExperimentManager
import com.appactor.android.managers.AppActorOfferingsManager
import com.appactor.android.managers.AppActorRemoteConfigManager
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.pipeline.AppActorPaymentProcessor
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorPostedLedgerStore
import com.appactor.android.storage.AppActorReceiptQueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

internal data class AppActorRuntimeState(
    val sessionId: Long,
    val configuration: AppActorConfiguration,
    val identityStore: AppActorIdentityStore,
    val eTagManager: AppActorETagManager,
    val backendClient: AppActorHttpBackendClient,
    val storeAdapter: AppActorStoreAdapter,
    val offeringsCacheStore: AppActorOfferingsCacheStore,
    val offlineProductCatalogStore: AppActorOfflineProductCatalogStore,
    val customerCacheStore: AppActorCustomerCacheStore,
    val receiptQueueStore: AppActorReceiptQueueStore,
    val postedLedgerStore: AppActorPostedLedgerStore,
    val offeringsManager: AppActorOfferingsManager,
    val customerManager: AppActorCustomerManager,
    val paymentProcessor: AppActorPaymentProcessor,
    val scope: CoroutineScope,
    val bootstrapCompletionJob: kotlinx.coroutines.Job? = null,
    val purchaseUpdatesJob: kotlinx.coroutines.Job? = null,
    val lifecycleCallbacks: AppActorLifecycleCallbacks? = null,
    val remoteConfigManager: AppActorRemoteConfigManager,
    val experimentManager: AppActorExperimentManager,
    val onCustomerInfoChanged: ((AppActorCustomerInfo) -> Unit)? = null,
    val onReceiptPipelineEvent: ((AppActorReceiptPipelineEvent) -> Unit)? = null,
    val onDeferredPurchaseResolved: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)? = null,
    val customerInfoStateFlow: kotlinx.coroutines.flow.StateFlow<AppActorCustomerInfo> = MutableStateFlow(AppActorCustomerInfo.empty),
    val lastCustomerInfo: AppActorCustomerInfo = AppActorCustomerInfo.empty,
    val lastCustomerInfoSource: AppActorDiagnosticsDataSource? = null,
    val lastOfferingsSource: AppActorDiagnosticsDataSource? = null,
    val lastRemoteConfigSource: AppActorDiagnosticsDataSource? = null,
)

internal data class AppActorOperationSnapshot(
    val runtime: AppActorRuntimeState,
    val epoch: Long,
    val appUserId: String,
)

internal data class AppActorCallbackState(
    val onCustomerInfoChanged: ((AppActorCustomerInfo) -> Unit)? = null,
    val onReceiptPipelineEvent: ((AppActorReceiptPipelineEvent) -> Unit)? = null,
    val onDeferredPurchaseResolved: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)? = null,
)

internal data class AppActorStartupHandles(
    val bootstrapCompletionJob: kotlinx.coroutines.Job? = null,
    val purchaseUpdatesJob: kotlinx.coroutines.Job? = null,
)

internal fun throwIfCancellation(throwable: Throwable) {
    if (throwable is kotlinx.coroutines.CancellationException) {
        throw throwable
    }
}

internal fun debugAttributes(vararg pairs: Pair<String, String?>): Map<String, String> {
    return pairs.mapNotNull { (key, value) ->
        value?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap(linkedMapOf())
}
