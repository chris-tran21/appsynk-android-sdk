package io.appsynk.sdk.config

import io.appsynk.sdk.core.AppSynkConstants

/**
 * Configuration options for the AppSynk Android SDK.
 *
 * Mirrors the iOS `AppSynkOptions` — same field names, defaults and toggles — so the
 * React Native / Flutter / Unity bridges stay trivial. The Android-specific privacy toggles
 * ([disableGaid], [disableAndroidId], [disableMetaReferrer]) stand in for the iOS
 * `disableIdfa` / `disableIdfv` / `disableAdServices`, and [referrerWaitTimeout] is the Android
 * analogue of the iOS `attWaitTimeout`.
 */
data class AppSynkOptions(
    /** Controls SDK console logging verbosity. */
    val logLevel: LogLevel = LogLevel.NONE,

    /** API environment. Use [Environment.SANDBOX] (with an `ak_sandbox_` key) for testing. */
    val environment: Environment = Environment.PRODUCTION,

    /**
     * Custom API base URL. Overrides the environment host — e.g. for data residency or a
     * self-hosted gateway (à la AppsFlyer `setHost`). Leave null to use the default endpoint.
     */
    val customApiUrl: String? = null,

    /** Seconds of inactivity after which a resumed app starts a new session. */
    val sessionTimeout: Long = 1800L,

    /** Number of events to accumulate before flushing to the API. */
    val batchSize: Int = 10,

    /** Seconds between automatic flushes, even when [batchSize] hasn't been reached. */
    val flushInterval: Long = 30L,

    /** Flush events while backgrounded using a WorkManager task. */
    val sendInBackground: Boolean = true,

    /**
     * Seconds the SDK waits for the Google Play Install Referrer and GAID collection to finish
     * before sending the gated `install` event — the Android analogue of the iOS `attWaitTimeout`.
     * The install fires once collection completes OR this timeout elapses, whichever comes first,
     * so it carries `gclid`/`gbraid` and the GAID whenever they are available.
     */
    val referrerWaitTimeout: Long = 10L,

    // ── Privacy toggles (all opt-out, default false) ─────────────────────────────

    /** Never read or send the Google Advertising ID (GAID). */
    val disableGaid: Boolean = false,

    /** Never read or send the `Settings.Secure` ANDROID_ID. */
    val disableAndroidId: Boolean = false,

    /** Never query the Meta (Facebook / Instagram) Install Referrer content provider. */
    val disableMetaReferrer: Boolean = false,

    /** Start every install in anonymized mode (identifiers stripped) until consent is granted. */
    val anonymizeUserByDefault: Boolean = false,

    // ── Request signing (optional HMAC-SHA256) ───────────────────────────────────

    /** Shared secret for HMAC-SHA256 request signing. Null disables signing. */
    val hmacSecretKey: String? = null,

    /** Key identifier sent with the signature so the backend can select the matching secret. */
    val hmacKeyId: String? = null
) {
    enum class LogLevel {
        NONE,
        DEBUG,
        VERBOSE
    }

    /**
     * API environment. Production and sandbox share the same host: the backend detects sandbox
     * mode from the API key prefix (`ak_sandbox_` / `ak_live_`), not from the URL.
     */
    enum class Environment(val baseUrl: String) {
        PRODUCTION(AppSynkConstants.PRODUCTION_BASE_URL),
        SANDBOX(AppSynkConstants.SANDBOX_BASE_URL)
    }
}
