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
    private const val KEY_SEARCH_FILES = "search_files"
    private const val KEY_SEARCH_WEB = "search_web"
    private const val KEY_QUICK_SEARCH = "quick_search_enabled"

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

    /** Include local files in search results (default OFF — it needs a storage permission the user
     *  has to grant deliberately, so opting in is the only honest default). Applies to both the
     *  drawer's search bar and the floating quick-search window. */
    fun searchFiles(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEARCH_FILES, false)

    fun setSearchFiles(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEARCH_FILES, value).apply()
    }

    /** Offer a Google search / "open this address" row for the typed query (default ON — it costs
     *  no permission and nothing is sent anywhere until the row is tapped). */
    fun searchWeb(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEARCH_WEB, true)

    fun setSearchWeb(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEARCH_WEB, value).apply()
    }

    /** Master switch for the floating quick-search window (default OFF — turning it on is what
     *  makes taking the assistant role meaningful, and that has system-wide side effects). The
     *  window checks this on every launch, since the assistant role outlives the toggle. */
    fun quickSearchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_QUICK_SEARCH, false)

    fun setQuickSearchEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_QUICK_SEARCH, value).apply()
    }
}
