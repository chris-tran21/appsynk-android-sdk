package io.appsynk.sdk.collection

import android.content.Context
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Google Advertising ID (GAID) provider — the Android analogue of the iOS IDFA.
 *
 * Off-main and defensive: the lookup runs on [Dispatchers.IO] and returns null when ad tracking is
 * limited, Play Services is unavailable, or on any error — never throws, never blocks the main
 * thread. The first result is cached for the process lifetime so events read it without re-querying
 * Play Services; [cached] is the non-blocking peek used when building events.
 */
object GaidProvider {

    @Volatile
    private var cachedValue: String? = null

    @Volatile
    private var fetched = false

    private val mutex = Mutex()

    /** Non-blocking peek at the resolved GAID (null if not yet fetched, disabled, or unavailable). */
    fun cached(): String? = cachedValue

    /**
     * Resolve the GAID, caching the first result.
     *
     * @return the GAID, or null when ad tracking is limited / Play Services is missing / an error
     *   occurs. Respecting `isLimitAdTrackingEnabled` is required by Google Play policy.
     */
    suspend fun gaid(context: Context): String? {
        if (fetched) return cachedValue
        val appContext = context.applicationContext
        return mutex.withLock {
            if (fetched) return@withLock cachedValue
            val value = fetch(appContext)
            cachedValue = value
            fetched = true
            value
        }
    }

    private suspend fun fetch(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
            if (info.isLimitAdTrackingEnabled) null else info.id
        } catch (e: Exception) {
            // GooglePlayServicesNotAvailableException, GooglePlayServicesRepairableException,
            // IOException, IllegalStateException… — all mean "no GAID", never a crash.
            null
        }
    }
}
