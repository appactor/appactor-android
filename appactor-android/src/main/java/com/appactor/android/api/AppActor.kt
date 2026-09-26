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
import com.appactor.android.managers.AppActorCustomAttributionField
import com.appactor.android.managers.normalizeAlpha2Country
import com.appactor.android.models.AppActorAttributeReservedKeys
import com.appactor.android.models.AppActorAttributeValue
import com.appactor.android.models.AppActorAttributesValidation
import com.appactor.android.models.AppActorAttribution
import com.appactor.android.models.AppActorCompletionCallback
import com.appactor.android.models.AppActorConfigValue
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorDebugCategory
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorEntitlementInfo
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorExperiment
import com.appactor.android.models.AppActorExperimentAssignment
import com.appactor.android.models.AppActorErrorCallback
import com.appactor.android.models.AppActorIntegrationIdentifier
import com.appactor.android.models.AppActorOffering
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorOfferingsFetchPolicy
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPlatformInfo
import com.appactor.android.models.AppActorPurchaseParams
import com.appactor.android.models.AppActorPurchaseResult
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.internal.AppActorSDK
import com.appactor.android.models.AppActorRemoteConfigs
import com.appactor.android.models.AppActorVerificationResult
import com.appactor.android.models.AppActorSuccessCallback
import com.appactor.android.models.toLegacyOptions
import com.appactor.android.models.toAppActorPackage
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import com.appactor.android.models.AppActorValidation
import com.appactor.android.pipeline.AppActorPurchaseUpdateProcessingResult
import com.appactor.android.storage.isAnonymousAppUserId
import com.appactor.android.storage.AppActorAtomicJsonPostedLedgerStore
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // One flow for the whole process, as iOS publishes customerInfo, so a collector taken before
    // configure() or held across a reset() keeps receiving.
    private val customerInfoStateFlow = MutableStateFlow(AppActorCustomerInfo.empty)

    @Volatile
    private var identityEpoch: Long = 0L

    @Volatile
    private var nextRuntimeSessionId: Long = 1L

    // The wipe of a reset() in progress; configure() is a no-op while it runs.
    private var resetWipe: CompletableDeferred<Unit>? = null
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
                        refreshEntitlementsIfMissing = false,
                        unfinishedOnly = true,
                    )
                }

                override suspend fun retryDeadLetteredItems(
                    snapshot: AppActorOperationSnapshot,
                ): AppActorCustomerInfo? {
                    return snapshot.runtime.paymentProcessor.retryDeadLetteredItems()
                }

                override suspend fun prefetchOfferings(
                    runtimeState: AppActorRuntimeState,
                ): AppActorDiagnosticsDataSource? {
                    return runtimeState.offeringsManager.prefetchForBootstrap()
                }

                override suspend fun fetchCustomerInfo(
                    snapshot: AppActorOperationSnapshot,
                ): Pair<AppActorCustomerInfo, AppActorDiagnosticsDataSource?> {
                    val info = snapshot.runtime.customerManager.getCustomerInfo(
                        appUserId = snapshot.appUserId,
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

                override suspend fun seedOfflineCustomerInfoIfEmpty(
                    snapshot: AppActorOperationSnapshot,
                ): Boolean {
                    return this@AppActor.seedOfflineCustomerInfoIfEmpty(snapshot)
                }

                override suspend fun persistOfferingsSource(
                    runtimeSessionId: Long,
                    source: AppActorDiagnosticsDataSource?,
                ) {
                    this@AppActor.persistOfferingsSource(runtimeSessionId, source)
                }

                override suspend fun processPurchaseUpdates(
                    runtimeState: AppActorRuntimeState,
                    purchases: List<AppActorStorePurchase>,
                ): AppActorPurchaseUpdateProcessingResult? {
                    return runtimeState.paymentProcessor.processLivePurchaseUpdates(
                        purchases = purchases,
                    )
                }

                override suspend fun publishPurchaseUpdateIfCurrent(
                    runtimeState: AppActorRuntimeState,
                    result: AppActorPurchaseUpdateProcessingResult,
                ): Boolean {
                    return this@AppActor.publishPurchaseUpdateIfCurrent(runtimeState, result)
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
                    publishDrainedCustomerInfo(runtimeState, runtimeState.paymentProcessor.drainAll())
                }

                override suspend fun flushPendingAttributes(runtimeState: AppActorRuntimeState) {
                    runtimeState.attributesManager.flushPendingForAllUsers()
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

    public val appUserId: String?
        get() = currentRuntimeSnapshot()?.identityStore?.currentAppUserId

    public val isAnonymous: Boolean
        get() = appUserId?.let(::isAnonymousAppUserId) ?: true

    public val customerInfo: AppActorCustomerInfo
        get() = currentRuntimeSnapshot()?.lastCustomerInfo ?: AppActorCustomerInfo.empty

    public val customerInfoFlow: StateFlow<AppActorCustomerInfo> = customerInfoStateFlow.asStateFlow()

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
                runtime?.let(::installRuntimeLocked)
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
            var referrerRecorded = false
            try {
                val manager = com.appactor.android.billing.AppActorInstallReferrerManager(
                    context = currentRuntime.configuration.applicationContext,
                    identityStore = currentRuntime.identityStore,
                    attributesManager = currentRuntime.attributesManager,
                )
                referrerRecorded = manager.fetchReferrerOnce() != null
            } catch (throwable: Throwable) {
                throwIfCancellation(throwable)
                AppActorLogger.debug("Install referrer fetch failed: ${throwable.message}")
            } finally {
                if (!referrerRecorded) {
                    installReferrerEnabled.set(false)
                }
            }
        }
    }

    internal suspend fun configure(configuration: AppActorConfiguration): Unit {
        val startupRuntime = transitionMutex.withLock {
            if (resetWipe != null || runtime != null) {
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
        appUserId: String? = null,
        options: AppActorOptions = AppActorOptions(),
    ) {
        val startupRuntime = throwingPublicErrors("Failed to configure AppActor.") {
            transitionMutex.withLock {
                if (resetWipe != null || runtime != null) {
                    return@withLock null
                }
                bumpIdentityEpochLocked()
                configureInternal(
                    configuration = AppActorConfiguration(
                        context = context,
                        apiKey = apiKey,
                        appUserId = appUserId,
                        options = options.toLegacyOptions(),
                    ),
                )
                runtime
            }
        }
        if (startupRuntime != null) {
            awaitStartupIfNeeded(startupRuntime)
        }
    }

    // Runs non-cancellably: the wipe must finish even when the caller is cancelled, which
    // includes an AppActorBridge.reset() running in the callbackScope cancelled below.
    public suspend fun reset(): Unit = withContext(NonCancellable) {
        val wipe = CompletableDeferred<Unit>()
        var earlierWipe: CompletableDeferred<Unit>? = null
        val currentRuntime = transitionMutex.withLock {
            bumpIdentityEpochLocked()
            // Under the lock the callback setters take too, so none can write the runtime back.
            val currentRuntime = synchronized(this@AppActor) {
                preconfiguredFallbackOfferingsDTO = null
                runtime?.also {
                    runtime = null
                    customerInfoStateFlow.value = AppActorCustomerInfo.empty
                }
            } ?: run {
                earlierWipe = resetWipe
                return@withLock null
            }
            resetWipe = wipe
            currentRuntime.lifecycleCallbacks?.let { callbacks ->
                (currentRuntime.configuration.applicationContext as? Application)
                    ?.unregisterActivityLifecycleCallbacks(callbacks)
            }
            currentRuntime
        }
        if (currentRuntime == null) {
            // A reset that finds nothing configured still returns only once an earlier one has
            // finished wiping, so a configure() after it is not skipped or wiped.
            earlierWipe?.await()
            return@withContext
        }

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
            currentRuntime.attributesManager.clearQueue()
            currentRuntime.identityStore.clearIdentity()
            currentRuntime.eTagManager.clearAll()
            installReferrerEnabled.set(false)
            AppActorAtomicJsonReceiptQueueStore.deletePersistedFile(currentRuntime.configuration.applicationContext)
            AppActorAtomicJsonPostedLedgerStore.deletePersistedFile(currentRuntime.configuration.applicationContext)
            currentRuntime.configuration.applicationContext
                .getSharedPreferences("com.appactor.android.pending_purchases", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (throwable: Throwable) {
            // A reset waiting on this one must not report a wipe that did not happen.
            wipe.completeExceptionally(throwable)
            throw throwable
        } finally {
            transitionMutex.withLock {
                resetWipe = null
            }
            wipe.complete(Unit)
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
        val (info, callbacks, profileContextTarget) = throwingPublicErrors("Failed to log in.") { transitionMutex.withLock {
            bumpIdentityEpochLocked()
            val currentRuntime = requireConfiguredRuntime()
            AppActorValidation.validateAppUserId(newAppUserId)
            val currentAppUserId = currentRuntime.identityStore.currentAppUserId
                ?: currentRuntime.identityStore.ensureAppUserId()
            val callbacks = mutableListOf<Triple<((AppActorCustomerInfo) -> Unit)?, AppActorCustomerInfo, Long>>()
            var transitionResults = emptyList<AppActorPurchaseUpdateProcessingResult>()
            currentRuntime.paymentProcessor.beginIdentityTransition()
            val info = try {
                currentRuntime.paymentProcessor.drainAll()
                flushOutgoingUserAttributes(currentRuntime, currentAppUserId)
                val info = currentRuntime.customerManager.logIn(
                    currentAppUserId = currentAppUserId,
                    newAppUserId = newAppUserId,
                )
                val source = currentRuntime.customerManager.lastLoadSource()
                // Only once the login has succeeded: a failed one leaves the user as they were,
                // caches included.
                if (currentAppUserId != currentRuntime.identityStore.currentAppUserId) {
                    currentRuntime.customerManager.clearCache(currentAppUserId)
                }
                currentRuntime.remoteConfigManager.clearCache(currentAppUserId)
                currentRuntime.experimentManager.clearCache(currentAppUserId)
                persistCustomerInfoLocked(currentRuntime, info)
                callbacks += Triple(
                    publishCustomerInfoLocked(
                        currentRuntime = currentRuntime,
                        info = info,
                        source = source,
                    ),
                    info,
                    identityEpoch,
                )
                info
            } finally {
                transitionResults = currentRuntime.paymentProcessor.endIdentityTransition()
            }
            transitionResults.forEach { result ->
                purchaseUpdatePublishCallbackIfCurrentLocked(currentRuntime, result)?.let(callbacks::add)
            }
            val targetAppUserId = currentRuntime.identityStore.currentAppUserId
                ?: info.appUserId
                ?: newAppUserId
            Triple(info, callbacks, currentRuntime to targetAppUserId)
        } }
        callbacks.forEach { (callback, callbackInfo, epoch) ->
            deliverOnMainIfCurrent(profileContextTarget.first.sessionId, epoch) {
                callback?.invoke(callbackInfo)
            }
        }
        scheduleAutomaticProfileContextSyncAfterIdentityTransition(
            runtimeState = profileContextTarget.first,
            appUserId = profileContextTarget.second,
        )
        return info
    }

    public suspend fun logOut(): Boolean {
        awaitStartupBeforeTransition()
        val (callbacks, profileContextTarget) = transitionMutex.withLock {
            bumpIdentityEpochLocked()
            val currentRuntime = requireConfiguredRuntime()
            val currentAppUserId = currentRuntime.identityStore.currentAppUserId
                ?: currentRuntime.identityStore.ensureAppUserId()
            if (isAnonymousAppUserId(currentAppUserId)) {
                throw AppActorError.InvalidConfiguration(
                    "logOut() called on an anonymous user. Use logIn() to switch identity."
                )
            }

            var transitionResults = emptyList<AppActorPurchaseUpdateProcessingResult>()
            currentRuntime.paymentProcessor.beginIdentityTransition()
            val callbacks = try {
                currentRuntime.paymentProcessor.drainAll()
                flushOutgoingUserAttributes(currentRuntime, currentAppUserId)
                currentRuntime.customerManager.clearCache(currentAppUserId)
                currentRuntime.remoteConfigManager.clearCache(currentAppUserId)
                currentRuntime.experimentManager.clearCache(currentAppUserId)
                val callbacks = mutableListOf<Triple<((AppActorCustomerInfo) -> Unit)?, AppActorCustomerInfo, Long>>()
                currentRuntime.identityStore.setAppUserId(null)
                val newAppUserId = currentRuntime.identityStore.ensureAppUserId()
                currentRuntime.identityStore.clearLegacyIdentityState()
                callbacks += Triple(
                    publishCustomerInfoLocked(
                        currentRuntime,
                        AppActorCustomerInfo.empty,
                        AppActorDiagnosticsDataSource.Unknown,
                    ),
                    AppActorCustomerInfo.empty,
                    identityEpoch,
                )
                callbacks to newAppUserId
            } finally {
                transitionResults = currentRuntime.paymentProcessor.endIdentityTransition()
            }
            transitionResults.forEach { result ->
                purchaseUpdatePublishCallbackIfCurrentLocked(currentRuntime, result)?.let(callbacks.first::add)
            }
            callbacks.first to (currentRuntime to callbacks.second)
        }
        callbacks.forEach { (callback, info, epoch) ->
            deliverOnMainIfCurrent(profileContextTarget.first.sessionId, epoch) {
                callback?.invoke(info)
            }
        }
        scheduleAutomaticProfileContextSyncAfterIdentityTransition(
            runtimeState = profileContextTarget.first,
            appUserId = profileContextTarget.second,
        )
        return true
    }

    public suspend fun offerings(): AppActorOfferings {
        return offerings(fetchPolicy = AppActorOfferingsFetchPolicy.FreshIfStale)
    }

    public suspend fun offerings(
        fetchPolicy: AppActorOfferingsFetchPolicy,
    ): AppActorOfferings {
        val currentRuntime = requireConfiguredRuntime()
        awaitStartupIfNeeded(currentRuntime)
        return currentRuntime.offeringsManager.getOfferings(fetchPolicy = fetchPolicy).also {
            persistOfferingsSource(
                runtimeSessionId = currentRuntime.sessionId,
                source = currentRuntime.offeringsManager.lastLoadSource(),
            )
        }
    }

    /**
     * Fetches offerings (see [offerings]) and returns the one with the given
     * [AppActorOffering.offeringKey], or `null` if the app has no such offering.
     *
     * ```kotlin
     * AppActor.shared.getOffering("onboarding")?.annual?.let { AppActor.shared.purchase(activity, it) }
     * ```
     */
    public suspend fun getOffering(
        offeringKey: String,
        fetchPolicy: AppActorOfferingsFetchPolicy = AppActorOfferingsFetchPolicy.FreshIfStale,
    ): AppActorOffering? = offerings(fetchPolicy).getOffering(offeringKey)

    internal suspend fun offerings(forceRefresh: Boolean = false): AppActorOfferings {
        return if (forceRefresh) {
            requireConfiguredRuntime().let { currentRuntime ->
                awaitStartupIfNeeded(currentRuntime)
                currentRuntime.offeringsManager.getOfferings(forceRefresh = true).also {
                    persistOfferingsSource(
                        runtimeSessionId = currentRuntime.sessionId,
                        source = currentRuntime.offeringsManager.lastLoadSource(),
                    )
                }
            }
        } else {
            offerings(fetchPolicy = AppActorOfferingsFetchPolicy.ReturnCachedThenRefresh)
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
        val dto = try {
            AppActorBackendJson.instance.decodeFromString<AppActorOfferingsEnvelopeDTO>(jsonData.decodeToString())
        } catch (error: Exception) {
            throw AppActorError.Decoding(error.message ?: "Invalid fallback offerings JSON", error)
        }
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
                ) to snapshot.runtime.customerManager.lastLoadSource()
            } catch (throwable: Throwable) {
                throwIfCancellation(throwable)
                val error = throwable.toPublicAppActorError("Failed to fetch customer info.")
                if (!error.isTransient) throw error
                val offlineKeys = snapshot.runtime.customerManager.activeEntitlementKeysOffline(snapshot.appUserId)
                // Only now, since the offline keys read the cache while it is fresh: reset its
                // freshness so the staleness timer retries at once.
                snapshot.runtime.customerManager.resetFreshness(snapshot.appUserId)
                if (offlineKeys.isEmpty()) throw error
                val baseCustomer = snapshot.runtime.lastCustomerInfo
                buildOfflineCustomerInfo(
                    appUserId = snapshot.appUserId,
                    baseCustomer = baseCustomer,
                    offlineKeys = offlineKeys,
                ) to AppActorDiagnosticsDataSource.Offline
            }

            if (persistCustomerInfoIfCurrent(snapshot, info)) {
                publishCustomerInfoIfCurrent(snapshot, info, source)
            }
            info
        }
    }

    private fun buildOfflineCustomerInfo(
        appUserId: String,
        baseCustomer: AppActorCustomerInfo,
        offlineKeys: Set<String>,
    ): AppActorCustomerInfo {
        return AppActorCustomerInfo(
            entitlements = baseCustomer.entitlements + offlineKeys.associateWith { key ->
                AppActorEntitlementInfo(identifier = key, isActive = true)
            },
            subscriptions = baseCustomer.subscriptions,
            nonSubscriptions = baseCustomer.nonSubscriptions,
            consumableBalances = baseCustomer.consumableBalances,
            tokenBalance = baseCustomer.tokenBalance,
            snapshotDate = baseCustomer.snapshotDate,
            appUserId = appUserId,
            requestId = baseCustomer.requestId,
            requestDate = baseCustomer.requestDate,
            firstSeen = baseCustomer.firstSeen,
            lastSeen = baseCustomer.lastSeen,
            managementUrl = baseCustomer.managementUrl,
            isComputedOffline = true,
            productEntitlements = baseCustomer.productEntitlements,
            verification = AppActorVerificationResult.VerifiedOnDevice,
        )
    }

    /**
     * Cold-start offline seed: when no entitlement state has been published yet (e.g.
     * reinstall with no cached customer), derive active entitlements from local Play
     * Billing purchases and publish them so premium renders before the network refresh.
     * Never downgrades a real value — only seeds while the published customer is still empty.
     */
    internal suspend fun seedOfflineCustomerInfoIfEmpty(snapshot: AppActorOperationSnapshot): Boolean {
        // Cheap pre-check to skip the Play Billing query when a value is already published.
        if ((currentRuntimeSnapshot()?.lastCustomerInfo ?: AppActorCustomerInfo.empty) != AppActorCustomerInfo.empty) {
            return false
        }
        val offlineKeys = snapshot.runtime.customerManager.activeEntitlementKeysOffline(snapshot.appUserId)
        if (offlineKeys.isEmpty()) return false

        // Publish from an empty base, re-checking emptiness inside the publish lock (the same
        // lock the concurrent purchase-update publisher uses) so the seed never overwrites a
        // real value that landed while the Play Billing query above was in flight.
        val info = buildOfflineCustomerInfo(
            appUserId = snapshot.appUserId,
            baseCustomer = AppActorCustomerInfo.empty,
            offlineKeys = offlineKeys,
        )
        return publishCustomerInfoIfCurrent(
            snapshot = snapshot,
            info = info,
            source = AppActorDiagnosticsDataSource.Offline,
            guard = { it.lastCustomerInfo == AppActorCustomerInfo.empty },
        )
    }

    public suspend fun activeEntitlementKeysOffline(): Set<String> {
        val snapshot = captureOperationSnapshot(resolveAppUserId = true)
        return snapshot.runtime.customerManager.activeEntitlementKeysOffline(snapshot.appUserId)
    }

    /**
     * Sets developer-defined custom attributes. Keys starting with `$` or
     * `appactor.` are reserved for AppActor system/profile context helpers.
     * A `null` value unsets that custom attribute.
     */
    public suspend fun setAttributes(attributes: Map<String, AppActorAttributeValue?>) {
        executeWrite { snapshot ->
            snapshot.runtime.attributesManager.setAttributes(
                appUserId = snapshot.appUserId,
                attributes = attributes,
            )
        }
    }

    public suspend fun setAttribute(
        key: String,
        value: AppActorAttributeValue,
    ) {
        executeWrite { snapshot ->
            snapshot.runtime.attributesManager.setAttribute(
                appUserId = snapshot.appUserId,
                key = key,
                value = value,
            )
        }
    }

    public suspend fun unsetAttribute(key: String) {
        executeWrite { snapshot ->
            snapshot.runtime.attributesManager.unsetAttribute(
                appUserId = snapshot.appUserId,
                key = key,
            )
        }
    }

    public suspend fun setEmail(email: String?) {
        email?.let(AppActorAttributesValidation::validateEmail)
        setReservedString(AppActorAttributeReservedKeys.email, email)
    }

    public suspend fun setDisplayName(displayName: String?) {
        setReservedString(AppActorAttributeReservedKeys.displayName, displayName)
    }

    public suspend fun setPhoneNumber(phoneNumber: String?) {
        phoneNumber?.let(AppActorAttributesValidation::validatePhoneNumber)
        setReservedString(AppActorAttributeReservedKeys.phoneNumber, phoneNumber)
    }

    public suspend fun setPushToken(pushToken: String?) {
        setReservedString(AppActorAttributeReservedKeys.fcmToken, pushToken)
    }

    /**
     * Collects optional device identifiers. Privacy-safe profile context is sent
     * automatically during configure.
     */
    public suspend fun collectDeviceIdentifiers() {
        executeWrite { snapshot ->
            snapshot.runtime.attributesManager.collectDeviceIdentifiers(snapshot.appUserId)
        }
    }

    /**
     * Sets or clears an integration identifier on the integration-identifiers endpoint.
     */
    public suspend fun setIntegrationIdentifier(
        type: String,
        value: String?,
    ) {
        executeWrite { snapshot ->
            snapshot.runtime.attributesManager.setIntegrationIdentifier(
                appUserId = snapshot.appUserId,
                type = type,
                value = value,
            )
        }
    }

    public suspend fun unsetIntegrationIdentifier(type: String) {
        executeWrite { snapshot ->
            snapshot.runtime.attributesManager.unsetIntegrationIdentifier(
                appUserId = snapshot.appUserId,
                type = type,
            )
        }
    }

    public suspend fun setIntegrationIdentifier(
        type: AppActorIntegrationIdentifier,
        value: String?,
    ) {
        setIntegrationIdentifier(type.wireValue, value)
    }

    public suspend fun unsetIntegrationIdentifier(type: AppActorIntegrationIdentifier) {
        unsetIntegrationIdentifier(type.wireValue)
    }

    public suspend fun setAppsflyerID(appsflyerID: String?) {
        setIntegrationIdentifier(AppActorIntegrationIdentifier.AppsFlyerId, appsflyerID)
    }

    public suspend fun setAppsFlyerID(appsFlyerID: String?) {
        setAppsflyerID(appsFlyerID)
    }

    public suspend fun setAdjustID(adjustID: String?) {
        setIntegrationIdentifier(AppActorIntegrationIdentifier.AdjustId, adjustID)
    }

    public suspend fun setBranchID(branchID: String?) {
        setIntegrationIdentifier(AppActorIntegrationIdentifier.BranchId, branchID)
    }

    public suspend fun setFirebaseAppInstanceID(firebaseAppInstanceID: String?) {
        setIntegrationIdentifier(AppActorIntegrationIdentifier.FirebaseAppInstanceId, firebaseAppInstanceID)
    }

    public suspend fun setOneSignalID(oneSignalID: String?) {
        setIntegrationIdentifier(AppActorIntegrationIdentifier.OneSignalId, oneSignalID)
    }

    /**
     * Sends acquisition attribution on the attribution endpoint.
     */
    public suspend fun updateAttribution(attribution: AppActorAttribution) {
        executeWrite { snapshot ->
            snapshot.runtime.attributesManager.updateAttribution(
                appUserId = snapshot.appUserId,
                attribution = attribution,
            )
        }
    }

    public suspend fun setMediaSource(mediaSource: String?) {
        updateCustomAttribution(AppActorAttribution(
            provider = "custom",
            providerName = mediaSource,
            network = mediaSource,
            source = mediaSource,
        ), clearFields = if (mediaSource == null) setOf(AppActorCustomAttributionField.MediaSource) else emptySet())
    }

    public suspend fun setCampaign(campaign: String?) {
        updateCustomAttribution(
            AppActorAttribution(provider = "custom", campaignName = campaign, campaign = campaign),
            clearFields = if (campaign == null) setOf(AppActorCustomAttributionField.Campaign) else emptySet(),
        )
    }

    public suspend fun setAdGroup(adGroup: String?) {
        updateCustomAttribution(
            AppActorAttribution(provider = "custom", adGroupName = adGroup, adGroup = adGroup),
            clearFields = if (adGroup == null) setOf(AppActorCustomAttributionField.AdGroup) else emptySet(),
        )
    }

    public suspend fun setAd(ad: String?) {
        updateCustomAttribution(
            AppActorAttribution(provider = "custom", adName = ad, ad = ad),
            clearFields = if (ad == null) setOf(AppActorCustomAttributionField.Ad) else emptySet(),
        )
    }

    public suspend fun setKeyword(keyword: String?) {
        updateCustomAttribution(
            AppActorAttribution(provider = "custom", keyword = keyword),
            clearFields = if (keyword == null) setOf(AppActorCustomAttributionField.Keyword) else emptySet(),
        )
    }

    public suspend fun setCreative(creative: String?) {
        updateCustomAttribution(
            AppActorAttribution(provider = "custom", creativeName = creative, creative = creative),
            clearFields = if (creative == null) setOf(AppActorCustomAttributionField.Creative) else emptySet(),
        )
    }

    private suspend fun updateCustomAttribution(
        attribution: AppActorAttribution,
        clearFields: Set<AppActorCustomAttributionField> = emptySet(),
    ) {
        executeWrite { snapshot ->
            snapshot.runtime.attributesManager.updateCustomAttribution(
                appUserId = snapshot.appUserId,
                patch = attribution,
                clearFields = clearFields,
            )
        }
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

    /**
     * Resolves the user's standing in an experiment.
     *
     * Never `null`: when the user is not in the experiment the result reports
     * `isEnrolled == false`, `variantKey == null`, and every typed getter returns its default.
     * Same caching and errors as [getExperimentAssignment].
     *
     * ```kotlin
     * val paywall = AppActor.shared.getExperiment("paywall_test")
     * if (paywall.isVariant("annual_first")) showAnnualFirst()
     *
     * val showOnboarding = AppActor.shared.getExperiment("has_onboard").boolValue(defaultValue = true)
     * val title = AppActor.shared.getExperiment("onboarding_flow")["title"]?.stringValue ?: "Welcome"
     * ```
     */
    public suspend fun getExperiment(experimentKey: String): AppActorExperiment =
        AppActorExperiment(experimentKey, getExperimentAssignment(experimentKey))

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
    ): AppActorPurchaseResult = purchase(
        activity = activity,
        appActorPackage = appActorPackage,
        placement = null,
    )

    public suspend fun purchase(
        activity: Activity,
        appActorPackage: AppActorPackage,
        placement: String?,
    ): AppActorPurchaseResult {
        val snapshot = captureOperationSnapshot(resolveAppUserId = true)
        require(appActorPackage.productId.isNotBlank()) {
            "purchase package must contain a non-blank productId."
        }
        val result = snapshot.runtime.paymentProcessor.purchase(
            activity = activity,
            appActorPackage = appActorPackage,
            appUserIdOverride = snapshot.appUserId,
            placement = placement,
        )
        handlePurchaseResult(snapshot, result)
        return result
    }

    @Deprecated(
        message = "Prefer AppActor.purchase(activity, appActorPackage). " +
            "AppActorPurchaseParams is only for explicit direct Play Store targets.",
    )
    public suspend fun purchase(
        activity: Activity,
        params: AppActorPurchaseParams,
    ): AppActorPurchaseResult = purchase(
        activity = activity,
        params = params,
        placement = null,
    )

    @Deprecated(
        message = "Prefer AppActor.purchase(activity, appActorPackage). " +
            "AppActorPurchaseParams is only for explicit direct Play Store targets.",
    )
    public suspend fun purchase(
        activity: Activity,
        params: AppActorPurchaseParams,
        placement: String?,
    ): AppActorPurchaseResult {
        val snapshot = captureOperationSnapshot(resolveAppUserId = true)
        val result = snapshot.runtime.paymentProcessor.purchase(
            activity = activity,
            params = params,
            appUserIdOverride = snapshot.appUserId,
            placement = placement,
        )
        handlePurchaseResult(snapshot, result)
        return result
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

    public suspend fun drainReceiptQueueAndRefreshCustomer(): AppActorCustomerInfo =
        refreshCustomerInfoAfter { snapshot -> snapshot.runtime.paymentProcessor.drainAll() }

    public suspend fun syncPurchases(): AppActorCustomerInfo =
        refreshCustomerInfoAfter { snapshot ->
            snapshot.runtime.paymentProcessor.syncCurrentPurchases(appUserIdOverride = snapshot.appUserId)
        }

    private suspend fun refreshCustomerInfoAfter(
        step: suspend (AppActorOperationSnapshot) -> Unit,
    ): AppActorCustomerInfo {
        return executeGuardedRead(resolveAppUserId = true) { snapshot ->
            step(snapshot)
            // The step may have adopted a canonical id, so fetch for whoever is current now.
            val info = snapshot.runtime.customerManager.getCustomerInfo(
                appUserId = snapshot.runtime.identityStore.currentAppUserId ?: snapshot.appUserId,
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

    private fun configureInternal(configuration: AppActorConfiguration) {
        require(configuration.apiKey.isNotBlank()) {
            "AppActor apiKey must not be blank."
        }
        if (runtime != null) {
            return
        }

        AppActorLogger.applyOverride(configuration.options.logLevel)
        val runtimeSessionId = nextRuntimeSessionId()
        var newRuntime = installRuntime(
            runtimeFactory.create(
                configuration = configuration,
                sessionId = runtimeSessionId,
                callbackState = AppActorCallbackState(),
                onPipelineEvent = { event -> publishReceiptPipelineEvent(runtimeSessionId, event) },
            )
        )
        customerInfoStateFlow.value = newRuntime.lastCustomerInfo
        val drainRuntime = newRuntime
        newRuntime.paymentProcessor.onRetryWakeDrained = { info -> publishDrainedCustomerInfo(drainRuntime, info) }
        preconfiguredFallbackOfferingsDTO?.let { dto ->
            newRuntime.offeringsManager.setFallbackOfferings(dto)
            preconfiguredFallbackOfferingsDTO = null
        }
        val lifecycleCallbacks = lifecycleCoordinator.registerLifecycleCallbacksIfNeeded(newRuntime)
        if (lifecycleCallbacks != null) {
            newRuntime = installRuntime(newRuntime.copy(lifecycleCallbacks = lifecycleCallbacks))
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
        val flushRuntime = installRuntime(
            newRuntime.copy(
                bootstrapCompletionJob = startupHandles.bootstrapCompletionJob,
                purchaseUpdatesJob = startupHandles.purchaseUpdatesJob,
            )
        )
        flushRuntime.scope.launch {
            // After bootstrap, whose profile-context phase flushes the current user: run first,
            // this would hold that user's flush lock and hold the startup chain back.
            flushRuntime.bootstrapCompletionJob?.join()
            // A failure here (an invalid API key's 401, say) used to crash the app.
            flushAttributesBestEffort("after configure") {
                flushRuntime.attributesManager.flushPendingForAllUsers()
            }
        }
    }

    /**
     * Makes [newRuntime] current, with the listeners as they are now. Under the lock the listener
     * setters take, so a listener set while configure() builds the runtime is not lost.
     */
    private fun installRuntime(newRuntime: AppActorRuntimeState): AppActorRuntimeState =
        synchronized(this) { installRuntimeLocked(newRuntime) }

    private fun installRuntimeLocked(newRuntime: AppActorRuntimeState): AppActorRuntimeState {
        val callbacks = preconfiguredCallbacks
        val sessionId = newRuntime.sessionId
        newRuntime.paymentProcessor.onDeferredPurchaseResolved = callbacks.onDeferredPurchaseResolved?.let { callback ->
            { productId, customerInfo ->
                deliverOnMainIfCurrent(sessionId) {
                    callback(productId, customerInfo)
                }
            }
        }
        return newRuntime.copy(
            onCustomerInfoChanged = callbacks.onCustomerInfoChanged,
            onReceiptPipelineEvent = callbacks.onReceiptPipelineEvent,
            onDeferredPurchaseResolved = callbacks.onDeferredPurchaseResolved,
        ).also { runtime = it }
    }

    /**
     * Flushes the outgoing user's attribute writes before an identity change. As on iOS, they
     * must not block the switch, nor must a flush of them that is already running.
     */
    private suspend fun flushOutgoingUserAttributes(
        currentRuntime: AppActorRuntimeState,
        appUserId: String,
    ) {
        flushAttributesBestEffort("before the identity change") {
            currentRuntime.attributesManager.flushPending(appUserId, waitForRunningFlush = false)
        }
    }

    /** Runs an attribute flush whose failure only leaves the writes queued for a later flush. */
    private suspend fun flushAttributesBestEffort(
        occasion: String,
        flush: suspend () -> Unit,
    ) {
        try {
            flush()
        } catch (throwable: Throwable) {
            throwIfCancellation(throwable)
            AppActorLogger.warn("Attribute flush $occasion failed; the writes stay queued: ${throwable.message}")
        }
    }

    private suspend fun setReservedString(
        key: String,
        value: String?,
    ) {
        executeWrite { snapshot ->
            snapshot.runtime.attributesManager.setReservedString(
                appUserId = snapshot.appUserId,
                key = key,
                value = value,
            )
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

    private fun scheduleAutomaticProfileContextSyncAfterIdentityTransition(
        runtimeState: AppActorRuntimeState,
        appUserId: String,
    ) {
        runtimeState.scope.launch {
            val shouldSync = synchronized(this@AppActor) {
                runtime?.sessionId == runtimeState.sessionId &&
                    runtimeState.identityStore.currentAppUserId == appUserId
            }
            if (!shouldSync) return@launch

            try {
                runtimeState.attributesManager.collectAutomaticProfileContext(appUserId)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    AppActorLogger.debug("Automatic profile context sync was cancelled after identity transition.")
                    return@launch
                }
                AppActorLogger.warn(
                    "Automatic profile context sync failed after identity transition; continuing without blocking identity transition."
                )
            }
        }
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
            // A 304 refresh brings the same info with only a new requestId. As on iOS that is no
            // change: the flow keeps its value, and the callback, once it has had this info,
            // stays quiet; a listener that re-fetches would otherwise loop.
            val unchanged = latestRuntime.lastCustomerInfo.isSameInfoAs(info)
            val alreadyNotified = latestRuntime.notifiedCustomerInfo?.isSameInfoAs(info) == true
            val updatedRuntime = latestRuntime.copy(
                lastCustomerInfo = if (unchanged) latestRuntime.lastCustomerInfo else info,
                lastCustomerInfoSource = source ?: latestRuntime.lastCustomerInfoSource,
                notifiedCustomerInfo = info,
            )
            runtime = updatedRuntime
            if (!unchanged) {
                customerInfoStateFlow.value = info
            }
            updatedRuntime.onCustomerInfoChanged.takeUnless { alreadyNotified }
        }
    }

    private fun AppActorCustomerInfo.isSameInfoAs(other: AppActorCustomerInfo): Boolean =
        copy(requestId = other.requestId) == other

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
        deliverOnMainIfCurrent(runtimeSessionId) {
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
                throwIfCancellation(throwable)
                throw throwable.toPublicAppActorError()
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

    // A write goes to the user current when it starts. Unlike a read it is never re-run after an
    // identity change, which would copy that user's data onto the next one.
    private suspend fun <T> executeWrite(operation: suspend (AppActorOperationSnapshot) -> T): T =
        operation(captureOperationSnapshot(resolveAppUserId = true))

    /**
     * Runs [block], turning what it throws into a public [AppActorError], as the Bridge and the
     * plugin already do; the SDK's internal exception types never reach the app.
     */
    private inline fun <T> throwingPublicErrors(defaultMessage: String, block: () -> T): T {
        return try {
            block()
        } catch (throwable: Throwable) {
            throwIfCancellation(throwable)
            throw throwable.toPublicAppActorError(defaultMessage)
        }
    }

    private suspend fun isSnapshotCurrent(snapshot: AppActorOperationSnapshot): Boolean {
        return transitionMutex.withLock { runtimeIfCurrentLocked(snapshot) != null }
    }

    private suspend fun persistCustomerInfoIfCurrent(
        snapshot: AppActorOperationSnapshot,
        info: AppActorCustomerInfo,
    ): Boolean {
        return transitionMutex.withLock {
            val currentRuntime = runtimeIfCurrentLocked(snapshot)?.takeIf { it.ownsCustomerInfo(info) }
                ?: return@withLock false
            persistCustomerInfoLocked(currentRuntime, info)
            true
        }
    }

    // Customer info never moves the identity: logIn, identify and canonical-id adoption set it
    // before their info arrives here.
    private fun persistCustomerInfoLocked(
        currentRuntime: AppActorRuntimeState,
        info: AppActorCustomerInfo,
    ) {
        currentRuntime.identityStore.setLastRequestId(info.requestId)
    }

    private fun runtimeIfCurrentLocked(snapshot: AppActorOperationSnapshot): AppActorRuntimeState? =
        runtime?.takeIf { it.sessionId == snapshot.runtime.sessionId && identityEpoch == snapshot.epoch }

    // A drain or sync also finishes purchases other users left queued; their customer info must
    // not reach this user's session.
    private fun AppActorRuntimeState.ownsCustomerInfo(info: AppActorCustomerInfo): Boolean {
        val infoAppUserId = info.appUserId?.takeIf { it.isNotBlank() } ?: return true
        return infoAppUserId == identityStore.currentAppUserId
    }

    private suspend fun publishCustomerInfoIfCurrent(
        snapshot: AppActorOperationSnapshot,
        info: AppActorCustomerInfo,
        source: AppActorDiagnosticsDataSource? = null,
        guard: (AppActorRuntimeState) -> Boolean = { true },
    ): Boolean {
        val callback = transitionMutex.withLock {
            val currentRuntime = runtimeIfCurrentLocked(snapshot)?.takeIf { it.ownsCustomerInfo(info) }
                ?: return@withLock null
            if (!guard(currentRuntime)) {
                return@withLock null
            }
            publishCustomerInfoLocked(currentRuntime, info, source)
        }
        deliverOnMainIfCurrent(snapshot.runtime.sessionId, snapshot.epoch) {
            callback?.invoke(info)
        }
        return callback != null
    }

    private suspend fun publishPurchaseUpdateIfCurrent(
        runtimeState: AppActorRuntimeState,
        result: AppActorPurchaseUpdateProcessingResult,
        source: AppActorDiagnosticsDataSource = AppActorDiagnosticsDataSource.Network,
    ): Boolean {
        val callbackAndInfo = transitionMutex.withLock {
            val currentRuntime = runtime ?: return@withLock null
            if (currentRuntime.sessionId != runtimeState.sessionId) {
                return@withLock null
            }
            purchaseUpdatePublishCallbackIfCurrentLocked(currentRuntime, result, source)
        } ?: return false
        val (callback, info, epoch) = callbackAndInfo
        deliverOnMainIfCurrent(runtimeState.sessionId, epoch) {
            callback?.invoke(info)
        }
        return callback != null
    }

    /**
     * Publishes what a background drain (the foreground or retry-wake one) got back for the current
     * user. It seeds the customer cache, so the next refresh would wait for that to go stale.
     */
    private suspend fun publishDrainedCustomerInfo(
        runtimeState: AppActorRuntimeState,
        info: AppActorCustomerInfo?,
    ) {
        val appUserId = info?.appUserId ?: return
        publishPurchaseUpdateIfCurrent(
            runtimeState = runtimeState,
            result = AppActorPurchaseUpdateProcessingResult(customerInfo = info, appUserId = appUserId),
        )
    }

    private fun purchaseUpdatePublishCallbackIfCurrentLocked(
        currentRuntime: AppActorRuntimeState,
        result: AppActorPurchaseUpdateProcessingResult,
        source: AppActorDiagnosticsDataSource = AppActorDiagnosticsDataSource.Network,
    ): Triple<((AppActorCustomerInfo) -> Unit)?, AppActorCustomerInfo, Long>? {
        val info = result.customerInfo ?: return null
        val currentAppUserId = currentRuntime.identityStore.currentAppUserId
            ?: return null
        val infoAppUserId = info.appUserId?.takeIf { it.isNotBlank() }
        if (currentAppUserId != result.appUserId || (infoAppUserId != null && infoAppUserId != result.appUserId)) {
            return null
        }
        persistCustomerInfoLocked(currentRuntime, info)
        return Triple(publishCustomerInfoLocked(currentRuntime, info, source), info, identityEpoch)
    }

    private suspend fun persistLastRequestIdIfCurrent(
        snapshot: AppActorOperationSnapshot,
        requestId: String?,
    ): Boolean {
        return transitionMutex.withLock {
            val currentRuntime = runtimeIfCurrentLocked(snapshot) ?: return@withLock false
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
            val currentRuntime = runtimeIfCurrentLocked(snapshot) ?: return@withLock
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

    internal fun deliverOnMainIfCurrent(
        runtimeSessionId: Long,
        expectedEpoch: Long? = null,
        block: () -> Unit,
    ) {
        deliverOnMain {
            val isCurrent = synchronized(this) {
                val currentRuntime = runtime ?: return@synchronized false
                currentRuntime.sessionId == runtimeSessionId &&
                    (expectedEpoch == null || identityEpoch == expectedEpoch)
            }
            if (!isCurrent) {
                return@deliverOnMain
            }
            block()
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
        val formattedMessage = synchronized(this) {
            val currentRuntime = runtime ?: return
            if (currentRuntime.sessionId != runtimeSessionId) {
                return
            }
            buildDebugEventMessage(
                name = name,
                message = message,
                requestId = requestId,
                attributes = attributes,
            )
        }
        AppActorLogger.log(
            level = level,
            category = category.name,
            message = formattedMessage,
        )
    }

    private fun buildDebugEventMessage(
        name: String,
        message: String,
        requestId: String?,
        attributes: Map<String, String>,
    ): String {
        return buildString {
            append(name)
            append(": ")
            append(message)
            requestId?.takeIf { it.isNotBlank() }?.let {
                append(" requestId=")
                append(it)
            }
            if (attributes.isNotEmpty()) {
                append(" attributes=")
                append(
                    attributes.entries.joinToString(",") { (key, value) ->
                        "$key=${redactDebugAttribute(key, value)}"
                    }
                )
            }
        }
    }

    private fun redactDebugAttribute(key: String, value: String): String {
        return if (key.contains("user_id", ignoreCase = true)) {
            "<redacted>"
        } else {
            value
        }
    }

    private fun platformDebugAttributes(platformInfo: AppActorPlatformInfo?): Map<String, String> {
        return debugAttributes(
            "platform_flavor" to platformInfo?.flavor,
            "platform_version" to platformInfo?.version,
        )
    }
}

private fun currentAppVersion(context: Context): String? {
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()
}

// Experiments answer 400 to anything but an alpha-2 code, so a region like "419" is left out.
private fun currentCountryCode(): String? {
    return runCatching { normalizeAlpha2Country(java.util.Locale.getDefault().country) }.getOrNull()
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
            requestId = requestId,
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

        is IllegalArgumentException -> AppActorError.InvalidConfiguration(message ?: defaultMessage)
        else -> AppActorError.Unknown(message ?: defaultMessage, this)
    }
}
