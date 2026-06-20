package io.appsynk.sdk

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.appsynk.sdk.collection.DeviceDataCollector
import io.appsynk.sdk.collection.GaidProvider
import io.appsynk.sdk.collection.InstallReferrerCollector
import io.appsynk.sdk.collection.MetaReferrer
import io.appsynk.sdk.collection.MetaReferrerCollector
import io.appsynk.sdk.collection.PlayReferrer
import io.appsynk.sdk.config.AppSynkOptions
import io.appsynk.sdk.core.AppSynkConstants
import io.appsynk.sdk.core.DeviceIdentity
import io.appsynk.sdk.deeplink.DeepLinkResolver
import io.appsynk.sdk.lifecycle.LifecycleTracker
import io.appsynk.sdk.models.AppSynkEvent
import io.appsynk.sdk.models.AttributionData
import io.appsynk.sdk.privacy.PrivacyManager
import io.appsynk.sdk.queue.EventQueue
import io.appsynk.sdk.services.AttributionService
import io.appsynk.sdk.services.NetworkError
import io.appsynk.sdk.services.NetworkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.UUID

/**
 * Main entry point for the AppSynk Android SDK.
 *
 * Usage:
 * ```kotlin
 * AppSynkSDK.configure(context, apiKey = "YOUR_KEY")
 * AppSynkSDK.trackEvent("level_complete", mapOf("level" to 5))
 * AppSynkSDK.trackRevenue(4.99, "USD", "premium_monthly", orderId = "GPA.123")
 * ```
 */
object AppSynkSDK {

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal var isConfigured = false
    private lateinit var applicationContext: Context
    private lateinit var options: AppSynkOptions
    private lateinit var eventQueue: EventQueue
    private lateinit var attributionService: AttributionService
    private lateinit var deviceCollector: DeviceDataCollector
    private lateinit var privacy: PrivacyManager
    private lateinit var lifecycle: LifecycleTracker
    private lateinit var deepLinkResolver: DeepLinkResolver
    internal lateinit var networkService: NetworkService

    // Collectors warmed in parallel at configure; the install is gated on these (or referrerWaitTimeout).
    @Volatile private var referrerWarmup: Deferred<PlayReferrer?>? = null
    @Volatile private var gaidWarmup: Deferred<String?>? = null
    @Volatile private var metaWarmup: Deferred<MetaReferrer?>? = null

    private val gson = Gson()

    // User identity is mutated from caller threads (setUserId/setUserProperties/reset) and read from
    // the IO dispatcher (buildEvent); guard both with a dedicated lock to avoid ConcurrentModification.
    private val userLock = Any()
    private var userId: String? = null
    private val userProperties: MutableMap<String, Any> = mutableMapOf()

    /**
     * Initialize the AppSynk SDK. Call once from Application.onCreate().
     */
    @JvmStatic
    @Synchronized
    fun configure(
        context: Context,
        apiKey: String,
        options: AppSynkOptions = AppSynkOptions()
    ) {
        if (isConfigured) return

        applicationContext = context.applicationContext
        this.options = options

        networkService = NetworkService(apiKey = apiKey, options = options)
        attributionService = AttributionService(applicationContext)
        deviceCollector = DeviceDataCollector(applicationContext, options)
        privacy = PrivacyManager(applicationContext, options.anonymizeUserByDefault)
        eventQueue = EventQueue(applicationContext, networkService, options)

        // Restore persisted user identity (mutate the in-memory copy under the lock).
        val userPrefs = applicationContext.getSharedPreferences("appsynk_user", Context.MODE_PRIVATE)
        val restoredUserId = userPrefs.getString("userId", null)
        val restoredProps = userPrefs.getString("userProps", null)?.let { json ->
            val type = object : TypeToken<Map<String, Any>>() {}.type
            runCatching { gson.fromJson<Map<String, Any>>(json, type) }.getOrNull()
        }
        synchronized(userLock) {
            userId = restoredUserId
            restoredProps?.let { userProperties.putAll(it) }
        }

        isConfigured = true

        if (options.logLevel != AppSynkOptions.LogLevel.NONE) {
            android.util.Log.i("AppSynk", "SDK configured. Environment: ${options.environment}")
        }

        // Reload any events persisted by a previous launch, then start the periodic flush timer.
        scope.launch { eventQueue.restore() }
        scope.launch {
            while (true) {
                delay(options.flushInterval * 1000L)
                eventQueue.flush()
            }
        }

        // Warm the attribution collectors in parallel (Play Install Referrer + GAID). The install is
        // gated on these completing — or referrerWaitTimeout — so it carries gclid/gbraid + the GAID.
        // The warmups keep running past the timeout, so a slow Play service never blocks the app.
        referrerWarmup = scope.async { InstallReferrerCollector.collect(applicationContext) }
        gaidWarmup = scope.async { if (options.disableGaid) null else GaidProvider.gaid(applicationContext) }
        metaWarmup = scope.async { if (options.disableMetaReferrer) null else MetaReferrerCollector.collect(applicationContext) }

        // Automatic lifecycle events (install / session / app_open / app_update) via
        // ProcessLifecycleOwner. The install is gated on the warmups above (awaitCollection).
        lifecycle = LifecycleTracker(
            context = applicationContext,
            options = options,
            scope = scope,
            track = { name, props -> eventQueue.enqueue(buildEvent(name, props)) },
            awaitCollection = ::awaitCollection
        )
        lifecycle.start()

        // Deep linking (App Links + custom scheme) with the cold-start buffer/replay pattern.
        deepLinkResolver = DeepLinkResolver(
            context = applicationContext,
            options = options,
            network = networkService,
            attributionStore = attributionService,
            scope = scope,
            track = { name, props -> eventQueue.enqueue(buildEvent(name, props)) },
            markOpenSource = lifecycle::markOpenSource
        )

        // Validate the API key + bundleId against the backend (non-blocking, best-effort).
        validateConfiguration()
    }

    /**
     * Track a custom event.
     */
    @JvmStatic
    @JvmOverloads
    fun trackEvent(eventName: String, properties: Map<String, Any> = emptyMap()) {
        requireConfigured()
        scope.launch {
            val event = buildEvent(eventName, properties)
            eventQueue.enqueue(event)
        }
    }

    /**
     * Track a purchase event. orderId is required for 24-hour deduplication.
     * Duplicate orderIds within 24 hours are silently dropped.
     */
    @JvmStatic
    fun trackRevenue(
        amount: Double,
        currency: String,
        productId: String,
        orderId: String
    ) {
        requireConfigured()
        val dedupPrefs = applicationContext
            .getSharedPreferences("appsynk_dedup", Context.MODE_PRIVATE)
        val storedEpoch = dedupPrefs.getLong(orderId, 0L)
        if (storedEpoch > 0L && System.currentTimeMillis() - storedEpoch < 86_400_000L) {
            if (options.logLevel != AppSynkOptions.LogLevel.NONE) {
                android.util.Log.d("AppSynk", "Skipping duplicate purchase — orderId already seen: $orderId")
            }
            return
        }
        dedupPrefs.edit().putLong(orderId, System.currentTimeMillis()).apply()

        trackEvent("purchase", mapOf(
            "amount"     to amount,
            "currency"   to currency,
            "product_id" to productId,
            "order_id"   to orderId
        ))
    }

    /**
     * Track an ad impression revenue event.
     */
    @JvmStatic
    @JvmOverloads
    fun trackAdRevenue(
        network: String,
        amount: Double,
        currency: String,
        adUnit: String? = null,
        adType: String? = null
    ) {
        val props = mutableMapOf<String, Any>(
            "network"  to network,
            "amount"   to amount,
            "currency" to currency
        )
        adUnit?.let { props["ad_unit"] = it }
        adType?.let { props["ad_type"] = it }
        trackEvent("ad_revenue", props)
    }

    /**
     * Persist a user identifier. Included in every subsequent event.
     * Survives app restarts until reset() is called.
     */
    @JvmStatic
    fun setUserId(userId: String) {
        requireConfigured()
        synchronized(userLock) { this.userId = userId }
        applicationContext.getSharedPreferences("appsynk_user", Context.MODE_PRIVATE)
            .edit().putString("userId", userId).apply()
    }

    /**
     * Merge additional user properties. Persisted across app restarts.
     * Attached to every subsequent event, folded into `properties.user`.
     */
    @JvmStatic
    fun setUserProperties(properties: Map<String, Any>) {
        requireConfigured()
        synchronized(userLock) { userProperties.putAll(properties) }
        val prefs = applicationContext.getSharedPreferences("appsynk_user", Context.MODE_PRIVATE)
        val merged: MutableMap<String, Any> = mutableMapOf()
        prefs.getString("userProps", null)?.let { json ->
            val type = object : TypeToken<Map<String, Any>>() {}.type
            runCatching { gson.fromJson<Map<String, Any>>(json, type) }
                .getOrNull()?.let { merged.putAll(it) }
        }
        merged.putAll(properties)
        prefs.edit().putString("userProps", gson.toJson(merged)).apply()
    }

    /**
     * Record the user's GDPR consent. Persisted and attached to every subsequent event's root
     * `consent` block (the backend copies it into the attribution for postback decisions).
     */
    @JvmStatic
    fun setConsent(
        isUserSubjectToGDPR: Boolean,
        hasConsentForDataUsage: Boolean,
        hasConsentForAdsPersonalization: Boolean
    ) {
        requireConfigured()
        privacy.setConsent(isUserSubjectToGDPR, hasConsentForDataUsage, hasConsentForAdsPersonalization)
    }

    /**
     * Enable or disable anonymized mode. When enabled, every event strips device identifiers
     * (gaid / androidId) and carries `isAnonymized = true`. Persisted across launches.
     */
    @JvmStatic
    fun anonymizeUser(enabled: Boolean) {
        requireConfigured()
        privacy.setAnonymized(enabled)
    }

    /**
     * Reset user identity and properties. Call on logout.
     * Clears userId and userProperties, but preserves the stable device_id.
     */
    @JvmStatic
    fun reset() {
        synchronized(userLock) {
            userId = null
            userProperties.clear()
        }
        // Clear user identity — intentionally NOT clearing appsynk_prefs (device_id lives there)
        if (isConfigured) {
            applicationContext.getSharedPreferences("appsynk_user", Context.MODE_PRIVATE)
                .edit().clear().apply()
            attributionService.clearAttribution()
            lifecycle.resetSession()
        }
    }

    /**
     * Flush all queued events to the API immediately.
     * Useful to call before the app is killed (e.g., in onTerminate).
     */
    @JvmStatic
    fun flush() {
        if (!isConfigured) return
        scope.launch { eventQueue.flush() }
    }

    /**
     * Call from Activity.onNewIntent() or onCreate() to handle AppSynk deep links.
     * Supports custom scheme:  appsynk://{linkId}
     * Supports App Links:      https://go.appsynk.io/{linkId}
     */
    @JvmStatic
    fun handleIntent(intent: Intent) {
        if (!isConfigured) return
        deepLinkResolver.handleIntent(intent)
    }

    /**
     * Register a callback to receive deep link attribution data. Replayed if the link resolved
     * before this call (buffer/replay); always fires on the main thread.
     */
    @JvmStatic
    fun getDeepLinkData(callback: (AttributionData?) -> Unit) {
        if (!isConfigured) return
        deepLinkResolver.getDeepLinkData(callback)
    }

    /**
     * Best-known attribution (the resolved deep link if any, else organic). Always fires, on the
     * main thread.
     */
    @JvmStatic
    fun getAttributionData(callback: (AttributionData?) -> Unit) {
        if (!isConfigured) return
        deepLinkResolver.getAttributionData(callback)
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun buildEvent(eventName: String, properties: Map<String, Any>): AppSynkEvent {
        // Fold user identity + session INTO properties — the backend has no top-level user/session
        // fields (they would be silently ignored), so we mirror iOS and nest them here.
        // Snapshot user identity under the lock (mutated from other threads), then fold into properties.
        val (snapUserId, snapUserProps) = synchronized(userLock) {
            userId to userProperties.toMutableMap()
        }
        val enrichedProperties: MutableMap<String, Any> = properties.toMutableMap()
        val userDict = mutableMapOf<String, Any>()
        userDict.putAll(snapUserProps)
        snapUserId?.let { userDict["id"] = it }
        if (userDict.isNotEmpty()) enrichedProperties["user"] = userDict
        enrichedProperties["sessionId"] = lifecycle.currentSessionId

        // Device + attribution block: the GAID (warmed cache) is injected into collect, which also
        // fills androidId (idfa/idfv stay null on Android). Overlay the raw Play referrer URL + the
        // resolved deep-link clickId. Every read here is non-blocking (the collectors are warmed).
        val anonymized = privacy.isAnonymized
        val deviceData = deviceCollector.collect(gaid = GaidProvider.cached())
        var attribution = deviceData.attribution.copy(
            referrerUrl = InstallReferrerCollector.cached()?.referrerUrl,
            metaInstallReferrer = MetaReferrerCollector.cached()?.installReferrer,
            clickId = attributionService.clickId
        )
        if (anonymized) attribution = attribution.anonymizedCopy()

        return AppSynkEvent(
            deviceId    = DeviceIdentity.installInstanceId(applicationContext),
            appId       = applicationContext.packageName,
            eventName   = eventName,
            timestamp   = Date(),
            platform    = AppSynkConstants.PLATFORM,
            osVersion   = android.os.Build.VERSION.RELEASE,
            appVersion  = getAppVersion(),
            device      = deviceData.device,
            attribution = attribution,
            properties  = enrichedProperties,
            consent     = privacy.consent,
            isAnonymized = anonymized
            // clientEventId defaults to a fresh UUID (idempotency key).
        )
    }

    /**
     * Gate on the install-time collection (Play referrer + GAID + Meta), bounded by
     * referrerWaitTimeout. The warmups keep running past the timeout (separate coroutines), so a slow
     * Play service never blocks the app. Used by [LifecycleTracker] before it emits the install.
     */
    private suspend fun awaitCollection() {
        withTimeoutOrNull(options.referrerWaitTimeout * 1000L) {
            referrerWarmup?.await()
            gaidWarmup?.await()
            metaWarmup?.await()
        }
    }

    private fun getAppVersion(): String = try {
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0)
            .versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    /**
     * Validate the API key + bundleId against GET /v1/sdk/init. Non-blocking and best-effort: logs
     * the plan/environment on success, a clear warning on 401 (bad key) / 404 (bundleId not
     * registered, or live/sandbox key mismatch). Never crashes the host app.
     */
    private fun validateConfiguration() {
        scope.launch {
            runCatching { networkService.sdkInit(applicationContext.packageName) }
                .onSuccess { resp ->
                    if (options.logLevel != AppSynkOptions.LogLevel.NONE) {
                        android.util.Log.i(
                            "AppSynk",
                            "SDK init OK — app=${resp.appName}, plan=${resp.plan}, environment=${resp.environment}"
                        )
                    }
                }
                .onFailure { e ->
                    when {
                        e is NetworkError.Unauthorized ->
                            android.util.Log.w("AppSynk", "SDK init: invalid API key (401). Check your AppSynk API key.")
                        e is NetworkError.ServerError && e.statusCode == 404 ->
                            android.util.Log.w(
                                "AppSynk",
                                "SDK init: bundleId '${applicationContext.packageName}' not registered for this key. " +
                                    "Check the bundleId in the AppSynk dashboard and that you're using the matching live/sandbox key."
                            )
                        else ->
                            if (options.logLevel != AppSynkOptions.LogLevel.NONE)
                                android.util.Log.d("AppSynk", "SDK init check skipped (${e.message})")
                    }
                }
        }
    }

    private fun requireConfigured() {
        check(isConfigured) {
            "AppSynkSDK not configured. Call AppSynkSDK.configure() in Application.onCreate() first."
        }
    }

    // ── Debug Interface ──────────────────────────────────────────────────────

    /** Debug utilities for integration testing. Not for production use. */
    object debug {

        /** Simulate an install event with hardcoded source/campaign. */
        @JvmStatic
        @JvmOverloads
        fun simulateInstall(source: String = "debug", campaign: String = "test_campaign") {
            AppSynkSDK.trackEvent("install", mapOf(
                "referrer"  to "direct",
                "source"    to source,
                "campaign"  to campaign,
                "is_debug"  to true
            ))
            android.util.Log.d("AppSynk.debug", "Simulated install — source: $source, campaign: $campaign")
        }

        /** Simulate a revenue event with a random orderId (bypasses deduplication). */
        @JvmStatic
        @JvmOverloads
        fun simulateRevenue(amount: Double = 0.99, currency: String = "USD") {
            val orderId = UUID.randomUUID().toString()
            AppSynkSDK.trackRevenue(
                amount     = amount,
                currency   = currency,
                productId  = "debug.product.001",
                orderId    = orderId
            )
            android.util.Log.d("AppSynk.debug", "Simulated revenue — amount: $amount $currency, orderId: $orderId")
        }

        /** Verify network connectivity and API key validity. */
        @JvmStatic
        fun testConnection(callback: (Result<String>) -> Unit) {
            check(isConfigured) { "AppSynkSDK not configured." }
            scope.launch {
                callback(runCatching { networkService.ping().let { "${it.status} (${it.environment})" } })
            }
        }
    }
}
