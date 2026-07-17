package dev.neffly.gesturelauncher.crash

import android.content.Context
import dev.neffly.gesturelauncher.data.Prefs

/**
 * Uncaught-exception handler that bumps a persistent crash counter *before* letting the crash
 * propagate normally. The counter drives Safe Mode (see [SafeModeActivity]) and is reset by the
 * home screen's 10s "healthy" heartbeat, so one-off crashes don't accumulate forever.
 */
class CrashHandler(
    private val appContext: Context,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { Prefs.recordCrash(appContext) }
        // Preserve normal crash behavior (logcat, system dialog, process death).
        previous?.uncaughtException(thread, throwable)
    }
}
