package com.appactor.android.api

import android.app.Activity
import android.content.Context
import com.appactor.android.models.AppActorAttributeValue
import com.appactor.android.models.AppActorAttribution
import com.appactor.android.models.AppActorBridgeErrorCallback
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorBridgeReceiptEvent
import com.appactor.android.models.AppActorConfigValue
import com.appactor.android.models.AppActorCompletionCallback
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorExperiment
import com.appactor.android.models.AppActorExperimentAssignment
import com.appactor.android.models.AppActorIntegrationIdentifier
import com.appactor.android.models.AppActorOffering
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorOfferingsFetchPolicy
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPurchaseParams
import com.appactor.android.models.AppActorPurchaseResult
import com.appactor.android.models.AppActorRemoteConfigs
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import com.appactor.android.models.AppActorSuccessCallback
import com.appactor.android.models.toBridgeError
import com.appactor.android.models.toBridgeEvent

/**
 * Official callback-first bridge surface for hybrid wrappers such as Flutter or React Native.
 */
public object AppActorBridge {

    @JvmStatic
    @JvmOverloads
    public fun configure(
        context: Context,
        apiKey: String,
        appUserId: String? = null,
        options: AppActorOptions = AppActorOptions(),
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.configure(context, apiKey, appUserId, options) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun reset(
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit {
        AppActor.launchAsync(
            operation = { AppActor.reset() },
            onComplete = onComplete,
            onError = onError.asSdkErrorCallback(),
        )
    }

    @JvmStatic
    @JvmOverloads
    public fun logIn(
        appUserId: String,
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.logIn(appUserId) },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun logOut(
        onSuccess: AppActorSuccessCallback<Boolean>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.logOut() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun getOfferings(
        fetchPolicy: AppActorOfferingsFetchPolicy = AppActorOfferingsFetchPolicy.FreshIfStale,
        onSuccess: AppActorSuccessCallback<AppActorOfferings>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.offerings(fetchPolicy = fetchPolicy) },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    public fun getOfferings(
        onSuccess: AppActorSuccessCallback<AppActorOfferings>?,
        onError: AppActorBridgeErrorCallback?,
    ): Unit = getOfferings(
        fetchPolicy = AppActorOfferingsFetchPolicy.FreshIfStale,
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    @JvmOverloads
    public fun getOffering(
        offeringKey: String,
        fetchPolicy: AppActorOfferingsFetchPolicy = AppActorOfferingsFetchPolicy.FreshIfStale,
        onSuccess: AppActorSuccessCallback<AppActorOffering?>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getOffering(offeringKey, fetchPolicy) },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    public fun getOffering(
        offeringKey: String,
        onSuccess: AppActorSuccessCallback<AppActorOffering?>?,
        onError: AppActorBridgeErrorCallback?,
    ): Unit = getOffering(
        offeringKey = offeringKey,
        fetchPolicy = AppActorOfferingsFetchPolicy.FreshIfStale,
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    @JvmOverloads
    public fun setFallbackOfferings(
        jsonData: ByteArray,
        onSuccess: (() -> Unit)? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        // Route through launchAsync like every other bridge method so the JSON
        // decode runs off the caller thread and callbacks are delivered on the
        // main thread (android-12). The decode failure is mapped to
        // AppActorError.Decoding so it still surfaces as CODE_DECODING — routing
        // the raw SerializationException (an IllegalArgumentException) through
        // launchAsync would instead map it to CODE_VALIDATION.
        operation = {
            try {
                AppActor.setFallbackOfferings(jsonData)
            } catch (error: AppActorError) {
                throw error
            } catch (error: Exception) {
                throw AppActorError.Decoding(
                    error.message ?: "Invalid fallback offerings JSON",
                    error,
                )
            }
        },
        onComplete = onSuccess?.let { callback -> AppActorCompletionCallback { callback() } },
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun getCustomerInfo(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getCustomerInfo() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun getRemoteConfigs(
        onSuccess: AppActorSuccessCallback<AppActorRemoteConfigs>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getRemoteConfigs() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun getExperimentAssignment(
        experimentKey: String,
        onSuccess: AppActorSuccessCallback<AppActorExperimentAssignment?>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getExperimentAssignment(experimentKey) },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun getExperiment(
        experimentKey: String,
        onSuccess: AppActorSuccessCallback<AppActorExperiment>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getExperiment(experimentKey) },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun getStorefront(
        onSuccess: AppActorSuccessCallback<AppActorStorefront?>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getStorefront() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun getStoreCapabilities(
        onSuccess: AppActorSuccessCallback<Set<AppActorStoreCapability>>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getStoreCapabilities() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun purchase(
        activity: Activity,
        appActorPackage: AppActorPackage,
        onSuccess: AppActorSuccessCallback<AppActorPurchaseResult>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.purchase(activity, appActorPackage) },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmName("purchaseWithPlacement")
    @JvmOverloads
    public fun purchase(
        activity: Activity,
        appActorPackage: AppActorPackage,
        placement: String?,
        onSuccess: AppActorSuccessCallback<AppActorPurchaseResult>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.purchase(activity, appActorPackage, placement) },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    @Deprecated(
        message = "Prefer AppActorBridge.purchase(activity, appActorPackage). " +
            "AppActorPurchaseParams is only for explicit direct Play Store targets.",
    )
    public fun purchase(
        activity: Activity,
        params: AppActorPurchaseParams,
        onSuccess: AppActorSuccessCallback<AppActorPurchaseResult>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.purchase(activity, params) },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmName("purchaseWithPlacement")
    @JvmOverloads
    @Deprecated(
        message = "Prefer AppActorBridge.purchase(activity, appActorPackage). " +
            "AppActorPurchaseParams is only for explicit direct Play Store targets.",
    )
    public fun purchase(
        activity: Activity,
        params: AppActorPurchaseParams,
        placement: String?,
        onSuccess: AppActorSuccessCallback<AppActorPurchaseResult>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.purchase(activity, params, placement) },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun restorePurchases(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.restorePurchases() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun syncPurchases(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.syncPurchases() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun quietSyncPurchases(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.syncPurchases() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun drainReceiptQueueAndRefreshCustomer(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.drainReceiptQueueAndRefreshCustomer() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    public fun appUserId(): String? = AppActor.appUserId

    @JvmStatic
    public fun isAnonymous(): Boolean = AppActor.isAnonymous

    @JvmStatic
    public fun getCachedOfferings(): AppActorOfferings? = AppActor.cachedOfferings

    @JvmStatic
    public fun getCachedRemoteConfigs(): AppActorRemoteConfigs? = AppActor.cachedRemoteConfigs

    @JvmStatic
    public fun getCurrentCustomerInfo(): AppActorCustomerInfo = AppActor.customerInfo

    @JvmStatic
    public fun canMakePurchases(): Boolean = AppActor.canMakePurchases()

    @JvmStatic
    public fun canMakePurchases(
        requiredCapabilities: Set<AppActorStoreCapability>,
    ): Boolean = AppActor.canMakePurchases(requiredCapabilities)

    @JvmStatic
    public fun getRemoteConfig(key: String): AppActorConfigValue? =
        AppActor.getRemoteConfig(key)

    @JvmStatic
    public fun getRemoteConfigBool(key: String): Boolean? =
        AppActor.getRemoteConfigBool(key)

    @JvmStatic
    public fun getRemoteConfigString(key: String): String? =
        AppActor.getRemoteConfigString(key)

    @JvmStatic
    public fun getRemoteConfigNumber(key: String): Double? =
        AppActor.getRemoteConfigNumber(key)

    @JvmStatic
    public fun getRemoteConfigInt(key: String): Int? =
        AppActor.getRemoteConfigInt(key)

    @JvmStatic
    @JvmOverloads
    public fun activeEntitlementKeysOffline(
        onSuccess: AppActorSuccessCallback<Set<String>>? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.activeEntitlementKeysOffline() },
        onSuccess = onSuccess,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setAttributes(
        attributes: Map<String, AppActorAttributeValue?>,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setAttributes(attributes) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setAttribute(
        key: String,
        value: AppActorAttributeValue,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setAttribute(key, value) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun unsetAttribute(
        key: String,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.unsetAttribute(key) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setEmail(
        email: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setEmail(email) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setDisplayName(
        displayName: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setDisplayName(displayName) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setPhoneNumber(
        phoneNumber: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setPhoneNumber(phoneNumber) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setPushToken(
        pushToken: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setPushToken(pushToken) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun collectDeviceIdentifiers(
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.collectDeviceIdentifiers() },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setIntegrationIdentifier(
        type: String,
        value: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setIntegrationIdentifier(type, value) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setIntegrationIdentifier(
        type: AppActorIntegrationIdentifier,
        value: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setIntegrationIdentifier(type, value) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun unsetIntegrationIdentifier(
        type: String,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.unsetIntegrationIdentifier(type) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun unsetIntegrationIdentifier(
        type: AppActorIntegrationIdentifier,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.unsetIntegrationIdentifier(type) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setAppsflyerID(
        appsflyerID: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setAppsflyerID(appsflyerID) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setAppsFlyerID(
        appsFlyerID: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setAppsFlyerID(appsFlyerID) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setAdjustID(
        adjustID: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setAdjustID(adjustID) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setBranchID(
        branchID: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setBranchID(branchID) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setFirebaseAppInstanceID(
        firebaseAppInstanceID: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setFirebaseAppInstanceID(firebaseAppInstanceID) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setOneSignalID(
        oneSignalID: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setOneSignalID(oneSignalID) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun updateAttribution(
        attribution: AppActorAttribution,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.updateAttribution(attribution) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setMediaSource(
        mediaSource: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setMediaSource(mediaSource) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setCampaign(
        campaign: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setCampaign(campaign) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setAdGroup(
        adGroup: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setAdGroup(adGroup) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setAd(
        ad: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setAd(ad) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setKeyword(
        keyword: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setKeyword(keyword) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @JvmStatic
    @JvmOverloads
    public fun setCreative(
        creative: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorBridgeErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setCreative(creative) },
        onComplete = onComplete,
        onError = onError.asSdkErrorCallback(),
    )

    @Volatile
    private var currentCustomerInfoListener: AppActorSuccessCallback<AppActorCustomerInfo>? = null

    @Volatile
    private var currentReceiptPipelineListener: AppActorSuccessCallback<AppActorBridgeReceiptEvent>? = null

    @JvmStatic
    public fun getCustomerInfoListener(): AppActorSuccessCallback<AppActorCustomerInfo>? =
        currentCustomerInfoListener

    @JvmStatic
    public fun getReceiptPipelineListener(): AppActorSuccessCallback<AppActorBridgeReceiptEvent>? =
        currentReceiptPipelineListener

    @JvmStatic
    public fun setCustomerInfoListener(
        listener: AppActorSuccessCallback<AppActorCustomerInfo>?,
    ): Unit {
        currentCustomerInfoListener = listener
        AppActor.onCustomerInfoChanged = listener?.let { callback ->
            { info ->
                AppActor.deliverOnMain {
                    callback.onSuccess(info)
                }
            }
        }
    }

    @JvmStatic
    public fun setReceiptPipelineListener(
        listener: AppActorSuccessCallback<AppActorBridgeReceiptEvent>?,
    ): Unit {
        currentReceiptPipelineListener = listener
        AppActor.onReceiptPipelineEvent = listener?.let { callback ->
            { event ->
                val bridgeEvent = event.toBridgeEvent()
                AppActor.deliverOnMain {
                    callback.onSuccess(bridgeEvent)
                }
            }
        }
    }

    @Volatile
    private var currentDeferredPurchaseListener: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)? = null

    @JvmStatic
    public fun getDeferredPurchaseListener(): ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)? =
        currentDeferredPurchaseListener

    @JvmStatic
    public fun setDeferredPurchaseListener(
        listener: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)?,
    ): Unit {
        currentDeferredPurchaseListener = listener
        AppActor.onDeferredPurchaseResolved = listener
    }

    @JvmStatic
    public fun clearListeners(): Unit {
        currentCustomerInfoListener = null
        currentReceiptPipelineListener = null
        currentDeferredPurchaseListener = null
        AppActor.onCustomerInfoChanged = null
        AppActor.onReceiptPipelineEvent = null
        AppActor.onDeferredPurchaseResolved = null
    }

    private fun AppActorBridgeErrorCallback?.asSdkErrorCallback() =
        this?.let { callback ->
            com.appactor.android.models.AppActorErrorCallback { error ->
                callback.onError(error.toBridgeError())
            }
        }
}
