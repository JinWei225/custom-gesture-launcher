package dev.neffly.gesturelauncher.data

import android.content.ComponentName
import android.os.Process
import dev.neffly.gesturelauncher.drawer.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for ComponentName and the user handle [PieStore.resolve] leans on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PieStoreTest {

    private fun app(pkg: String, cls: String) =
        AppInfo(cls, pkg, ComponentName(pkg, "$pkg.$cls"), Process.myUserHandle())

    private val maps = app("com.maps", "Main")
    private val filesA = app("com.files", "Browse")
    private val filesB = app("com.files", "Recent")

    private fun flat(app: AppInfo) = app.componentName.flattenToString()

    @Test
    fun `stored components resolve in order`() {
        assertEquals(listOf(filesA, maps), PieStore.resolve(listOf(flat(filesA), flat(maps)), listOf(maps, filesA)))
    }

    @Test
    fun `a swapped alias falls back to its package's only entry`() {
        assertEquals(listOf(maps), PieStore.resolve(listOf("com.maps/com.maps.OldAlias"), listOf(maps)))
    }

    @Test
    fun `no fallback when the package has several entries`() {
        val gone = "com.files/com.files.Gone"
        assertEquals(listOf(filesB), PieStore.resolve(listOf(gone, flat(filesB)), listOf(filesA, filesB)))
    }

    @Test
    fun `an app is never resolved twice`() {
        assertEquals(listOf(maps), PieStore.resolve(listOf("com.maps/com.maps.OldAlias", flat(maps)), listOf(maps)))
    }
}
