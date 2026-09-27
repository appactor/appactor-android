package com.appactor.android.managers

import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.client.toAppActorError
import com.appactor.android.backend.dto.AppActorOfferingDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.backend.dto.AppActorPackageDTO
import com.appactor.android.backend.dto.AppActorProductReferenceDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStoreProduct
import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.cache.AppActorCachedValue
import com.appactor.android.cache.AppActorOfflineProductCatalog
import com.appactor.android.cache.AppActorOfflineProductCatalogStore
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.internal.runtime.appActorBackgroundScope
import com.appactor.android.internal.runtime.forSharedAwaiters
import com.appactor.android.internal.runtime.launchSharedRequest
import com.appactor.android.internal.runtime.throwIfCancellation
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorOfferingsFetchPolicy
import com.appactor.android.models.AppActorVerificationResult
import com.appactor.android.models.AppActorMetadata
import com.appactor.android.models.AppActorOffering
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPackageType
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.appActorStoreLookupProductId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

internal class AppActorOfferingsManager(
    private val backendClient: AppActorBackendClient,
    private val cacheStore: AppActorOfferingsCacheStore,
    private val offlineProductCatalogStore: AppActorOfflineProductCatalogStore,
    private val storeAdapter: AppActorStoreAdapter,
    private val backgroundScope: CoroutineScope = appActorBackgroundScope(),
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val stateMutex = Mutex()
    @Volatile
    private var cachedOfferings: AppActorOfferings? = null
    private var cachedAtMillis: Long? = null
    // The ETag of the entry cachedOfferings was built from; null for the bundled fallback.
    private var cachedETag: String? = null
    private var cachedLocales: List<String> = emptyList()
    @Volatile
    private var isBackground: Boolean = false
    private var inFlight: CompletableDeferred<AppActorOfferings>? = null
    private var cacheGeneration: Long = 0
    @Volatile
    private var lastLoadSource: AppActorDiagnosticsDataSource? = null
    @Volatile
    private var fallbackDTO: AppActorOfferingsEnvelopeDTO? = null

    suspend fun getOfferings(
        fetchPolicy: AppActorOfferingsFetchPolicy = AppActorOfferingsFetchPolicy.FreshIfStale,
    ): AppActorOfferings {
        // Phase 1: Check state under lock, capture what to do, release lock immediately
        val action = stateMutex.withLock {
            // Fresh cache → return immediately
            if (isMemoryCacheFreshLocked()) {
                lastLoadSource = AppActorDiagnosticsDataSource.Cache
                return requireNotNull(cachedOfferings)
            }

            when (fetchPolicy) {
                AppActorOfferingsFetchPolicy.ReturnCachedThenRefresh -> {
                    suitableInMemoryCacheLocked()?.let { stale ->
                        lastLoadSource = AppActorDiagnosticsDataSource.Cache
                        if (inFlight == null) {
                            val request = CompletableDeferred<AppActorOfferings>()
                            inFlight = request
                            launchBackgroundRefresh(request, cacheGeneration)
                        }
                        return stale
                    }

                    inFlight?.let { existing ->
                        return@withLock OfferingsAction.Await(existing)
                    }

                    loadCachedPayloadForImmediateReturnLocked()
                        ?.let { cachedValue ->
                            // inFlight is null here: an existing one returned Await just above.
                            return@withLock OfferingsAction.ReturnCachedPayload(
                                cachedValue = cachedValue,
                                generation = cacheGeneration,
                                refreshRequest = CompletableDeferred<AppActorOfferings>().also { inFlight = it },
                            )
                        }
                    }

                AppActorOfferingsFetchPolicy.CacheOnly -> {
                    suitableInMemoryCacheLocked()?.let { cached ->
                        lastLoadSource = AppActorDiagnosticsDataSource.Cache
                        return cached
                    }

                    loadCachedPayloadForImmediateReturnLocked()
                        ?.let { cachedValue ->
                            return@withLock OfferingsAction.ReturnCachedPayload(
                                cachedValue = cachedValue,
                                generation = cacheGeneration,
                                refreshRequest = null,
                            )
                        }

                    throw AppActorError.InvalidConfiguration("Offerings cache miss.")
                }

                AppActorOfferingsFetchPolicy.FreshIfStale -> Unit
            }

            // Cold cache — join or start the fetch, awaited outside the lock
            OfferingsAction.Await(inFlight ?: startFetchLocked(forceRefresh = false))
        }

        // Phase 2: Execute action WITHOUT holding the lock
        return when (action) {
            is OfferingsAction.Await -> action.deferred.await()
            is OfferingsAction.ReturnCachedPayload -> try {
                decodeAndEnrich(action.cachedValue, action.generation)
            } finally {
                // The refresh is the only code that completes and clears the inFlight request
                // planted in phase 1, so it must start even when enrichment throws or the caller
                // is cancelled; otherwise every later offerings call awaits that request forever.
                action.refreshRequest?.let { launchBackgroundRefresh(it, action.generation) }
            }
        }
    }

    suspend fun getOfferings(forceRefresh: Boolean): AppActorOfferings {
        if (forceRefresh) {
            return executeFetchOrAwait(forceRefresh = true)
        }
        return getOfferings(fetchPolicy = AppActorOfferingsFetchPolicy.ReturnCachedThenRefresh)
    }

    suspend fun prefetchForBootstrap(): AppActorDiagnosticsDataSource? {
        val action = stateMutex.withLock {
            if (isMemoryCacheFreshLocked() && cachedOfferings?.all?.isNotEmpty() == true) {
                return@withLock BootstrapPrefetchAction.Skip(
                    lastLoadSource ?: AppActorDiagnosticsDataSource.Cache
                )
            }

            inFlight?.let { existing ->
                return@withLock BootstrapPrefetchAction.Await(existing, lastLoadSource)
            }

            val request = CompletableDeferred<AppActorOfferings>()
            inFlight = request
            BootstrapPrefetchAction.Execute(request, cacheGeneration)
        }

        return when (action) {
            is BootstrapPrefetchAction.Skip -> action.source
            is BootstrapPrefetchAction.Await -> {
                runCatching { action.deferred.await() }
                action.source ?: lastLoadSource
            }

            is BootstrapPrefetchAction.Execute -> {
                try {
                    val seed = fetchBootstrapSeed()
                    stateMutex.withLock {
                        if (cacheGeneration == action.generation) {
                            offlineProductCatalogStore.save(seed.dto.toOfflineProductCatalog())
                            lastLoadSource = seed.source
                        }
                    }
                    launchBootstrapEnrichment(
                        request = action.request,
                        seed = seed,
                        generation = action.generation,
                    )
                    seed.source
                } catch (throwable: Throwable) {
                    // Cleared first, so a call made once the request fails starts its own fetch.
                    withContext(NonCancellable) { clearInFlight(action.request) }
                    action.request.completeExceptionally(throwable.forSharedAwaiters())
                    throwIfCancellation(throwable)
                    AppActorLogger.debug("Bootstrap offerings prefetch failed: ${throwable.message}")
                    null
                }
            }
        }
    }

    private suspend fun executeFetchOrAwait(forceRefresh: Boolean): AppActorOfferings =
        stateMutex.withLock { inFlight ?: startFetchLocked(forceRefresh) }.await()

    /** Starts a fetch every caller shares, as the inFlight request. Under stateMutex. */
    private fun startFetchLocked(forceRefresh: Boolean): CompletableDeferred<AppActorOfferings> {
        val generation = cacheGeneration
        return CompletableDeferred<AppActorOfferings>().also { request ->
            inFlight = request
            launchRequest(request) { fetchOfferings(forceRefresh, generation) }
        }
    }

    private fun loadCachedPayloadForImmediateReturnLocked(): AppActorCachedValue? {
        val cachedValue = cacheStore.loadLocaleCompatible(currentLocales()) ?: return null
        if (cachedValue.cachedAtMillis <= 0L) return null
        return cachedValue
    }

    fun cached(): AppActorOfferings? = cachedOfferings

    fun lastLoadSource(): AppActorDiagnosticsDataSource? = lastLoadSource

    fun setBackground(isBackground: Boolean) {
        this.isBackground = isBackground
    }

    suspend fun clearCache() {
        stateMutex.withLock {
            cacheGeneration += 1
            inFlight?.cancel()
            inFlight = null
            cachedOfferings = null
            cachedAtMillis = null
            cachedETag = null
            cachedLocales = emptyList()
            lastLoadSource = null
        }
        cacheStore.clear()
        offlineProductCatalogStore.clear()
    }

    fun setFallbackOfferings(dto: AppActorOfferingsEnvelopeDTO) {
        this.fallbackDTO = dto
    }

    fun currentProductEntitlements(): Map<String, List<String>> {
        cachedOfferings?.productEntitlements?.takeIf { it.isNotEmpty() }?.let { return it }
        cachedPayloadOfflineProductCatalog()
            ?.productEntitlements
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        offlineProductCatalogStore.load()
            ?.productEntitlements
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return emptyMap()
    }

    fun currentOneTimeProductType(productId: String): AppActorProductType? {
        if (productId.isBlank()) return null
        cachedOfferings
            ?.toOfflineProductCatalog()
            ?.oneTimeProductType(productId)
            ?.let { return it }
        cachedPayloadOfflineProductCatalog()
            ?.oneTimeProductType(productId)
            ?.let { return it }
        offlineProductCatalogStore.load()
            ?.oneTimeProductType(productId)
            ?.let { return it }
        return null
    }

    private fun cachedPayloadOfflineProductCatalog(): AppActorOfflineProductCatalog? {
        val payload = cacheStore.load()?.payload ?: return null
        return runCatching {
            AppActorBackendJson.instance
                .decodeFromString<AppActorOfferingsEnvelopeDTO>(payload)
                .toOfflineProductCatalog()
        }.getOrNull()
    }

    // MARK: - Internal

    private suspend fun fetchOfferings(forceRefresh: Boolean, generation: Long): AppActorOfferings {
        val requestLocales = currentLocales()
        return try {
            val sentETag = cacheStore.eTag(forceRefresh = forceRefresh, currentLocales = requestLocales)
            val response = backendClient.getOfferings(eTag = sentETag)
            when {
                response.isNotModified -> {
                    val cachedValue = cacheStore.handleNotModified(
                        rotatedETag = response.eTag,
                        currentLocales = requestLocales,
                    ) ?: cacheStore.loadLocaleCompatible(requestLocales)
                    memoryConfirmedBy(sentETag, cachedValue, requestLocales, generation)
                        ?: offeringsWithoutPayload(cachedValue, generation) {
                            IllegalStateException("Offerings cache missing for 304 response with no fallback.")
                        }
                }

                else -> {
                    val body = requireNotNull(response.body) {
                        "Offerings response body was null."
                    }
                    val payload = AppActorBackendJson.instance.encodeToString(body)
                    cacheStore.save(
                        payload = payload,
                        eTag = response.eTag,
                        verified = response.signatureVerified,
                        preferredLocales = requestLocales,
                    )
                    val verification = AppActorVerificationResult.from(response.signatureVerified)
                    decodeAndEnrich(payload, dateProviderMillis(), generation, AppActorDiagnosticsDataSource.Network, verification, response.eTag)
                }
            }
        } catch (throwable: Throwable) {
            throwIfCancellation(throwable)
            if (forceRefresh || !shouldFallbackToCache(throwable)) {
                throw throwable.toAppActorError("Failed to fetch offerings.")
            }
            offeringsWithoutPayload(cacheStore.loadLocaleCompatible(requestLocales), generation) {
                throwable.toAppActorError("Failed to fetch offerings.")
            }
        }
    }

    /**
     * Offerings when the backend sent no payload to use: [cachedValue], else the offerings in memory
     * (though built from an older entry), else the bundled fallback, else [noOfferings].
     */
    private suspend fun offeringsWithoutPayload(
        cachedValue: AppActorCachedValue?,
        generation: Long,
        noOfferings: () -> Throwable,
    ): AppActorOfferings {
        val decoded = cachedValue?.let {
            try {
                decodeAndEnrich(it, generation)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
        }
        if (decoded != null) return decoded
        stateMutex.withLock {
            suitableInMemoryCacheLocked()?.let { memory ->
                lastLoadSource = AppActorDiagnosticsDataSource.Cache
                return memory
            }
        }
        // Use 0L (epoch) so the cache is immediately stale — next call triggers SWR refresh
        val fallback = fallbackDTO ?: throw noOfferings()
        return enrichAndCache(fallback, 0L, generation, AppActorDiagnosticsDataSource.Cache)
    }

    private suspend fun fetchBootstrapSeed(): BootstrapSeed {
        val requestLocales = currentLocales()
        return try {
            val response = backendClient.getOfferings(
                eTag = cacheStore.eTag(forceRefresh = false, currentLocales = requestLocales),
            )
            when {
                response.isNotModified -> {
                    val cachedValue = cacheStore.handleNotModified(
                        rotatedETag = response.eTag,
                        currentLocales = requestLocales,
                    ) ?: cacheStore.loadLocaleCompatible(requestLocales)
                    val fallback = fallbackDTO
                    when {
                        cachedValue != null -> decodeBootstrapSeed(cachedValue)

                        fallback != null -> fallbackBootstrapSeed(fallback)

                        else -> throw IllegalStateException(
                            "Offerings cache missing for 304 response with no fallback."
                        )
                    }
                }

                else -> {
                    val body = requireNotNull(response.body) {
                        "Offerings response body was null."
                    }
                    val payload = AppActorBackendJson.instance.encodeToString(body)
                    cacheStore.save(
                        payload = payload,
                        eTag = response.eTag,
                        verified = response.signatureVerified,
                        preferredLocales = requestLocales,
                    )
                    BootstrapSeed(
                        dto = body,
                        cachedAtMillis = dateProviderMillis(),
                        source = AppActorDiagnosticsDataSource.Network,
                        verification = AppActorVerificationResult.from(response.signatureVerified),
                        eTag = response.eTag,
                    )
                }
            }
        } catch (throwable: Throwable) {
            throwIfCancellation(throwable)
            if (!shouldFallbackToCache(throwable)) {
                throw throwable.toAppActorError("Failed to fetch offerings.")
            }
            val cachedValue = cacheStore.loadLocaleCompatible(requestLocales)
            when {
                cachedValue != null -> decodeBootstrapSeed(cachedValue)

                else -> fallbackDTO?.let(::fallbackBootstrapSeed)
                    ?: throw throwable.toAppActorError("Failed to fetch offerings.")
            }
        }
    }

    private fun isMemoryCacheFreshLocked(): Boolean {
        val cachedAt = cachedAtMillis ?: return false
        val ttl = if (isBackground) BACKGROUND_TTL_MILLIS else FOREGROUND_TTL_MILLIS
        if (dateProviderMillis() - cachedAt >= ttl) return false
        return cachedLocales == currentLocales()
    }

    private fun suitableInMemoryCacheLocked(): AppActorOfferings? {
        return cachedOfferings?.takeIf { cachedLocales == currentLocales() }
    }

    private fun currentLocales(): List<String> =
        listOf(Locale.getDefault().toLanguageTag())

    private fun launchBackgroundRefresh(
        request: CompletableDeferred<AppActorOfferings>,
        generation: Long,
    ) {
        launchRequest(request) { fetchOfferings(false, generation) }
    }

    private fun launchBootstrapEnrichment(
        request: CompletableDeferred<AppActorOfferings>,
        seed: BootstrapSeed,
        generation: Long,
    ) {
        launchRequest(request) {
            enrichAndCache(
                dto = seed.dto,
                cachedAtMillis = seed.cachedAtMillis,
                generation = generation,
                source = seed.source,
                verification = seed.verification,
                eTag = seed.eTag,
            )
        }
    }

    /** Runs [block] in the background for every caller of [request], clearing it from inFlight first. */
    private fun launchRequest(
        request: CompletableDeferred<AppActorOfferings>,
        block: suspend () -> AppActorOfferings,
    ) {
        backgroundScope.launchSharedRequest(
            request = request,
            cleanup = { clearInFlight(request) },
            block = block,
        )
    }

    private suspend fun clearInFlight(request: CompletableDeferred<AppActorOfferings>) {
        stateMutex.withLock {
            if (inFlight === request) inFlight = null
        }
    }

    private fun decodeBootstrapSeed(cachedValue: AppActorCachedValue): BootstrapSeed {
        val dto = AppActorBackendJson.instance.decodeFromString<AppActorOfferingsEnvelopeDTO>(cachedValue.payload)
        return BootstrapSeed(
            dto = dto,
            cachedAtMillis = cachedValue.cachedAtMillis,
            source = AppActorDiagnosticsDataSource.Cache,
            verification = cachedValue.verification,
            eTag = cachedValue.eTag,
        )
    }

    // Stamped 0L (epoch) like the fallback on the fetch path: stale at once, so the next call
    // goes to the backend instead of serving the bundled offerings for a whole TTL.
    private fun fallbackBootstrapSeed(dto: AppActorOfferingsEnvelopeDTO): BootstrapSeed =
        BootstrapSeed(dto = dto, cachedAtMillis = 0L, source = AppActorDiagnosticsDataSource.Cache)

    /**
     * The offerings in memory, with their freshness restarted, when a 304 for [sentETag] confirms
     * the entry they were built from. Null otherwise: a 200 whose enrichment failed leaves memory
     * on the previous payload while disk already holds the new one and sends its ETag.
     */
    private suspend fun memoryConfirmedBy(
        sentETag: String?,
        confirmed: AppActorCachedValue?,
        requestLocales: List<String>,
        generation: Long,
    ): AppActorOfferings? = stateMutex.withLock {
        val offerings = cachedOfferings
        // The locales too: the backend isn't sent them, so another locale's entry can share the ETag.
        if (offerings == null || sentETag == null || cachedETag != sentETag ||
            cachedLocales != requestLocales || cacheGeneration != generation
        ) {
            return@withLock null
        }
        cachedAtMillis = dateProviderMillis()
        // The entry's ETag as the 304 left it, which the next request sends.
        cachedETag = confirmed?.eTag ?: sentETag
        lastLoadSource = AppActorDiagnosticsDataSource.Cache
        offerings
    }

    private suspend fun decodeAndEnrich(cachedValue: AppActorCachedValue, generation: Long): AppActorOfferings =
        decodeAndEnrich(
            payload = cachedValue.payload,
            cachedAtMillis = cachedValue.cachedAtMillis,
            generation = generation,
            source = AppActorDiagnosticsDataSource.Cache,
            verification = cachedValue.verification,
            eTag = cachedValue.eTag,
        )

    private suspend fun decodeAndEnrich(
        payload: String,
        cachedAtMillis: Long,
        generation: Long,
        source: AppActorDiagnosticsDataSource,
        verification: AppActorVerificationResult,
        eTag: String?,
    ): AppActorOfferings {
        val dto = AppActorBackendJson.instance.decodeFromString<AppActorOfferingsEnvelopeDTO>(payload)
        return enrichAndCache(dto, cachedAtMillis, generation, source, verification, eTag)
    }

    private suspend fun enrichAndCache(
        dto: AppActorOfferingsEnvelopeDTO,
        cachedAtMillis: Long,
        generation: Long,
        source: AppActorDiagnosticsDataSource,
        verification: AppActorVerificationResult = AppActorVerificationResult.NotRequested,
        eTag: String? = null,
    ): AppActorOfferings {
        val offerings = enrich(dto).copy(verification = verification)
        stateMutex.withLock {
            if (cacheGeneration == generation) {
                offlineProductCatalogStore.save(offerings.toOfflineProductCatalog())
                cachedOfferings = offerings
                this.cachedAtMillis = cachedAtMillis
                cachedETag = eTag
                cachedLocales = currentLocales()
                lastLoadSource = source
            }
        }
        return offerings
    }

    private suspend fun enrich(dto: AppActorOfferingsEnvelopeDTO): AppActorOfferings {
        val sourceOfferings = linkedMapOf<String, AppActorOfferingDTO>().apply {
            dto.data.offerings.forEach { offering -> put(offering.id, offering) }
            dto.data.currentOffering?.let { offering -> put(offering.id, offering) }
        }.values.toList()

        val productRequests = sourceOfferings
            .flatMap { offering ->
                offering.packages.flatMap { packageDTO ->
                    packageDTO.playStoreProducts().map { product ->
                        product.toStoreRequest(packageDTO.packageType)
                    }
                }
            }
            .distinctBy { it.cacheKey() }

        // Key each product by the request it was resolved for (see sourceRequest), so every
        // package shows what its own request resolved to.
        val resolvedProducts = storeAdapter.queryProductDetails(productRequests)
            .associateBy { product -> product.sourceRequest?.cacheKey() ?: product.cacheKey() }
        val droppedPackageRefs = linkedSetOf<String>()

        val offeringPairs = sourceOfferings.mapNotNull { offeringDTO ->
            val packages = offeringDTO.packages.mapNotNull { packageDTO ->
                packageDTO.toEnrichedPackage(resolvedProducts, offeringId = offeringDTO.id) ?: run {
                    val playRefs = packageDTO.playStoreProducts()
                    if (playRefs.isNotEmpty()) {
                        val packageRefs = playRefs.map { product -> product.logDescriptor(packageDTO.packageType) }
                        droppedPackageRefs += packageRefs
                        AppActorLogger.warn(
                            "[Offerings] Dropped package id=${packageDTO.id} offeringId=${offeringDTO.id} " +
                                "because no Play product matched refs=${packageRefs.joinToString("; ")}"
                        )
                    }
                    null
                }
            }
            if (packages.isEmpty()) {
                if (offeringDTO.packages.any { packageDTO -> packageDTO.playStoreProducts().isNotEmpty() }) {
                    AppActorLogger.warn(
                        "[Offerings] Dropped offering id=${offeringDTO.id} because all Play-backed packages were unresolved."
                    )
                }
                null
            } else {
                AppActorOffering(
                    id = offeringDTO.id,
                    displayName = offeringDTO.displayName ?: offeringDTO.id,
                    isCurrent = offeringDTO.isCurrent,
                    lookupKey = offeringDTO.lookupKey,
                    metadata = offeringDTO.metadata.toMetadata(),
                    packages = packages,
                )
            }
        }

        val allOfferings = linkedMapOf<String, AppActorOffering>().apply {
            offeringPairs.forEach { offering -> put(offering.id, offering) }
        }
        val currentOfferingId = dto.data.currentOffering?.id
        val currentOffering = currentOfferingId?.let(allOfferings::get)
            ?: allOfferings.values.firstOrNull { it.isCurrent }

        if (allOfferings.isEmpty() && productRequests.isNotEmpty()) {
            val unresolvedSummary = droppedPackageRefs.ifEmpty {
                productRequests.map { request -> request.logDescriptor() }.toSet()
            }.joinToString("; ")
            AppActorLogger.error(
                "[Offerings] All Play-backed offerings were dropped during enrichment. unresolved=$unresolvedSummary"
            )
            throw AppActorError.StoreProductsMissing(
                "Failed to resolve Play product details for $unresolvedSummary"
            )
        }

        if (currentOfferingId != null && currentOffering == null && allOfferings.isNotEmpty()) {
            AppActorLogger.warn(
                "[Offerings] Current offering id=$currentOfferingId was dropped during enrichment. " +
                    "Remaining offerings=${allOfferings.keys.joinToString(",")}"
            )
        }

        return AppActorOfferings(
            current = currentOffering,
            all = allOfferings,
            productEntitlements = dto.data.productEntitlements,
        )
    }

    private fun shouldFallbackToCache(throwable: Throwable): Boolean {
        return when (throwable) {
            is AppActorBackendException.Network -> true
            // A 304 the client refused (CACHE_INCONSISTENCY) falls back like iOS does.
            is AppActorBackendException.Http -> throwable.statusCode >= 500 || throwable.statusCode == 304
            else -> false
        }
    }

    private fun AppActorPackageDTO.toEnrichedPackage(
        resolvedProducts: Map<String, AppActorStoreProduct>,
        offeringId: String,
    ): AppActorPackage? {
        val selected = products
            .filter { AppActorStore.fromWireValue(it.store) == AppActorStore.PlayStore }
            .mapNotNull { product ->
                val request = product.toStoreRequest(packageType)
                resolvedProducts[request.cacheKey()]?.let { resolved ->
                    product to resolved
                }
            }
            .firstOrNull()
            ?: return null

        val productRef = selected.first
        val resolved = selected.second
        val resolvedPackageType = AppActorPackageType.fromServerValue(packageType)
        val customTypeIdentifier = if (resolvedPackageType == AppActorPackageType.Custom) packageType else null

        return AppActorPackage(
            id = id,
            packageType = resolvedPackageType,
            customTypeIdentifier = customTypeIdentifier,
            store = AppActorStore.PlayStore,
            productId = productRef.productId,
            storeProductId = productRef.storeLookupProductId(),
            productType = resolved.productType,
            basePlanId = resolved.basePlanId,
            offerId = resolved.offerId,
            localizedPriceString = resolved.localizedPrice,
            price = resolved.priceAmountMicros?.div(1_000_000.0),
            currencyCode = resolved.currencyCode,
            displayName = displayName,
            productName = resolved.displayName ?: productRef.displayName,
            productDescription = resolved.description,
            metadata = metadata.toMetadata(),
            tokenAmount = tokenAmount,
            position = position,
            offeringId = offeringId,
            pricingPhases = resolved.pricingPhases,
        )
    }

    private fun AppActorStoreProduct.cacheKey(): String {
        return listOf(productType.name, productId, basePlanId.orEmpty(), offerId.orEmpty()).joinToString("|")
    }

    private fun AppActorStoreProductRequest.cacheKey(): String {
        return listOf(productType.name, productId, basePlanId.orEmpty(), offerId.orEmpty()).joinToString("|")
    }

    private fun AppActorProductReferenceDTO.cacheKey(): String {
        val resolvedType = resolvedProductType(packageType = null)
        return listOf(resolvedType.name, storeLookupProductId(), basePlanId.orEmpty(), offerId.orEmpty()).joinToString("|")
    }

    private fun AppActorPackageDTO.playStoreProducts(): List<AppActorProductReferenceDTO> {
        return products.filter { AppActorStore.fromWireValue(it.store) == AppActorStore.PlayStore }
    }

    private fun AppActorProductReferenceDTO.toStoreRequest(
        packageType: String?,
    ): AppActorStoreProductRequest {
        return AppActorStoreProductRequest(
            productId = storeLookupProductId(),
            productType = resolvedProductType(packageType),
            basePlanId = basePlanId,
            offerId = offerId,
        )
    }

    private fun AppActorProductReferenceDTO.resolvedProductType(
        packageType: String?,
    ): AppActorProductType {
        val packageKind = AppActorPackageType.fromServerValue(packageType)
        return when {
            !basePlanId.isNullOrBlank() || !offerId.isNullOrBlank() -> AppActorProductType.Subscription
            packageKind == AppActorPackageType.Weekly ||
                packageKind == AppActorPackageType.Monthly ||
                packageKind == AppActorPackageType.TwoMonth ||
                packageKind == AppActorPackageType.ThreeMonth ||
                packageKind == AppActorPackageType.SixMonth ||
                packageKind == AppActorPackageType.Annual -> AppActorProductType.Subscription
            packageKind == AppActorPackageType.Lifetime -> AppActorProductType.NonConsumable
            packageKind == AppActorPackageType.Consumable -> AppActorProductType.Consumable
            else -> AppActorProductType.fromWireValue(productType)
        }
    }

    private fun AppActorProductReferenceDTO.logDescriptor(
        packageType: String?,
    ): String {
        return listOfNotNull(
            "productId=$productId",
            "storeProductId=${storeLookupProductId()}",
            "productType=${resolvedProductType(packageType).wireValue}",
            "basePlanId=${basePlanId ?: "null"}",
            "offerId=${offerId ?: "null"}",
            "packageType=${packageType ?: "null"}",
        ).joinToString(",")
    }

    private fun AppActorProductReferenceDTO.storeLookupProductId(): String {
        return appActorStoreLookupProductId(productId = productId, storeProductId = storeProductId)
    }

    private fun AppActorStoreProductRequest.logDescriptor(): String {
        return listOf(
            "productId=$productId",
            "productType=${productType.wireValue}",
            "basePlanId=${basePlanId ?: "null"}",
            "offerId=${offerId ?: "null"}",
        ).joinToString(",")
    }

    private fun Map<String, JsonElement>.toMetadata(): AppActorMetadata {
        return mapValues { (_, value) -> value.toAnyValue() }
    }

    private fun JsonElement.toAnyValue(): Any? {
        return when (this) {
            JsonNull -> null
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                doubleOrNull != null -> doubleOrNull
                else -> content
            }

            is JsonArray -> map { it.toAnyValue() }
            is JsonObject -> mapValues { (_, value) -> value.toAnyValue() }
        }
    }

    private sealed interface OfferingsAction {
        data class Await(val deferred: CompletableDeferred<AppActorOfferings>) : OfferingsAction
        data class ReturnCachedPayload(
            val cachedValue: AppActorCachedValue,
            val generation: Long,
            /** The inFlight request a background refresh must complete; null for CacheOnly. */
            val refreshRequest: CompletableDeferred<AppActorOfferings>?,
        ) : OfferingsAction
    }

    private sealed interface BootstrapPrefetchAction {
        data class Skip(val source: AppActorDiagnosticsDataSource?) : BootstrapPrefetchAction
        data class Await(
            val deferred: CompletableDeferred<AppActorOfferings>,
            val source: AppActorDiagnosticsDataSource?,
        ) : BootstrapPrefetchAction
        data class Execute(
            val request: CompletableDeferred<AppActorOfferings>,
            val generation: Long,
        ) : BootstrapPrefetchAction
    }

    private data class BootstrapSeed(
        val dto: AppActorOfferingsEnvelopeDTO,
        val cachedAtMillis: Long,
        val source: AppActorDiagnosticsDataSource,
        val verification: AppActorVerificationResult = AppActorVerificationResult.NotRequested,
        val eTag: String? = null,
    )

    private companion object {
        const val FOREGROUND_TTL_MILLIS: Long = 5 * 60 * 1_000
        const val BACKGROUND_TTL_MILLIS: Long = 24 * 60 * 60 * 1_000
    }
}

private fun AppActorOfferings.toOfflineProductCatalog(): AppActorOfflineProductCatalog {
    val oneTimeProductKinds = all.values
        .asSequence()
        .flatMap { offering -> offering.packages.asSequence() }
        .filter { appActorPackage ->
            appActorPackage.store == AppActorStore.PlayStore &&
                (appActorPackage.productType == AppActorProductType.Consumable ||
                    appActorPackage.productType == AppActorProductType.NonConsumable)
        }
        .groupBy { appActorPackage ->
            AppActorOfflineProductCatalog.oneTimeKey(
                appActorStoreLookupProductId(
                    productId = appActorPackage.productId,
                    storeProductId = appActorPackage.storeProductId,
                )
            )
        }
        .mapNotNull { (key, packages) ->
            packages.map { it.productType }.distinct().singleOrNull()?.wireValue?.let { key to it }
        }
        .toMap(linkedMapOf())

    return AppActorOfflineProductCatalog(
        productEntitlements = productEntitlements,
        oneTimeProductKinds = oneTimeProductKinds,
    )
}

private fun AppActorOfferingsEnvelopeDTO.toOfflineProductCatalog(): AppActorOfflineProductCatalog {
    val sourceOfferings = linkedMapOf<String, AppActorOfferingDTO>().apply {
        data.offerings.forEach { offering -> put(offering.id, offering) }
        data.currentOffering?.let { offering -> put(offering.id, offering) }
    }.values.toList()

    val oneTimeProductKinds = sourceOfferings
        .asSequence()
        .flatMap { offering ->
            offering.packages.asSequence().flatMap { packageDTO ->
                packageDTO.products
                    .asSequence()
                    .filter { productRef -> AppActorStore.fromWireValue(productRef.store) == AppActorStore.PlayStore }
                    .map { productRef -> productRef to packageDTO.packageType }
            }
        }
        .mapNotNull { (productRef, packageType) ->
            val packageKind = AppActorPackageType.fromServerValue(packageType)
            val productType = when {
                !productRef.basePlanId.isNullOrBlank() || !productRef.offerId.isNullOrBlank() -> {
                    AppActorProductType.Subscription
                }
                packageKind == AppActorPackageType.Weekly ||
                    packageKind == AppActorPackageType.Monthly ||
                    packageKind == AppActorPackageType.TwoMonth ||
                    packageKind == AppActorPackageType.ThreeMonth ||
                    packageKind == AppActorPackageType.SixMonth ||
                    packageKind == AppActorPackageType.Annual -> AppActorProductType.Subscription
                packageKind == AppActorPackageType.Lifetime -> AppActorProductType.NonConsumable
                packageKind == AppActorPackageType.Consumable -> AppActorProductType.Consumable
                else -> AppActorProductType.fromWireValue(productRef.productType)
            }
            if (productType == AppActorProductType.Consumable ||
                productType == AppActorProductType.NonConsumable
            ) {
                AppActorOfflineProductCatalog.oneTimeKey(
                    appActorStoreLookupProductId(
                        productId = productRef.productId,
                        storeProductId = productRef.storeProductId,
                    )
                ) to productType.wireValue
            } else {
                null
            }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapNotNull { (key, types) ->
            types.distinct().singleOrNull()?.let { key to it }
        }
        .toMap(linkedMapOf())

    return AppActorOfflineProductCatalog(
        productEntitlements = data.productEntitlements,
        oneTimeProductKinds = oneTimeProductKinds,
    )
}
