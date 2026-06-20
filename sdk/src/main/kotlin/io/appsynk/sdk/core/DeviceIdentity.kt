package io.appsynk.sdk.core

import android.content.Context
import java.util.UUID

/**
 * Stable per-install device identifier — the "install instance id".
 *
 * Persisted in a dedicated [PREFS_NAME] SharedPreferences file that is **excluded from Android
 * Auto Backup** (see `res/xml/appsynk_backup_rules.xml` and `res/xml/appsynk_full_backup.xml`).
 * Auto Backup would otherwise restore the id across devices / reinstalls via the user's Google
 * backup, so two distinct installs would share one id and corrupt attribution. We want an id that
 * is stable for the life of one install and gone on uninstall.
 *
 * (Telling install from reinstall is a separate, server-side concern: Android has no equivalent to
 * the iOS Keychain flag that survives uninstall.)
 *
 * Mirrors the iOS `DeviceIdentity`.
 */
object DeviceIdentity {

    /**
     * Dedicated SharedPreferences file name. On disk this becomes `appsynk_id.xml`, which the
     * shipped backup rules exclude. Kept separate from other SDK prefs so only the device id is
     * excluded from backup.
     */
    const val PREFS_NAME = "appsynk_id"

    /** Key holding the install instance id within [PREFS_NAME]. */
    private const val KEY_INSTALL_INSTANCE_ID = "install_instance_id"

    /** Serializes get-or-create so concurrent first accesses can't generate two different ids. */
    private val lock = Any()

    /**
     * The stable install instance id, generated (UUID) and persisted on first access.
     *
     * Thread-safe: the first concurrent callers all observe the same id. Returns the same value on
     * every subsequent launch until the app is uninstalled.
     */
    fun installInstanceId(context: Context): String = synchronized(lock) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALL_INSTANCE_ID, null)
        if (!existing.isNullOrEmpty()) {
            existing
        } else {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_INSTANCE_ID, newId).apply()
            newId
        }
    }
}
