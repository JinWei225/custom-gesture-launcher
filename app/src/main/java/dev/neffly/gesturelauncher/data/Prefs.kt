package dev.neffly.gesturelauncher.data

import android.content.Context

/**
 * SharedPreferences wrapper for the crash-counter safety net and the tunable recognition threshold.
 */
object Prefs {

    private const val FILE = "gesture_launcher_prefs"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_LAST_CRASH = "last_crash_ts"
    private const val KEY_THRESHOLD = "match_threshold"
    private const val KEY_AUTO_KEYBOARD = "auto_keyboard"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"

    /** Repeated crashes before the home screen drops into Safe Mode. */
    const val SAFE_MODE_CRASH_LIMIT = 3

    /** Conservative default: stricter matching = fewer accidental launches. */
    const val DEFAULT_THRESHOLD = 0.80f

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun recordCrash(context: Context) {
        val p = prefs(context)
        val count = p.getInt(KEY_CRASH_COUNT, 0) + 1
        // commit() (synchronous) so it lands before the process dies.
        p.edit().putInt(KEY_CRASH_COUNT, count)
            .putLong(KEY_LAST_CRASH, System.currentTimeMillis())
            .commit()
    }

    /** Called by the 10s "heartbeat" and by the Safe Mode reset button. */
    fun resetCrashCount(context: Context) {
        prefs(context).edit().putInt(KEY_CRASH_COUNT, 0).apply()
    }

    fun crashCount(context: Context): Int = prefs(context).getInt(KEY_CRASH_COUNT, 0)

    fun shouldEnterSafeMode(context: Context): Boolean =
        crashCount(context) >= SAFE_MODE_CRASH_LIMIT

    fun matchThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    fun setMatchThreshold(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_THRESHOLD, value).apply()
    }

    /** Auto-open the soft keyboard when the app drawer appears (default ON). */
    fun autoKeyboard(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_KEYBOARD, true)

    fun setAutoKeyboard(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_KEYBOARD, value).apply()
    }

    /** Buzz on a successful home-screen gesture match (default ON). */
    fun hapticFeedback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTIC_FEEDBACK, true)

    fun setHapticFeedback(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()
    }

    /** Local display name shown in the settings hub. Null means "never asked yet" (first run);
     *  "" means the user was asked and skipped — both are distinct from a real chosen name. */
    fun profileName(context: Context): String? = prefs(context).getString(KEY_PROFILE_NAME, null)

    fun setProfileName(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PROFILE_NAME, value).apply()
    }
}
