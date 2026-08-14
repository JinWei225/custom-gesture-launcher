package dev.neffly.gesturelauncher.search

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the ranking rules now that this scorer is shared between app labels and file names —
 * a change made for files must not quietly reorder the drawer.
 */
class SearchScoringTest {

    private fun score(text: String, query: String) =
        SearchScoring.fuzzyScore(SearchScoring.normalize(text), SearchScoring.normalize(query))

    @Test
    fun `out-of-order characters do not match`() {
        assertNull(score("Google Maps", "sm"))
        assertNotNull(score("Google Maps", "gm"))
    }

    @Test
    fun `word-boundary initials outrank a buried subsequence`() {
        val initials = score("Google Maps", "gm")!!
        val buried = score("Backgammon", "gm")!!
        assertTrue("$initials should beat $buried", initials > buried)
    }

    @Test
    fun `contiguous run outranks a scattered one`() {
        val contiguous = score("Telegram", "tele")!!
        val scattered = score("Time Lapse Editor", "tele")!!
        assertTrue("$contiguous should beat $scattered", contiguous > scattered)
    }

    @Test
    fun `matching ignores case and diacritics`() {
        assertNotNull(score("Café Noir", "cafe"))
        assertNotNull(score("RÉSUMÉ.pdf", "resume"))
    }

    @Test
    fun `separators in file names count as word boundaries`() {
        val boundary = score("tax_return_2025.pdf", "tr")!!
        val buried = score("attribute.txt", "tr")!!
        assertTrue("$boundary should beat $buried", boundary > buried)
    }
}
