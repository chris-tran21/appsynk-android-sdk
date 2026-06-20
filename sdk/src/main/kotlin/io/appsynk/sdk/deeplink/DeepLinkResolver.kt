package io.appsynk.sdk.deeplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.appsynk.sdk.config.AppSynkOptions
import io.appsynk.sdk.models.AttributionData
import io.appsynk.sdk.services.AttributionService
import io.appsynk.sdk.services.NetworkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Buffers deep-link attribution that resolves BEFORE a callback is registered, then replays it.
 *
 * Mirrors the iOS `DeepLinkBuffer` — the AppsFlyer "bridge ready" pattern that prevents lost deep
 * links at cold start (and that naive bridges get wrong). Groundwork for the RN/Flutter/Unity
 * bridges. Seeded with the last persisted attribution so a cold-start callback replays the deferred
 * deep link. Callbacks always fire on the main thread.
 */
class DeepLinkBuffer(seed: AttributionData?) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var resolved: AttributionData? = seed
    private val callbacks = mutableListOf<(AttributionData?) -> Unit>()

    /** Fire immediately (main thread) if already resolved; otherwise buffer until resolution. */
    fun register(callback: (AttributionData?) -> Unit) {
        val resolvedNow = synchronized(lock) {
            if (resolved == null) {
                callbacks.add(callback)
                null
            } else {
                resolved
            }
        }
        if (resolvedNow != null) mainHandler.post { callback(resolvedNow) }
    }

    /** Resolution arrived: store it and replay every buffered callback on the main thread. */
    fun deliver(data: AttributionData) {
        val pending = synchronized(lock) {
            resolved = data
            val snapshot = callbacks.toList()
            callbacks.clear()
            snapshot
        }
        mainHandler.post { pending.forEach { it(data) } }
    }

    /** Current best-known attribution (does not buffer); completion fires on the main thread. */
    fun current(completion: (AttributionData?) -> Unit) {
        val snapshot = synchronized(lock) { resolved }
        mainHandler.post { completion(snapshot) }
    }
}

/**
 * Deep linking: App Link + custom-scheme handling, backend resolution, buffering/replay, attribution
 * access, and re-engagement tracking. Mirrors the iOS `DeepLinkResolver`.
 *
 * @param track emits an event through the facade (folds user/sessionId, enqueues).
 * @param markOpenSource attributes the next app_open to the deep link (LifecycleTracker).
 */
class DeepLinkResolver(
    context: Context,
    private val options: AppSynkOptions,
    private val network: NetworkService,
    private val attributionStore: AttributionService,
    private val scope: CoroutineScope,
    private val track: suspend (name: String, properties: Map<String, Any>) -> Unit,
    private val markOpenSource: (String) -> Unit
) {
    // Seed with the last resolved deep link so a cold-start callback replays it (deferred deep link).
    // AttributionService is the single attribution store — no separate deep-link prefs, so reset()
    // (which calls attributionStore.clearAttribution()) also clears the deep-link attribution.
    private val buffer = DeepLinkBuffer(seed = attributionStore.getAttribution())

    // ── Entry points ───────────────────────────────────────────────────────────

    /**
     * Handle an incoming Intent. Supports the custom scheme `appsynk://{linkId}` and the App Link
     * `https://go.appsynk.io/{linkId}`. No-op for unrelated intents.
     */
    fun handleIntent(intent: Intent) {
        val uri: Uri = intent.data ?: return
        val linkId = linkId(uri) ?: return
        scope.launch { resolve(linkId) }
    }

    // ── Callbacks ──────────────────────────────────────────────────────────────

    /** Deep-link attribution, replayed if it resolved before this call (fires on the main thread). */
    fun getDeepLinkData(callback: (AttributionData?) -> Unit) = buffer.register(callback)

    /** Best-known attribution (deep link if any, else organic). Always fires, on the main thread. */
    fun getAttributionData(callback: (AttributionData?) -> Unit) =
        buffer.current { resolved -> callback(resolved ?: AttributionData.ORGANIC) }

    // ── Resolution ─────────────────────────────────────────────────────────────

    private suspend fun resolve(linkId: String) {
        val attribution = runCatching { network.resolveLinkAttribution(linkId) }.getOrNull()
        if (attribution == null) {
            log("Deep link resolution failed for $linkId")
            return
        }
        attributionStore.storeAttribution(attribution)   // sole store: event enrichment + cold-start replay
        buffer.deliver(attribution)                        // replay to buffered callbacks
        markOpenSource("deeplink")                         // attribute the next app_open to the link

        // Re-engagement: record the deep-link open in the events pipeline.
        track(
            "deeplink",
            mapOf(
                "link_id" to linkId,
                "channel" to (attribution.channel ?: "unknown"),
                "campaign" to (attribution.campaignName ?: ""),
                "deep_link" to (attribution.deepLink ?: "")
            )
        )
        log("Deep link resolved: $linkId channel=${attribution.channel}")
    }

    // ── URL parsing ────────────────────────────────────────────────────────────

    /** Extracts the tracking linkId from an `appsynk://` custom scheme or a `go.appsynk.io` link. */
    private fun linkId(uri: Uri): String? = when {
        uri.scheme == CUSTOM_SCHEME ->
            uri.host?.takeIf { it.isNotEmpty() } ?: uri.pathSegments.firstOrNull()
        uri.host == UNIVERSAL_HOST ->
            uri.lastPathSegment?.takeIf { it.isNotEmpty() && it != "/" }
        else -> null
    }?.takeIf { it.isNotEmpty() }

    private fun log(message: String) {
        if (options.logLevel != AppSynkOptions.LogLevel.NONE) {
            android.util.Log.d("AppSynk", message)
        }
    }

    companion object {
        const val UNIVERSAL_HOST = "go.appsynk.io"
        const val CUSTOM_SCHEME = "appsynk"
    }
}
