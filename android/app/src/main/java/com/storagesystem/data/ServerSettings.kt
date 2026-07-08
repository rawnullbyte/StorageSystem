package com.storagesystem.data

import android.content.Context
import android.content.SharedPreferences
import com.storagesystem.BuildConfig

/**
 * Runtime server configuration persisted via SharedPreferences.
 * Defaults to the build config values.
 */
object ServerSettings {

    private const val PREFS_NAME = "storage_system_prefs"
    private const val KEY_API_BASE_URL = "api_base_url"
    private const val KEY_WS_URL = "ws_url"
    private const val KEY_AUTO_SCAN = "auto_scan"

    fun autoScan(): Boolean = prefs()?.getBoolean(KEY_AUTO_SCAN, false) ?: false
    fun setAutoScan(enabled: Boolean) { prefs()?.edit()?.putBoolean(KEY_AUTO_SCAN, enabled)?.apply() }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences {
        return prefs ?: throw IllegalStateException("ServerSettings not initialized — call init() from Application or Activity")
    }

    var apiBaseUrl: String
        get() = prefs().getString(KEY_API_BASE_URL, BuildConfig.API_BASE_URL) ?: BuildConfig.API_BASE_URL
        set(value) = prefs().edit().putString(KEY_API_BASE_URL, value).apply()

    var wsUrl: String
        get() = prefs().getString(KEY_WS_URL, BuildConfig.WS_URL) ?: BuildConfig.WS_URL
        set(value) = prefs().edit().putString(KEY_WS_URL, value).apply()

    /** Convenience: set both URLs from a base HTTP URL (e.g. "http://192.168.1.100:8000") */
    fun setFromHttpBase(base: String) {
        val clean = base.trimEnd('/')
        apiBaseUrl = clean
        wsUrl = clean.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
    }
}
