package io.appsynk.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import io.appsynk.sdk.models.AppSynkEvent
import io.appsynk.sdk.models.AttributionInfo
import io.appsynk.sdk.models.ConsentPayload
import io.appsynk.sdk.models.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure-JVM wire-format tests: the camelCase contract, UTC timestamp, consent omission, folding. */
class EventWireFormatTest {

    // Same UTC-locked Date adapter the production NetworkService uses.
    private fun gson(): Gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, JsonSerializer<Date> { src, _, _ ->
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            JsonPrimitive(fmt.format(src))
        })
        .create()

    private fun sampleEvent(consent: ConsentPayload? = null, anonymized: Boolean = false) = AppSynkEvent(
        deviceId = "dev-1",
        appId = "com.acme.app",
        eventName = "purchase",
        timestamp = Date(1718791200000L), // 2024-06-19T10:00:00Z
        platform = "android",
        osVersion = "14",
        appVersion = "1.2.0",
        device = DeviceInfo(
            "Pixel 7", "Google", "phone", "fr_FR", "Europe/Paris",
            "wifi", "1080x2400", "Orange", 87, 3, true
        ),
        attribution = AttributionInfo(gaid = "g-1", androidId = "a-1"),
        properties = mapOf("level" to 5, "user" to mapOf("id" to "u-1"), "sessionId" to "s-1"),
        consent = consent,
        isAnonymized = anonymized
    )

    @Test
    fun topLevelKeysMatchContract() {
        val json = JsonParser.parseString(gson().toJson(sampleEvent())).asJsonObject
        // consent is omitted when null (the only optional top-level key).
        assertEquals(
            setOf(
                "clientEventId", "deviceId", "appId", "eventName", "timestamp",
                "platform", "osVersion", "appVersion", "device", "attribution",
                "properties", "isAnonymized"
            ),
            json.keySet()
        )
    }

    @Test
    fun timestampIsRealUtc() {
        val json = JsonParser.parseString(gson().toJson(sampleEvent())).asJsonObject
        assertEquals("2024-06-19T10:00:00.000Z", json["timestamp"].asString)
    }

    @Test
    fun userAndSessionAreFoldedIntoProperties() {
        val props = JsonParser.parseString(gson().toJson(sampleEvent())).asJsonObject["properties"].asJsonObject
        assertEquals("u-1", props["user"].asJsonObject["id"].asString)
        assertEquals("s-1", props["sessionId"].asString)
    }

    @Test
    fun consentBlockPresentWhenSet() {
        val consent = ConsentPayload(true, true, false, "2026-06-19T00:00:00.000Z")
        val json = JsonParser.parseString(gson().toJson(sampleEvent(consent = consent))).asJsonObject
        assertTrue(json.has("consent"))
        val c = json["consent"].asJsonObject
        assertEquals(true, c["isUserSubjectToGDPR"].asBoolean)
        assertEquals(false, c["hasConsentForAdsPersonalization"].asBoolean)
    }

    @Test
    fun clientEventIdIsGenerated() {
        assertTrue(sampleEvent().clientEventId.isNotEmpty())
    }

    @Test
    fun subObjectsUseDeviceAndAttributionKeys() {
        val json = JsonParser.parseString(gson().toJson(sampleEvent())).asJsonObject
        assertTrue(json.has("device"))
        assertTrue(json.has("attribution"))
        assertEquals("Pixel 7", json["device"].asJsonObject["model"].asString)
        assertEquals("g-1", json["attribution"].asJsonObject["gaid"].asString)
    }
}
