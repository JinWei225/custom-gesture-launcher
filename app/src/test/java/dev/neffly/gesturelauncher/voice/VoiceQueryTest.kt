package dev.neffly.gesturelauncher.voice

import android.content.ComponentName
import android.os.Process
import dev.neffly.gesturelauncher.drawer.AppInfo
import dev.neffly.gesturelauncher.search.Command
import dev.neffly.gesturelauncher.voice.VoiceQuery.Resolution
import dev.neffly.gesturelauncher.voice.VoiceQuery.Spoken
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceQueryTest {

    private fun app(label: String, tag: String? = null) = AppInfo(
        label = label,
        packageName = "pkg.${label.filter { it.isLetter() }.lowercase()}",
        componentName = ComponentName("pkg", "pkg.$label"),
        user = Process.myUserHandle(),
        tag = tag
    )

    private val whatsApp = app("WhatsApp")
    private val keep = app("Keep Notes")
    private val calendar = app("Google Calendar")
    private val camera = app("Camera")
    private val cameraRaw = app("Camera Raw")
    private val bank = app("银行", tag = "bank")
    private val hok = app("Hong Kong Observatory", tag = "hok")
    private val myPb = app("My\uFEFFPB")
    private val microsoftAuth = app("Authenticator")
    private val bitwardenAuth = app("Authenticator")
    private val protonAuth = app("Proton Authenticator")
    private val apps = listOf(whatsApp, keep, calendar, camera, cameraRaw, bank, hok, myPb)
    private val withAuthenticators = apps + listOf(microsoftAuth, bitwardenAuth, protonAuth)

    @Test
    fun `open names an app to launch`() {
        assertEquals(Spoken("Spotify", open = true), VoiceQuery.read("Open Spotify"))
        assertEquals(Spoken("camera", open = true), VoiceQuery.read("launch the camera app."))
    }

    @Test
    fun `spoken requests are rewritten onto the command keywords`() {
        assertEquals("timer 10 minutes", VoiceQuery.read("Set a timer for 10 minutes").query)
        assertEquals("timer ", VoiceQuery.read("start a timer").query)
        assertEquals("alarm 7:30 am tomorrow", VoiceQuery.read("Set an alarm for 7:30 a.m. tomorrow").query)
        assertEquals("event dinner friday 7 pm", VoiceQuery.read("Add an event dinner friday 7 p.m.").query)
        assertEquals("web how tall is k2", VoiceQuery.read("Search for how tall is k2?").query)
        assertEquals("map petrol station", VoiceQuery.read("Navigate to petrol station").query)
    }

    @Test
    fun `anything else is searched as heard`() {
        assertEquals(Spoken("Timer 10 min", open = false), VoiceQuery.read("Timer 10 min"))
        assertEquals(Spoken("12 times 12", open = false), VoiceQuery.read("12 times 12"))
    }

    @Test
    fun `app names match without spaces or punctuation`() {
        assertEquals(listOf(whatsApp), VoiceQuery.matchingApps(apps, "what's app"))
    }

    @Test
    fun `a label with an invisible character in it matches what it looks like`() {
        assertEquals(listOf(myPb), VoiceQuery.matchingApps(apps, "my PB"))
    }

    @Test
    fun `an exact name beats a longer one that starts the same`() {
        assertEquals(listOf(camera), VoiceQuery.matchingApps(apps, "camera"))
    }

    @Test
    fun `a name can match its first words or a later one`() {
        assertEquals(listOf(keep), VoiceQuery.matchingApps(apps, "keep"))
        assertEquals(listOf(calendar), VoiceQuery.matchingApps(apps, "calendar"))
    }

    @Test
    fun `an alias counts like a label, spelled out or not`() {
        assertEquals(listOf(bank), VoiceQuery.matchingApps(apps, "bank"))
        assertEquals(listOf(hok), VoiceQuery.matchingApps(apps, "H O K"))
        assertEquals(Resolution.Launch(hok), VoiceQuery.resolve(listOf("Open h.o.k."), apps))
    }

    @Test
    fun `scattered letters are not a match`() {
        // The search bar's fuzzy matcher would find WhatsApp in this; launching it would be wrong.
        assertEquals(emptyList<AppInfo>(), VoiceQuery.matchingApps(apps, "wasp"))
    }

    @Test
    fun `a name several apps share is searched so the user picks`() {
        assertEquals(
            Resolution.Search("authenticator"),
            VoiceQuery.resolve(listOf("open authenticator"), withAuthenticators)
        )
    }

    @Test
    fun `a fuller name picks one of the apps that share a word`() {
        assertEquals(
            Resolution.Launch(protonAuth),
            VoiceQuery.resolve(listOf("open Proton Authenticator"), withAuthenticators)
        )
    }

    @Test
    fun `the vocabulary carries labels and aliases without invisible characters`() {
        assertEquals(
            listOf("WhatsApp", "MyPB", "Hong Kong Observatory", "hok"),
            VoiceQuery.vocabulary(listOf(whatsApp, myPb, hok))
        )
    }

    @Test
    fun `a hypothesis with one of the user's words beats the recognizer's first guess`() {
        assertEquals(
            Resolution.Search("web Ollama"),
            VoiceQuery.resolve(listOf("search for Obama", "search for Ollama"), apps, words = listOf("ollama"))
        )
    }

    @Test
    fun `without the user's words the first guess stands`() {
        assertEquals(
            Resolution.Search("web Obama"),
            VoiceQuery.resolve(listOf("search for Obama", "search for Ollama"), apps)
        )
    }

    @Test
    fun `the user's words lead the vocabulary`() {
        assertEquals(
            listOf("Ollama", "WhatsApp"),
            VoiceQuery.vocabulary(listOf(whatsApp), words = listOf("Ollama"))
        )
    }

    @Test
    fun `a later hypothesis can supply the app name`() {
        assertEquals(
            Resolution.Launch(whatsApp),
            VoiceQuery.resolve(listOf("open what sap", "open whatsapp"), apps)
        )
    }

    @Test
    fun `an unknown app is searched for instead`() {
        assertEquals(Resolution.Search("Strava"), VoiceQuery.resolve(listOf("open Strava"), apps))
    }

    @Test
    fun `a command is searched so its row can be confirmed`() {
        assertEquals(Resolution.Search("timer 5 minutes"), VoiceQuery.resolve(listOf("set a timer for 5 minutes"), apps))
    }

    @Test
    fun `going home or to work is read as the map command`() {
        assertEquals("map home", VoiceQuery.read("Take me home").query)
        assertEquals("map home", VoiceQuery.read("navigate to home").query)
        assertEquals("map my work", VoiceQuery.read("Navigate to my work.").query)
        assertEquals("map home", VoiceQuery.read("go home").query)
    }

    @Test
    fun `a saved place is routed to straight away`() {
        assertEquals(
            Resolution.Perform(Command.Map("home")),
            VoiceQuery.resolve(listOf("take me home"), apps)
        )
        assertEquals(
            Resolution.Perform(Command.Map("work")),
            VoiceQuery.resolve(listOf("navigate to work"), apps)
        )
    }

    @Test
    fun `any other place goes straight to the maps app`() {
        assertEquals(
            Resolution.Perform(Command.Map("home depot")),
            VoiceQuery.resolve(listOf("navigate to home depot"), apps)
        )
    }

    @Test
    fun `an app's own name is searched as heard, whatever word it starts with`() {
        val maps = app("Google Maps")
        assertEquals(Spoken("Google Maps", open = false), VoiceQuery.read("Google Maps", apps + maps))
        assertEquals(Resolution.Search("Google Maps"), VoiceQuery.resolve(listOf("Google Maps"), apps + maps))
        // Still a web search when no app is called that.
        assertEquals(Spoken("web tall buildings", open = false), VoiceQuery.read("google tall buildings", apps + maps))
    }
}
