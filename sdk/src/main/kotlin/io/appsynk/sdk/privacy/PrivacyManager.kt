package io.appsynk.sdk.privacy

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import io.appsynk.sdk.models.ConsentPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GDPR consent + anonymization state. Persisted and thread-safe — read by `buildEvent` on every
 * event, so it must be safe to read from any thread.
 *
 * Mirrors the iOS `PrivacyManager`. Persistence uses EncryptedSharedPreferences on API 23+ (the
 * consent record is privacy-sensitive); on API 21–22, or if the keystore is unavailable on some OEM
 * device, it falls back to regular app-private SharedPreferences.
 */
class PrivacyManager(context: Context, anonymizeByDefault: Boolean) {

    private val prefs: SharedPreferences = securePrefs(context.applicationContext)
    private val gson = Gson()
    private val lock = Any()

    @Volatile
    private var _consent: ConsentPayload? = null

    @Volatile
    private var _anonymized: Boolean = anonymizeByDefault

    init {
        // A backed-up EncryptedSharedPreferences restored on another device references a Keystore
        // master key that didn't transfer → reads throw with a mismatched key. Recover by clearing
        // the corrupted file instead of crashing the host app at configure().
        try {
            _consent = loadConsent()
            _anonymized =
                if (prefs.contains(KEY_ANONYMIZED)) prefs.getBoolean(KEY_ANONYMIZED, false)
                else anonymizeByDefault
        } catch (e: Exception) {
            runCatching { prefs.edit().clear().apply() }
            _consent = null
            _anonymized = anonymizeByDefault
        }
    }

    /** Current consent (thread-safe). Attached to every event's root `consent`. */
    val consent: ConsentPayload?
        get() = _consent

    /** Whether events are anonymized (thread-safe). */
    val isAnonymized: Boolean
        get() = _anonymized

    fun setConsent(
        isUserSubjectToGDPR: Boolean,
        hasConsentForDataUsage: Boolean,
        hasConsentForAdsPersonalization: Boolean
    ) {
        val payload = ConsentPayload(
            isUserSubjectToGDPR = isUserSubjectToGDPR,
            hasConsentForDataUsage = hasConsentForDataUsage,
            hasConsentForAdsPersonalization = hasConsentForAdsPersonalization,
            consentTimestamp = isoNow()
        )
        synchronized(lock) {
            _consent = payload
            prefs.edit().putString(KEY_CONSENT, gson.toJson(payload)).apply()
        }
    }

    fun setAnonymized(enabled: Boolean) {
        synchronized(lock) {
            _anonymized = enabled
            prefs.edit().putBoolean(KEY_ANONYMIZED, enabled).apply()
        }
    }

    private fun loadConsent(): ConsentPayload? {
        val json = prefs.getString(KEY_CONSENT, null) ?: return null
        return runCatching { gson.fromJson(json, ConsentPayload::class.java) }.getOrNull()
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun securePrefs(context: Context): SharedPreferences {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
            // Keystore unavailable on some OEM devices → fall through to plain prefs.
        }
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    companion object {
        private const val FILE = "appsynk_privacy"
        private const val KEY_CONSENT = "consent"
        private const val KEY_ANONYMIZED = "anonymized"
    }
}
