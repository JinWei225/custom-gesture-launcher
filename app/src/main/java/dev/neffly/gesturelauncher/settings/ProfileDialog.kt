package dev.neffly.gesturelauncher.settings

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import dev.neffly.gesturelauncher.R

/**
 * Local-only profile name prompt: shown once on first run (MainActivity, [currentName] == null)
 * and reused for "edit profile" from the settings hub. No account, no network — just a
 * SharedPreferences string (see [dev.neffly.gesturelauncher.data.Prefs.profileName]).
 */
object ProfileDialog {
    fun show(activity: Activity, currentName: String?, onSaved: (String) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_profile_setup, null)
        val input = view.findViewById<TextInputEditText>(R.id.profileNameInput)
        input.setText(currentName)

        val firstRun = currentName == null
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (firstRun) R.string.profile_welcome_title else R.string.edit_profile)
            .setView(view)
            .setPositiveButton(if (firstRun) R.string.get_started else R.string.save) { _, _ ->
                onSaved(input.text?.toString()?.trim().orEmpty())
            }
        if (firstRun) {
            builder.setMessage(R.string.profile_welcome_message).setCancelable(false)
        } else {
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.show()
    }
}
