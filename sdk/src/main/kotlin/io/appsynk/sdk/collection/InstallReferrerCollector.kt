package io.appsynk.sdk.collection

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * The Google Play Install Referrer — all 7 fields.
 *
 * [referrerUrl] is the **RAW** referrer string (e.g. `utm_source=google-play&gclid=…&gbraid=…`),
 * sent verbatim so the backend `ReferrerParser` can extract gclid/gbraid/utm. The SDK must NOT
 * parse it.
 */
data class PlayReferrer(
    @SerializedName("referrerUrl")           val referrerUrl: String,
    @SerializedName("referrerClickTs")       val referrerClickTs: Long,
    @SerializedName("installBeginTs")        val installBeginTs: Long,
    @SerializedName("referrerClickTsServer") val referrerClickTsServer: Long,
    @SerializedName("installBeginTsServer")  val installBeginTsServer: Long,
    @SerializedName("installVersion")        val installVersion: String?,
    @SerializedName("googlePlayInstant")     val googlePlayInstant: Boolean
)

/**
 * Collects the Google Play Install Referrer via [InstallReferrerClient].
 *
 * The raw referrer string is preserved verbatim in [PlayReferrer.referrerUrl] — the SDK deliberately
 * does NOT parse out gclid/gbraid/utm. The backend `ReferrerParser` does (feeding Google App
 * Conversion attribution: `market_referrer_gclid` + `gbraid`). The previous implementation kept only
 * the `utm_*` parameters and dropped gclid/gbraid, losing Google Ads attribution — this is that fix.
 *
 * The Play referrer is reliably readable only once, so the first successful read is cached (in
 * memory + SharedPreferences) and returned on later calls without re-querying Play.
 */
object InstallReferrerCollector {
    private const val PREFS = "appsynk_referrer"
    private const val KEY = "play_referrer"
    private const val RETRY_DELAY_MS = 3_000L

    private val gson = Gson()
    private val mutex = Mutex()

    @Volatile
    private var memoryCache: PlayReferrer? = null

    /**
     * Returns the cached referrer, or fetches it from Play — retrying on `SERVICE_DISCONNECTED`
     * (up to [maxRetries], 3s apart) — and caches the first success.
     */
    suspend fun collect(context: Context, maxRetries: Int = 3): PlayReferrer? {
        memoryCache?.let { return it }
        val appContext = context.applicationContext
        return mutex.withLock {
            memoryCache?.let { return@withLock it }
            readCache(appContext)?.let { memoryCache = it; return@withLock it }
            val fresh = fetch(appContext, maxRetries)
            if (fresh != null) {
                writeCache(appContext, fresh)
                memoryCache = fresh
            }
            fresh
        }
    }

    /** Non-blocking peek at the cached referrer (null until the first successful collection). */
    fun cached(): PlayReferrer? = memoryCache

    private sealed class Attempt {
        /** OK (with the referrer) or a permanent failure (null) — do not retry. */
        data class Done(val referrer: PlayReferrer?) : Attempt()

        /** SERVICE_DISCONNECTED — retry after a delay. */
        object Retry : Attempt()
    }

    private suspend fun fetch(context: Context, maxRetries: Int): PlayReferrer? {
        repeat(maxRetries) { attempt ->
            when (val result = tryOnce(context)) {
                is Attempt.Done -> return result.referrer
                Attempt.Retry -> if (attempt < maxRetries - 1) delay(RETRY_DELAY_MS) else return null
            }
        }
        return null
    }

    private suspend fun tryOnce(context: Context): Attempt =
        suspendCancellableCoroutine { cont ->
            val client = InstallReferrerClient.newBuilder(context).build()
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val outcome: Attempt = try {
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> {
                                val d = client.installReferrer
                                Attempt.Done(
                                    PlayReferrer(
                                        referrerUrl = d.installReferrer,                 // RAW string
                                        referrerClickTs = d.referrerClickTimestampSeconds,
                                        installBeginTs = d.installBeginTimestampSeconds,
                                        referrerClickTsServer = d.referrerClickTimestampServerSeconds,
                                        installBeginTsServer = d.installBeginTimestampServerSeconds,
                                        installVersion = d.installVersion,
                                        googlePlayInstant = d.googlePlayInstantParam
                                    )
                                )
                            }
                            // FEATURE_NOT_SUPPORTED / SERVICE_UNAVAILABLE / DEVELOPER_ERROR → give up.
                            else -> Attempt.Done(null)
                        }
                    } catch (e: Exception) {
                        Attempt.Done(null)
                    } finally {
                        runCatching { client.endConnection() }
                    }
                    if (cont.isActive) cont.resume(outcome)
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // Connection dropped → clean up and signal a retry.
                    runCatching { client.endConnection() }
                    if (cont.isActive) cont.resume(Attempt.Retry)
                }
            })
            cont.invokeOnCancellation { runCatching { client.endConnection() } }
        }

    private fun readCache(context: Context): PlayReferrer? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return null
        return runCatching { gson.fromJson(json, PlayReferrer::class.java) }.getOrNull()
    }

    private fun writeCache(context: Context, referrer: PlayReferrer) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, gson.toJson(referrer)).apply()
    }
}
