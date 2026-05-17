package com.appactor.android.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.AppActorHttpBackendClient
import com.appactor.android.backend.dto.AppActorAttributionRequestDTO
import com.appactor.android.backend.dto.AppActorAttributesPatchRequestDTO
import com.appactor.android.backend.dto.AppActorIdentifyRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.backend.dto.AppActorIntegrationIdentifierRequestDTO
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorPlatformInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AppActorHttpBackendClientTests {

    @Test
    fun `identify retries on transient server failure and succeeds`() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val currentAttempt = attempts.incrementAndGet()
                    if (currentAttempt == 1) {
                        response(
                            chain = chain,
                            code = 500,
                            body = """
                                {
                                  "requestId": "req_retry_500",
                                  "error": {
                                    "code": "INTERNAL_ERROR",
                                    "message": "temporary failure"
                                  }
                                }
                            """.trimIndent(),
                        )
                    } else {
                        response(
                            chain = chain,
                            code = 200,
                            body = fixture("fixtures/backend/identify_android_sample.json"),
                        )
                    }
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        val result = client.identify(AppActorIdentifyRequestDTO(appUserId = "user_android_123"))

        assertEquals(2, attempts.get())
        assertEquals("user_android_123", result.body?.appUserId)
        assertFalse(result.signatureVerified)
    }

    @Test
    fun `identify enforces response signatures when required`() = runBlocking {
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    response(
                        chain = chain,
                        code = 200,
                        body = fixture("fixtures/backend/identify_android_sample.json"),
                    )
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = true,
                requireResponseSignatures = true,
            ),
        )

        try {
            client.identify(AppActorIdentifyRequestDTO(appUserId = "user_android_123"))
            throw AssertionError("Expected signature exception")
        } catch (error: AppActorBackendException.Signature) {
            assertEquals("req_android_identify_001", error.requestId)
            assertEquals("SignatureMissing", error.result.name)
        }
    }

    @Test
    fun `google receipt remains single attempt because queue owns retry policy`() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    attempts.incrementAndGet()
                    response(
                        chain = chain,
                        code = 500,
                        body = """
                            {
                              "requestId": "req_receipt_500",
                              "error": {
                                "code": "INTERNAL_ERROR",
                                "message": "temporary failure"
                              }
                            }
                        """.trimIndent(),
                    )
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        try {
            client.postGoogleReceipt(
                AppActorGoogleReceiptRequestDTO(
                    appUserId = "user_android_123",
                    packageName = "com.appactor.android",
                    environment = "production",
                    productId = "com.appactor.pro.monthly",
                    productType = "subscription",
                    purchaseToken = "token_123",
                    purchaseTime = "1710000000000",
                    purchaseState = "PURCHASED",
                    sourceIntent = "purchase",
                    idempotencyKey = "google:purchase:token_123",
                )
            )
            throw AssertionError("Expected HTTP exception")
        } catch (error: AppActorBackendException.Http) {
            assertEquals(500, error.statusCode)
            assertEquals(1, attempts.get())
        }
    }

    @Test
    fun `remote config client sends query params and decodes envelope`() = runBlocking {
        var capturedPath = ""
        var capturedQuery = ""
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedPath = chain.request().url.encodedPath
                    capturedQuery = chain.request().url.encodedQuery.orEmpty()
                    response(
                        chain = chain,
                        code = 200,
                        body = """
                            {
                              "requestId": "req_remote_001",
                              "data": [
                                { "key": "has_rating", "value": true, "valueType": "boolean" }
                              ]
                            }
                        """.trimIndent(),
                        headers = mapOf("X-AppActor-Remote-Config-Requires-User-Context" to "false"),
                    )
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        val result = client.getRemoteConfigs(
            appUserId = "user_android_123",
            appVersion = "1.2.3",
            country = "TR",
            eTag = "\"etag_remote\"",
        )

        assertEquals("/v1/remote-config", capturedPath)
        assertTrue(capturedQuery.contains("app_user_id=user_android_123"))
        assertTrue(capturedQuery.contains("app_version=1.2.3"))
        assertTrue(capturedQuery.contains("country=TR"))
        assertEquals("has_rating", result.body?.data?.single()?.key)
        assertEquals(false, result.remoteConfigRequiresUserContext)
    }

    @Test
    fun `experiment client posts assignment route with query params`() = runBlocking {
        var capturedPath = ""
        var capturedQuery = ""
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedPath = chain.request().url.encodedPath
                    capturedQuery = chain.request().url.encodedQuery.orEmpty()
                    response(
                        chain = chain,
                        code = 200,
                        body = """
                            {
                              "requestId": "req_exp_001",
                              "data": {
                                "inExperiment": true,
                                "experiment": { "id": "exp_001", "key": "paywall_copy" },
                                "variant": {
                                  "id": "var_001",
                                  "key": "variant_a",
                                  "valueType": "string",
                                  "payload": "new_copy"
                                },
                                "assignedAt": "2026-03-14T12:00:00Z"
                              }
                            }
                        """.trimIndent(),
                    )
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        val result = client.postExperimentAssignment(
            experimentKey = "paywall_copy",
            appUserId = "user_android_123",
            appVersion = "1.2.3",
            country = "TR",
        )

        assertEquals("/v1/experiments/paywall_copy/assignments", capturedPath)
        assertTrue(capturedQuery.contains("app_user_id=user_android_123"))
        assertTrue(capturedQuery.contains("app_version=1.2.3"))
        assertTrue(capturedQuery.contains("country=TR"))
        assertTrue(result.body?.data?.inExperiment == true)
    }

    @Test
    fun `platform info headers are attached to backend requests`() = runBlocking {
        var capturedFlavor: String? = null
        var capturedVersion: String? = null
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedFlavor = chain.request().header("X-Platform-Flavor")
                    capturedVersion = chain.request().header("X-Platform-Flavor-Version")
                    response(
                        chain = chain,
                        code = 200,
                        body = fixture("fixtures/backend/identify_android_sample.json"),
                    )
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
                platformInfo = AppActorPlatformInfo("flutter", "3.0.0"),
            ),
        )

        client.identify(AppActorIdentifyRequestDTO(appUserId = "user_android_123"))

        assertEquals("flutter", capturedFlavor)
        assertEquals("3.0.0", capturedVersion)
    }

    @Test
    fun `offerings request does not include nonce header`() = runBlocking {
        var capturedNonce: String? = "UNSET"
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedNonce = chain.request().header("X-AppActor-Nonce")
                    response(
                        chain = chain,
                        code = 200,
                        body = """
                            {
                              "requestId": "req_off_001",
                              "data": {
                                "offerings": [],
                                "currentOfferingId": null
                              }
                            }
                        """.trimIndent(),
                    )
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        client.getOfferings(eTag = null)

        assertNull("Offerings request should NOT include X-AppActor-Nonce", capturedNonce)
    }

    @Test
    fun `remote config request does not include nonce header`() = runBlocking {
        var capturedNonce: String? = "UNSET"
        var capturedSignatureTarget: String? = null
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedNonce = chain.request().header("X-AppActor-Nonce")
                    capturedSignatureTarget = chain.request().header("X-AppActor-Signature-Target")
                    response(
                        chain = chain,
                        code = 200,
                        body = """
                            {
                              "requestId": "req_remote_nonce",
                              "data": []
                            }
                        """.trimIndent(),
                    )
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        client.getRemoteConfigs(appUserId = null, appVersion = null, country = null, eTag = null)

        assertNull("Remote config request should NOT include X-AppActor-Nonce", capturedNonce)
        assertEquals("path-query", capturedSignatureTarget)
    }

    @Test
    fun `identify request includes nonce header`() = runBlocking {
        var capturedNonce: String? = null
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedNonce = chain.request().header("X-AppActor-Nonce")
                    response(
                        chain = chain,
                        code = 200,
                        body = fixture("fixtures/backend/identify_android_sample.json"),
                    )
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        client.identify(AppActorIdentifyRequestDTO(appUserId = "user_android_123"))

        assertNotNull("Identify request MUST include X-AppActor-Nonce", capturedNonce)
    }

    @Test
    fun `attributes client patches attributes route with typed json body`() = runBlocking {
        var capturedMethod = ""
        var capturedPath = ""
        var capturedBody = ""
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedMethod = chain.request().method
                    capturedPath = chain.request().url.encodedPath
                    capturedBody = chain.request().bodyToString()
                    response(chain = chain, code = 204, body = "")
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        client.patchUserAttributes(
            appUserId = "user_android_123",
            request = AppActorAttributesPatchRequestDTO(
                attributes = mapOf("favorite_color" to JsonPrimitive("blue")),
                unsetAttributes = listOf("old_key"),
            ),
        )

        assertEquals("PATCH", capturedMethod)
        assertEquals("/v1/payment/users/user_android_123/attributes", capturedPath)
        assertTrue(capturedBody.contains("\"favorite_color\":\"blue\""))
        assertTrue(capturedBody.contains("\"unset_attributes\":[\"old_key\"]"))
    }

    @Test
    fun `attributes client deletes encoded attribute key`() = runBlocking {
        var capturedMethod = ""
        var capturedPath = ""
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedMethod = chain.request().method
                    capturedPath = chain.request().url.encodedPath
                    response(chain = chain, code = 204, body = "")
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        client.deleteUserAttribute(appUserId = "user_android_123", key = "\$email")

        assertEquals("DELETE", capturedMethod)
        assertEquals("/v1/payment/users/user_android_123/attributes/\$email", capturedPath)
    }

    @Test
    fun `integration and attribution clients post planned user routes`() = runBlocking {
        val captured = mutableListOf<Triple<String, String, String>>()
        val client = backendClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    captured += Triple(
                        chain.request().method,
                        chain.request().url.encodedPath,
                        chain.request().bodyToString(),
                    )
                    response(chain = chain, code = 204, body = "")
                }
                .build(),
            options = AppActorConfiguration.Options(
                verifyResponseSignatures = false,
                requireResponseSignatures = false,
            ),
        )

        client.postIntegrationIdentifier(
            appUserId = "user_android_123",
            request = AppActorIntegrationIdentifierRequestDTO(type = "firebase_app_instance_id", value = "fid_123"),
        )
        client.deleteIntegrationIdentifier(
            appUserId = "user_android_123",
            type = "firebase_app_instance_id",
        )
        client.postAttribution(
            appUserId = "user_android_123",
            request = AppActorAttributionRequestDTO(
                provider = "adjust",
                status = "non_organic",
                campaignName = "spring",
                adGroupId = "ag_123",
            ),
        )

        assertEquals("POST", captured[0].first)
        assertEquals("/v1/payment/users/user_android_123/integration-identifiers", captured[0].second)
        assertTrue(captured[0].third.contains("\"type\":\"firebase_app_instance_id\""))
        assertEquals("DELETE", captured[1].first)
        assertEquals("/v1/payment/users/user_android_123/integration-identifiers/firebase_app_instance_id", captured[1].second)
        assertEquals("", captured[1].third)
        assertEquals("POST", captured[2].first)
        assertEquals("/v1/payment/users/user_android_123/attribution", captured[2].second)
        assertTrue(captured[2].third.contains("\"provider\":\"adjust\""))
        assertTrue(captured[2].third.contains("\"status\":\"non_organic\""))
        assertTrue(captured[2].third.contains("\"campaign_name\":\"spring\""))
        assertTrue(captured[2].third.contains("\"ad_group_id\":\"ag_123\""))
    }

    private fun backendClient(
        okHttpClient: OkHttpClient,
        options: AppActorConfiguration.Options,
    ): AppActorHttpBackendClient {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AppActorHttpBackendClient(
            configuration = AppActorConfiguration(
                context = context,
                apiKey = "pk_test_123",
                baseUrl = "https://api.appactor.com",
                options = options,
            ),
            okHttpClient = okHttpClient,
        )
    }

    private fun response(
        chain: Interceptor.Chain,
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val responseBuilder = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "ERROR")
            .body(body.toResponseBody("application/json".toMediaType()))

        headers.forEach { (key, value) ->
            responseBuilder.header(key, value)
        }

        return responseBuilder.build()
    }

    private fun okhttp3.Request.bodyToString(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun fixture(path: String): String {
        return requireNotNull(javaClass.classLoader?.getResource(path)) {
            "Missing fixture: $path"
        }.readText()
    }
}
