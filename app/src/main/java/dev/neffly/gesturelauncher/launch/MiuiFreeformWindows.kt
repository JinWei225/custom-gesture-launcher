package dev.neffly.gesturelauncher.launch

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.lang.reflect.Proxy

/**
 * Tells [UnrequestedHomeLaunch] when a floating window appears or changes mode, which is the one
 * thing that separates the system's spurious home launches from the user's real ones.
 *
 * The two are otherwise identical from inside this process, and that was measured rather than
 * assumed: tapping the background of the recents screen — a perfectly ordinary way to go home —
 * produces the same intent, from the same caller (`com.miui.home`), with the same flags, delivered
 * to a stopped activity that ends up with the same window focus, as the launch HyperOS fires after
 * a floating window is restored. Nothing about the launch itself can tell them apart. What does
 * tell them apart is what came immediately before: the spurious one always follows a freeform mode
 * change, and going home never produces one.
 *
 * HyperOS publishes that as `MiuiFreeFormManager.registerFreeformCallback`, part of the
 * small-window adaptation surface it offers apps — the framework even logs an instruction to use
 * the older scheme when a device predates it — so this is an intended integration point rather
 * than a private interface prised open. It is reached by reflection because it exists only on
 * HyperOS, and everything here fails closed: on any other device the class lookup throws, no
 * watch is installed, and the launcher behaves exactly as it would without this file.
 *
 * The callback's arguments are deliberately never read. The interface carries a single method,
 * `dispatchFreeFormStackModeChanged(int action, MiuiFreeFormStackInfo info)`, and *which*
 * transition it describes doesn't matter — measured on device, ordinary use of a floating window
 * (scrolling it, tapping it, dragging it about) produces no callback at all, so any callback at
 * all is the signal. Not parsing the parcel means nothing here depends on the argument layout, the
 * OEM's action numbering, or the shape of a Parcelable this app can't see.
 */
object MiuiFreeformWindows {

    private const val TAG = "MiuiFreeformWindows"
    private const val MANAGER_CLASS = "miui.app.MiuiFreeFormManager"
    private const val CALLBACK_CLASS = "miui.app.IFreeformCallback"

    /**
     * Starts watching, for as long as this process lives, and reports whether this device offers
     * the callback at all — which is to say whether it is running HyperOS.
     *
     * That answer is worth returning rather than discarding: it is the only honest test for the
     * platform whose behaviour [UnrequestedHomeLaunch] exists to correct. "Can this device float a
     * window" is a different question with a different answer — Samsung DeX and desktop windowing
     * on a large screen both report the platform's freeform feature without sharing any of the
     * launcher behaviour dealt with here.
     *
     * Call once, from Application.onCreate: the launcher process outlives every one of its
     * activities, and the changes worth hearing about happen while the home screen is stopped.
     * There is no matching stop — the system drops the callback when the process dies.
     */
    fun watch(onChanged: () -> Unit): Boolean =
        runCatching {
            val manager = Class.forName(MANAGER_CLASS)
            val callbackType = Class.forName(CALLBACK_CLASS)
            manager.getMethod("registerFreeformCallback", callbackType)
                .invoke(null, callback(callbackType, onChanged))
        }.onFailure {
            // Every device that isn't running HyperOS lands here, so this is a fact about the
            // device rather than a fault: log it once and leave the feature switched off.
            Log.i(TAG, "no freeform window callback on this device: ${it.cause ?: it}")
        }.isSuccess

    /**
     * An object the OEM's method will accept, backed by a Binder that does the actual receiving.
     *
     * [Proxy] is what makes the argument type work: `registerFreeformCallback` takes an interface
     * this app cannot implement at compile time because it isn't in the SDK, and a proxy is the
     * standard way to produce an instance of an interface known only at runtime. All the system
     * does with it is ask for `asBinder()` and hand that to system_server, so the proxy answers
     * that one call with a real Binder and the calls come back through [Binder.onTransact].
     */
    private fun callback(callbackType: Class<*>, onChanged: () -> Unit): Any {
        val binder = object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int
            ): Boolean {
                if (code != IBinder.FIRST_CALL_TRANSACTION) {
                    return super.onTransact(code, data, reply, flags)
                }
                // Checks the interface token, which is also what a generated stub does first. A
                // failure means this transaction isn't the one we registered for, so it is
                // swallowed rather than acted on.
                if (runCatching { data.enforceInterface(CALLBACK_CLASS) }.isFailure) return true
                reply?.writeNoException()
                onChanged()
                return true
            }
        }
        return Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "hashCode" -> binder.hashCode()
                "equals" -> args?.getOrNull(0) === proxy
                "toString" -> TAG
                // The single interface method, were it ever called in-process rather than over
                // Binder: same meaning, same response.
                else -> onChanged()
            }
        }
    }
}
