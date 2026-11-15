package dev.neffly.gesturelauncher.search

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import dev.neffly.gesturelauncher.drawer.AppInfo
import dev.neffly.gesturelauncher.voice.VoiceQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantsTest {

    private fun app(label: String, pkg: String) =
        AppInfo(label, pkg, ComponentName(pkg, "$pkg.Main"), Process.myUserHandle())

    private val claude = app("Claude", "com.anthropic.claude")
    private val gemini = app("Gemini", "com.google.android.apps.bard")
    private val notes = app("Notes", "com.example.notes")
    private val assistants = Assistants.installed(listOf(notes, gemini, claude))

    @Test
    fun `only known assistant apps count, by name`() {
        assertEquals(listOf(claude, gemini), assistants)
    }

    @Test
    fun `only the keyword and a space make it an ask`() {
        assertNull(Assistants.parse("ask", assistants))
        assertNull(Assistants.parse("asking", assistants))
    }

    @Test
    fun `a named assistant gets the rest as its prompt`() {
        assertEquals(
            listOf(Command.Ask("summarise this article", claude)),
            Assistants.parse("ask Claude to summarise this article", assistants)
        )
        assertEquals(
            listOf(Command.Ask("how tall is K2", gemini)),
            Assistants.parse("ask gemini: how tall is K2", assistants)
        )
    }

    @Test
    fun `with no assistant named, every one gets a row`() {
        assertEquals(
            listOf(Command.Ask("how tall is K2", claude), Command.Ask("how tall is K2", gemini)),
            Assistants.parse("ask how tall is K2", assistants)
        )
    }

    @Test
    fun `the prompt is shared to the assistant`() {
        val toGemini = Assistants.intent(Command.Ask("how tall is K2", gemini))
        assertEquals(Intent.ACTION_SEND, toGemini.action)
        assertEquals("how tall is K2", toGemini.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("com.google.android.apps.bard", toGemini.`package`)
    }

    @Test
    fun `spoken asks naming an assistant go straight to it`() {
        val apps = listOf(notes, gemini, claude)
        assertEquals(
            VoiceQuery.Resolution.Perform(Command.Ask("what's the capital of Peru", claude)),
            VoiceQuery.resolve(listOf("Ask Claude what's the capital of Peru?"), apps)
        )
        assertEquals(
            VoiceQuery.Resolution.Search("ask what's the capital of Peru"),
            VoiceQuery.resolve(listOf("ask what's the capital of Peru"), apps)
        )
    }
}
