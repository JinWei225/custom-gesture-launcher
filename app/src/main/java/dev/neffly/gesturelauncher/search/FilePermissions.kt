package dev.neffly.gesturelauncher.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The storage access local file search needs.
 *
 * Two eras, because scoped storage split them: on API 30+ only All-files access reaches non-media
 * files (documents, PDFs, archives) through MediaStore, and it's granted on a settings screen
 * rather than by a runtime dialog. Below that, plain READ_EXTERNAL_STORAGE covers everything.
 */
object FilePermissions {

    /** The runtime permission to request on API 26-29; null on API 30+, which uses [requestIntents]. */
    val runtimePermission: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) null
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * Settings intents to try in order for the API 30+ All-files grant, most specific first. Some
     * OEM builds don't handle the package-targeted variant, which lands the user on the app's own
     * toggle; the plain one only opens the list of every app. Empty below API 30, where the grant
     * is a runtime permission instead ([runtimePermission]).
     */
    fun requestIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
    }
}
