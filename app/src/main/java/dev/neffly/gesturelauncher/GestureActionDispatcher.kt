package dev.neffly.gesturelauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import dev.neffly.gesturelauncher.data.GestureAction
import dev.neffly.gesturelauncher.data.GestureMapping
import dev.neffly.gesturelauncher.drawer.AppDrawerActivity
import dev.neffly.gesturelauncher.drawer.AppRepository

/** Carries out whatever a recognized [GestureMapping] means — the one place that understands all
 *  three [GestureAction] values, so callers (currently just [MainActivity]) don't need to. */
object GestureActionDispatcher {

    /** Returns whether the action actually fired — false only for [GestureAction.OPEN_URL] with
     *  an unhandleable URL, so the caller can skip the "success" haptic/feedback for it. */
    fun perform(context: Context, mapping: GestureMapping): Boolean = when (mapping.action) {
        GestureAction.LAUNCH_APP -> {
            ComponentName.unflattenFromString(mapping.componentName)?.let { comp ->
                AppRepository.launch(context, comp)
            }
            true
        }
        GestureAction.OPEN_DRAWER -> {
            context.startActivity(Intent(context, AppDrawerActivity::class.java))
            // Same suppression MainActivity's drawer button uses: the drawer animates its own
            // slide-up, so the OS's default cross-activity transition must not fight it.
            if (context is Activity && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(0, 0)
            }
            true
        }
        GestureAction.OPEN_URL -> {
            val url = mapping.url
            val launched = url != null && runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            if (!launched) {
                Toast.makeText(context, R.string.gesture_url_open_failed, Toast.LENGTH_SHORT).show()
            }
            launched
        }
    }
}
