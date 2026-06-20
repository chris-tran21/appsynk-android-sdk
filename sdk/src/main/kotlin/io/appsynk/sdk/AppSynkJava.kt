package io.appsynk.sdk

import android.content.Context
import android.content.Intent
import io.appsynk.sdk.config.AppSynkOptions
import io.appsynk.sdk.models.AttributionData

/**
 * Java-friendly facade over [AppSynkSDK].
 *
 * Pure-Java apps can't ergonomically pass Kotlin function types, so the deep-link / attribution
 * callbacks are exposed here as SAM interfaces (a Java lambda works directly). Every other method is
 * already Java-callable on [AppSynkSDK] (all `@JvmStatic`); they're re-exposed here so a Java app can
 * use a single entry point.
 *
 * ```java
 * AppSynkJava.configure(this, "ak_live_xxx");
 * AppSynkJava.trackEvent("level_complete", Map.of("level", 5));
 * AppSynkJava.getDeepLinkData(data -> { if (data != null) route(data); });
 * ```
 */
object AppSynkJava {

    /** Java callback for attribution / deep-link results. */
    fun interface AttributionCallback {
        fun onResult(data: AttributionData?)
    }

    /** Java callback for the debug connection test. */
    fun interface ConnectionCallback {
        fun onResult(success: Boolean, message: String)
    }

    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, apiKey: String, options: AppSynkOptions = AppSynkOptions()) =
        AppSynkSDK.configure(context, apiKey, options)

    @JvmStatic
    @JvmOverloads
    fun trackEvent(eventName: String, properties: Map<String, Any> = emptyMap()) =
        AppSynkSDK.trackEvent(eventName, properties)

    @JvmStatic
    fun trackRevenue(amount: Double, currency: String, productId: String, orderId: String) =
        AppSynkSDK.trackRevenue(amount, currency, productId, orderId)

    @JvmStatic
    @JvmOverloads
    fun trackAdRevenue(
        network: String,
        amount: Double,
        currency: String,
        adUnit: String? = null,
        adType: String? = null
    ) = AppSynkSDK.trackAdRevenue(network, amount, currency, adUnit, adType)

    @JvmStatic
    fun setUserId(userId: String) = AppSynkSDK.setUserId(userId)

    @JvmStatic
    fun setUserProperties(properties: Map<String, Any>) = AppSynkSDK.setUserProperties(properties)

    @JvmStatic
    fun setConsent(
        isUserSubjectToGDPR: Boolean,
        hasConsentForDataUsage: Boolean,
        hasConsentForAdsPersonalization: Boolean
    ) = AppSynkSDK.setConsent(isUserSubjectToGDPR, hasConsentForDataUsage, hasConsentForAdsPersonalization)

    @JvmStatic
    fun anonymizeUser(enabled: Boolean) = AppSynkSDK.anonymizeUser(enabled)

    @JvmStatic
    fun reset() = AppSynkSDK.reset()

    @JvmStatic
    fun flush() = AppSynkSDK.flush()

    @JvmStatic
    fun handleIntent(intent: Intent) = AppSynkSDK.handleIntent(intent)

    @JvmStatic
    fun getDeepLinkData(callback: AttributionCallback) =
        AppSynkSDK.getDeepLinkData { callback.onResult(it) }

    @JvmStatic
    fun getAttributionData(callback: AttributionCallback) =
        AppSynkSDK.getAttributionData { callback.onResult(it) }

    @JvmStatic
    fun testConnection(callback: ConnectionCallback) =
        AppSynkSDK.debug.testConnection { result ->
            result.fold(
                onSuccess = { callback.onResult(true, it) },
                onFailure = { callback.onResult(false, it.message ?: "error") }
            )
        }
}
