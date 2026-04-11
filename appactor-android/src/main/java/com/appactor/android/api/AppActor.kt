package com.appactor.android.api

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.client.AppActorResponseSignatureVerifier
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.GooglePlayStoreAdapter
import com.appactor.android.internal.runtime.AppActorCallbackState
import com.appactor.android.internal.runtime.AppActorLifecycleCoordinator
import com.appactor.android.internal.runtime.AppActorLifecycleCoordinatorHost
import com.appactor.android.internal.runtime.AppActorOperationSnapshot
import com.appactor.android.internal.runtime.AppActorRuntimeFactory
import com.appactor.android.internal.runtime.debugAttributes
import com.appactor.android.internal.runtime.throwIfCancellation
import com.appactor.android.internal.runtime.AppActorRuntimeState
import com.appactor.android.internal.runtime.AppActorStartupCoordinator
import com.appactor.android.internal.runtime.AppActorStartupCoordinatorHost
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.models.AppActorCompletionCallback
import com.appactor.android.models.AppActorConfigValue
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorDebugCategory
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorEntitlementInfo
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorExperimentAssignment
import com.appactor.android.models.AppActorErrorCallback
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPlatformInfo
import com.appactor.android.models.AppActorPurchaseParams
import com.appactor.android.models.AppActorPurchaseResult
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.internal.AppActorSDK
import com.appactor.android.models.AppActorRemoteConfigs
import com.appactor.android.models.AppActorSuccessCallback
import com.appactor.android.models.toLegacyOptions
import com.appactor.android.models.toAppActorPackage
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import com.appactor.android.storage.AppActorAtomicJsonPostedLedgerStore
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public object AppActor {
    public val shared: AppActor
        get() = this

    public val sdkVersion: String
        get() = AppActorSDK.version

    @Volatile
    private var runtime: AppActorRuntimeState? = null
    private val transitionMutex = Mutex()
    private var preconfiguredCallbacks = AppActorCallbackState()
    @Volatile
    private var preconfiguredFallbackOfferingsDTO: AppActorOfferingsEnvelopeDTO? = null
    private var callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val emptyCustomerInfoFlow: StateFlow<AppActorCustomerInfo> =
        MutableStateFlow(AppActorCustomerInfo.empty)

    @Volatile
    private var identityEpoch: Long = 0L

    @Volatile
    private var nextRuntimeSessionId: Long = 1L

    @Volatile
    private var isResetting: Boolean = false
    private val installReferrerEnabled = java.util.concurrent.atomic.AtomicBoolean(false)

    internal var storeAdapterFactory: (Context) -> AppActorStoreAdapter = { context ->
        GooglePlayStoreAdapter(context)
    }

    private val runtimeFactory: AppActorRuntimeFactory
        get() = AppActorRuntimeFactory(
            storeAdapterFactory = { context -> storeAdapterFactory(context) },
            appVersionProvider = ::currentAppVersion,
            countryProvider = ::currentCountryCode,
        )

    private val startupCoordinator: AppActorStartupCoordinator by lazy {
        AppActorStartupCoordinator(
            host = object : AppActorStartupCoordinatorHost {
                override suspend fun performStartupIdentify(
                    runtimeState: AppActorRuntimeState,
                ): Pair<((AppActorCustomerInfo) -> Unit)?, AppActorCustomerInfo>? {
                    return performStartupIdentifyTransition(runtimeState)
                }

                override fun confirmIdentity(runtimeState: AppActorRuntimeState) {
                    runtimeState.paymentProcessor.confirmIdentity()
                }

                override suspend fun captureOperationSnapshot(
                    resolveAppUserId: Boolean,
                    awaitBootstrapCompletion: Boolean,
                ): AppActorOperationSnapshot {
                    return this@AppActor.captureOperationSnapshot(
                        resolveAppUserId = resolveAppUserId,
                        awaitBootstrapCompletion = awaitBootstrapCompletion,
                    )
                }

                override suspend fun syncCurrentPurchases(
                    snapshot: AppActorOperationSnapshot,
                ): AppActorCustomerInfo? {
                    return snapshot.runtime.paymentProcessor.syncCurrentPurchases(
                        appUserIdOverride = snapshot.appUserId,
                    )
                }

                override suspend fun retryDeadLetteredItems(
                    runtimeState: AppActorRuntimeState,
                ) {
                    runtimeState.paymentProcessor.retryDeadLetteredItems()
                }

                override suspend fun fetchOfferings(
                    runtimeState: AppActorRuntimeState,
                ): AppActorDiagnosticsDataSource? {
                    runtimeState.offeringsManager.getOfferings(forceRefresh = false)
                    return runtimeState.offeringsManager.lastLoadSource()
                }

                override suspend fun fetchCustomerInfo(
                    snapshot: AppActorOperationSnapshot,
                ): Pair<AppActorCustomerInfo, AppActorDiagnosticsDataSource?> {
                    val info = snapshot.runtime.customerManager.getCustomerInfo(
                        appUserId = snapshot.appUserId,
                        forceRefresh = true,
                        persistIdentityState = false,
                    )
                    return info to snapshot.runtime.customerManager.lastLoadSource()
                }

                override suspend fun persistCustomerInfoIfCurrent(
                    snapshot: AppActorOperationSnapshot,
                    info: AppActorCustomerInfo,
                ): Boolean {
                    return this@AppActor.persistCustomerInfoIfCurrent(snapshot, info)
                }

                override suspend fun publishCustomerInfoIfCurrent(
                    snapshot: AppActorOperationSnapshot,
                    info: AppActorCustomerInfo,
                    source: AppActorDiagnosticsDataSource?,
                ): Boolean {
                    return this@AppActor.publishCustomerInfoIfCurrent(snapshot, info, source)
                }

                override suspend fun persistOfferingsSource(
                    runtimeSessionId: Long,
                    source: AppActorDiagnosticsDataSource?,
                ) {
                    this@AppActor.persistOfferingsSource(runtimeSessionId, source)
                }

                override suspend fun processPurchaseUpdates(
                    runtimeState: AppActorRuntimeState,
                    snapshot: AppActorOperationSnapshot,
                    purchases: List<AppActorStorePurchase>,
                ): AppActorCustomerInfo? {
                    return runtimeState.paymentProcessor.processPurchaseUpdates(
                        purchases = purchases,
                        appUserIdOverride = snapshot.appUserId,
                    )
                }

                override fun deliverOnMain(block: () -> Unit) {
                    this@AppActor.deliverOnMain(block)
                }

                override fun emitDebugEvent(
                    runtimeSessionId: Long,
                    category: AppActorDebugCategory,
                    level: com.appactor.android.models.AppActorLogLevel,
                    name: String,
                    message: String,
                    requestId: String?,
                    attributes: Map<String, String>,
                ) {
                    this@AppActor.emitDebugEvent(
                        runtimeSessionId = runtimeSessionId,
                        category = category,
                        level = level,
                        name = name,
                        message = message,
                        requestId = requestId,
                        attributes = attributes,
                    )
                }
            }
        )
    }

    private val lifecycleCoordinator: AppActorLifecycleCoordinator by lazy {
        AppActorLifecycleCoordinator(
            host = object : AppActorLifecycleCoordinatorHost {
                override fun currentRuntimeSnapshot(): AppActorRuntimeState? {
                    return this@AppActor.currentRuntimeSnapshot()
                }

                override suspend fun awaitStartupIfNeeded(runtimeState: AppActorRuntimeState) {
                    startupCoordinator.awaitStartupIfNeeded(runtimeState)
                }

                override suspend fun drainReceipts(runtimeState: AppActorRuntimeState) {
                    runtimeState.paymentProcessor.drainAll()
                }

                override suspend fun refreshCustomerInfoIfNeeded(runtimeState: AppActorRuntimeState) {
                    this@AppActor.refreshCustomerInfoOnForeground(runtimeState)
                }

                override fun emitDebugEvent(
                    runtimeSessionId: Long,
                    category: AppActorDebugCategory,
                    level: com.appactor.android.models.AppActorLogLevel,
                    name: String,
                    message: String,
                    requestId: String?,
                    attributes: Map<String, String>,
                ) {
                    this@AppActor.emitDebugEvent(
                        runtimeSessionId = runtimeSessionId,
                        category = category,
                        level = level,
                        name = name,
                        message = message,
                        requestId = requestId,
                        attributes = attributes,
                    )
                }
            }
        )
    }

    public val isConfigured: Boolean
        get() = currentRuntimeSnapshot() != null

    public val appUserId: String?
        get() = currentRuntimeSnapshot()?.identityStore?.currentAppUserId

    public val isAnonymous: Boolean
        get() = appUserId?.startsWith(ANONYMOUS_USER_PREFIX) == true

    public val customerInfo: AppActorCustomerInfo
        get() = currentRuntimeSnapshot()?.lastCustomerInfo ?: AppActorCustomerInfo.empty

    public val customerInfoFlow: StateFlow<AppActorCustomerInfo>
        get() = currentRuntimeSnapshot()?.customerInfoStateFlow
            ?: emptyCustomerInfoFlow

    public val cachedOfferings: AppActorOfferings?
        get() = currentRuntimeSnapshot()?.offeringsManager?.cached()

    public val cachedRemoteConfigs: AppActorRemoteConfigs?
        get() = currentRuntimeSnapshot()?.remoteConfigManager?.cached()

    public var onCustomerInfoChanged: ((AppActorCustomerInfo) -> Unit)?
        get() = synchronized(this) {
            runtime?.onCustomerInfoChanged ?: preconfiguredCallbacks.onCustomerInfoChanged
        }
        set(value) {
            synchronized(this) {
                preconfiguredCallbacks = preconfiguredCallbacks.copy(onCustomerInfoChanged = value)
                runtime = runtime?.copy(onCustomerInfoChanged = value)
            }
        }

    public var onReceiptPipelineEvent: ((AppActorReceiptPipelineEvent) -> Unit)?
        get() = synchronized(this) {
            runtime?.onReceiptPipelineEvent ?: preconfiguredCallbacks.onReceiptPipelineEvent
        }
        set(value) {
            synchronized(this) {
                preconfiguredCallbacks = preconfiguredCallbacks.copy(onReceiptPipelineEvent = value)
                runtime = runtime?.copy(onReceiptPipelineEvent = value)
            }
        }

    /**
     * Called when a previously pending (deferred) purchase resolves.
     * Provides the product ID and updated customer info.
     */
    public var onDeferredPurchaseResolved: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)?
        get() = synchronized(this) {
            runtime?.onDeferredPurchaseResolved ?: preconfiguredCallbacks.onDeferredPurchaseResolved
        }
        set(value) {
            synchronized(this) {
                preconfiguredCallbacks = preconfiguredCallbacks.copy(onDeferredPurchaseResolved = value)
                runtime?.paymentProcessor?.onDeferredPurchaseResolved = value?.let { callback ->
                    { productId, customerInfo -> deliverOnMain { callback(productId, customerInfo) } }
                }
                runtime = runtime?.copy(onDeferredPurchaseResolved = value)
            }
        }

    /**
     * Sets a custom log handler that receives SDK log messages alongside Android Log.
     * Pass `null` to remove the handler.
     */
    public fun setLogHandler(
        handler: ((level: String, message: String, category: String, timestamp: String) -> Unit)?
    ) {
        AppActorLogger.logHandler = handler
    }

    /**
     * Sets the SDK log level at runtime.
     */
    public fun setLogLevel(level: com.appactor.android.models.AppActorLogLevel) {
        AppActorLogger.setLevel(level)
    }

    /**
     * Enables Google Play Install Referrer tracking.
     *
     * Must be called **after** `configure()`. Calling before configuration is a no-op.
     * Calling more than once is a no-op. The referrer fetch runs in the background
     * and does **not** block this call.
     *
     * ```kotlin
     * AppActor.configure(context, "pk_YOUR_PUBLIC_API_KEY")
     * AppActor.enableInstallReferrer()
     * ```
     */
    public fun enableInstallReferrer() {
        val currentRuntime = runtime ?: run {
            AppActorLogger.warn("enableInstallReferrer() called before configure() — ignored.")
            return
        }
        if (!installReferrerEnabled.compareAndSet(false, true)) return
        currentRuntime.scope.launch {
            try {
                val manager = com.appactor.android.billing.AppActorInstallReferrerManager(
                    context = currentRuntime.configuration.applicationContext,
                    identityStore = currentRuntime.identityStore,
                )
                manager.fetchReferrerOnce()
            } catch (throwable: Throwable) {
                throwIfCancellation(throwable)
                AppActorLogger.debug("Install referrer fetch failed: ${throwable.message}")
            }
        }
    }

    internal suspend fun configure(configuration: AppActorConfiguration): Unit {
        val startupRuntime = transitionMutex.withLock {
            if (isResetting || runtime != null) {
                return@withLock null
            }
            bumpIdentityEpochLocked()
            configureInternal(configuration)
            runtime
        }
        if (startupRuntime != null) {
            awaitStartupIfNeeded(startupRuntime)
        }
    }

    public suspend fun configure(
        context: Context,
        apiKey: String,
        options: AppActorOptions = AppActorOptions(),
    ) {
        val startupRuntime = transitionMutex.withLock {
            if (isResetting || runtime != null) {
                return@withLock null
            }
            bumpIdentityEpochLocked()
            configureInternal(
                configuration = AppActorConfiguration(
                    context = context,
                    apiKey = apiKey,
                    options = options.toLegacyOptions(),
                ),
            )
            runtime
        }
        if (startupRuntime != null) {
            awaitStartupIfNeeded(startupRuntime)
        }
    }

    public suspend fun reset(): Unit {
        val currentRuntime = transitionMutex.withLock {
            bumpIdentityEpochLocked()
            isResetting = true
            val currentRuntime = runtime ?: run {
                preconfiguredCallbacks = AppActorCallbackState()
                preconfiguredFallbackOfferingsDTO = null
                isResetting = false
                return@withLock null
            }
            currentRuntime.lifecycleCallbacks?.let { callbacks ->
                (currentRuntime.configuration.applicationContext as? Application)
                    ?.unregisterActivityLifecycleCallbacks(callbacks)
            }
            runtime = null
            currentRuntime
        } ?: return

        try {
            val currentAppUserId = currentRuntime.identityStore.currentAppUserId
            currentRuntime.paymentProcessor.onDeferredPurchaseResolved = null
            currentRuntime.scope.cancel()
            callbackScope.cancel()
            callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            currentRuntime.storeAdapter.shutdown()
            currentRuntime.scope.coroutineContext[Job]?.join()
            currentRuntime.remoteConfigManager.clearCache(currentAppUserId)
            currentRuntime.experimentManager.clearCache(currentAppUserId)
            currentRuntime.identityStore.clearIdentity()
            currentRuntime.eTagManager.clearAll()
            installReferrerEnabled.set(false)
            synchronized(this) {
                preconfiguredCallbacks = AppActorCallbackState()
                preconfiguredFallbackOfferingsDTO = null
            }
            AppActorAtomicJsonReceiptQueueStore.deletePersistedFile(currentRuntime.configuration.applicationContext)
            AppActorAtomicJsonPostedLedgerStore.deletePersistedFile(currentRuntime.configuration.applicationContext)
            currentRuntime.configuration.applicationContext
                .getSharedPreferences("com.appactor.android.pending_purchases", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        } finally {
            transitionMutex.withLock {
                if (runtime == null) {
                    isResetting = false
                }
            }
        }
    }

    internal suspend fun identify(): AppActorCustomerInfo {
        awaitStartupBeforeTransition()
        val (info, callback) = transitionMutex.withLock {
            bumpIdentityEpochLocked()
            val currentRuntime = requireConfiguredRuntime()
            val info = currentRuntime.customerManager.identify()
            persistCustomerInfoLocked(currentRuntime, info)
            info to publishCustomerInfoLocked(
                currentRuntime = currentRuntime,
                info = info,
                source = currentRuntime.customerManager.lastLoadSource(),
            )
        }
        deliverOnMain {
            callback?.invoke(info)
        }
        return info
    }

    public suspend fun logIn(newAppUserId: String): AppActorCustomerInfo {
        awaitStartupBeforeTransition()
        val (info, callback) = transitionMutex.withLock {
            bumpIdentityEpochLocked()
            val currentRuntime = requireConfiguredRuntime()
            if (newAppUserId.isBlank()) {
                throw AppActorError.InvalidConfiguration("login appUserId must not be blank.")
            }
            val currentAppUserId = currentRuntime.identityStore.currentAppUserId
                ?: currentRuntime.identityStore.ensureAppUserId()
            currentRuntime.paymentProcessor.beginIdentityTransition()
            try {
                currentRuntime.paymentProcessor.drainAll()
                if (currentAppUserId != newAppUserId) {
                    currentRuntime.customerManager.clearCache(currentAppUserId)
                }
                currentRuntime.remoteConfigManager.clearCache(currentAppUserId)
                currentRuntime.experimentManager.clearCache(currentAppUserId)
                val info = if (currentAppUserId == newAppUserId) {
                    currentRuntime.customerManager.identify()
                } else {
                    currentRuntime.customerManager.logIn(
                        currentAppUserId = currentAppUserId,
                        newAppUserId = newAppUserId,
                    )
                }
                persistCustomerInfoLocked(currentRuntime, info)
                info to publishCustomerInfoLocked(
                    currentRuntime = currentRuntime,
                    info = info,
                    source = currentRuntime.customerManager.lastLoadSource(),
                )
            } finally {
                currentRuntime.paymentProcessor.endIdentityTransition()
            }
        }
        deliverOnMain {
            callback?.invoke(info)
        }
        return info
    }

    public suspend fun logOut(): Boolean {
        awaitStartupBeforeTransition()
        val (acknowledged, callbacks) = transitionMutex.withLock {
            bumpIdentityEpochLocked()
            val currentRuntime = requireConfiguredRuntime()
            val currentAppUserId = currentRuntime.identityStore.currentAppUserId
                ?: currentRuntime.identityStore.ensureAppUserId()
            if (currentAppUserId.startsWith(ANONYMOUS_USER_PREFIX)) {
                throw AppActorError.InvalidConfiguration(
                    "logOut() called on an anonymous user. Use logIn() to switch identity."
                )
            }

            currentRuntime.paymentProcessor.beginIdentityTransition()
            try {
                currentRuntime.paymentProcessor.drainAll()
                currentRuntime.customerManager.clearCache(currentAppUserId)
                currentRuntime.remoteConfigManager.clearCache(currentAppUserId)
                currentRuntime.experimentManager.clearCache(currentAppUserId)
                val callbacks = mutableListOf<Pair<((AppActorCustomerInfo) -> Unit)?, AppActorCustomerInfo>>()
                val acknowledged = runCatching { currentRuntime.customerManager.logOut(currentAppUserId) }
                    .getOrDefault(false)
                currentRuntime.identityStore.setServerUserId(null)
                currentRuntime.identityStore.setAppUserId(null)
                callbacks += publishCustomerInfoLocked(
                    currentRuntime,
                    AppActorCustomerInfo.empty,
                    AppActorDiagnosticsDataSource.Unknown,
                ) to AppActorCustomerInfo.empty
                val info = currentRuntime.customerManager.identify()
                persistCustomerInfoLocked(currentRuntime, info)
                callbacks += publishCustomerInfoLocked(
                    currentRuntime = currentRuntime,
                    info = info,
                    source = currentRuntime.customerManager.lastLoadSource(),
                ) to info
                acknowledged to callbacks
            } finally {
                currentRuntime.paymentProcessor.endIdentityTransition()
            }
        }
        callbacks.forEach { (callback, info) ->
            deliverOnMain {
                callback?.invoke(info)
            }
        }
        return acknowledged
    }

    public suspend fun offerings(): AppActorOfferings {
        return offerings(forceRefresh = false)
    }

    internal suspend fun offerings(forceRefresh: Boolean = false): AppActorOfferings {
        val currentRuntime = requireConfiguredRuntime()
        awaitStartupIfNeeded(currentRuntime)
        return currentRuntime.offeringsManager.getOfferings(forceRefresh = forceRefresh).also {
            persistOfferingsSource(
                runtimeSessionId = currentRuntime.sessionId,
                source = currentRuntime.offeringsManager.lastLoadSource(),
            )
        }
    }

    /**
     * Sets bundled JSON as fallback offerings for first-launch offline scenarios.
     * The fallback is used only when both network and disk cache fail.
     * Fallback offerings are immediately stale — the next call triggers a network refresh.
     *
     * Can be called before or after [configure].
     */
    public fun setFallbackOfferings(jsonData: ByteArray) {
        val dto = AppActorBackendJson.instance
            .decodeFromString<AppActorOfferingsEnvelopeDTO>(jsonData.decodeToString())
        synchronized(this) {
            runtime?.offeringsManager?.setFallbackOfferings(dto)
                ?: run { preconfiguredFallbackOfferingsDTO = dto }
        }
    }

    public suspend fun getCustomerInfo(): AppActorCustomerInfo {
        return getCustomerInfo(forceRefresh = false)
    }

    internal suspend fun getCustomerInfo(forceRefresh: Boolean = false): AppActorCustomerInfo {
        return executeGuardedRead(resolveAppUserId = true) { snapshot ->
            val (info, source) = try {
                snapshot.runtime.customerManager.getCustomerInfo(
                    appUserId = snapshot.appUserId,
                    forceRefresh = forceRefresh,
                    persistIdentityState = false,
                ) to snapshot.runtime.customerManager.lastLoadSource()
            } catch (throwable: Throwable) {
                throwIfCancellation(throwable)
                val error = throwable.toPublicAppActorError("Failed to fetch customer info.")
                if (!error.isTransient) throw error
                val offlineKeys = snapshot.runtime.customerManager.activeEntitlementKeysOffline(snapshot.appUserId)
                if (offlineKeys.isEmpty()) throw error
                val baseCustomer = snapshot.runtime.lastCustomerInfo
                AppActorCustomerInfo(
                    entitlements = baseCustomer.entitlements + offlineKeys.associateWith { key ->
                        AppActorEntitlementInfo(identifier = key, isActive = true)
                    },
                    subscriptions = baseCustomer.subscriptions,
                    nonSubscriptions = baseCustomer.nonSubscriptions,
                    consumableBalances = baseCustomer.consumableBalances,
                    tokenBalance = baseCustomer.tokenBalance,
                    snapshotDate = baseCustomer.snapshotDate,
                    appUserId = snapshot.appUserId,
                    requestId = baseCustomer.requestId,
                    requestDate = baseCustomer.requestDate,
                    firstSeen = baseCustomer.firstSeen,
                    lastSeen = baseCustomer.lastSeen,
                    managementUrl = baseCustomer.managementUrl,
                    isComputedOffline = true,
                    productEntitlements = baseCustomer.productEntitlements,
                ) to AppActorDiagnosticsDataSource.Offline
            }

            if (persistCustomerInfoIfCurrent(snapshot, info)) {
                publishCustomerInfoIfCurrent(snapshot, info, source)
            }
            info
        }
    }

    public suspend fun activeEntitlementKeysOffline(): Set<String> {
        val snapshot = captureOperationSnapshot(resolveAppUserId = true)
        return snapshot.runtime.customerManager.activeEntitlementKeysOffline(snapshot.appUserId)
    }

    public suspend fun getRemoteConfigs(): AppActorRemoteConfigs {
        return executeGuardedRead(resolveAppUserId = true) { snapshot ->
            val configs = snapshot.runtime.remoteConfigManager.getRemoteConfigs(appUserId = snapshot.appUserId)
            persistLastRequestIdIfCurrent(snapshot, snapshot.runtime.remoteConfigManager.requestId())
            persistRemoteConfigSource(
                snapshot = snapshot,
                source = snapshot.runtime.remoteConfigManager.lastLoadSource(),
            )
            configs
        }
    }

    public fun getRemoteConfig(key: String): AppActorConfigValue? {
        return cachedRemoteConfigs?.get(key)
    }

    public fun getRemoteConfigBool(key: String): Boolean? = getRemoteConfig(key)?.boolValue

    public fun getRemoteConfigString(key: String): String? = getRemoteConfig(key)?.stringValue

    public fun getRemoteConfigNumber(key: String): Double? = getRemoteConfig(key)?.doubleValue

    public fun getRemoteConfigInt(key: String): Int? = getRemoteConfig(key)?.intValue

    public suspend fun getExperimentAssignment(experimentKey: String): AppActorExperimentAssignment? {
        if (experimentKey.isBlank()) {
            throw AppActorError.InvalidConfiguration("experimentKey must not be blank.")
        }
        return executeGuardedRead(resolveAppUserId = true) { snapshot ->
            val assignment = snapshot.runtime.experimentManager.getAssignment(
                experimentKey = experimentKey,
                appUserId = snapshot.appUserId,
            )
            persistLastRequestIdIfCurrent(snapshot, snapshot.runtime.experimentManager.requestId())
            assignment
        }
    }

    public suspend fun getStorefront(): AppActorStorefront? {
        val currentRuntime = currentRuntimeSnapshot() ?: return null
        awaitStartupIfNeeded(currentRuntime)
        return currentRuntimeSnapshot()
            ?.takeIf { it.sessionId == currentRuntime.sessionId }
            ?.storeAdapter
            ?.currentStorefront()
    }

    public fun getStoreCapabilities(): Set<AppActorStoreCapability> {
        return currentRuntimeSnapshot()
            ?.storeAdapter
            ?.currentCapabilities()
            .orEmpty()
    }

    public fun canMakePurchases(
        requiredCapabilities: Set<AppActorStoreCapability> = emptySet(),
    ): Boolean {
        return currentRuntimeSnapshot()
            ?.storeAdapter
            ?.canMakePurchases(requiredCapabilities)
            ?: false
    }

    public suspend fun purchase(
        activity: Activity,
        appActorPackage: AppActorPackage,
    ): AppActorPurchaseResult {
        val snapshot = captureOperationSnapshot(resolveAppUserId = true)
        require(appActorPackage.productId.isNotBlank()) {
            "purchase package must contain a non-blank productId."
        }
        val result = snapshot.runtime.paymentProcessor.purchase(
            activity = activity,
            appActorPackage = appActorPackage,
            appUserIdOverride = snapshot.appUserId,
        )
        handlePurchaseResult(snapshot, result)
        return result
    }

    public suspend fun purchase(
        activity: Activity,
        params: AppActorPurchaseParams,
    ): AppActorPurchaseResult {
        return purchase(activity, params.toAppActorPackage())
    }

    public suspend fun restorePurchases(): AppActorCustomerInfo {
        return executeGuardedRead(resolveAppUserId = true) { snapshot ->
            val info = snapshot.runtime.paymentProcessor.restorePurchases(
                appUserIdOverride = snapshot.appUserId,
            )
            if (persistCustomerInfoIfCurrent(snapshot, info)) {
                publishCustomerInfoIfCurrent(snapshot, info, AppActorDiagnosticsDataSource.Network)
            }
            info
        }
    }

    public suspend fun syncPurchases(): AppActorCustomerInfo {
        return executeGuardedRead(resolveAppUserId = true) { snapshot ->
            snapshot.runtime.paymentProcessor.syncCurrentPurchases(
                appUserIdOverride = snapshot.appUserId,
            )
            val info = snapshot.runtime.customerManager.getCustomerInfo(
                appUserId = snapshot.appUserId,
                forceRefresh = true,
                persistIdentityState = false,
            )
            if (persistCustomerInfoIfCurrent(snapshot, info)) {
                publishCustomerInfoIfCurrent(snapshot, info, AppActorDiagnosticsDataSource.Network)
            }
            info
        }
    }

    private fun configureInternal(configuration: AppActorConfiguration) {
        require(configuration.apiKey.isNotBlank()) {
            "AppActor apiKey must not be blank."
        }
        if (runtime != null) {
            return
        }

        val callbackState = synchronized(this) {
            preconfiguredCallbacks
        }

        AppActorLogger.applyOverride(configuration.options.logLevel)
        val runtimeSessionId = nextRuntimeSessionId()
        var newRuntime = runtimeFactory.create(
            configuration = configuration,
            sessionId = runtimeSessionId,
            callbackState = callbackState,
            onPipelineEvent = { event -> publishReceiptPipelineEvent(runtimeSessionId, event) },
        )
        runtime = newRuntime
        preconfiguredFallbackOfferingsDTO?.let { dto ->
            newRuntime.offeringsManager.setFallbackOfferings(dto)
            preconfiguredFallbackOfferingsDTO = null
        }
        callbackState.onDeferredPurchaseResolved?.let { callback ->
            newRuntime.paymentProcessor.onDeferredPurchaseResolved = { productId, customerInfo ->
                deliverOnMain { callback(productId, customerInfo) }
            }
        }
        val lifecycleCallbacks = lifecycleCoordinator.registerLifecycleCallbacksIfNeeded(newRuntime)
        if (lifecycleCallbacks != null) {
            newRuntime = newRuntime.copy(lifecycleCallbacks = lifecycleCallbacks)
            runtime = newRuntime
        }
        emitDebugEvent(
            runtimeSessionId = runtimeSessionId,
            category = AppActorDebugCategory.Lifecycle,
            level = com.appactor.android.models.AppActorLogLevel.Info,
            name = "configured",
            message = "AppActor configured.",
            attributes = debugAttributes(
                "environment" to configuration.environment.name,
                "app_user_id" to newRuntime.identityStore.currentAppUserId,
                "platform_flavor" to configuration.options.platformInfo?.flavor,
                "platform_version" to configuration.options.platformInfo?.version,
            ),
        )

        val startupHandles = startupCoordinator.start(newRuntime)
        newRuntime = newRuntime.copy(
            identityReadyJob = startupHandles.identityReadyJob,
            bootstrapCompletionJob = startupHandles.bootstrapCompletionJob,
            purchaseUpdatesJob = startupHandles.purchaseUpdatesJob,
        )
        runtime = newRuntime
    }

    private suspend fun performStartupIdentifyTransition(
        runtimeState: AppActorRuntimeState,
    ): Pair<((AppActorCustomerInfo) -> Unit)?, AppActorCustomerInfo>? {
        return transitionMutex.withLock {
            bumpIdentityEpochLocked()
            val runtimeForTransition = requireConfiguredRuntime()
            if (runtimeForTransition.sessionId != runtimeState.sessionId) {
                return@withLock null
            }
            val info = runtimeForTransition.customerManager.identify()
            persistCustomerInfoLocked(runtimeForTransition, info)
            publishCustomerInfoLocked(
                currentRuntime = runtimeForTransition,
                info = info,
                source = runtimeForTransition.customerManager.lastLoadSource(),
            ) to info
        }
    }

    private suspend fun refreshCustomerInfoOnForeground(runtimeState: AppActorRuntimeState) {
        executeGuardedRead(resolveAppUserId = true) { snapshot ->
            if (snapshot.runtime.sessionId != runtimeState.sessionId) {
                return@executeGuardedRead null
            }
            if (snapshot.runtime.customerManager.isCustomerCacheFresh(snapshot.appUserId)) {
                return@executeGuardedRead null
            }
            val info = snapshot.runtime.customerManager.getCustomerInfo(
                appUserId = snapshot.appUserId,
                forceRefresh = false,
                persistIdentityState = false,
            )
            if (persistCustomerInfoIfCurrent(snapshot, info)) {
                publishCustomerInfoIfCurrent(
                    snapshot = snapshot,
                    info = info,
                    source = snapshot.runtime.customerManager.lastLoadSource(),
                )
            }
            info
        }
    }

    private suspend fun awaitStartupIfNeeded(currentRuntime: AppActorRuntimeState) {
        startupCoordinator.awaitStartupIfNeeded(currentRuntime)
    }

    private suspend fun handlePurchaseResult(
        snapshot: AppActorOperationSnapshot,
        result: AppActorPurchaseResult,
    ) {
        if (result is AppActorPurchaseResult.Success) {
            if (persistCustomerInfoIfCurrent(snapshot, result.customerInfo)) {
                publishCustomerInfoIfCurrent(
                    snapshot = snapshot,
                    info = result.customerInfo,
                    source = AppActorDiagnosticsDataSource.Network,
                )
            }
        }
    }

    private fun publishCustomerInfoLocked(
        currentRuntime: AppActorRuntimeState,
        info: AppActorCustomerInfo,
        source: AppActorDiagnosticsDataSource? = null,
    ): ((AppActorCustomerInfo) -> Unit)? {
        return synchronized(this) {
            val latestRuntime = runtime
            if (latestRuntime?.sessionId != currentRuntime.sessionId) {
                return@synchronized null
            }
            val updatedRuntime = latestRuntime.copy(
                lastCustomerInfo = info,
                lastCustomerInfoSource = source ?: latestRuntime.lastCustomerInfoSource,
            )
            runtime = updatedRuntime
            (latestRuntime.customerInfoStateFlow as? MutableStateFlow)?.value = info
            updatedRuntime.onCustomerInfoChanged
        }
    }

    private fun publishReceiptPipelineEvent(
        runtimeSessionId: Long,
        event: AppActorReceiptPipelineEvent,
    ) {
        val (callback, platformInfo) = synchronized(this) {
            val currentRuntime = runtime ?: return
            if (currentRuntime.sessionId != runtimeSessionId) {
                return
            }
            currentRuntime.onReceiptPipelineEvent to currentRuntime.configuration.options.platformInfo
        }
        deliverOnMain {
            callback?.invoke(event)
        }
        emitDebugEvent(
            runtimeSessionId = runtimeSessionId,
            category = AppActorDebugCategory.ReceiptPipeline,
            level = com.appactor.android.models.AppActorLogLevel.Debug,
            name = "receipt_pipeline_event",
            message = "Receipt pipeline event emitted.",
            requestId = (event as? AppActorReceiptPipelineEvent.PostedOk)?.requestId,
            attributes = platformDebugAttributes(platformInfo),
        )
    }

    private fun requireConfiguredRuntime(): AppActorRuntimeState {
        return currentRuntimeSnapshot() ?: throw AppActorError.NotConfigured
    }

    private fun currentRuntimeSnapshot(): AppActorRuntimeState? = synchronized(this) { runtime }

    private suspend fun awaitStartupBeforeTransition() {
        val currentRuntime = currentRuntimeSnapshot() ?: return
        awaitStartupIfNeeded(currentRuntime)
    }

    private suspend fun captureOperationSnapshot(
        resolveAppUserId: Boolean,
        awaitBootstrapCompletion: Boolean = true,
    ): AppActorOperationSnapshot {
        val currentRuntime = currentRuntimeSnapshot()
        if (awaitBootstrapCompletion && currentRuntime != null) {
            awaitStartupIfNeeded(currentRuntime)
        }
        return transitionMutex.withLock {
            val latestRuntime = requireConfiguredRuntime()
            AppActorOperationSnapshot(
                runtime = latestRuntime,
                epoch = identityEpoch,
                appUserId = if (resolveAppUserId) {
                    latestRuntime.identityStore.currentAppUserId ?: latestRuntime.identityStore.ensureAppUserId()
                } else {
                    latestRuntime.identityStore.currentAppUserId.orEmpty()
                },
            )
        }
    }

    private suspend fun <T> executeGuardedRead(
        resolveAppUserId: Boolean,
        operation: suspend (AppActorOperationSnapshot) -> T,
    ): T {
        var attempts = 0
        while (true) {
            val snapshot = captureOperationSnapshot(resolveAppUserId)
            val result = try {
                operation(snapshot)
            } catch (throwable: Throwable) {
                val snapshotStillCurrent = isSnapshotCurrent(snapshot)
                if (attempts == 0 && !snapshotStillCurrent) {
                    attempts += 1
                    continue
                }
                if (!snapshotStillCurrent) {
                    throw AppActorError.Network(
                        description = "State changed while performing the operation. Please retry.",
                    )
                }
                throw throwable
            }
            if (isSnapshotCurrent(snapshot)) {
                return result
            }
            if (attempts > 0) {
                throw AppActorError.Network(
                    description = "State changed while performing the operation. Please retry.",
                )
            }
            attempts += 1
        }
    }

    private suspend fun isSnapshotCurrent(snapshot: AppActorOperationSnapshot): Boolean {
        return transitionMutex.withLock {
            val currentRuntime = runtime ?: return@withLock false
            currentRuntime.sessionId == snapshot.runtime.sessionId && identityEpoch == snapshot.epoch
        }
    }

    private suspend fun persistCustomerInfoIfCurrent(
        snapshot: AppActorOperationSnapshot,
        info: AppActorCustomerInfo,
    ): Boolean {
        return transitionMutex.withLock {
            val currentRuntime = runtime ?: return@withLock false
            if (currentRuntime.sessionId != snapshot.runtime.sessionId || identityEpoch != snapshot.epoch) {
                return@withLock false
            }
            persistCustomerInfoLocked(currentRuntime, info)
            true
        }
    }

    private fun persistCustomerInfoLocked(
        currentRuntime: AppActorRuntimeState,
        info: AppActorCustomerInfo,
    ) {
        val resolvedAppUserId = info.appUserId?.takeIf { it.isNotBlank() }
        if (resolvedAppUserId != null) {
            currentRuntime.identityStore.setAppUserId(resolvedAppUserId)
            currentRuntime.identityStore.setServerUserId(resolvedAppUserId)
        }
        currentRuntime.identityStore.setLastRequestId(info.requestId)
    }

    private suspend fun publishCustomerInfoIfCurrent(
        snapshot: AppActorOperationSnapshot,
        info: AppActorCustomerInfo,
        source: AppActorDiagnosticsDataSource? = null,
    ): Boolean {
        val callback = transitionMutex.withLock {
            val currentRuntime = runtime ?: return@withLock null
            if (currentRuntime.sessionId != snapshot.runtime.sessionId || identityEpoch != snapshot.epoch) {
                return@withLock null
            }
            publishCustomerInfoLocked(currentRuntime, info, source)
        }
        deliverOnMain {
            callback?.invoke(info)
        }
        return callback != null
    }

    private suspend fun persistLastRequestIdIfCurrent(
        snapshot: AppActorOperationSnapshot,
        requestId: String?,
    ): Boolean {
        return transitionMutex.withLock {
            val currentRuntime = runtime ?: return@withLock false
            if (currentRuntime.sessionId != snapshot.runtime.sessionId || identityEpoch != snapshot.epoch) {
                return@withLock false
            }
            currentRuntime.identityStore.setLastRequestId(requestId)
            true
        }
    }


    private fun bumpIdentityEpochLocked(): Long {
        identityEpoch += 1
        return identityEpoch
    }

    private fun nextRuntimeSessionId(): Long = synchronized(this) {
        val sessionId = nextRuntimeSessionId
        nextRuntimeSessionId += 1
        sessionId
    }

    private suspend fun persistOfferingsSource(
        runtimeSessionId: Long,
        source: AppActorDiagnosticsDataSource?,
    ) {
        transitionMutex.withLock {
            val currentRuntime = runtime ?: return@withLock
            if (currentRuntime.sessionId != runtimeSessionId) {
                return@withLock
            }
            runtime = currentRuntime.copy(
                lastOfferingsSource = source ?: currentRuntime.lastOfferingsSource,
            )
        }
    }

    private suspend fun persistRemoteConfigSource(
        snapshot: AppActorOperationSnapshot,
        source: AppActorDiagnosticsDataSource?,
    ) {
        transitionMutex.withLock {
            val currentRuntime = runtime ?: return@withLock
            if (currentRuntime.sessionId != snapshot.runtime.sessionId || identityEpoch != snapshot.epoch) {
                return@withLock
            }
            runtime = currentRuntime.copy(
                lastRemoteConfigSource = source ?: currentRuntime.lastRemoteConfigSource,
            )
        }
    }

    internal fun launchAsync(
        operation: suspend () -> Unit,
        onComplete: AppActorCompletionCallback? = null,
        onError: AppActorErrorCallback? = null,
    ) {
        callbackScope.launch {
            runCatching {
                operation()
            }.onSuccess {
                deliverOnMain {
                    onComplete?.onComplete()
                }
            }.onFailure { throwable ->
                throwIfCancellation(throwable)
                val error = throwable.toPublicAppActorError()
                deliverOnMain {
                    onError?.onError(error)
                }
            }
        }
    }

    internal fun <T> launchAsync(
        operation: suspend () -> T,
        onSuccess: AppActorSuccessCallback<T>? = null,
        onError: AppActorErrorCallback? = null,
    ) {
        callbackScope.launch {
            runCatching {
                operation()
            }.onSuccess { result ->
                deliverOnMain {
                    onSuccess?.onSuccess(result)
                }
            }.onFailure { throwable ->
                throwIfCancellation(throwable)
                val error = throwable.toPublicAppActorError()
                deliverOnMain {
                    onError?.onError(error)
                }
            }
        }
    }

    internal fun deliverOnMain(block: () -> Unit) {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper == null || mainLooper.thread === Thread.currentThread()) {
            block()
        } else {
            Handler(mainLooper).post(block)
        }
    }

    private fun emitDebugEvent(
        runtimeSessionId: Long,
        category: AppActorDebugCategory,
        level: com.appactor.android.models.AppActorLogLevel,
        name: String,
        message: String,
        requestId: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        synchronized(this) {
            val currentRuntime = runtime ?: return
            if (currentRuntime.sessionId != runtimeSessionId) {
                return
            }
            category
            level
            name
            message
            requestId
            attributes
        }
    }

    private fun platformDebugAttributes(platformInfo: AppActorPlatformInfo?): Map<String, String> {
        return debugAttributes(
            "platform_flavor" to platformInfo?.flavor,
            "platform_version" to platformInfo?.version,
        )
    }

    private const val ANONYMOUS_USER_PREFIX = "appactor-anon-"
}

private fun currentAppVersion(context: Context): String? {
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()
}

private fun currentCountryCode(): String? {
    return runCatching {
        android.os.Build.VERSION.SDK_INT.let {
            java.util.Locale.getDefault().country.takeIf(String::isNotBlank)
        }
    }.getOrNull()
}

private fun Throwable.toPublicAppActorError(
    defaultMessage: String = "AppActor request failed.",
): AppActorError {
    return when (this) {
        is AppActorError -> this
        is AppActorBackendException.Network -> AppActorError.Network(description, throwable)
        is AppActorBackendException.Decoding -> AppActorError.Unknown(description, throwable)
        is AppActorBackendException.Signature -> when (result) {
            AppActorResponseSignatureVerifier.VerificationResult.SignatureMissing ->
                AppActorError.SignatureMissing(message ?: defaultMessage, this)
            AppActorResponseSignatureVerifier.VerificationResult.TimestampOutOfRange ->
                AppActorError.SignatureTimestampOutOfRange(message ?: defaultMessage, this)
            AppActorResponseSignatureVerifier.VerificationResult.NonceMismatch ->
                AppActorError.NonceMismatch(message ?: defaultMessage, this)
            AppActorResponseSignatureVerifier.VerificationResult.IntermediateCertInvalid ->
                AppActorError.IntermediateCertInvalid(message ?: defaultMessage, this)
            AppActorResponseSignatureVerifier.VerificationResult.IntermediateKeyExpired ->
                AppActorError.IntermediateKeyExpired(message ?: defaultMessage, this)
            else -> AppActorError.SignatureVerificationFailed(message ?: defaultMessage, this)
        }
        is AppActorBackendException.CustomerNotFound -> AppActorError.CustomerNotFound(
            appUserId = appUserId,
            description = message ?: defaultMessage,
        )
        is AppActorBackendException.Http -> {
            if (statusCode >= 500 || statusCode == 429) {
                AppActorError.Server(
                    description = message ?: defaultMessage,
                    statusCode = statusCode,
                    scope = error?.scope,
                    retryAfterSeconds = retryAfterSeconds,
                    throwable = this,
                )
            } else {
                AppActorError.Unknown(message ?: defaultMessage, this)
            }
        }

        else -> AppActorError.Unknown(message ?: defaultMessage, this)
    }
}
