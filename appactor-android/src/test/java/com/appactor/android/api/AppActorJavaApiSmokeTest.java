package com.appactor.android.api;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.appactor.android.models.AppActorOptions;
import com.appactor.android.models.AppActorPlatformInfo;
import com.appactor.android.models.AppActorProductType;
import com.appactor.android.models.AppActorPurchaseParams;
import com.appactor.android.models.AppActorStoreCapability;
import com.appactor.android.models.AppActorIntegrationIdentifier;

import android.app.Activity;

import java.util.Collections;
import java.util.function.Supplier;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class AppActorJavaApiSmokeTest {

    @Test
    public void javaApiSurfaceCompiles() {
        Context context = ApplicationProvider.getApplicationContext();
        AppActorOptions options = new AppActorOptions(null);
        AppActorOptions bridgeOptions = new AppActorOptions(
            null,
            new AppActorPlatformInfo("flutter", "0.1.1")
        );
        AppActorOptions compatibilityOptions = new AppActorOptions();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        Runnable javaCustomerListener = () -> AppActorJava.setOnCustomerInfoChangedListener(info -> { });
        Runnable javaReceiptListener = () -> AppActorJava.setOnReceiptPipelineEventListener(event -> { });
        Runnable javaConfigure = () -> AppActorJava.configureAsync(context, "pk_test_java", null, options, () -> { }, error -> { });
        Runnable javaCompatibilityConfigure = () -> AppActorJava.configureAsync(context, "pk_test_java", null, compatibilityOptions, () -> { }, error -> { });
        Runnable javaLogIn = () -> AppActorJava.logInAsync("user_b", info -> { }, error -> { });
        Runnable javaLogOut = () -> AppActorJava.logOutAsync(success -> { }, error -> { });
        Runnable javaOfferings = () -> AppActorJava.getOfferingsAsync(offerings -> { }, error -> { });
        Runnable javaCustomerInfo = () -> AppActorJava.getCustomerInfoAsync(info -> { }, error -> { });
        Runnable javaRemoteConfigs = () -> AppActorJava.getRemoteConfigsAsync(configs -> { }, error -> { });
        Runnable javaExperiment = () -> AppActorJava.getExperimentAssignmentAsync("paywall_copy", assignment -> { }, error -> { });
        Runnable javaStorefront = () -> AppActorJava.getStorefrontAsync(storefront -> { }, error -> { });
        Runnable javaStoreCapabilities = () -> AppActorJava.getStoreCapabilitiesAsync(capabilities -> { }, error -> { });
        Supplier<Boolean> javaCanMakePurchases = AppActorJava::canMakePurchases;
        Supplier<Boolean> javaCanMakePurchasesWithCapabilities = () -> AppActorJava.canMakePurchases(Collections.singleton(AppActorStoreCapability.Purchases));
        Runnable javaRestore = () -> AppActorJava.restorePurchasesAsync(info -> { }, error -> { });
        Runnable javaSync = () -> AppActorJava.syncPurchasesAsync(info -> { }, error -> { });
        Runnable javaQuietSync = () -> AppActorJava.quietSyncPurchasesAsync(info -> { }, error -> { });
        Runnable javaDrain = () -> AppActorJava.drainReceiptQueueAndRefreshCustomerAsync(info -> { }, error -> { });
        Runnable javaIntegrationId = () -> AppActorJava.setIntegrationIdentifierAsync(AppActorIntegrationIdentifier.AppsFlyerId, "af-user-123", () -> { }, error -> { });
        Runnable javaIntegrationIdClear = () -> AppActorJava.unsetIntegrationIdentifierAsync(AppActorIntegrationIdentifier.AppsFlyerId, () -> { }, error -> { });
        Runnable javaIntegrationHelperClear = () -> AppActorJava.setAppsFlyerIDAsync(null, () -> { }, error -> { });
        Runnable javaMediaSource = () -> AppActorJava.setMediaSourceAsync("facebook", () -> { }, error -> { });
        Runnable javaCampaign = () -> AppActorJava.setCampaignAsync("spring_sale", () -> { }, error -> { });

        Runnable bridgeCustomerListener = () -> AppActorBridge.setCustomerInfoListener(info -> { });
        Runnable bridgeReceiptListener = () -> AppActorBridge.setReceiptPipelineListener(event -> { });
        Runnable bridgeConfigure = () -> AppActorBridge.configure(context, "pk_test_java", null, bridgeOptions, () -> { }, error -> { });
        Runnable bridgeReset = () -> AppActorBridge.reset(() -> { }, error -> { });
        Runnable bridgeLogIn = () -> AppActorBridge.logIn("user_bridge", info -> { }, error -> { });
        Runnable bridgeLogOut = () -> AppActorBridge.logOut(success -> { }, error -> { });
        Runnable bridgeOfferings = () -> AppActorBridge.getOfferings(offerings -> { }, error -> { });
        Runnable bridgeCustomerInfo = () -> AppActorBridge.getCustomerInfo(info -> { }, error -> { });
        Runnable bridgeRemoteConfigs = () -> AppActorBridge.getRemoteConfigs(configs -> { }, error -> { });
        Runnable bridgeExperiment = () -> AppActorBridge.getExperimentAssignment("paywall_copy", assignment -> { }, error -> { });
        Runnable bridgeStorefront = () -> AppActorBridge.getStorefront(storefront -> { }, error -> { });
        Runnable bridgeStoreCapabilities = () -> AppActorBridge.getStoreCapabilities(capabilities -> { }, error -> { });
        Runnable bridgePurchase = () -> AppActorBridge.purchase(
            activity,
            new AppActorPurchaseParams(
                "com.appactor.pro.monthly",
                "monthly_plan",
                "monthly001",
                "intro7d",
                null,
                null,
                Collections.emptyMap(),
                AppActorProductType.Subscription
            ),
            result -> { },
            error -> { }
        );
        Runnable bridgePurchaseNullCallbackCompatibility = () -> AppActorBridge.purchase(
            activity,
            new AppActorPurchaseParams(
                "com.appactor.pro.monthly",
                "monthly_plan",
                "monthly001",
                "intro7d",
                null,
                null,
                Collections.emptyMap(),
                AppActorProductType.Subscription
            ),
            null
        );
        Runnable bridgePurchaseWithPlacement = () -> AppActorBridge.purchaseWithPlacement(
            activity,
            new AppActorPurchaseParams(
                "com.appactor.pro.monthly",
                "monthly_plan",
                "monthly001",
                "intro7d",
                null,
                null,
                Collections.emptyMap(),
                AppActorProductType.Subscription
            ),
            "paywall_hero",
            result -> { },
            error -> { }
        );
        Runnable javaPurchaseWithPlacement = () -> AppActorJava.purchaseAsyncWithPlacement(
            activity,
            null,
            "paywall_hero",
            result -> { },
            error -> { }
        );
        Runnable bridgeRestore = () -> AppActorBridge.restorePurchases(info -> { }, error -> { });
        Runnable bridgeSync = () -> AppActorBridge.syncPurchases(info -> { }, error -> { });
        Runnable bridgeQuietSync = () -> AppActorBridge.quietSyncPurchases(info -> { }, error -> { });
        Runnable bridgeDrain = () -> AppActorBridge.drainReceiptQueueAndRefreshCustomer(info -> { }, error -> { });
        Runnable bridgeIntegrationId = () -> AppActorBridge.setIntegrationIdentifier(AppActorIntegrationIdentifier.AppsFlyerId, "af-user-123", () -> { }, error -> { });
        Runnable bridgeIntegrationIdClear = () -> AppActorBridge.unsetIntegrationIdentifier(AppActorIntegrationIdentifier.AppsFlyerId, () -> { }, error -> { });
        Runnable bridgeIntegrationHelperClear = () -> AppActorBridge.setAppsFlyerID(null, () -> { }, error -> { });
        Runnable bridgeMediaSource = () -> AppActorBridge.setMediaSource("facebook", () -> { }, error -> { });
        Runnable bridgeCampaign = () -> AppActorBridge.setCampaign("spring_sale", () -> { }, error -> { });
        AppActorBridge.appUserId();
        AppActorBridge.isAnonymous();
        AppActorBridge.getCachedOfferings();
        AppActorBridge.getCachedRemoteConfigs();
        AppActorBridge.getCurrentCustomerInfo();
        AppActorBridge.canMakePurchases();
        AppActorBridge.canMakePurchases(Collections.singleton(AppActorStoreCapability.Purchases));
        Runnable bridgeClearListeners = AppActorBridge::clearListeners;

        if (javaCustomerListener == null || javaReceiptListener == null || javaConfigure == null ||
            javaCompatibilityConfigure == null || javaLogIn == null || javaLogOut == null ||
            javaOfferings == null || javaCustomerInfo == null || javaRemoteConfigs == null ||
            javaExperiment == null || javaStorefront == null || javaStoreCapabilities == null ||
            javaCanMakePurchases == null || javaCanMakePurchasesWithCapabilities == null ||
            javaRestore == null || javaSync == null || javaQuietSync == null ||
            javaDrain == null || javaIntegrationId == null || javaIntegrationIdClear == null ||
            javaIntegrationHelperClear == null || javaMediaSource == null ||
            javaCampaign == null || bridgeCustomerListener == null ||
            bridgeReceiptListener == null || bridgeConfigure == null || bridgeReset == null ||
            bridgeLogIn == null || bridgeLogOut == null || bridgeOfferings == null ||
            bridgeCustomerInfo == null || bridgeRemoteConfigs == null || bridgeExperiment == null ||
            bridgeStorefront == null || bridgeStoreCapabilities == null || bridgePurchase == null ||
            bridgePurchaseNullCallbackCompatibility == null || bridgePurchaseWithPlacement == null ||
            javaPurchaseWithPlacement == null || bridgeRestore == null || bridgeSync == null || bridgeQuietSync == null ||
            bridgeDrain == null || bridgeIntegrationId == null || bridgeIntegrationIdClear == null ||
            bridgeIntegrationHelperClear == null || bridgeMediaSource == null ||
            bridgeCampaign == null || bridgeClearListeners == null) {
            throw new AssertionError("unreachable");
        }

        AppActor shared = AppActor.INSTANCE.getShared();
        if (shared != AppActor.INSTANCE) {
            throw new AssertionError("unreachable");
        }
    }
}
