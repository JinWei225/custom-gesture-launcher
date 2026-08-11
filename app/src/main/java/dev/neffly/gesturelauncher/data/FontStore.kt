package dev.neffly.gesturelauncher.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * The user's imported font file, kept as a private copy in filesDir.
 *
 * A copy rather than a persisted URI grant: `takePersistableUriPermission` only works when the
 * provider offered FLAG_GRANT_PERSISTABLE, and Downloads/Drive URIs stop resolving when the file
 * moves, the provider updates, or external storage unmounts. A launcher has to render its own text
 * on every cold boot without depending on another app's process being alive, so the file has to be
 * ours. A font is a few hundred KB.
 *
 * Reading the installed font is [dev.neffly.gesturelauncher.ui.FontEngine]'s job; this class only
 * gets fonts in and out of storage.
 */
object FontStore {

    private const val DIR = "fonts"
    private const val ACTIVE = "user_font.ttf"
    private const val IMPORT_TMP = "import.tmp"

    /** Generous, but bounds a pathological pick — CJK fonts run large, Latin ones don't. */
    private const val MAX_BYTES = 8L * 1024 * 1024

    /**
     * SAF MIME types offered to the picker. Deliberately loose, including
     * `application/octet-stream`: most file managers report that for a .ttf rather than a real
     * font type, and a filter that misses the user's font looks like the feature is broken. The
     * actual gate is the validation in [import], not this list.
     */
    val PICKER_MIME_TYPES = arrayOf(
        "font/ttf",
        "font/otf",
        "font/sfnt",
        "application/x-font-ttf",
        "application/x-font-otf",
        "application/font-sfnt",
        "application/octet-stream"
    )

    fun file(context: Context): File = File(File(context.filesDir, DIR), ACTIVE)

    fun exists(context: Context): Boolean = file(context).exists()

    /**
     * Copies the font at [uri] into filesDir and validates it. Blocking — call off the main thread.
     *
     * On any failure the previously installed font is left exactly as it was: the copy goes to a
     * temp file and is only renamed over the live one after it has been proven loadable. That
     * ordering is the whole reason this isn't a two-line copy.
     */
    fun import(context: Context, uri: Uri): Result<Typeface> {
        val dir = File(context.filesDir, DIR)
        val tmp = File(dir, IMPORT_TMP)
        return runCatching {
            dir.mkdirs()
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("could not open the selected file")
            require(copied <= MAX_BYTES) { "font is larger than 8 MB" }
            require(!isWebFont(tmp)) { "WOFF web fonts aren't supported — use a .ttf or .otf" }
            require(isSfnt(tmp)) { "that isn't a TrueType or OpenType font" }

            val typeface = Typeface.createFromFile(tmp)
            // createFromFile doesn't reliably throw on malformed input — on some platform versions
            // it just hands back the system default. Identity-checking it is what catches those.
            require(typeface != null && typeface != Typeface.DEFAULT) { "font could not be parsed" }

            val target = file(context)
            target.delete()
            require(tmp.renameTo(target)) { "could not install the font" }
            typeface
        }.also { tmp.delete() }
    }

    /** Removes the installed font, reverting to the system default. */
    fun clear(context: Context) {
        file(context).delete()
    }

    /** The picked file's user-visible name, for the settings row's subtitle. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /** sfnt wrappers Android's Typeface can actually parse: TrueType, OpenType/CFF, the old Apple
     *  variant, and TrueType collections. */
    private fun isSfnt(file: File): Boolean = when (magic(file)) {
        0x00010000, 0x4F54544F /* OTTO */, 0x74727565 /* true */, 0x74746366 /* ttcf */ -> true
        else -> false
    }

    /** WOFF/WOFF2 are common downloads from font sites and look like fonts to the user, but
     *  Typeface can't read either — worth naming in the error rather than "not a font". */
    private fun isWebFont(file: File): Boolean =
        magic(file) == 0x774F4646 /* wOFF */ || magic(file) == 0x774F4632 /* wOF2 */

    /** First four bytes, big-endian, or 0 if the file is too short to have any. */
    private fun magic(file: File): Int = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) < 4) return@use 0
            (header[0].toInt() and 0xFF shl 24) or
                (header[1].toInt() and 0xFF shl 16) or
                (header[2].toInt() and 0xFF shl 8) or
                (header[3].toInt() and 0xFF)
        }
    }.getOrDefault(0)
}
