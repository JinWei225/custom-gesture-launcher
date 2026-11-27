package dev.neffly.gesturelauncher.search

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Guards the ranking rules now that this scorer is shared between app labels and quicklinks —
 * a change made for one must not quietly reorder the other.
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

    /** A CJK keyboard types fullwidth latin, so the same query has to reach the same app. */
    @Test
    fun `matching ignores fullwidth and halfwidth forms`() {
        assertNotNull(score("WhatsApp", "ｗｈａｔｓ"))
        assertNotNull(score("ＷｈａｔｓＡｐｐ", "whats"))
        assertNotNull(score("Photos 2024", "photos ２０２４"))
    }

    @Test
    fun `matching ignores case and diacritics`() {
        assertNotNull(score("Café Noir", "cafe"))
        assertNotNull(score("RÉSUMÉ.pdf", "resume"))
    }

    @Test
    fun `separators inside a name count as word boundaries`() {
        val boundary = score("tax_return", "tr")!!
        val buried = score("attribute", "tr")!!
        assertTrue("$boundary should beat $buried", boundary > buried)
    }

    @Test
    fun `matching ignores the device locale's casing rules`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            assertNotNull(score("Instagram", "insta"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
