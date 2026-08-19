package dev.neffly.gesturelauncher.search

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import dev.neffly.gesturelauncher.R
import java.io.File

/**
 * Filename search over MediaStore.
 *
 * MediaStore rather than a filesystem walk: it's an already-built index, so a query costs a DB
 * lookup instead of a recursive scan, and with All-files access (see [FilePermissions]) it returns
 * non-media files too. The trade-off is that a file the media scanner hasn't seen yet is invisible
 * here — acceptable for a search box, and far better than several seconds of walking per keystroke.
 */
object FileSearcher {

    private const val TAG = "FileSearcher"

    /** Below this, a LIKE over every file on the device matches too much to be useful. */
    const val MIN_QUERY_LENGTH = 2

    /** Rows pulled from the cursor before fuzzy re-ranking. Bounds the work on a broad query. */
    private const val CANDIDATE_LIMIT = 200

    /** Blocking — call off the main thread. Returns at most [limit] hits, best match first. */
    fun search(context: Context, query: String, limit: Int = 8): List<FileHit> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return emptyList()
        if (!FilePermissions.isGranted(context)) return emptyList()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn =
            if (hasRelativePath) MediaStore.MediaColumns.RELATIVE_PATH
            else @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            pathColumn
        )
        // MIME_TYPE IS NOT NULL drops the directory rows MediaStore.Files also carries.
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\' AND " +
            "${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL"
        val args = arrayOf("%${escapeLike(trimmed)}%")
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val candidates = ArrayList<FileHit>(CANDIDATE_LIMIT)
        runCatching {
            context.contentResolver.query(collection, projection, selection, args, order)
        }.onFailure {
            Log.w(TAG, "MediaStore query failed", it)
        }.getOrNull()?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val pathCol = cursor.getColumnIndex(pathColumn)
            while (cursor.moveToNext() && candidates.size < CANDIDATE_LIMIT) {
                val name = cursor.getString(nameCol) ?: continue
                val rawPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                candidates += FileHit(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                    name = name,
                    mimeType = cursor.getString(mimeCol).orEmpty(),
                    folder = folderLabel(rawPath, hasRelativePath)
                )
            }
        }

        // Ranked by the same scorer as app labels, so "gm" behaves consistently across sections.
        val normalizedQuery = SearchScoring.normalize(trimmed)
        return candidates
            .mapNotNull { hit ->
                SearchScoring.fuzzyScore(SearchScoring.normalize(hit.name), normalizedQuery)
                    ?.let { hit to it }
            }
            .sortedWith(compareByDescending<Pair<FileHit, Int>> { it.second }.thenBy { it.first.name })
            .take(limit)
            .map { it.first }
    }

    /** Opens [hit] in whatever app handles its type. Returns whether anything took it. */
    /** The viewer intent for [hit]. Split out from [open] so a floating-window launch can attach
     *  its own ActivityOptions to the same intent. */
    fun intentFor(hit: FileHit): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(hit.uri, hit.mimeType.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(context: Context, hit: FileHit): Boolean {
        val opened = runCatching { context.startActivity(intentFor(hit)) }.isSuccess
        if (!opened) {
            Toast.makeText(context, R.string.file_open_failed, Toast.LENGTH_SHORT).show()
        }
        return opened
    }

    /**
     * Trailing folder name for the row's subtitle. RELATIVE_PATH is already a bare
     * "Download/Invoices/" style path; DATA (API 26-28) is an absolute file path, so take its
     * parent directory instead.
     */
    private fun folderLabel(rawPath: String?, isRelativePath: Boolean): String {
        val path = rawPath?.takeIf { it.isNotBlank() } ?: return ""
        return if (isRelativePath) path.trim('/') else File(path).parent?.trim('/').orEmpty()
    }

    /** LIKE treats % and _ as wildcards; a query containing them should match them literally. */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Icon bucket for a MIME type — the row shows a glyph, not a thumbnail. */
    fun iconFor(mimeType: String): Int = when {
        mimeType.startsWith("image/") -> R.drawable.ic_file_image
        mimeType.startsWith("video/") -> R.drawable.ic_file_video
        mimeType.startsWith("audio/") -> R.drawable.ic_file_audio
        else -> R.drawable.ic_file
    }

}
