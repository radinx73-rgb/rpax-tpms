package com.rpax.tpms

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user-configurable alert thresholds and unit preferences.
 * Defaults are tuned for a Kawasaki VN800 Classic (cold pressure spec ~2.25 bar F/R).
 */
class TpmsSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var frontMinBar: Float
        get() = prefs.getFloat(KEY_FRONT_MIN, DEFAULT_FRONT_MIN)
        set(value) = prefs.edit().putFloat(KEY_FRONT_MIN, value).apply()

    var frontMaxBar: Float
        get() = prefs.getFloat(KEY_FRONT_MAX, DEFAULT_FRONT_MAX)
        set(value) = prefs.edit().putFloat(KEY_FRONT_MAX, value).apply()

    var rearMinBar: Float
        get() = prefs.getFloat(KEY_REAR_MIN, DEFAULT_REAR_MIN)
        set(value) = prefs.edit().putFloat(KEY_REAR_MIN, value).apply()

    var rearMaxBar: Float
        get() = prefs.getFloat(KEY_REAR_MAX, DEFAULT_REAR_MAX)
        set(value) = prefs.edit().putFloat(KEY_REAR_MAX, value).apply()

    var maxTempC: Int
        get() = prefs.getInt(KEY_MAX_TEMP, DEFAULT_MAX_TEMP)
        set(value) = prefs.edit().putInt(KEY_MAX_TEMP, value).apply()

    var soundAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    var vibrationAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBE_ENABLED, value).apply()

    var watchNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_WATCH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WATCH_ENABLED, value).apply()

    var useFahrenheit: Boolean
        get() = prefs.getBoolean(KEY_USE_F, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_F, value).apply()

    var usePsi: Boolean
        get() = prefs.getBoolean(KEY_USE_PSI, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_PSI, value).apply()

    fun isFrontAlert(bar: Float): Boolean = bar < frontMinBar || bar > frontMaxBar
    fun isRearAlert(bar: Float): Boolean = bar < rearMinBar || bar > rearMaxBar
    fun isTempAlert(celsius: Int): Boolean = celsius > maxTempC

    companion object {
        private const val PREFS_NAME = "rpax_tpms_settings"

        private const val KEY_FRONT_MIN = "front_min_bar"
        private const val KEY_FRONT_MAX = "front_max_bar"
        private const val KEY_REAR_MIN = "rear_min_bar"
        private const val KEY_REAR_MAX = "rear_max_bar"
        private const val KEY_MAX_TEMP = "max_temp_c"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_VIBE_ENABLED = "vibe_enabled"
        private const val KEY_WATCH_ENABLED = "watch_enabled"
        private const val KEY_USE_F = "use_fahrenheit"
        private const val KEY_USE_PSI = "use_psi"

        const val DEFAULT_FRONT_MIN = 1.9f
        const val DEFAULT_FRONT_MAX = 2.6f
        const val DEFAULT_REAR_MIN = 2.0f
        const val DEFAULT_REAR_MAX = 2.8f
        const val DEFAULT_MAX_TEMP = 65
    }
}
