package dev.neffly.gesturelauncher.settings

import android.content.Context
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.GestureAction
import dev.neffly.gesturelauncher.data.GestureMapping
import dev.neffly.gesturelauncher.ui.FontEngine
import dev.neffly.gesturelauncher.ui.StrokePreviewView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists saved gesture -> app mappings with a stroke thumbnail and a per-row overflow.
 *
 * Backed by [ListAdapter]/DiffUtil like the drawer's list, so the refresh this screen runs on every
 * resume rebinds only what actually changed — and a delete animates out instead of the whole list
 * blinking. Icons load on [scope] for the same reason the drawer's do: reading one goes through
 * PackageManager, which is IPC, and paying for that per row during bind is main-thread work between
 * the list appearing and its first frame.
 */
class GestureListAdapter(
    private val scope: CoroutineScope,
    private val onOverflow: (GestureMapping, View) -> Unit
) : ListAdapter<GestureMapping, GestureListAdapter.VH>(DIFF) {

    fun submit(list: List<GestureMapping>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gesture, parent, false)
        // Rows outlive the activity's one-shot pass over its content view — see FontEngine.
        FontEngine.applyTo(v)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val mapping = getItem(position)
        holder.label.text = mapping.label
        holder.overflow.setOnClickListener { onOverflow(mapping, it) }

        val firstStroke = mapping.templates.firstOrNull().orEmpty().map { PointF(it.x, it.y) }
        holder.preview.setStroke(firstStroke, mapping.subStrokeLengths.firstOrNull().orEmpty())

        holder.iconJob?.cancel()
        holder.boundId = mapping.id
        when (mapping.action) {
            // The only action whose icon isn't a fixed drawable, and the only one that can be
            // missing: the app a gesture was trained against may have been uninstalled since.
            GestureAction.LAUNCH_APP -> bindAppIcon(holder, mapping)
            GestureAction.OPEN_DRAWER -> bindBuiltInIcon(holder, R.drawable.ic_apps)
            GestureAction.OPEN_URL -> bindBuiltInIcon(holder, R.drawable.ic_link)
            GestureAction.QUICK_SEARCH -> bindBuiltInIcon(holder, R.drawable.ic_search)
        }
    }

    private fun bindBuiltInIcon(holder: VH, resId: Int) {
        holder.icon.setImageResource(resId)
        holder.warning.visibility = View.GONE
    }

    /** The icon and the is-it-still-installed warning both come from PackageManager, so they are
     *  fetched together off the main thread and applied only if the holder still shows the same
     *  gesture by the time they land. */
    private fun bindAppIcon(holder: VH, mapping: GestureMapping) {
        holder.icon.setImageResource(R.drawable.bg_icon_placeholder)
        holder.warning.visibility = View.GONE
        val context = holder.itemView.context.applicationContext
        holder.iconJob = scope.launch {
            val app = withContext(Dispatchers.IO) { appIcon(context, mapping.packageName) }
            if (holder.boundId != mapping.id) return@launch
            holder.icon.setImageDrawable(app.icon)
            holder.warning.visibility = if (app.installed) View.GONE else View.VISIBLE
        }
    }

    private class AppIcon(val icon: Drawable, val installed: Boolean)

    private fun appIcon(context: Context, packageName: String): AppIcon {
        val pm = context.packageManager
        val installed = runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess
        val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
            ?: pm.defaultActivityIcon
        return AppIcon(icon, installed)
    }

    override fun onViewRecycled(holder: VH) {
        holder.iconJob?.cancel()
        super.onViewRecycled(holder)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
        val warning: ImageView = view.findViewById(R.id.warningIcon)
        val preview: StrokePreviewView = view.findViewById(R.id.strokePreview)
        val overflow: ImageButton = view.findViewById(R.id.overflow)
        var iconJob: Job? = null
        var boundId: String? = null
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<GestureMapping>() {
            override fun areItemsTheSame(old: GestureMapping, new: GestureMapping) = old.id == new.id
            override fun areContentsTheSame(old: GestureMapping, new: GestureMapping) = old == new
        }
    }
}
