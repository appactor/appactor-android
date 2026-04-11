package com.appactor.android.backend.client

import com.appactor.android.backend.auth.AppActorAuthHeaderProvider
import com.appactor.android.backend.dto.AppActorBackendErrorEnvelopeDTO
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorExperimentAssignmentEnvelopeDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncResponseDTO
import com.appactor.android.backend.dto.AppActorIdentifyRequestDTO
import com.appactor.android.backend.dto.AppActorLoginRequestDTO
import com.appactor.android.backend.dto.AppActorLoginResponseDTO
import com.appactor.android.backend.dto.AppActorLogoutRequestDTO
import com.appactor.android.backend.dto.AppActorLogoutResponseDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.backend.dto.AppActorRemoteConfigsEnvelopeDTO
import com.appactor.android.models.AppActorConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class AppActorHttpBackendClient(
    private val configuration: AppActorConfiguration,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) : AppActorBackendClient {

    private companion object {
        const val maxRetries: Int = 3
        const val maxRetryAfterSeconds: Double = 3600.0
        const val maxRetryDelaySeconds: Double = 120.0
    }

    override suspend fun identify(request: AppActorIdentifyRequestDTO): AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO> {
        val httpRequest = buildJsonRequest(
            url = buildAppActorUrl(configuration.baseUrl, "v1", "payment", "identify"),
            method = "POST",
            body = request,
        )
        return executeRetryable(httpRequest)
    }

    override suspend fun login(request: AppActorLoginRequestDTO): AppActorBackendHttpResponse<AppActorLoginResponseDTO> {
        val httpRequest = buildJsonRequest(
            url = buildAppActorUrl(configuration.baseUrl, "v1", "payment", "login"),
            method = "POST",
            body = request,
        )
        return executeRetryable(httpRequest)
    }

    override suspend fun logout(request: AppActorLogoutRequestDTO): AppActorBackendHttpResponse<AppActorLogoutResponseDTO> {
        val httpRequest = buildJsonRequest(
            url = buildAppActorUrl(configuration.baseUrl, "v1", "payment", "logout"),
            method = "POST",
            body = request,
        )
        return executeRetryable(httpRequest)
    }

    override suspend fun getOfferings(eTag: String?): AppActorBackendHttpResponse<AppActorOfferingsEnvelopeDTO> {
        val httpRequest = buildGetRequest(
            url = buildAppActorUrl(configuration.baseUrl, "v1", "payment", "offerings"),
            eTag = eTag,
        )
        return executeRetryable(httpRequest)
    }

    override suspend fun getCustomer(
        appUserId: String,
        eTag: String?,
    ): AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO> {
        val httpRequest = buildGetRequest(
            url = buildAppActorUrl(configuration.baseUrl, "v1", "customers", appUserId),
            eTag = eTag,
        )
        return executeRetryable(httpRequest) { statusCode, requestId ->
            if (statusCode == 404) {
                throw AppActorBackendException.CustomerNotFound(
                    appUserId = appUserId,
                    requestId = requestId,
                )
            }
        }
    }

    override suspend fun getRemoteConfigs(
        appUserId: String?,
        appVersion: String?,
        country: String?,
        eTag: String?,
    ): AppActorBackendHttpResponse<AppActorRemoteConfigsEnvelopeDTO> {
        val url = buildAppActorUrl(
            baseUrl = configuration.baseUrl,
            pathSegments = arrayOf("v1", "remote-config"),
            queryParameters = listOfNotNull(
                appVersion?.takeIf { it.isNotBlank() }?.let { "app_version" to it },
                country?.takeIf { it.isNotBlank() }?.let { "country" to it },
                appUserId?.takeIf { it.isNotBlank() }?.let { "app_user_id" to it },
            ),
        )
        val httpRequest = buildGetRequest(
            url = url,
            eTag = eTag,
        )
        return executeRetryable(httpRequest)
    }

    override suspend fun postExperimentAssignment(
        experimentKey: String,
        appUserId: String,
        appVersion: String?,
        country: String?,
    ): AppActorBackendHttpResponse<AppActorExperimentAssignmentEnvelopeDTO> {
        val url = buildAppActorUrl(
            baseUrl = configuration.baseUrl,
            pathSegments = arrayOf("v1", "experiments", experimentKey, "assignments"),
            queryParameters = listOf(
                "app_user_id" to appUserId,
            ) + listOfNotNull(
                appVersion?.takeIf { it.isNotBlank() }?.let { "app_version" to it },
                country?.takeIf { it.isNotBlank() }?.let { "country" to it },
            ),
        )
        val httpRequest = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .header("Accept", "application/json")
            .build()
            .newBuilder()
            .also { builder -> AppActorAuthHeaderProvider.apply(builder, configuration) }
            .build()
        return executeRetryable(httpRequest)
    }

    override suspend fun postGoogleReceipt(
        request: AppActorGoogleReceiptRequestDTO,
    ): AppActorBackendHttpResponse<AppActorGoogleReceiptResponseDTO> {
        val httpRequest = buildJsonRequest(
            url = buildAppActorUrl(configuration.baseUrl, "v1", "payment", "receipts", "google"),
            method = "POST",
            body = request,
        )
        return executeSingleAttempt(httpRequest)
    }

    override suspend fun postGoogleRestore(
        request: AppActorGoogleRestoreRequestDTO,
    ): AppActorBackendHttpResponse<AppActorGoogleRestoreResponseDTO> {
        val httpRequest = buildJsonRequest(
            url = buildAppActorUrl(configuration.baseUrl, "v1", "payment", "restore", "google"),
            method = "POST",
            body = request,
        )
        return executeRetryable(httpRequest)
    }

    override suspend fun postGoogleSync(
        request: AppActorGoogleSyncRequestDTO,
    ): AppActorBackendHttpResponse<AppActorGoogleSyncResponseDTO> {
        val httpRequest = buildJsonRequest(
            url = buildAppActorUrl(configuration.baseUrl, "v1", "payment", "sync", "google"),
            method = "POST",
            body = request,
        )
        return executeRetryable(httpRequest)
    }

    private inline fun <reified T> buildJsonRequest(
        url: String,
        method: String,
        body: T,
    ): Request {
        val jsonBody = AppActorBackendJson.instance.encodeToString(body)
        val builder = Request.Builder()
            .url(url)
            .method(
                method,
                jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")

        AppActorAuthHeaderProvider.apply(builder, configuration)
        return builder.build()
    }

    private fun buildGetRequest(
        url: String,
        eTag: String? = null,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")

        if (!eTag.isNullOrBlank()) {
            builder.header("If-None-Match", eTag)
        }

        AppActorAuthHeaderProvider.apply(builder, configuration)
        return builder.build()
    }

    private suspend inline fun <reified T> executeSingleAttempt(
        request: Request,
    ): AppActorBackendHttpResponse<T> = executeInternal(
        initialRequest = request,
        retryEnabled = false,
    )

    private suspend inline fun <reified T> executeRetryable(
        request: Request,
        noinline additionalNonRetryableHandler: ((statusCode: Int, requestId: String?) -> Unit)? = null,
    ): AppActorBackendHttpResponse<T> = executeInternal(
        initialRequest = request,
        retryEnabled = true,
        additionalNonRetryableHandler = additionalNonRetryableHandler,
    )

    private suspend inline fun <reified T> executeInternal(
        initialRequest: Request,
        retryEnabled: Boolean,
        noinline additionalNonRetryableHandler: ((statusCode: Int, requestId: String?) -> Unit)? = null,
    ): AppActorBackendHttpResponse<T> = withContext(Dispatchers.IO) {
        val totalAttempts = if (retryEnabled) maxRetries else 1
        var lastError: AppActorBackendException = AppActorBackendException.Network(
            description = "Backend request failed.",
        )
        var retryAfterOverride: Double? = null

        for (attempt in 0 until totalAttempts) {
            if (attempt > 0) {
                val baseDelay = min(2.0.pow((attempt - 1).toDouble()), 30.0)
                val ownDelay = baseDelay + kotlin.random.Random.nextDouble(0.0, baseDelay)
                val resolvedDelay = min(maxOf(ownDelay, retryAfterOverride ?: 0.0), maxRetryDelaySeconds)
                retryAfterOverride = null
                delay((resolvedDelay * 1_000).toLong())
            }

            val request = requestForAttempt(
                initialRequest = initialRequest,
                attempt = attempt,
            )

            try {
                val rawResponse = executeRaw(request)
                val errorEnvelope = parseErrorEnvelope(rawResponse.rawBody)
                val resolvedRequestId = errorEnvelope?.requestId ?: rawResponse.requestId

                when (rawResponse.statusCode) {
                    304 -> {
                        return@withContext AppActorBackendHttpResponse(
                            body = null,
                            statusCode = rawResponse.statusCode,
                            requestId = resolvedRequestId,
                            eTag = rawResponse.eTag,
                            isNotModified = true,
                            signatureHeaders = rawResponse.signatureHeaders,
                            signatureVerified = rawResponse.signatureVerified,
                        )
                    }

                    in 200..299 -> {
                        val bodyString = rawResponse.rawBody ?: throw AppActorBackendException.Decoding(
                            description = "Response body was null.",
                            requestId = resolvedRequestId,
                        )
                        val decoded = runCatching {
                            AppActorBackendJson.instance.decodeFromString<T>(bodyString)
                        }.getOrElse { throwable ->
                            throw AppActorBackendException.Decoding(
                                description = "Failed to decode backend response.",
                                requestId = resolvedRequestId,
                                throwable = throwable,
                            )
                        }

                        return@withContext AppActorBackendHttpResponse(
                            body = decoded,
                            statusCode = rawResponse.statusCode,
                            requestId = resolvedRequestId,
                            eTag = rawResponse.eTag,
                            isNotModified = false,
                            signatureHeaders = rawResponse.signatureHeaders,
                            signatureVerified = rawResponse.signatureVerified,
                        )
                    }

                    429 -> {
                        additionalNonRetryableHandler?.invoke(rawResponse.statusCode, resolvedRequestId)
                        val parsedRetryAfter = parseRetryAfterHeader(rawResponse.retryAfterHeader)
                        lastError = AppActorBackendException.Http(
                            statusCode = rawResponse.statusCode,
                            requestId = resolvedRequestId,
                            error = errorEnvelope?.error,
                            rawBodyLength = rawResponse.rawBody?.length,
                            retryAfterSeconds = parsedRetryAfter,
                        )
                        retryAfterOverride = parsedRetryAfter
                        if (attempt < totalAttempts - 1) {
                            continue
                        }
                        throw lastError
                    }

                    in 500..599 -> {
                        lastError = AppActorBackendException.Http(
                            statusCode = rawResponse.statusCode,
                            requestId = resolvedRequestId,
                            error = errorEnvelope?.error,
                            rawBodyLength = rawResponse.rawBody?.length,
                        )
                        if (attempt < totalAttempts - 1) {
                            continue
                        }
                        throw lastError
                    }

                    else -> {
                        additionalNonRetryableHandler?.invoke(rawResponse.statusCode, resolvedRequestId)
                        throw AppActorBackendException.Http(
                            statusCode = rawResponse.statusCode,
                            requestId = resolvedRequestId,
                            error = errorEnvelope?.error,
                            rawBodyLength = rawResponse.rawBody?.length,
                        )
                    }
                }
            } catch (backendException: AppActorBackendException.Signature) {
                throw backendException
            } catch (backendException: AppActorBackendException.Decoding) {
                throw backendException
            } catch (backendException: AppActorBackendException.CustomerNotFound) {
                throw backendException
            } catch (backendException: AppActorBackendException.Http) {
                throw backendException
            } catch (ioException: IOException) {
                lastError = AppActorBackendException.Network(
                    description = "Backend request failed.",
                    throwable = ioException,
                )
                if (attempt < totalAttempts - 1) {
                    continue
                }
                throw lastError
            } catch (throwable: Throwable) {
                lastError = AppActorBackendException.Network(
                    description = "Backend request failed.",
                    throwable = throwable,
                )
                if (retryEnabled && attempt < totalAttempts - 1) {
                    continue
                }
                throw lastError
            }
        }

        throw lastError
    }

    private fun requestForAttempt(
        initialRequest: Request,
        attempt: Int,
    ): Request {
        if (attempt == 0) return initialRequest

        val builder = initialRequest.newBuilder()
            .removeHeader("If-None-Match")
        AppActorAuthHeaderProvider.apply(builder, configuration)
        return builder.build()
    }

    private fun parseErrorEnvelope(rawBody: String?): AppActorBackendErrorEnvelopeDTO? {
        return rawBody
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    AppActorBackendJson.instance.decodeFromString<AppActorBackendErrorEnvelopeDTO>(it)
                }.getOrNull()
            }
    }

    private fun parseRetryAfterHeader(value: String?): Double? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val seconds = raw.toDoubleOrNull()
        if (seconds != null && seconds > 0 && seconds <= maxRetryAfterSeconds) {
            return seconds
        }
        return null
    }

    private fun extractRequestId(rawBody: String?): String? {
        val jsonString = rawBody?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            AppActorBackendJson.instance
                .parseToJsonElement(jsonString)
                .jsonObject["requestId"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
    }

    private fun executeRaw(request: Request): RawBackendResponse {
        try {
            okHttpClient.newCall(request).execute().use { response ->
                val statusCode = response.code
                val rawBytes = response.body?.bytes()
                val rawBody = rawBytes?.toString(Charsets.UTF_8)
                val requestId = response.header("X-Request-Id") ?: extractRequestId(rawBody)
                val eTag = response.header("ETag")
                val signatureHeaders = AppActorResponseSignatureHeaders.fromHeaders(response.headers)
                val retryAfterHeader = response.header("Retry-After")
                val sentNonce = request.header("X-AppActor-Nonce").orEmpty()
                val signatureVerified = verifyResponseSignature(
                    statusCode = statusCode,
                    signatureHeaders = signatureHeaders,
                    rawBody = rawBody.orEmpty(),
                    sentNonce = sentNonce,
                    requestId = requestId,
                )

                return RawBackendResponse(
                    statusCode = statusCode,
                    rawBody = rawBody,
                    requestId = requestId,
                    eTag = eTag,
                    signatureHeaders = signatureHeaders,
                    signatureVerified = signatureVerified,
                    retryAfterHeader = retryAfterHeader,
                )
            }
        } catch (backendException: AppActorBackendException) {
            throw backendException
        } catch (ioException: IOException) {
            throw ioException
        } catch (throwable: Throwable) {
            throw AppActorBackendException.Network(
                description = "Backend request failed.",
                throwable = throwable,
            )
        }
    }

    private fun verifyResponseSignature(
        statusCode: Int,
        signatureHeaders: AppActorResponseSignatureHeaders?,
        rawBody: String,
        sentNonce: String,
        requestId: String?,
    ): Boolean {
        if (statusCode !in 200..299 || !configuration.options.verifyResponseSignatures || sentNonce.isBlank()) {
            return false
        }

        return when (
            val result = AppActorResponseSignatureVerifier.verify(
                headers = signatureHeaders,
                body = rawBody,
                sentNonce = sentNonce,
            )
        ) {
            AppActorResponseSignatureVerifier.VerificationResult.Success -> true
            AppActorResponseSignatureVerifier.VerificationResult.SigningNotSupported -> {
                if (configuration.options.requireResponseSignatures) {
                    throw AppActorBackendException.Signature(
                        result = AppActorResponseSignatureVerifier.VerificationResult.SignatureMissing,
                        requestId = requestId,
                    )
                }
                false
            }
            else -> throw AppActorBackendException.Signature(
                result = result,
                requestId = requestId,
            )
        }
    }
}

internal fun buildAppActorUrl(
    baseUrl: String,
    vararg pathSegments: String,
): String {
    return buildAppActorUrl(baseUrl, pathSegments = pathSegments, queryParameters = emptyList())
}

internal fun buildAppActorUrl(
    baseUrl: String,
    pathSegments: Array<out String>,
    queryParameters: List<Pair<String, String>> = emptyList(),
): String {
    val baseHttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) {
        "Invalid AppActor base URL: $baseUrl"
    }

    val builder = baseHttpUrl.newBuilder()
    pathSegments.forEach(builder::addPathSegment)
    queryParameters.forEach { (key, value) ->
        builder.addQueryParameter(key, value)
    }
    return builder.build().toString()
}

private data class RawBackendResponse(
    val statusCode: Int,
    val rawBody: String?,
    val requestId: String?,
    val eTag: String?,
    val signatureHeaders: AppActorResponseSignatureHeaders?,
    val signatureVerified: Boolean,
    val retryAfterHeader: String?,
)
