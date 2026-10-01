package com.bangla.translator.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user preferences for overlays, notifications, and Bengali detection ratio.
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    var isNotificationTranslationEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, value).apply()

    var bengaliRatioThreshold: Float
        get() = prefs.getFloat(KEY_BENGALI_RATIO, 0.20f)
        set(value) = prefs.edit().putFloat(KEY_BENGALI_RATIO, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "bangla_translator_prefs"
        const val KEY_OVERLAY_ENABLED = "key_overlay_enabled"
        const val KEY_NOTIFICATION_ENABLED = "key_notification_enabled"
        const val KEY_BENGALI_RATIO = "key_bengali_ratio"
    }
}
