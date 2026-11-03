package dev.neffly.gesturelauncher.drawer

import android.content.ComponentName
import android.content.Context
import android.os.Process
import dev.neffly.gesturelauncher.data.AppTagStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * An app's alias against its launcher activity. The regression this guards: an update that swaps
 * the launcher activity to change the icon (Apple Music, Duolingo) must leave the alias in place,
 * which a tag keyed by component could never do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppAliasTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private val oldIcon = ComponentName("com.apple.android.music", "com.apple.android.music.OldIconActivity")
    private val newIcon = ComponentName("com.apple.android.music", "com.apple.android.music.NewIconActivity")

    private fun app(component: ComponentName) = AppInfo(
        label = "Apple Music",
        packageName = component.packageName,
        componentName = component,
        user = Process.myUserHandle(),
        tag = null
    )

    @Test
    fun `alias survives the app switching its launcher activity`() {
        AppTagStore.setTag(context, oldIcon.packageName, "am")
        AppListSnapshot.write(context, listOf(app(newIcon)))

        val listed = AppListSnapshot.read(context).single()

        assertEquals(newIcon, listed.componentName)
        assertEquals("am", listed.tag)
    }

    @Test
    fun `cleared alias is gone for every launcher activity of the package`() {
        AppTagStore.setTag(context, oldIcon.packageName, "am")
        AppTagStore.clearTag(context, newIcon.packageName)

        assertNull(AppTagStore.tag(context, oldIcon.packageName))
    }
}
