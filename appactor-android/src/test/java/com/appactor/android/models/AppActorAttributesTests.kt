package com.appactor.android.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class AppActorAttributesTests {

    @Test
    fun `attribute values encode supported primitive and array types`() {
        assertEquals(JsonPrimitive("hello"), AppActorAttributeValue.string("hello").toJsonElement())
        assertEquals(JsonPrimitive(42.0), AppActorAttributeValue.number(42.0).toJsonElement())
        assertEquals(JsonPrimitive(true), AppActorAttributeValue.bool(true).toJsonElement())
        assertEquals(
            JsonObject(
                mapOf(
                    "value" to JsonPrimitive("1970-01-01T00:00:00.000Z"),
                    "valueType" to JsonPrimitive("date"),
                ),
            ),
            AppActorAttributeValue.date(Date(0)).toJsonElement(),
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            AppActorAttributeValue.stringArray(listOf("a", "b")).toJsonElement(),
        )
    }

    @Test
    fun `a date attribute accepts any Date subclass`() {
        // java.sql.Date#toInstant always throws, so the formatter must not go through it.
        assertEquals(
            JsonPrimitive("1970-01-01T00:00:00.000Z"),
            (AppActorAttributeValue.date(java.sql.Date(0)).toJsonElement() as JsonObject)["value"],
        )
    }

    @Test
    fun `attribute PATCH values state the type of number and boolean arrays`() {
        // The backend would infer string_array for a bare [] and fix the key's definition to it.
        assertEquals(
            JsonObject(mapOf("value" to JsonArray(emptyList()), "valueType" to JsonPrimitive("number_array"))),
            AppActorAttributeValue.numberArray(emptyList()).toAttributePatchElement(),
        )
        assertEquals(
            JsonObject(mapOf("value" to JsonArray(emptyList()), "valueType" to JsonPrimitive("boolean_array"))),
            AppActorAttributeValue.boolArray(emptyList()).toAttributePatchElement(),
        )
        assertEquals(
            JsonObject(
                mapOf(
                    "value" to JsonArray(listOf(JsonPrimitive(1.5))),
                    "valueType" to JsonPrimitive("number_array"),
                ),
            ),
            AppActorAttributeValue.numberArray(listOf(1.5)).toAttributePatchElement(),
        )
        assertEquals(JsonPrimitive("a"), AppActorAttributeValue.string("a").toAttributePatchElement())
        // Attribution metadata keeps the plain form.
        assertEquals(
            JsonArray(listOf(JsonPrimitive(true))),
            AppActorAttributeValue.boolArray(listOf(true)).toJsonElement(),
        )
    }

    @Test
    fun `custom keys reject reserved prefixes while helpers can use dollar keys`() {
        assertEquals("favorite_color", AppActorAttributesValidation.normalizeCustomKey(" favorite_color "))
        assertEquals("\$email", AppActorAttributesValidation.normalizeReservedKey("\$email"))

        val dollarFailure = runCatching {
            AppActorAttributesValidation.normalizeCustomKey("\$email")
        }.exceptionOrNull()
        assertTrue(dollarFailure is IllegalArgumentException)

        val appactorFailure = runCatching {
            AppActorAttributesValidation.normalizeCustomKey("appactor.internal")
        }.exceptionOrNull()
        assertTrue(appactorFailure is IllegalArgumentException)

        val integrationFailure = runCatching {
            AppActorAttributesValidation.normalizeCustomKey("integration.adjust_id")
        }.exceptionOrNull()
        assertTrue(integrationFailure is IllegalArgumentException)

        val appVersionFailure = runCatching {
            AppActorAttributesValidation.normalizeCustomKey("appVersion")
        }.exceptionOrNull()
        assertTrue(appVersionFailure is IllegalArgumentException)

        val platformFailure = runCatching {
            AppActorAttributesValidation.normalizeCustomKey("platform")
        }.exceptionOrNull()
        assertTrue(platformFailure is IllegalArgumentException)

        val userCountryFailure = runCatching {
            AppActorAttributesValidation.normalizeCustomKey("userCountry")
        }.exceptionOrNull()
        assertTrue(userCountryFailure is IllegalArgumentException)

        val longKeyFailure = runCatching {
            AppActorAttributesValidation.normalizeCustomKey("a".repeat(65))
        }.exceptionOrNull()
        assertTrue(longKeyFailure is IllegalArgumentException)

        val invalidCharacterFailure = runCatching {
            AppActorAttributesValidation.normalizeCustomKey("bad key")
        }.exceptionOrNull()
        assertTrue(invalidCharacterFailure is IllegalArgumentException)
    }

    @Test
    fun `number values must be finite`() {
        val failure = runCatching {
            AppActorAttributeValue.number(Double.NaN)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `values enforce backend scale limits before queueing`() {
        val longStringFailure = runCatching {
            AppActorAttributesValidation.validateValue(AppActorAttributeValue.string("x".repeat(1_025)))
        }.exceptionOrNull()
        assertTrue(longStringFailure is IllegalArgumentException)

        val longArrayFailure = runCatching {
            AppActorAttributesValidation.validateValue(AppActorAttributeValue.stringArray(List(21) { "v$it" }))
        }.exceptionOrNull()
        assertTrue(longArrayFailure is IllegalArgumentException)

        @Suppress("DEPRECATION")
        val dateArrayFailure = runCatching {
            AppActorAttributesValidation.validateValue(AppActorAttributeValue.dateArray(listOf(Date(0))))
        }.exceptionOrNull()
        assertTrue(dateArrayFailure is IllegalArgumentException)
    }

    @Test
    fun `integration identifiers validate type and value shape`() {
        assertEquals("appsflyer_id", AppActorAttributesValidation.normalizeIntegrationIdentifierType("appsflyer_id"))
        assertTrue(
            runCatching { AppActorAttributesValidation.normalizeIntegrationIdentifierType("bad key") }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { AppActorAttributesValidation.validateIntegrationIdentifierValue(" padded") }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { AppActorAttributesValidation.validateIntegrationIdentifierValue("x".repeat(1_025)) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `attribution canonical fields validate before sending`() {
        assertTrue(
            runCatching { AppActorAttribution(provider = "custom", providerName = " facebook") }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { AppActorAttribution(provider = "x".repeat(65), campaignName = "spring") }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { AppActorAttribution(provider = "custom", campaignName = "x".repeat(1_025)) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                AppActorAttribution(
                    provider = "custom",
                    metadata = mapOf("appactor.private" to AppActorAttributeValue.string("x")),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `profile helpers validate email and phone formats`() {
        assertEquals(Unit, AppActorAttributesValidation.validateEmail("user@example.com"))
        assertEquals(Unit, AppActorAttributesValidation.validatePhoneNumber("+15551234567"))

        assertTrue(
            runCatching { AppActorAttributesValidation.validateEmail("bad-email") }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { AppActorAttributesValidation.validatePhoneNumber("abc") }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }
}
