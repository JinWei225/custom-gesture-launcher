package dev.neffly.gesturelauncher.launch

import android.os.SystemClock

/**
 * Hands the foreground back to the app a floating window was opened over.
 *
 * HyperOS's own launcher stays the device's recents provider even when it isn't the home app, and
 * its `RecentsView.startHome` takes one of two paths depending on that: when it is both home and
 * overview it only flips its own state, and otherwise — which is to say whenever a third-party
 * launcher is installed — it starts the HOME intent for real. Anything that makes a freeform
 * window appear runs that path, so tens of milliseconds after a window floats, the system launches
 * *us*, and the home screen lands on top of the app the user was reading. Nothing crashes and
 * nothing is finished; the app is occluded, which looks exactly like it closed itself.
 *
 * Measured on a Xiaomi Pad (HyperOS, Android 16): `am start --windowingMode 5` over a full-screen
 * app logs `RecentsView: checkAndLauncherHome` and then `START {act=MAIN cat=[HOME]} ...
 * callingPackage com.miui.home`, while the same launch without the freeform mode logs neither. It
 * is not the shape of this launcher's launch that is wrong — a bare `am start` from the shell does
 * it too — so declining the foreground afterwards is the only lever left on our side.
 *
 * Two things arm the expectation, because a floating window can appear in two ways. Opening one is
 * the obvious one. The other is the system restoring a window the user had shrunk or stashed, which
 * this app plays no part in and can only learn about from [MiuiFreeformWindows].
 *
 * Three things make declining safe, and the first is the device. Nothing here arms unless
 * [install] found the HyperOS callback, because declining the foreground is only ever right where
 * the system took it uninvited; anywhere else it would be answering a Home press the user genuinely
 * made, and "the home button doesn't work" is the worst thing a launcher can be. Freeform support
 * is emphatically not the test for that — Samsung DeX and an Android tablet in desktop windowing
 * both float windows without any of this behaviour, and both would otherwise arm on every float.
 *
 * The second is what is on screen: arming only happens when none of the launcher's own screens is
 * showing, so a window floated from the home screen or the app drawer — where the home screen
 * genuinely is what belongs behind it — never arms at all. The third is the deadline, which covers
 * the case where the system doesn't take the foreground: the expectation lapses rather than lying
 * in wait for the user's next real Home press.
 */
object UnrequestedHomeLaunch {

    /** Generous beside the ~40ms the system actually takes, and still far shorter than the pause
     *  before anyone reaches for Home deliberately. Too long swallows a genuine Home press; too
     *  short leaves the user looking at a home screen they didn't ask for. */
    private const val DEADLINE_MILLIS = 2_000L

    /** Whether this device is one whose foreground is worth declining at all — see [install]. */
    @Volatile private var installed = false

    /** Volatile for the reader rather than the writers: [onScreenStarted]/[onScreenStopped] are
     *  activity lifecycle callbacks, so their non-atomic `++`/`--` only ever run on the main thread
     *  and cannot race each other. What crosses threads is [expectAfterFreeformChange] reading this
     *  from a Binder thread, and volatile is what keeps that read from seeing a stale count. */
    @Volatile private var screensShowing = 0

    /** Armed from either thread and consumed on the main one. Every access is one whole assignment,
     *  so the only race is a callback arming in the instants [consume] is clearing — which costs a
     *  single declined foreground, not correctness, and is not worth a lock on this path. */
    @Volatile private var armedAtUptime = 0L

    /** Counted rather than a flag: opening the drawer starts it before the home screen behind it
     *  stops, so two of the launcher's screens are briefly showing at once. */
    fun onScreenStarted() {
        screensShowing++
    }

    fun onScreenStopped() {
        if (screensShowing > 0) screensShowing--
    }

    /**
     * Binds this to the device, leaving the whole mechanism inert unless the device is one it
     * applies to. Call once, from Application.onCreate.
     *
     * Registering the watch is what answers that question, so the two are one call rather than a
     * flag some caller has to remember to check.
     */
    fun install() {
        installed = MiuiFreeformWindows.watch(::onFreeformChanged)
    }

    /** The system's own signal. Unguarded, and safe to be: this can only ever be called by a
     *  callback that exists on no other platform. */
    private fun onFreeformChanged() = arm()

    /** Records that a floating window just appeared over another app, and that a home launch
     *  arriving on its heels is therefore the system's doing rather than the user's. Unlike the
     *  callback above, this is reached on every device that can float a window, so it is the one
     *  that needs the device gate. */
    fun expectAfterFreeformChange() {
        if (installed) arm()
    }

    private fun arm() {
        if (screensShowing == 0) armedAtUptime = SystemClock.uptimeMillis()
    }

    /** True once, for a home screen being started by the launch described above. */
    fun consume(): Boolean {
        val expected = armedAtUptime != 0L &&
            SystemClock.uptimeMillis() - armedAtUptime < DEADLINE_MILLIS
        armedAtUptime = 0L
        return expected
    }
}
