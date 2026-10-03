package dev.neffly.gesturelauncher.lock

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.neffly.gesturelauncher.R

/**
 * The device-admin grant behind the home screen's double-tap lock.
 *
 * Locking used to go through an accessibility service, and banking apps refuse to run while any
 * accessibility service from an unrecognised source is enabled — the lock button cost the user their
 * bank. Device admin with the single `force-lock` policy (see `res/xml/lock_device_admin.xml`) locks
 * the screen without that, and unlike accessibility it can be requested straight from the app: the
 * system shows its own confirmation screen.
 *
 * What it asks for is exactly [DevicePolicyManager.lockNow] and nothing else — no password policy,
 * no wipe. The one cost is that Android won't uninstall an active admin, so the grant has to be
 * revoked in Settings → Security → Device admin apps first.
 */
class LockDeviceAdmin : DeviceAdminReceiver()

object LockAdmin {

    private fun component(context: Context) = ComponentName(context, LockDeviceAdmin::class.java)

    private fun policyManager(context: Context) =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isActive(context: Context): Boolean =
        policyManager(context).isAdminActive(component(context))

    /** Locks the screen; only valid while [isActive]. */
    fun lockNow(context: Context) = policyManager(context).lockNow()

    /** The system's own confirmation screen for granting the admin. */
    fun requestIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.lock_admin_explanation)
            )
}
