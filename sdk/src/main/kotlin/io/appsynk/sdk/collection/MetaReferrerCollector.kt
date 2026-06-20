package io.appsynk.sdk.collection

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Meta (Facebook / Instagram) Install Referrer read from the FB/IG content providers.
 *
 * [installReferrer] is the RAW value from the provider (encrypted base64 or not) — sent verbatim as
 * `attribution.metaInstallReferrer`. The backend `MetaInstallReferrerDecryptor` decrypts it with the
 * Meta Dashboard "Referrer Decryption Key" for view-through attribution. The SDK never decrypts it.
 */
data class MetaReferrer(
    val installReferrer: String,
    val actualTimestamp: Long?,
    val isClickThrough: Boolean?,   // is_ct: 1 = click-through, 0 = view-through
    val source: String              // facebook | instagram | facebook_lite
)

/**
 * Reads the Meta Install Referrer via the Facebook / Instagram / Facebook-Lite content providers.
 *
 * This is the NATIVE Android piece a React Native SDK can't do: querying the FB/IG
 * `InstallReferrerProvider` for the (encrypted) Meta referrer value. Fully defensive — a missing
 * app, a missing Facebook App ID, a SecurityException, or a null/empty cursor all yield null without
 * crashing. The first successful read is cached (memory) and returned on later calls.
 *
 * Requires the host app to declare its Facebook App ID as `<meta-data>` (`com.facebook.sdk.
 * ApplicationId`, or the AppSynk fallback `com.appsynk.FacebookApplicationId`) and the FB/IG
 * `<queries>` (shipped in this library's manifest).
 */
object MetaReferrerCollector {

    /** (authority, source) in resolution order — the first resolvable provider wins. */
    private val AUTHORITIES = listOf(
        "com.facebook.katana.provider.InstallReferrerProvider" to "facebook",
        "com.instagram.contentprovider.InstallReferrerProvider" to "instagram",
        "com.facebook.lite.provider.InstallReferrerProvider" to "facebook_lite"
    )

    private const val FB_SDK_APP_ID_KEY = "com.facebook.sdk.ApplicationId"
    private const val APPSYNK_FB_APP_ID_KEY = "com.appsynk.FacebookApplicationId"
    private const val COLUMN_INSTALL_REFERRER = "install_referrer"
    private const val COLUMN_ACTUAL_TIMESTAMP = "actual_timestamp"
    private const val COLUMN_IS_CT = "is_ct"
    private const val QUERY_TIMEOUT_MS = 5_000L

    @Volatile
    private var cachedValue: MetaReferrer? = null

    @Volatile
    private var fetched = false

    private val mutex = Mutex()

    /** Non-blocking peek at the cached Meta referrer (null until collected, or unavailable). */
    fun cached(): MetaReferrer? = cachedValue

    /** Reads the Meta referrer (cached after the first attempt), bounded by a 5s timeout. */
    suspend fun collect(context: Context): MetaReferrer? {
        if (fetched) return cachedValue
        val appContext = context.applicationContext
        return mutex.withLock {
            if (fetched) return@withLock cachedValue
            val result = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { query(appContext) }
            }
            cachedValue = result
            fetched = true
            result
        }
    }

    private fun query(context: Context): MetaReferrer? {
        val fbAppId = facebookAppId(context) ?: return null
        for ((authority, source) in AUTHORITIES) {
            if (context.packageManager.resolveContentProvider(authority, 0) == null) continue
            val uri = Uri.parse("content://$authority/$fbAppId")
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val refIndex = cursor.getColumnIndex(COLUMN_INSTALL_REFERRER)
                    if (refIndex < 0) return@use
                    val ref = cursor.getString(refIndex)?.takeIf { it.isNotEmpty() } ?: return@use
                    val ts = cursor.getColumnIndex(COLUMN_ACTUAL_TIMESTAMP).takeIf { it >= 0 }
                        ?.let { cursor.getLong(it) }
                    val isCt = cursor.getColumnIndex(COLUMN_IS_CT).takeIf { it >= 0 }
                        ?.let { cursor.getInt(it) == 1 }
                    return MetaReferrer(ref, ts, isCt, source)   // `use` closes the cursor on return
                }
            } catch (e: SecurityException) {
                // Provider denied access — try the next authority.
            } catch (e: Exception) {
                // Defensive: any provider error → try the next authority.
            }
        }
        return null
    }

    /** Facebook App ID from the host app's manifest `<meta-data>`, or null if absent. */
    private fun facebookAppId(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        val bundle = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData ?: return null
        bundle.getString(FB_SDK_APP_ID_KEY)?.takeIf { it.isNotBlank() }
            ?: bundle.getString(APPSYNK_FB_APP_ID_KEY)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
