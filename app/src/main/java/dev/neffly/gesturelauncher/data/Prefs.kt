package dev.neffly.gesturelauncher.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * SharedPreferences wrapper for the crash-counter safety net and the tunable recognition threshold.
 */
object Prefs {

    private const val FILE = "gesture_launcher_prefs"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_LAST_CRASH = "last_crash_ts"
    private const val KEY_THRESHOLD = "match_threshold"
    private const val KEY_AUTO_KEYBOARD = "auto_keyboard"
    private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_FONT_NAME = "font_name"
    private const val KEY_FONT_SCALE = "font_scale"

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

    /** Light / dark / follow-system preference, stored as the raw [AppCompatDelegate]
     *  MODE_NIGHT_* constant so it feeds straight back into
     *  [AppCompatDelegate.setDefaultNightMode] with no mapping table. Those constants are part
     *  of the stable API, so persisting them is safe.
     *
     *  Applied once per process in App.onCreate, before any activity inflates. */
    fun themeMode(context: Context): Int =
        prefs(context).getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    /** Display name of the user's imported font, or null for "use the system font".
     *
     *  Doubles as the "is a custom font set" flag, which is why
     *  [dev.neffly.gesturelauncher.ui.FontEngine] can decide whether to touch the filesystem at all
     *  from a value already in this prefs file. The font itself lives in filesDir — see
     *  [FontStore]. */
    fun fontName(context: Context): String? = prefs(context).getString(KEY_FONT_NAME, null)

    fun setFontName(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_FONT_NAME, value).apply()
    }

    /** Multiplier applied on top of the device's own text-size setting, so every `sp` dimension in
     *  the app scales together. Exists mainly because imported fonts differ a lot in how large
     *  they render at the same point size — a font that draws small is otherwise unusable.
     *  1.0 means "exactly what the system says", which is the default. */
    fun fontScale(context: Context): Float = prefs(context).getFloat(KEY_FONT_SCALE, 1f)

    fun setFontScale(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_FONT_SCALE, value).apply()
    }
}
