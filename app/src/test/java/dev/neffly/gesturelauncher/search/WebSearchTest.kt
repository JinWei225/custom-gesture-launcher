package dev.neffly.gesturelauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The URL heuristic decides whether the web row offers "open this address" or "search Google for
 * this", so both false positives (a search turning into a failed navigation) and false negatives
 * matter. Pure Kotlin — no Android dependency in [WebSearch.detectUrl].
 */
class WebSearchTest {

    @Test
    fun `bare domain becomes an https url`() {
        assertEquals("https://example.com", WebSearch.detectUrl("example.com"))
        assertEquals("https://docs.google.com", WebSearch.detectUrl("docs.google.com"))
    }

    @Test
    fun `explicit scheme is passed through unchanged`() {
        assertEquals("http://example.com/x", WebSearch.detectUrl("http://example.com/x"))
        assertEquals("https://example.com", WebSearch.detectUrl("https://example.com"))
    }

    @Test
    fun `path port and query still resolve to the host`() {
        assertEquals("https://example.com/a/b", WebSearch.detectUrl("example.com/a/b"))
        assertEquals("https://example.com:8080", WebSearch.detectUrl("example.com:8080"))
        assertEquals("https://example.com/s?q=1", WebSearch.detectUrl("example.com/s?q=1"))
    }

    @Test
    fun `ordinary searches are not urls`() {
        assertNull(WebSearch.detectUrl("weather"))
        assertNull(WebSearch.detectUrl("example dot com"))
        // A numeric TLD is what separates "version 2.0" from a real address.
        assertNull(WebSearch.detectUrl("2.0"))
        assertNull(WebSearch.detectUrl("3.5 inch"))
        assertNull(WebSearch.detectUrl("hello."))
        assertNull(WebSearch.detectUrl(""))
    }
}
