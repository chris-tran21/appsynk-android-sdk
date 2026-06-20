package io.appsynk.sdk.collection

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import com.google.gson.annotations.SerializedName
import io.appsynk.sdk.config.AppSynkOptions
import io.appsynk.sdk.models.AttributionInfo
import io.appsynk.sdk.models.DeviceInfo
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The collected `device` + `attribution` wire blocks.
 *
 * Mirrors the iOS `DeviceData`. Only [device] and [attribution] are serialized — they are the event
 * sub-blocks. (The iOS `userAgent` field, an HTTP header, is added with the network layer.)
 */
data class DeviceData(
    @SerializedName("device")      val device: DeviceInfo,
    @SerializedName("attribution") val attribution: AttributionInfo
)

/**
 * Produces the `device` block + `attribution` sub-block at the backend's exact wire format.
 *
 * Mirrors the iOS `DeviceDataCollector`. Collection is synchronous and non-blocking: the
 * asynchronous Google Advertising ID is resolved off-main by [GaidProvider] and **injected** into
 * [collect] (the facade fetches it before building an event). `idfa`/`idfv` are always null on
 * Android — kept only for wire-format symmetry with iOS.
 */
class DeviceDataCollector(
    context: Context,
    private val options: AppSynkOptions
) {
    private val appContext: Context = context.applicationContext

    /**
     * Build a fresh device + attribution snapshot.
     *
     * @param gaid Google Advertising ID, pre-resolved off-main by [GaidProvider]. Defaults to null
     *   (the placeholder) until the facade injects it; also forced null by [AppSynkOptions.disableGaid].
     * @param adServicesToken Always null on Android — the parameter exists only for signature
     *   symmetry with the iOS SDK (Apple Search Ads token).
     */
    fun collect(gaid: String? = null, adServicesToken: String? = null): DeviceData =
        DeviceData(
            device = collectDevice(appContext),
            attribution = AttributionInfo(
                idfa = null,
                idfv = null,
                gaid = if (options.disableGaid) null else gaid,
                androidId = if (options.disableAndroidId) null else androidId()
            )
        )

    /** Settings.Secure ANDROID_ID — deterministic backup identifier; null when blank/unavailable. */
    private fun androidId(): String? =
        runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {

        /**
         * The `device` block — pure hardware / locale / connectivity, no identifiers or options.
         * Static across a session except [networkType], which reflects the moment of the call.
         */
        fun collectDevice(context: Context): DeviceInfo {
            val appContext = context.applicationContext
            val metrics: DisplayMetrics = appContext.resources.displayMetrics
            val isTablet = appContext.resources.configuration.smallestScreenWidthDp >= 600

            return DeviceInfo(
                // On Android, Build.MODEL is already the marketing name (e.g. "Pixel 7").
                model = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                deviceType = if (isTablet) "tablet" else "phone",
                locale = Locale.getDefault().toString(),
                timezone = TimeZone.getDefault().id,
                networkType = networkType(appContext),
                screenResolution = "${metrics.widthPixels}x${metrics.heightPixels}",
                carrier = carrier(appContext),
                batteryLevel = batteryLevel(appContext),
                // densityDpi / 160, rounded — the iOS-symmetric scale factor (~2 or 3), not raw dpi.
                screenDensity = (metrics.densityDpi / 160f).roundToInt(),
                hasTelephony = hasTelephony(appContext)
            )
        }

        /**
         * Current connectivity: wifi | cellular | ethernet | other | disconnected | unknown.
         *
         * Uses `NetworkCapabilities` on API 23+ (the recommended path); falls back to the
         * deprecated `activeNetworkInfo` on API 21–22 so the SDK never crashes on its minSdk.
         */
        internal fun networkType(context: Context): String {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return "disconnected"
                val caps = cm.getNetworkCapabilities(network) ?: return "disconnected"
                return when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
            }

            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo ?: return "disconnected"
            @Suppress("DEPRECATION")
            if (!info.isConnected) return "disconnected"
            @Suppress("DEPRECATION")
            return when (info.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                else -> "other"
            }
        }

        /** Mobile carrier name, or null when empty / unavailable (Wi-Fi-only, no SIM, emulator). */
        private fun carrier(context: Context): String? =
            runCatching {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                tm?.networkOperatorName?.takeIf { it.isNotEmpty() }
            }.getOrNull()

        /** True when the device has a telephony radio (false on Wi-Fi-only tablets / emulators). */
        private fun hasTelephony(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        /** Battery 0–100, or -1 when unavailable (e.g. emulator without battery simulation). */
        private fun batteryLevel(context: Context): Int =
            runCatching {
                // Sticky broadcast — readable with a null receiver, no flag needed on any API level.
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level < 0 || scale <= 0) -1 else level * 100 / scale
            }.getOrElse { -1 }
    }
}
