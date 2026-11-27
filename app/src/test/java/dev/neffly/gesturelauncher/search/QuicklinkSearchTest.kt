package dev.neffly.gesturelauncher.search

import dev.neffly.gesturelauncher.data.Quicklink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for [Links.normalize], which leans on android.net.Uri. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuicklinkSearchTest {

    private val github = Quicklink("1", "GitHub", "https://github.com", alias = "gh")
    private val gmail = Quicklink("2", "Gmail", "https://mail.google.com")
    private val calendar = Quicklink("3", "Calendar", "https://calendar.google.com", alias = "cal")

    private fun names(query: String) =
        SearchEngine.links(listOf(github, gmail, calendar), query).map { it.link.name }

    @Test
    fun `links are found by name`() {
        assertEquals(listOf("Calendar"), names("calen"))
    }

    @Test
    fun `an exact alias ranks its link first`() {
        assertEquals("GitHub", names("gh").first())
    }

    @Test
    fun `a link whose name and alias don't match is left out`() {
        assertFalse("Calendar" in names("gh"))
    }

    @Test
    fun `alias detection is exact and case-insensitive`() {
        val results = SearchEngine.links(listOf(github, gmail, calendar), "GH")
        assertTrue(SearchEngine.isLinkAlias(results, "GH"))
        assertFalse(SearchEngine.isLinkAlias(SearchEngine.links(listOf(github), "g"), "g"))
    }

    @Test
    fun `bare addresses get https`() {
        assertEquals("https://github.com", Links.normalize(" github.com "))
        assertEquals("http://intranet.local", Links.normalize("http://intranet.local"))
    }
}
