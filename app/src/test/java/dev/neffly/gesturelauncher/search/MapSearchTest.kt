package dev.neffly.gesturelauncher.search

import android.content.Intent
import dev.neffly.gesturelauncher.search.MapSearch.App
import dev.neffly.gesturelauncher.search.MapSearch.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapSearchTest {

    @Test
    fun `google maps searches with its maps url`() {
        val intent = MapSearch.intentFor(App.GOOGLE_MAPS, "petrol station", null)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.google.com/maps/search/?api=1&query=petrol%20station", intent.dataString)
        assertNull(intent.`package`)
    }

    @Test
    fun `google maps navigates to the saved place by its name, in the maps app`() {
        val intent = MapSearch.intentFor(App.GOOGLE_MAPS, "my home", Place.HOME)
        assertEquals("google.navigation:q=Home", intent.dataString)
        assertEquals("com.google.android.apps.maps", intent.`package`)
    }

    @Test
    fun `waze searches and routes to its favourites`() {
        assertEquals("https://waze.com/ul?q=KLCC", MapSearch.intentFor(App.WAZE, "KLCC", null).dataString)
        assertEquals(
            "https://waze.com/ul?favorite=work&navigate=yes",
            MapSearch.intentFor(App.WAZE, "work", Place.WORK).dataString
        )
    }
}
