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
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorVerificationResult
import com.appactor.android.models.AppActorMetadata
import com.appactor.android.models.AppActorOffering
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPackageType
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val storeAdapter: AppActorStoreAdapter,
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val stateMutex = Mutex()
    @Volatile
    private var cachedOfferings: AppActorOfferings? = null
    private var cachedAtMillis: Long? = null
    private var cachedLocales: List<String> = emptyList()
    @Volatile
    private var isBackground: Boolean = false
    private var inFlight: CompletableDeferred<AppActorOfferings>? = null
    private var cacheGeneration: Long = 0
    @Volatile
    private var lastLoadSource: AppActorDiagnosticsDataSource? = null
    @Volatile
    private var fallbackDTO: AppActorOfferingsEnvelopeDTO? = null

    suspend fun getOfferings(forceRefresh: Boolean = false): AppActorOfferings {
        // Phase 1: Check state under lock, capture what to do, release lock immediately
        val action = stateMutex.withLock {
            // Fresh cache → return immediately
            if (!forceRefresh && isMemoryCacheFreshLocked()) {
                lastLoadSource = AppActorDiagnosticsDataSource.Cache
                return requireNotNull(cachedOfferings)
            }

            // SWR: stale cache exists → return it immediately, trigger background refresh
            if (!forceRefresh) {
                cachedOfferings?.let { stale ->
                    lastLoadSource = AppActorDiagnosticsDataSource.Cache
                    if (inFlight == null) {
                        val request = CompletableDeferred<AppActorOfferings>()
                        inFlight = request
                        launchBackgroundRefresh(request, cacheGeneration)
                    }
                    return stale
                }
            }

            // Cold cache — decide action WITHOUT awaiting under lock
            inFlight?.let { existing ->
                return@withLock OfferingsAction.Await(existing)
            }

            val request = CompletableDeferred<AppActorOfferings>()
            inFlight = request
            OfferingsAction.Execute(request, cacheGeneration)
        }

        // Phase 2: Execute action WITHOUT holding the lock
        return when (action) {
            is OfferingsAction.Await -> action.deferred.await()
            is OfferingsAction.Execute -> executeFetch(action.request, action.generation, forceRefresh)
        }
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
            cachedLocales = emptyList()
            lastLoadSource = null
        }
        cacheStore.clear()
    }

    fun setFallbackOfferings(dto: AppActorOfferingsEnvelopeDTO) {
        this.fallbackDTO = dto
    }

    fun currentProductEntitlements(): Map<String, List<String>> {
        cachedOfferings?.productEntitlements?.takeIf { it.isNotEmpty() }?.let { return it }
        val payload = cacheStore.load()?.payload ?: return emptyMap()
        return runCatching {
            AppActorBackendJson.instance
                .decodeFromString<AppActorOfferingsEnvelopeDTO>(payload)
                .data
                .productEntitlements
        }.getOrDefault(emptyMap())
    }

    // MARK: - Internal

    private suspend fun executeFetch(
        request: CompletableDeferred<AppActorOfferings>,
        generation: Long,
        forceRefresh: Boolean,
    ): AppActorOfferings {
        return try {
            val result = fetchOfferings(forceRefresh, generation)
            request.complete(result)
            result
        } catch (throwable: Throwable) {
            request.completeExceptionally(throwable)
            throw throwable
        } finally {
            stateMutex.withLock {
                if (inFlight === request) {
                    inFlight = null
                }
            }
        }
    }

    private suspend fun fetchOfferings(forceRefresh: Boolean, generation: Long): AppActorOfferings {
        return try {
            val response = backendClient.getOfferings(
                eTag = cacheStore.eTag(forceRefresh = forceRefresh),
            )
            when {
                response.isNotModified -> {
                    cached()?.takeIf { !forceRefresh } ?: run {
                        val cachedValue = cacheStore.handleNotModified(response.eTag) ?: cacheStore.load()
                        val decoded = cachedValue?.let {
                            try {
                                decodeAndEnrich(it.payload, it.cachedAtMillis, generation, AppActorDiagnosticsDataSource.Cache, it.verification)
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (decoded != null) {
                            decoded
                        } else {
                            val fallback = fallbackDTO
                            if (fallback != null) {
                                enrichAndCache(fallback, 0L, generation, AppActorDiagnosticsDataSource.Cache)
                            } else {
                                throw IllegalStateException("Offerings cache missing for 304 response with no fallback.")
                            }
                        }
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
                    )
                    val verification = AppActorVerificationResult.from(response.signatureVerified)
                    decodeAndEnrich(payload, dateProviderMillis(), generation, AppActorDiagnosticsDataSource.Network, verification)
                }
            }
        } catch (throwable: Throwable) {
            if (forceRefresh || !shouldFallbackToCache(throwable)) {
                throw throwable.toAppActorError("Failed to fetch offerings.")
            }
            // Fallback chain: disk cache → bundled fallback DTO → throw
            val cachedValue = cacheStore.load()
            val diskOfferings = if (cachedValue != null) {
                try {
                    decodeAndEnrich(cachedValue.payload, cachedValue.cachedAtMillis, generation, AppActorDiagnosticsDataSource.Cache, cachedValue.verification)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }
            } else null
            if (diskOfferings != null) {
                diskOfferings
            } else {
                val fallback = fallbackDTO
                if (fallback != null) {
                    // Use 0L (epoch) so the cache is immediately stale — next call triggers SWR refresh
                    enrichAndCache(fallback, 0L, generation, AppActorDiagnosticsDataSource.Cache)
                } else {
                    throw throwable.toAppActorError("Failed to fetch offerings.")
                }
            }
        }
    }

    private fun isMemoryCacheFreshLocked(): Boolean {
        val cachedAt = cachedAtMillis ?: return false
        val ttl = if (isBackground) BACKGROUND_TTL_MILLIS else FOREGROUND_TTL_MILLIS
        if (dateProviderMillis() - cachedAt >= ttl) return false
        return cachedLocales == currentLocales()
    }

    private fun currentLocales(): List<String> =
        listOf(Locale.getDefault().toLanguageTag())

    private fun launchBackgroundRefresh(
        request: CompletableDeferred<AppActorOfferings>,
        generation: Long,
    ) {
        backgroundScope.launch {
            try {
                val result = fetchOfferings(false, generation)
                request.complete(result)
            } catch (throwable: Throwable) {
                request.completeExceptionally(throwable)
            } finally {
                stateMutex.withLock {
                    if (inFlight === request) inFlight = null
                }
            }
        }
    }

    private suspend fun decodeAndEnrich(
        payload: String,
        cachedAtMillis: Long,
        generation: Long,
        source: AppActorDiagnosticsDataSource,
        verification: AppActorVerificationResult = AppActorVerificationResult.NotRequested,
    ): AppActorOfferings {
        val dto = AppActorBackendJson.instance.decodeFromString<AppActorOfferingsEnvelopeDTO>(payload)
        return enrichAndCache(dto, cachedAtMillis, generation, source, verification)
    }

    private suspend fun enrichAndCache(
        dto: AppActorOfferingsEnvelopeDTO,
        cachedAtMillis: Long,
        generation: Long,
        source: AppActorDiagnosticsDataSource,
        verification: AppActorVerificationResult = AppActorVerificationResult.NotRequested,
    ): AppActorOfferings {
        val offerings = enrich(dto).copy(verification = verification)
        stateMutex.withLock {
            if (cacheGeneration == generation) {
                cachedOfferings = offerings
                this.cachedAtMillis = cachedAtMillis
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
            .flatMap { offering -> offering.packages }
            .flatMap { packageDTO ->
                packageDTO.products
                    .filter { AppActorStore.fromWireValue(it.store) == AppActorStore.PlayStore }
                    .map { product ->
                        AppActorStoreProductRequest(
                            productId = product.productId,
                            productType = AppActorProductType.fromWireValue(product.productType),
                            basePlanId = product.basePlanId,
                            offerId = product.offerId,
                        )
                    }
            }
            .distinctBy { it.cacheKey() }

        val resolvedProducts = storeAdapter.queryProductDetails(productRequests)
            .associateBy { it.cacheKey() }

        val offeringPairs = sourceOfferings.mapNotNull { offeringDTO ->
            val packages = offeringDTO.packages.mapNotNull { packageDTO ->
                packageDTO.toEnrichedPackage(resolvedProducts, offeringId = offeringDTO.id)
            }
            if (packages.isEmpty()) {
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

        return AppActorOfferings(
            current = currentOffering,
            all = allOfferings,
            productEntitlements = dto.data.productEntitlements,
        )
    }

    private fun shouldFallbackToCache(throwable: Throwable): Boolean {
        return when (throwable) {
            is AppActorBackendException.Network -> true
            is AppActorBackendException.Http -> throwable.statusCode >= 500
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
                val productType = AppActorProductType.fromWireValue(product.productType)
                val request = AppActorStoreProductRequest(
                    productId = product.productId,
                    productType = productType,
                    basePlanId = product.basePlanId,
                    offerId = product.offerId,
                )
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
            storeProductId = productRef.storeProductId ?: productRef.productId,
            serverId = productRef.id,
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
        )
    }

    private fun AppActorStoreProduct.cacheKey(): String {
        return listOf(productType.name, productId, basePlanId.orEmpty(), offerId.orEmpty()).joinToString("|")
    }

    private fun AppActorStoreProductRequest.cacheKey(): String {
        return listOf(productType.name, productId, basePlanId.orEmpty(), offerId.orEmpty()).joinToString("|")
    }

    private fun AppActorProductReferenceDTO.cacheKey(): String {
        val resolvedType = AppActorProductType.fromWireValue(productType)
        return listOf(resolvedType.name, productId, basePlanId.orEmpty(), offerId.orEmpty()).joinToString("|")
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
        data class Execute(val request: CompletableDeferred<AppActorOfferings>, val generation: Long) : OfferingsAction
    }

    private companion object {
        const val FOREGROUND_TTL_MILLIS: Long = 5 * 60 * 1_000
        const val BACKGROUND_TTL_MILLIS: Long = 24 * 60 * 60 * 1_000
    }
}
