package com.appactor.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

internal object AppActorLiveSmokeSupport {

    suspend fun configureSdk(config: LiveSmokeConfig) {
        AppActor.reset()
        AppActor.configure(
            configuration = AppActorConfiguration(
                context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                apiKey = config.apiKey.orEmpty(),
                appUserId = config.appUserId?.takeIf { it.isNotBlank() },
                options = AppActorConfiguration.Options(
                    verifyResponseSignatures = !config.disableSignatureVerification,
                    requireResponseSignatures = !config.disableSignatureVerification,
                ),
            )
        )
    }

    suspend fun reconfigureSdk(config: LiveSmokeConfig) {
        configureSdk(config)
    }

    data class LiveSmokeConfig(
        val apiKey: String?,
        val appUserId: String?,
        val loginUserId: String?,
        val subscriptionProductId: String?,
        val inAppProductId: String?,
        val experimentKey: String?,
        val runInteractivePurchase: Boolean,
        val runRecoverySmoke: Boolean,
        val runReplaySmoke: Boolean,
        val disableSignatureVerification: Boolean,
    ) {
        val hasRequiredCore: Boolean
            get() = !apiKey.isNullOrBlank() && !experimentKey.isNullOrBlank()

        val hasRequiredInteractive: Boolean
            get() = !apiKey.isNullOrBlank() &&
                !subscriptionProductId.isNullOrBlank() &&
                !inAppProductId.isNullOrBlank()

        companion object {
            fun fromEnvironment(): LiveSmokeConfig {
                val args = InstrumentationRegistry.getArguments()
                fun value(key: String, envKey: String): String? {
                    return args.getString(key)
                        ?: System.getProperty(key)
                        ?: System.getenv(envKey)
                }
                return LiveSmokeConfig(
                    apiKey = value("appactorApiKey", "APPACTOR_LIVE_API_KEY"),
                    appUserId = value("appactorAppUserId", "APPACTOR_LIVE_APP_USER_ID"),
                    loginUserId = value("appactorLoginUserId", "APPACTOR_LIVE_LOGIN_USER_ID"),
                    subscriptionProductId = value("appactorSubscriptionProductId", "APPACTOR_LIVE_SUBS_PRODUCT_ID"),
                    inAppProductId = value("appactorInAppProductId", "APPACTOR_LIVE_INAPP_PRODUCT_ID"),
                    experimentKey = value("appactorExperimentKey", "APPACTOR_LIVE_EXPERIMENT_KEY"),
                    runInteractivePurchase = value(
                        "appactorRunInteractivePurchase",
                        "APPACTOR_LIVE_RUN_INTERACTIVE_PURCHASE",
                    )?.toBooleanStrictOrNull() == true,
                    runRecoverySmoke = value(
                        "appactorRunRecoverySmoke",
                        "APPACTOR_LIVE_RUN_RECOVERY_SMOKE",
                    )?.toBooleanStrictOrNull() == true,
                    runReplaySmoke = value(
                        "appactorRunReplaySmoke",
                        "APPACTOR_LIVE_RUN_REPLAY_SMOKE",
                    )?.toBooleanStrictOrNull() == true,
                    disableSignatureVerification = value(
                        "appactorDisableSignatureVerification",
                        "APPACTOR_LIVE_DISABLE_SIGNATURE_VERIFICATION",
                    )?.toBooleanStrictOrNull() == true,
                )
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ConnectedCoreSmokeTests {

    private lateinit var config: AppActorLiveSmokeSupport.LiveSmokeConfig

    @Before
    fun setUp() = runBlocking {
        config = AppActorLiveSmokeSupport.LiveSmokeConfig.fromEnvironment()
        assumeTrue("Core live smoke config missing required values.", config.hasRequiredCore)
        AppActorLiveSmokeSupport.configureSdk(config)
    }

    @Test
    fun configureBootstrapAndReadOnlyFlows() = runBlocking {
        val identified = AppActor.getCustomerInfo(forceRefresh = true)
        val offerings = AppActor.offerings(forceRefresh = true)
        val customer = AppActor.getCustomerInfo(forceRefresh = true)
        val synced = AppActor.syncPurchases()
        val restored = AppActor.restorePurchases()

        assertTrue(identified.appUserId?.isNotBlank() == true)
        assertNotNull(offerings.current ?: offerings.all.values.firstOrNull())
        assertNotNull(customer.appUserId)
        assertNotNull(synced.appUserId)
        assertNotNull(restored.appUserId)
    }

    @Test
    fun remoteConfigAndExperimentFetch() = runBlocking {
        val remoteConfigs = AppActor.getRemoteConfigs()
        val assignment = AppActor.getExperimentAssignment(config.experimentKey.orEmpty())

        assertTrue(remoteConfigs.items.isNotEmpty() || AppActor.cachedRemoteConfigs != null)
        assertTrue(assignment == null || assignment.experimentKey == config.experimentKey)
    }

    @Test
    fun logInAndLogOutIdentitySwitchSmoke() = runBlocking {
        val targetUserId = config.loginUserId
            ?.takeIf { it.isNotBlank() }
            ?: "appactor_android_live_${System.currentTimeMillis()}"

        val loggedIn = AppActor.logIn(targetUserId)
        assertEquals(targetUserId, loggedIn.appUserId)
        assertFalse(AppActor.isAnonymous)

        val assignment = AppActor.getExperimentAssignment(config.experimentKey.orEmpty())
        assertTrue(assignment == null || assignment.experimentKey == config.experimentKey)

        AppActor.logOut()
        val afterLogout = AppActor.getCustomerInfo(forceRefresh = true)

        assertTrue(afterLogout.appUserId?.isNotBlank() == true)
        assertTrue(afterLogout.appUserId != targetUserId)
        assertTrue(AppActor.isAnonymous)
    }

    @Test
    fun replayAndRestartReadOnlyRecoverySmoke() = runBlocking {
        assumeTrue("Replay smoke disabled.", config.runReplaySmoke)

        val initial = AppActor.getCustomerInfo(forceRefresh = true)
        val firstSync = AppActor.syncPurchases()

        AppActorLiveSmokeSupport.reconfigureSdk(config)

        val afterRestart = AppActor.getCustomerInfo(forceRefresh = true)
        val replaySync = AppActor.syncPurchases()
        val restored = AppActor.restorePurchases()

        assertNotNull(initial.appUserId)
        assertNotNull(firstSync.appUserId)
        assertNotNull(afterRestart.appUserId)
        assertNotNull(replaySync.appUserId)
        assertNotNull(restored.appUserId)
    }
}

@RunWith(AndroidJUnit4::class)
class ConnectedInteractiveSmokeTests {

    private lateinit var config: AppActorLiveSmokeSupport.LiveSmokeConfig

    @Before
    fun setUp() = runBlocking {
        config = AppActorLiveSmokeSupport.LiveSmokeConfig.fromEnvironment()
        assumeTrue("Interactive live smoke config missing required values.", config.hasRequiredInteractive)
        assumeTrue("Interactive purchase smoke disabled.", config.runInteractivePurchase)
        AppActorLiveSmokeSupport.configureSdk(config)
    }

    @Test
    fun manualRawSubscriptionPurchaseSmoke() = runBlocking {
        var launchedActivity: TestBillingActivity? = null
        ActivityScenario.launch(TestBillingActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> launchedActivity = activity }
            val result = AppActor.purchase(requireNotNull(launchedActivity), config.subscriptionProductId.orEmpty())
            assertNotNull(result)
        }
    }

    @Test
    fun manualRawInAppProductPurchaseSmoke() = runBlocking {
        AppActor.offerings(forceRefresh = true)

        var launchedActivity: TestBillingActivity? = null
        ActivityScenario.launch(TestBillingActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> launchedActivity = activity }
            val result = AppActor.purchase(requireNotNull(launchedActivity), config.inAppProductId.orEmpty())
            assertNotNull(result)
        }
    }

    @Test
    fun manualInAppRecoveryAcrossReconfigureSmoke() = runBlocking {
        assumeTrue("Recovery smoke disabled.", config.runRecoverySmoke)

        AppActor.offerings(forceRefresh = true)

        var launchedActivity: TestBillingActivity? = null
        ActivityScenario.launch(TestBillingActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> launchedActivity = activity }
            val result = AppActor.purchase(requireNotNull(launchedActivity), config.inAppProductId.orEmpty())
            assertNotNull(result)
        }

        val firstSync = AppActor.syncPurchases()
        assertNotNull(firstSync.appUserId)

        AppActorLiveSmokeSupport.reconfigureSdk(config)
        val identified = AppActor.getCustomerInfo(forceRefresh = true)
        val secondSync = AppActor.syncPurchases()
        val refreshed = AppActor.getCustomerInfo(forceRefresh = true)

        assertTrue(identified.appUserId?.isNotBlank() == true)
        assertNotNull(secondSync.appUserId)
        assertNotNull(refreshed.appUserId)
    }

    @Test
    fun manualExplicitRestoreAfterReconfigureLeavesReceiptQueueClean() = runBlocking {
        assumeTrue("Recovery smoke disabled.", config.runRecoverySmoke)

        AppActor.offerings(forceRefresh = true)

        var launchedActivity: TestBillingActivity? = null
        ActivityScenario.launch(TestBillingActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> launchedActivity = activity }
            val result = AppActor.purchase(requireNotNull(launchedActivity), config.inAppProductId.orEmpty())
            assertNotNull(result)
        }

        AppActorLiveSmokeSupport.reconfigureSdk(config)

        val restored = AppActor.restorePurchases()
        val synced = AppActor.syncPurchases()
        assertTrue(restored.appUserId?.isNotBlank() == true)
        assertTrue(synced.appUserId?.isNotBlank() == true)
    }
}
