package dev.neffly.gesturelauncher.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import dev.neffly.gesturelauncher.search.MapSearch
import dev.neffly.gesturelauncher.search.WebSearch
import kotlin.math.abs

/**
 * SharedPreferences wrapper for every setting the app keeps: the crash-counter safety net, the
 * recognition threshold, the search engine and quick-search toggle, theme, font and font scale. One
 * file, so the first read pages everything in — see App.onCreate.
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
    private const val KEY_SEARCH_ENGINE = "search_engine"
    private const val KEY_MAPS_APP = "maps_app"
    private const val KEY_QUICK_SEARCH = "quick_search_enabled"
    private const val KEY_SEARCH_ON_LEFT = "search_on_left"
    private const val KEY_VOICE_WORDS = "voice_words"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_VOICE_ON_ASSIST = "voice_on_assist"
    private const val KEY_WIDGETS_PAGE = "widgets_page"
    private const val KEY_NOTES_PAGE = "notes_page"
    private const val KEY_HINTS_SEEN = "hints_seen"

    /** Repeated crashes before the home screen drops into Safe Mode. */
    const val SAFE_MODE_CRASH_LIMIT = 3

    /** Conservative default: stricter matching = fewer accidental launches. */
    const val DEFAULT_THRESHOLD = 0.80f

    /** The sensitivity slider's range. Below it any long stroke launches something; above it
     *  nothing a hand draws matches. */
    const val MIN_THRESHOLD = 0.60f
    const val MAX_THRESHOLD = 0.95f

    /** The text sizes the settings offer, as multipliers of the system's own. Skewed upward
     *  because the reason the setting exists is imported fonts that render small at a given point
     *  size; 0.9 is there so the drawer can still be made denser if someone wants that. */
    val FONT_SCALES = floatArrayOf(0.9f, 1f, 1.1f, 1.25f, 1.4f, 1.6f)

    /** The theme choices the settings offer. */
    val THEME_MODES = intArrayOf(
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    )

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

    /** Clamped: an imported backup is as able to write here as the slider is, and a threshold
     *  outside its range either launches on any stroke or never matches. */
    fun setMatchThreshold(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_THRESHOLD, value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)).apply()
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

    /** An unknown value (only a backup file can carry one) falls back to following the system. */
    fun setThemeMode(context: Context, mode: Int) {
        val valid = if (mode in THEME_MODES) mode else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        prefs(context).edit().putInt(KEY_THEME_MODE, valid).apply()
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

    /** Snapped to the nearest offered size: a backup file is as able to write here as the
     *  settings are, and a scale of 0 or 40 would make every screen unreadable — the one that
     *  fixes it included. */
    fun setFontScale(context: Context, value: Float) {
        val snapped = FONT_SCALES.minBy { abs(it - value) }
        prefs(context).edit().putFloat(KEY_FONT_SCALE, snapped).apply()
    }

    /** Where the `web` command sends a search (default Google). Stored by enum name; a name this
     *  build doesn't know falls back to the default. */
    fun searchEngine(context: Context): WebSearch.Engine =
        WebSearch.Engine.fromName(prefs(context).getString(KEY_SEARCH_ENGINE, null))

    fun setSearchEngine(context: Context, value: WebSearch.Engine) {
        prefs(context).edit().putString(KEY_SEARCH_ENGINE, value.name).apply()
    }

    /** Where the `map` command searches and routes (default Google Maps) — see [MapSearch.app]
     *  for what is used while the chosen app isn't installed. */
    fun mapsApp(context: Context): MapSearch.App =
        MapSearch.App.fromName(prefs(context).getString(KEY_MAPS_APP, null))

    fun setMapsApp(context: Context, value: MapSearch.App) {
        prefs(context).edit().putString(KEY_MAPS_APP, value.name).apply()
    }

    /** Master switch for the floating quick-search window (default OFF — turning it on is what
     *  makes taking the assistant role meaningful, and that has system-wide side effects). The
     *  window checks this on every launch, since the assistant role outlives the toggle. */
    fun quickSearchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_QUICK_SEARCH, false)

    fun setQuickSearchEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_QUICK_SEARCH, value).apply()
    }

    /** Which corner of the home screen's dock the search button sits in (default right). The
     *  strip that turns the pages and is push-to-talk takes the rest of the dock. */
    fun searchOnLeft(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEARCH_ON_LEFT, false)

    fun setSearchOnLeft(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEARCH_ON_LEFT, value).apply()
    }

    /** Whether the home screen has its widgets page, to the left of home (default ON). */
    fun widgetsPage(context: Context): Boolean = prefs(context).getBoolean(KEY_WIDGETS_PAGE, true)

    fun setWidgetsPage(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIDGETS_PAGE, value).apply()
    }

    /** Whether the home screen has its notes page, to the right of home (default ON). */
    fun notesPage(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTES_PAGE, true)

    fun setNotesPage(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTES_PAGE, value).apply()
    }

    /** Which guided tours have been finished or skipped, by [dev.neffly.gesturelauncher.onboarding.Hint]
     *  name. Empty by default, so everyone sees each one once — including people updating from a
     *  version without them. Cleared by the settings row that replays them. */
    fun hintSeen(context: Context, name: String): Boolean =
        name in prefs(context).getStringSet(KEY_HINTS_SEEN, null).orEmpty()

    fun markHintSeen(context: Context, name: String) {
        val seen = prefs(context).getStringSet(KEY_HINTS_SEEN, null).orEmpty()
        // A fresh set: the one SharedPreferences hands out must not be modified in place.
        prefs(context).edit().putStringSet(KEY_HINTS_SEEN, seen + name).apply()
    }

    fun clearHintsSeen(context: Context) {
        prefs(context).edit().remove(KEY_HINTS_SEEN).apply()
    }

    /** Master switch for voice search — the dock's push-to-talk and quick search's mic (default
     *  ON). Off, neither is offered and nothing listens. */
    fun voiceEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VOICE, true)

    fun setVoiceEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE, value).apply()
    }

    /** Whether quick search starts listening by itself when the assistant gesture opens it
     *  (default ON). Off, it opens to the keyboard like any other way in; the mic is still there. */
    fun voiceOnAssist(context: Context): Boolean = prefs(context).getBoolean(KEY_VOICE_ON_ASSIST, true)

    fun setVoiceOnAssist(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_ON_ASSIST, value).apply()
    }

    /** Words and names the user has told voice search to listen for — see
     *  [dev.neffly.gesturelauncher.voice.VoiceQuery]. One per line in storage, in the user's order. */
    fun voiceWords(context: Context): List<String> =
        prefs(context).getString(KEY_VOICE_WORDS, null).orEmpty().lines().filter { it.isNotBlank() }

    fun setVoiceWords(context: Context, words: List<String>) {
        val clean = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs(context).edit().putString(KEY_VOICE_WORDS, clean.joinToString("\n")).apply()
    }
}
