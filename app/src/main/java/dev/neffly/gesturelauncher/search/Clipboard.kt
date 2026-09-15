package dev.neffly.gesturelauncher.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import dev.neffly.gesturelauncher.R

/** The action behind the answer rows — a sum or a time has nothing to open, so tapping copies it. */
object Clipboard {

    /**
     * Puts [text] on the clipboard under [label] and, where the system doesn't already say so,
     * confirms it. From Android 13 the platform shows its own clipboard confirmation, so a toast
     * there would be the second thing saying the same thing at the same moment; below it the
     * copy would otherwise be silent.
     */
    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        Toast.makeText(context, context.getString(R.string.search_copied, text), Toast.LENGTH_SHORT)
            .show()
    }
}
