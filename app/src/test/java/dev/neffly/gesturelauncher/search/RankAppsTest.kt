package dev.neffly.gesturelauncher.search

import android.content.ComponentName
import android.os.Process
import dev.neffly.gesturelauncher.drawer.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ranking the drawer, quick search and the gesture picker all share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RankAppsTest {

    private fun app(label: String, tag: String? = null) =
        AppInfo(label, "pkg.$label", ComponentName("pkg.$label", "Main"), Process.myUserHandle(), tag)

    private fun rank(apps: List<AppInfo>, query: String) = SearchScoring.rankApps(apps, query).map { it.label }

    @Test
    fun `an exact alias is pinned above everything`() {
        val apps = listOf(app("Maps"), app("Google Maps"), app("Waze", tag = "maps"))
        assertEquals("Waze", rank(apps, "maps").first())
    }

    @Test
    fun `any alias match outranks label matches`() {
        val apps = listOf(app("Tasks"), app("Todoist", tag = "tasklist"))
        assertEquals(listOf("Todoist", "Tasks"), rank(apps, "task"))
    }

    @Test
    fun `apps that don't match are left out`() {
        assertEquals(listOf("Camera"), rank(listOf(app("Camera"), app("Clock")), "cam"))
    }

    @Test
    fun `a blank query keeps every app`() {
        assertEquals(2, rank(listOf(app("A"), app("B")), "").size)
    }
}
