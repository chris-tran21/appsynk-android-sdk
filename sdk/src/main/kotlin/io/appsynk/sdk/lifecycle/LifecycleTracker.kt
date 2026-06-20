package io.appsynk.sdk.lifecycle

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.appsynk.sdk.collection.InstallReferrerCollector
import io.appsynk.sdk.collection.MetaReferrerCollector
import io.appsynk.sdk.config.AppSynkOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Emits the automatic lifecycle events — install / session_start / session_end / app_open /
 * app_update — and owns session state, via `ProcessLifecycleOwner` (app-wide foreground/background).
 *
 * Mirrors the iOS `LifecycleTracker`, minus reinstall detection: Android has no reliable
 * survives-uninstall flag (SharedPreferences are deliberately excluded from backup), so a fresh
 * install always emits `install` and reinstall detection is done server-side via device-fingerprint
 * matching (GAID / androidId). The install is gated on the async collection (Play referrer + GAID +
 * Meta) just like iOS gates on the ATT decision.
 *
 * @param track emits an event through the facade (folds user/sessionId into properties, enqueues).
 * @param awaitCollection suspends until the install-time collection completes or referrerWaitTimeout.
 */
class LifecycleTracker(
    context: Context,
    private val options: AppSynkOptions,
    private val scope: CoroutineScope,
    private val track: suspend (name: String, properties: Map<String, Any>) -> Unit,
    private val awaitCollection: suspend () -> Unit
) : DefaultLifecycleObserver {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sessionTimeoutMs = options.sessionTimeout * 1000L

    // Session state — accessed only under stateLock.
    private val stateLock = Any()
    private var sessionId = UUID.randomUUID().toString()
    private var sessionStartTime = System.currentTimeMillis()
    private var sessionNumber = 0
    private var backgroundedAt = 0L
    private var didStartFirstSession = false
    private var pendingOpenSource: String? = null

    // ── Facade surface ───────────────────────────────────────────────────────

    /** Current session id (thread-safe). The facade folds it into every event's properties. */
    val currentSessionId: String
        get() = synchronized(stateLock) { sessionId }

    /** Source attributed to the NEXT app_open (e.g. "deeplink"); otherwise "direct". */
    fun markOpenSource(source: String) {
        synchronized(stateLock) { pendingOpenSource = source }
    }

    /** Rotate the session (called from reset()); emits no event. */
    fun resetSession() {
        synchronized(stateLock) {
            sessionId = UUID.randomUUID().toString()
            sessionStartTime = System.currentTimeMillis()
        }
    }

    /** Registers the ProcessLifecycleOwner observer (on main) and runs the launch sequence. */
    fun start() {
        runOnMain {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
        scope.launch { trackLaunch() }
    }

    // ── ProcessLifecycleOwner: app-wide foreground / background (main thread) ──

    override fun onStart(owner: LifecycleOwner) {
        scope.launch { handleForeground() }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch { handleBackground() }
    }

    // ── Launch: install + app_update ──────────────────────────────────────────

    private suspend fun trackLaunch() {
        trackInstallIfNeeded()
        trackAppUpdateIfNeeded()
    }

    private suspend fun trackInstallIfNeeded() {
        if (prefs.contains(KEY_INSTALL_TRACKED)) return // already installed

        // Record the install date (epoch seconds) on first launch, for time/day_since_install.
        if (!prefs.contains(KEY_INSTALL_DATE)) {
            prefs.edit().putLong(KEY_INSTALL_DATE, System.currentTimeMillis() / 1000L).apply()
        }

        // Gate the install on the async collection (Play referrer + GAID + Meta), bounded by
        // referrerWaitTimeout — the Android analogue of iOS's ATT-decision gating.
        awaitCollection()

        track("install", buildInstallProps())

        // Persist the flag AFTER the enqueue so a kill mid-collection re-tries the install next
        // launch (same discipline as iOS — the install is never lost).
        prefs.edit().putBoolean(KEY_INSTALL_TRACKED, true).apply()
    }

    private suspend fun trackAppUpdateIfNeeded() {
        val current = appVersion()
        val stored = prefs.getString(KEY_APP_VERSION, null)
        if (stored != null && stored != current) {
            track("app_update", mapOf("previous_version" to stored, "new_version" to current))
        }
        prefs.edit().putString(KEY_APP_VERSION, current).apply()
    }

    /** Install properties: app version + the collected Play-referrer and Meta-referrer fields. */
    private fun buildInstallProps(): Map<String, Any> {
        val props = mutableMapOf<String, Any>("version" to appVersion())
        InstallReferrerCollector.cached()?.let { r ->
            props["referrer_click_ts"] = r.referrerClickTs
            props["install_begin_ts"] = r.installBeginTs
            props["referrer_click_ts_server"] = r.referrerClickTsServer
            props["install_begin_ts_server"] = r.installBeginTsServer
            r.installVersion?.let { props["install_version"] = it }
            props["google_play_instant"] = r.googlePlayInstant
        }
        MetaReferrerCollector.cached()?.let { m ->
            m.actualTimestamp?.let { props["meta_actual_timestamp"] = it }
            m.isClickThrough?.let { props["meta_is_ct"] = it }
        }
        return props
    }

    // ── Foreground / background handling ──────────────────────────────────────

    private suspend fun handleForeground() {
        // Compute the session_start (if any) + app_open under the lock; emit outside it (track is
        // suspend). ProcessLifecycleOwner's ON_START covers both cold launch and return-to-foreground.
        val toEmit = mutableListOf<Pair<String, Map<String, Any>>>()
        synchronized(stateLock) {
            if (!didStartFirstSession) {
                didStartFirstSession = true
                toEmit += beginSessionLocked() // cold launch → session 1
            } else {
                val inactivity = System.currentTimeMillis() - backgroundedAt
                backgroundedAt = 0L
                if (shouldStartNewSession(inactivity)) toEmit += beginSessionLocked()
            }
            toEmit += emitAppOpenLocked()
        }
        toEmit.forEach { (name, props) -> track(name, props) }
    }

    private suspend fun handleBackground() {
        val event = synchronized(stateLock) {
            backgroundedAt = System.currentTimeMillis()
            val durationSec = ((System.currentTimeMillis() - sessionStartTime) / 1000L).toInt()
            "session_end" to mapOf<String, Any>("session_duration_seconds" to durationSec)
        }
        // Enqueuing on background also schedules the WorkManager upload (EventQueue.enqueue), so a
        // session_end survives the process being killed shortly after backgrounding.
        track(event.first, event.second)
    }

    private fun shouldStartNewSession(inactivityMs: Long): Boolean = inactivityMs > sessionTimeoutMs

    // ── Session helpers (call under stateLock; return the event to emit) ──────

    private fun beginSessionLocked(): Pair<String, Map<String, Any>> {
        sessionId = UUID.randomUUID().toString()
        sessionStartTime = System.currentTimeMillis()
        sessionNumber += 1
        val props = mutableMapOf<String, Any>("session_id" to sessionId)
        val installDateSec = prefs.getLong(KEY_INSTALL_DATE, 0L)
        if (installDateSec > 0L) {
            val sinceInstallSec = (System.currentTimeMillis() / 1000L) - installDateSec
            props["time_since_install"] = sinceInstallSec.toInt()
            props["day_since_install"] = (sinceInstallSec / 86_400L).toInt()
        }
        return "session_start" to props
    }

    private fun emitAppOpenLocked(): Pair<String, Map<String, Any>> {
        val source = pendingOpenSource ?: "direct"
        pendingOpenSource = null
        return "app_open" to mapOf("source" to source, "session_number" to sessionNumber)
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    private fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0.0.0"
    }.getOrDefault("0.0.0")

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    companion object {
        private const val PREFS = "appsynk_prefs"
        private const val KEY_INSTALL_TRACKED = "install_tracked"
        private const val KEY_INSTALL_DATE = "appsynk_install_date"
        private const val KEY_APP_VERSION = "appsynk_app_version"
    }
}
