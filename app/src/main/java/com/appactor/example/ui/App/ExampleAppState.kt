package com.appactor.example.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHostState
import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPurchaseResult
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun rememberExampleAppState(activity: Activity): ExampleAppState {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    return remember(activity) {
        ExampleAppState(
            activity = activity,
            mainHandler = mainHandler,
        )
    }
}

class ExampleAppState(
    private val activity: Activity,
    private val mainHandler: Handler,
) {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var storeStatusPollingJob: Job? = null
    private var didAttemptInitialConfigure = false

    var mainTab by mutableStateOf(MainExampleTab.Console.name)
    var toolsScreen by mutableStateOf(ToolExampleScreen.RemoteConfig.name)

    var apiKey by mutableStateOf("pk_YOUR_PUBLIC_API_KEY")
    var loginUserId by mutableStateOf("")
    var experimentKeyInput by mutableStateOf("")
    var remoteConfigLookupKey by mutableStateOf("")

    var configured by mutableStateOf(AppActor.shared.appUserId != null)
    var isConfiguring by mutableStateOf(AppActor.shared.appUserId == null)
    var busyAction by mutableStateOf<String?>(null)
    var statusText by mutableStateOf("SDK initializing...")
    var customerInfo by mutableStateOf(AppActor.shared.customerInfo)
    var offerings by mutableStateOf(AppActor.shared.cachedOfferings)
    var remoteConfigs by mutableStateOf(AppActor.shared.cachedRemoteConfigs)
    var storefront by mutableStateOf<AppActorStorefront?>(null)
    var storeCapabilities by mutableStateOf<Set<AppActorStoreCapability>>(emptySet())
    var canMakePurchases by mutableStateOf(false)
    var offlineKeys by mutableStateOf<Set<String>>(emptySet())
    var lastRemoteConfigLoadAt by mutableStateOf<String?>(null)
    var lastExperimentLoadAt by mutableStateOf<String?>(null)
    var lastCustomerLoadAt by mutableStateOf<String?>(null)
    var lastRemoteConfigLookup by mutableStateOf<ExampleConfigLookup?>(null)
    val logs = mutableStateListOf<ExampleLogEntry>()
    val experimentResults = mutableStateListOf<ExampleExperimentResult>()

    val summaryMetrics: List<ExampleMetric>
        get() = listOf(
            ExampleMetric(
                title = "Status",
                value = if (configured) "Ready" else "Booting",
                color = if (configured) ExamplePalette.Success else ExamplePalette.Warning,
            ),
            ExampleMetric(
                title = "Identity",
                value = if (AppActor.shared.isAnonymous) "Anon" else "Known",
                color = if (AppActor.shared.isAnonymous) ExamplePalette.Warning else ExamplePalette.Info,
            ),
            ExampleMetric(
                title = "User",
                value = if ((AppActor.shared.appUserId ?: "-") == "-") "Waiting" else "Set",
                color = if ((AppActor.shared.appUserId ?: "-") == "-") androidx.compose.ui.graphics.Color.Gray else ExamplePalette.Accent,
            ),
        )

    fun syncLocalState() {
        configured = AppActor.shared.appUserId != null
        customerInfo = AppActor.shared.customerInfo
        offerings = AppActor.shared.cachedOfferings
        remoteConfigs = AppActor.shared.cachedRemoteConfigs
        storeCapabilities = if (configured) AppActor.shared.getStoreCapabilities() else emptySet()
        canMakePurchases = if (configured) {
            AppActor.shared.canMakePurchases(setOf(AppActorStoreCapability.Purchases))
        } else {
            false
        }
        if (!configured) {
            storefront = null
        }
    }

    fun wireSdkCallbacks() {
        startStoreStatusPolling()
        AppActor.shared.onCustomerInfoChanged = { info ->
            withMainThread {
                publishCustomer(info, "onCustomerInfoChanged")
                syncLocalState()
            }
            refreshStoreStatusAsync()
        }
        AppActor.shared.onReceiptPipelineEvent = { event ->
            withMainThread {
                log(receiptEventCopy(event), receiptEventTone(event))
                syncLocalState()
            }
            refreshStoreStatusAsync()
        }
    }

    fun clearSdkCallbacks() {
        stopStoreStatusPolling()
        if (AppActor.shared.appUserId != null) {
            AppActor.shared.onCustomerInfoChanged = null
            AppActor.shared.onReceiptPipelineEvent = null
        }
    }

    fun launchAction(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        label: String,
        action: suspend () -> Unit,
    ) {
        scope.launch {
            busyAction = label
            statusText = "$label calisiyor..."
            runCatching {
                action()
            }.onFailure { throwable ->
                val message = throwable.message ?: throwable.javaClass.simpleName
                statusText = "$label basarisiz."
                log("$label failed -> $message", ExampleLogTone.Error)
                snackbarHostState.showSnackbar("$label: $message")
            }
            syncLocalState()
            if (configured) {
                startStoreStatusPolling()
                refreshStoreStatus()
            }
            busyAction = null
        }
    }

    suspend fun configure() {
        val trimmedKey = apiKey.trim()
        require(trimmedKey.isNotEmpty()) { "API key bos olamaz." }
        isConfiguring = true
        statusText = "SDK initializing..."
        try {
            AppActor.shared.configure(
                context = activity,
                apiKey = trimmedKey,
                options = AppActorOptions(),
            )
            wireSdkCallbacks()
            syncLocalState()
            startStoreStatusPolling()
            refreshStoreStatus()
            statusText = "SDK ready."
            log("configure(context, apiKey, appUserId, options) completed", ExampleLogTone.Success)
        } finally {
            isConfiguring = false
        }
    }

    suspend fun configureOnLaunchIfNeeded() {
        if (didAttemptInitialConfigure || configured) {
            return
        }
        didAttemptInitialConfigure = true
        log("launch -> auto configure starting", ExampleLogTone.Info)
        configure()
    }

    suspend fun retryInitialConfigure() {
        didAttemptInitialConfigure = false
        configureOnLaunchIfNeeded()
    }

    fun handleInitialConfigureFailure(throwable: Throwable) {
        isConfiguring = false
        val message = throwable.message ?: throwable.javaClass.simpleName
        statusText = "SDK initialize failed: $message"
        log("auto configure failed -> $message", ExampleLogTone.Error)
    }

    val canRetryInitialConfigure: Boolean
        get() = didAttemptInitialConfigure && !configured && !isConfiguring

    suspend fun logIn() {
        val target = loginUserId.trim()
        require(target.isNotEmpty()) { "Login user id bos olamaz." }
        publishCustomer(AppActor.shared.logIn(target), "logIn")
    }

    suspend fun logOut() {
        val acknowledged = AppActor.shared.logOut()
        publishCustomer(AppActor.shared.customerInfo, "logOut")
        log("logout acknowledged -> $acknowledged", ExampleLogTone.Info)
    }

    suspend fun restore() {
        publishCustomer(AppActor.shared.restorePurchases(), "restorePurchases")
    }

    suspend fun refreshCustomer() {
        publishCustomer(AppActor.shared.getCustomerInfo(), "getCustomerInfo")
    }

    suspend fun syncPurchases() {
        publishCustomer(AppActor.shared.syncPurchases(), "syncPurchases")
    }

    suspend fun loadOfferings() {
        offerings = AppActor.shared.offerings()
        log("offerings loaded -> ${offerings?.all?.size ?: 0}", ExampleLogTone.Success)
    }

    suspend fun checkPremium() {
        publishCustomer(AppActor.shared.getCustomerInfo(), "check premium")
    }

    suspend fun purchasePackage(pack: AppActorPackage) {
        when (val result = AppActor.shared.purchase(activity, pack)) {
            is AppActorPurchaseResult.Success -> {
                publishCustomer(result.customerInfo, "purchase")
                log("purchase success -> ${pack.productId}", ExampleLogTone.Success)
            }

            AppActorPurchaseResult.Pending -> {
                log("purchase pending -> ${pack.productId}", ExampleLogTone.Warn)
            }

            AppActorPurchaseResult.Cancelled -> {
                log("purchase cancelled -> ${pack.productId}", ExampleLogTone.Warn)
            }
        }
    }

    suspend fun fetchCustomer() {
        publishCustomer(AppActor.shared.getCustomerInfo(), "fetch customer")
    }

    suspend fun loadOfflineKeys() {
        offlineKeys = AppActor.shared.activeEntitlementKeysOffline()
        log(
            "offline keys -> ${offlineKeys.sorted().joinToString().ifBlank { "none" }}",
            ExampleLogTone.Info,
        )
    }

    suspend fun loadRemoteConfigs() {
        remoteConfigs = AppActor.shared.getRemoteConfigs()
        lastRemoteConfigLoadAt = timestamp()
        remoteConfigLookupKey.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { key ->
                AppActor.shared.getRemoteConfig(key)?.let {
                    lastRemoteConfigLookup = ExampleConfigLookup(key, it)
                }
            }
        log("remote configs loaded -> ${remoteConfigs?.items?.size ?: 0}", ExampleLogTone.Success)
    }

    fun lookupCachedRemoteConfig() {
        val key = remoteConfigLookupKey.trim()
        if (key.isNotEmpty()) {
            val value = AppActor.shared.getRemoteConfig(key)
            lastRemoteConfigLookup = value?.let { ExampleConfigLookup(key, it) }
            log(
                if (value == null) "remote config cache miss -> $key" else "remote config cache hit -> $key",
                if (value == null) ExampleLogTone.Warn else ExampleLogTone.Info,
            )
        }
    }

    suspend fun getExperimentAssignment() {
        val key = experimentKeyInput.trim()
        require(key.isNotEmpty()) { "Experiment key bos olamaz." }
        val assignment = AppActor.shared.getExperimentAssignment(key)
        recordExperiment(key, assignment, "network")
        log(
            if (assignment == null) "experiment miss -> $key" else "experiment hit -> $key/${assignment.variantKey}",
            if (assignment == null) ExampleLogTone.Warn else ExampleLogTone.Success,
        )
    }

    private suspend fun refreshStoreStatus() {
        storefront = AppActor.shared.getStorefront()
        storeCapabilities = AppActor.shared.getStoreCapabilities()
        canMakePurchases = AppActor.shared.canMakePurchases(setOf(AppActorStoreCapability.Purchases))
    }

    private fun refreshStoreStatusAsync() {
        if (!configured) return
        uiScope.launch {
            refreshStoreStatus()
        }
    }

    private fun startStoreStatusPolling() {
        if (!configured || storeStatusPollingJob?.isActive == true) {
            return
        }
        storeStatusPollingJob = uiScope.launch {
            while (isActive && configured) {
                refreshStoreStatus()
                delay(1_000L)
            }
        }
    }

    private fun stopStoreStatusPolling() {
        storeStatusPollingJob?.cancel()
        storeStatusPollingJob = null
    }

    private fun log(message: String, tone: ExampleLogTone = ExampleLogTone.Info) {
        logs.add(
            0,
            ExampleLogEntry(
                message = message,
                tone = tone,
                timestamp = timestamp(),
            ),
        )
        if (logs.size > 120) {
            logs.removeAt(logs.lastIndex)
        }
    }

    private fun publishCustomer(info: AppActorCustomerInfo, source: String) {
        customerInfo = info
        lastCustomerLoadAt = timestamp()
        statusText = "$source tamamlandi."
        log(
            "$source -> active=${info.activeEntitlementKeys.sorted().joinToString().ifBlank { "none" }}",
            if (info.isComputedOffline) ExampleLogTone.Warn else ExampleLogTone.Success,
        )
    }

    private fun recordExperiment(
        key: String,
        assignment: com.appactor.android.models.AppActorExperimentAssignment?,
        source: String,
    ) {
        experimentResults.removeAll { it.key == key }
        experimentResults.add(
            0,
            ExampleExperimentResult(
                key = key,
                assignment = assignment,
                source = source,
                timestamp = timestamp(),
            ),
        )
        lastExperimentLoadAt = timestamp()
    }

    private fun withMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
