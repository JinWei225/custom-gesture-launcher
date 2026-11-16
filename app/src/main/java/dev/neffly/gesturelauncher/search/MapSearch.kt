package dev.neffly.gesturelauncher.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.Prefs

/**
 * The maps side of the `map` command: a place searched in the user's chosen [App], or a route
 * started to the [Place] they saved in it.
 *
 * Home and Work are the app's own: each app reads them from the user's account, so the launcher
 * never knows the address, only how to ask for it. Waze has a documented link for a saved place.
 * Google Maps has none on phones (its `place=home` is for car head units and opens with no
 * destination), but it answers navigation to the words "Home" and "Work" with the saved
 * addresses, the way a spoken request to it would.
 */
object MapSearch {

    /** The maps apps the settings hub offers. */
    enum class App(@param:StringRes val label: Int, val packageName: String) {
        GOOGLE_MAPS(R.string.maps_google, "com.google.android.apps.maps"),
        WAZE(R.string.maps_waze, "com.waze");

        companion object {
            val DEFAULT = GOOGLE_MAPS

            /** The app stored under [name], or [DEFAULT] for null or a name this build lacks. */
            fun fromName(name: String?): App = entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }

    /** A place the user saved in their maps app. */
    enum class Place(@param:StringRes val label: Int) {
        HOME(R.string.search_map_home),
        WORK(R.string.search_map_work)
    }

    /** "home", "work", or either with "my" — "map home depot" stays a search. */
    fun place(query: String): Place? =
        PLACE.matchEntire(query.trim())?.groupValues?.get(1)?.let { Place.valueOf(it.uppercase()) }

    /**
     * The app the command uses: the chosen one while it is installed, since only the app can read
     * the saved places, and Google Maps once it isn't.
     */
    fun app(context: Context): App {
        val chosen = Prefs.mapsApp(context)
        return if (chosen == App.DEFAULT || isInstalled(context, chosen)) chosen else App.DEFAULT
    }

    fun isInstalled(context: Context, app: App): Boolean =
        context.packageManager.getLaunchIntentForPackage(app.packageName) != null

    /** Opens [app] on a search for [query], or on the route to [place] when there is one. */
    fun intentFor(app: App, query: String, place: Place?): Intent {
        val uri = when (app) {
            // Google's documented Maps URL rather than a geo: URI. It is claimed by the Maps app
            // alone, so it opens there directly instead of through a chooser on a phone with Waze
            // installed as well, and it still works in a browser where Maps is absent.
            App.GOOGLE_MAPS ->
                if (place == null) GOOGLE_SEARCH + Uri.encode(query)
                else GOOGLE_NAVIGATE + Uri.encode(place.name.lowercase().replaceFirstChar { it.uppercase() })
            App.WAZE ->
                if (place == null) WAZE_SEARCH + Uri.encode(query)
                else WAZE_FAVORITE + place.name.lowercase() + "&navigate=yes"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        // google.navigation: is the Maps app's own scheme; nothing else should answer it.
        if (app == App.GOOGLE_MAPS && place != null) intent.setPackage(app.packageName)
        return intent
    }

    private const val GOOGLE_SEARCH = "https://www.google.com/maps/search/?api=1&query="
    private const val GOOGLE_NAVIGATE = "google.navigation:q="
    private const val WAZE_SEARCH = "https://waze.com/ul?q="
    private const val WAZE_FAVORITE = "https://waze.com/ul?favorite="

    private val PLACE = Regex("""(?:my\s+)?(home|work)""", RegexOption.IGNORE_CASE)
}
