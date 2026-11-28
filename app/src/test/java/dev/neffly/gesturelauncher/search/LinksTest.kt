package dev.neffly.gesturelauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a gesture or a quicklink may save and open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinksTest {

    @Test
    fun `a bare address becomes https`() {
        assertEquals("https://github.com", Links.normalize("github.com"))
        assertEquals("https://example.com/a?b=1", Links.normalize("  example.com/a?b=1 "))
    }

    @Test
    fun `a host with a port is an address, not a scheme`() {
        assertEquals("https://localhost:3000", Links.normalize("localhost:3000"))
        assertEquals("https://192.168.1.10:8080/x", Links.normalize("192.168.1.10:8080/x"))
    }

    @Test
    fun `allowed schemes are kept, lower-cased`() {
        assertEquals("https://example.com", Links.normalize("HTTPS://example.com"))
        assertEquals("mailto:me@example.com", Links.normalize("mailto:me@example.com"))
        assertEquals("tel:123", Links.normalize("tel:123"))
    }

    @Test
    fun `other schemes are refused`() {
        assertNull(Links.normalize("javascript:alert(1)"))
        assertNull(Links.normalize("file:///sdcard/x"))
        assertNull(Links.normalize("intent://x#Intent;end"))
        assertNull(Links.normalize("content://media/x"))
    }
}
