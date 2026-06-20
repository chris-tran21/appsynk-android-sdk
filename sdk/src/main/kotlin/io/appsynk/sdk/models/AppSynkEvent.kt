package io.appsynk.sdk.models

import com.google.gson.annotations.SerializedName
import java.util.Date
import java.util.UUID

/**
 * A single tracked event, serialized to the backend's exact wire format
 * (`AppSynk.Api` `EventIngestionRequest`). Field order and `@SerializedName` keys mirror the iOS
 * SDK and the C# contract verbatim — camelCase, no key transformation.
 *
 * Note: there are **no** top-level `user` / `userId` / `sessionId` fields — the backend has no such
 * columns. User identity and the session id are folded INTO [properties] when the event is built
 * (`properties.user = { …profile, id }`, `properties.sessionId`).
 */
data class AppSynkEvent(
    /**
     * Client-generated id for idempotent dedup. Generated once per event and stable across retries
     * (the queued event carries it), so the backend drops duplicates (24h cache keyed on this id).
     */
    @SerializedName("clientEventId") val clientEventId: String = UUID.randomUUID().toString(),
    @SerializedName("deviceId")      val deviceId: String,
    @SerializedName("appId")         val appId: String,
    @SerializedName("eventName")     val eventName: String,
    @SerializedName("timestamp")     val timestamp: Date,
    @SerializedName("platform")      val platform: String,
    @SerializedName("osVersion")     val osVersion: String,
    @SerializedName("appVersion")    val appVersion: String,
    @SerializedName("device")        val device: DeviceInfo,
    @SerializedName("attribution")   val attribution: AttributionInfo,
    /** Heterogeneous event properties — also carries the folded `user` and `sessionId`. */
    @SerializedName("properties")    val properties: Map<String, Any>,
    /** GDPR consent block; omitted from the JSON when null (Gson skips null fields by default). */
    @SerializedName("consent")       val consent: ConsentPayload? = null,
    /** True when identifiers were stripped SDK-side (anonymized mode). */
    @SerializedName("isAnonymized")  val isAnonymized: Boolean = false
)

/**
 * GDPR consent block sent at the event root (wire block "consent").
 *
 * Mirrors the backend `ConsentPayload` and the iOS `ConsentPayload`. The backend copies these flags
 * into `AttributionInfo` for postback decisions.
 */
data class ConsentPayload(
    @SerializedName("isUserSubjectToGDPR")             val isUserSubjectToGDPR: Boolean,
    @SerializedName("hasConsentForDataUsage")          val hasConsentForDataUsage: Boolean,
    @SerializedName("hasConsentForAdsPersonalization") val hasConsentForAdsPersonalization: Boolean,
    /** ISO 8601 UTC timestamp of when consent was recorded; omitted when null. */
    @SerializedName("consentTimestamp")                val consentTimestamp: String? = null
)

/**
 * Device hardware, locale and connectivity information — the event `device` block.
 *
 * Mirrors `AppSynk.Core.Models.DeviceInfo`. Populated by
 * [io.appsynk.sdk.collection.DeviceDataCollector]; `screenDensity` and `batteryLevel` double as
 * fraud-detection signals.
 */
data class DeviceInfo(
    @SerializedName("model")            val model: String,
    @SerializedName("manufacturer")     val manufacturer: String,
    @SerializedName("deviceType")       val deviceType: String,
    @SerializedName("locale")           val locale: String,
    @SerializedName("timezone")         val timezone: String,
    /** wifi | cellular | ethernet | other | disconnected | unknown. */
    @SerializedName("networkType")      val networkType: String,
    @SerializedName("screenResolution") val screenResolution: String,
    /** Mobile carrier name; null when unavailable (Wi-Fi-only / no SIM / emulator). */
    @SerializedName("carrier")          val carrier: String? = null,
    /** 0–100, or -1 if unavailable (e.g. emulator without battery simulation). */
    @SerializedName("batteryLevel")     val batteryLevel: Int,
    /** densityDpi / 160, rounded (~2 or 3) — the iOS-symmetric scale factor. 0 = not reported. */
    @SerializedName("screenDensity")    val screenDensity: Int,
    /** False on Wi-Fi-only tablets and emulators — combined with other signals for fraud detection. */
    @SerializedName("hasTelephony")     val hasTelephony: Boolean
)

/**
 * Attribution identifiers attached to each event — the event `attribution` block.
 *
 * Mirrors `AppSynk.Core.Models.AttributionInfo`. On Android `idfa`/`idfv` are always null (kept for
 * wire-format symmetry with iOS); `gaid`/`androidId` carry the device identifiers. `ipAddress` and
 * `userAgent` are intentionally absent — the backend fills them from the HTTP request.
 */
data class AttributionInfo(
    /** iOS only — always null on Android (kept for wire-format symmetry). */
    @SerializedName("idfa")      val idfa: String? = null,
    /** iOS only — always null on Android (kept for wire-format symmetry). */
    @SerializedName("idfv")      val idfv: String? = null,
    /** Google Advertising ID — null when the user limits ad tracking or Play Services is absent. */
    @SerializedName("gaid")      val gaid: String? = null,
    /** Settings.Secure ANDROID_ID — deterministic backup identifier. */
    @SerializedName("androidId") val androidId: String? = null,
    @SerializedName("clickId")   val clickId: String? = null,
    /** Raw Play Store referrer URL (utm_source=…&gclid=…&gbraid=…) — backend parses gclid/gbraid/utm. */
    @SerializedName("referrerUrl") val referrerUrl: String? = null,
    /** Meta (FB/IG) Install Referrer — raw value (encrypted or not); backend decrypts for view-through. */
    @SerializedName("metaInstallReferrer") val metaInstallReferrer: String? = null
) {
    /**
     * A copy with personal device identifiers stripped — used in anonymized mode. Non-identifying
     * attribution (referrerUrl / metaInstallReferrer / clickId) is preserved; the event root
     * `isAnonymized` flag is set separately.
     */
    fun anonymizedCopy(): AttributionInfo = copy(idfa = null, idfv = null, gaid = null, androidId = null)
}
