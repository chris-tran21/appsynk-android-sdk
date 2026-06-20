package io.appsynk.sdk.services

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import io.appsynk.sdk.models.AttributionData

/**
 * Persists the resolved deep-link attribution result and exposes its clickId for event enrichment.
 *
 * GAID and Play Install Referrer collection live in their own collectors
 * ([io.appsynk.sdk.collection.GaidProvider] / [io.appsynk.sdk.collection.InstallReferrerCollector]);
 * this service is now only the deep-link attribution store.
 */
class AttributionService(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("appsynk_attribution", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** The resolved deep-link clickId, overlaid onto every event's attribution block. */
    val clickId: String?
        get() = getAttribution()?.clickId

    /** Store the attribution result returned by the AppSynk API. */
    fun storeAttribution(attribution: AttributionData) {
        prefs.edit().putString(KEY_ATTRIBUTION, gson.toJson(attribution)).apply()
    }

    /** Retrieve the stored attribution result (null if not yet attributed). */
    fun getAttribution(): AttributionData? {
        val json = prefs.getString(KEY_ATTRIBUTION, null) ?: return null
        return runCatching { gson.fromJson(json, AttributionData::class.java) }.getOrNull()
    }

    /** Clear all stored attribution data. Called on SDK reset. */
    fun clearAttribution() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ATTRIBUTION = "attribution_data"
    }
}
