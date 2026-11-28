package dev.neffly.gesturelauncher.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a stored gesture has to carry to be read at all — anything less is kept aside as
 *  unreadable rather than reaching the recognizer. */
class GestureMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun mapping(extra: String) = """
        {"id":"g","packageName":"p","componentName":"p/.A","label":"A",
         "templates":[[{"x":0,"y":0},{"x":1,"y":1}]]$extra}
    """.trimIndent()

    @Test
    fun `a complete gesture decodes, with its pen lifts derived`() {
        val m = json.decodeFromString<GestureMapping>(mapping(""","subStrokeLengths":[[1,1]],"action":"LAUNCH_APP""""))
        assertEquals(GestureAction.LAUNCH_APP, m.action)
        assertTrue(m.isMultiStroke)
        assertEquals(2, listOf(m).toTemplates().single().subStrokeCount)
    }

    @Test
    fun `a gesture without its action or stroke counts is unreadable`() {
        assertThrows(Exception::class.java) {
            json.decodeFromString<GestureMapping>(mapping(""","subStrokeLengths":[[2]]"""))
        }
        assertThrows(Exception::class.java) {
            json.decodeFromString<GestureMapping>(mapping(""","action":"LAUNCH_APP""""))
        }
    }

    @Test
    fun `stroke counts that don't line up with the strokes are unreadable`() {
        assertThrows(Exception::class.java) {
            json.decodeFromString<GestureMapping>(mapping(""","subStrokeLengths":[],"action":"LAUNCH_APP""""))
        }
        assertThrows(Exception::class.java) {
            json.decodeFromString<GestureMapping>(mapping(""","subStrokeLengths":[[]],"action":"LAUNCH_APP""""))
        }
    }
}
