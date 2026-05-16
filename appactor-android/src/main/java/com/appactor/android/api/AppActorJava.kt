package com.appactor.android.api

import android.app.Activity
import android.content.Context
import com.appactor.android.models.AppActorAttributeValue
import com.appactor.android.models.AppActorAttribution
import com.appactor.android.models.AppActorCompletionCallback
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorErrorCallback
import com.appactor.android.models.AppActorExperimentAssignment
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPurchaseResult
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.models.AppActorRemoteConfigs
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import com.appactor.android.models.AppActorSuccessCallback

/**
 * Legacy Java-friendly compatibility facade.
 *
 * New hybrid wrappers should prefer [AppActorBridge].
 */
public object AppActorJava {

    @JvmStatic
    @JvmOverloads
    public fun configureAsync(
        context: Context,
        apiKey: String,
        appUserId: String? = null,
        options: AppActorOptions = AppActorOptions(),
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.configure(context, apiKey, appUserId, options) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun logInAsync(
        appUserId: String,
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.logIn(appUserId) },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun logOutAsync(
        onSuccess: AppActorSuccessCallback<Boolean>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.logOut() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun getOfferingsAsync(
        onSuccess: AppActorSuccessCallback<AppActorOfferings>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.offerings() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun getCustomerInfoAsync(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getCustomerInfo() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun getRemoteConfigsAsync(
        onSuccess: AppActorSuccessCallback<AppActorRemoteConfigs>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getRemoteConfigs() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun getExperimentAssignmentAsync(
        experimentKey: String,
        onSuccess: AppActorSuccessCallback<AppActorExperimentAssignment?>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getExperimentAssignment(experimentKey) },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun getStorefrontAsync(
        onSuccess: AppActorSuccessCallback<AppActorStorefront?>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getStorefront() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun getStoreCapabilitiesAsync(
        onSuccess: AppActorSuccessCallback<Set<AppActorStoreCapability>>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.getStoreCapabilities() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun purchaseAsync(
        activity: Activity,
        appActorPackage: AppActorPackage,
        onSuccess: AppActorSuccessCallback<AppActorPurchaseResult>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.purchase(activity, appActorPackage) },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun restorePurchasesAsync(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.restorePurchases() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun syncPurchasesAsync(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.drainReceiptQueueAndRefreshCustomer() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun quietSyncPurchasesAsync(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.syncPurchases() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun drainReceiptQueueAndRefreshCustomerAsync(
        onSuccess: AppActorSuccessCallback<AppActorCustomerInfo>? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.drainReceiptQueueAndRefreshCustomer() },
        onSuccess = onSuccess,
        onError = onError,
    )

    @JvmStatic
    public fun setAttributesAsync(
        attributes: Map<String, AppActorAttributeValue?>,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setAttributes(attributes) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun setAttributeAsync(
        key: String,
        value: AppActorAttributeValue,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setAttribute(key, value) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun unsetAttributeAsync(
        key: String,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.unsetAttribute(key) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun setEmailAsync(
        email: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setEmail(email) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun setDisplayNameAsync(
        displayName: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setDisplayName(displayName) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun setPhoneNumberAsync(
        phoneNumber: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setPhoneNumber(phoneNumber) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun setPushTokenAsync(
        pushToken: String?,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setPushToken(pushToken) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun collectDeviceIdentifiersAsync(
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.collectDeviceIdentifiers() },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun setIntegrationIdentifierAsync(
        type: String,
        value: String,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.setIntegrationIdentifier(type, value) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun updateAttributionAsync(
        attribution: AppActorAttribution,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ): Unit = AppActor.launchAsync(
        operation = { AppActor.updateAttribution(attribution) },
        onComplete = onComplete,
        onError = onError,
    )

    @JvmStatic
    public fun canMakePurchases(): Boolean = AppActor.canMakePurchases()

    @JvmStatic
    public fun canMakePurchases(
        requiredCapabilities: Set<AppActorStoreCapability>,
    ): Boolean = AppActor.canMakePurchases(requiredCapabilities)

    @JvmStatic
    public fun setOnCustomerInfoChangedListener(
        listener: AppActorSuccessCallback<AppActorCustomerInfo>?,
    ): Unit {
        AppActor.onCustomerInfoChanged = listener?.let { callback ->
            { info -> callback.onSuccess(info) }
        }
    }

    @JvmStatic
    public fun setOnReceiptPipelineEventListener(
        listener: AppActorSuccessCallback<AppActorReceiptPipelineEvent>?,
    ): Unit {
        AppActor.onReceiptPipelineEvent = listener?.let { callback ->
            { event -> callback.onSuccess(event) }
        }
    }
}
