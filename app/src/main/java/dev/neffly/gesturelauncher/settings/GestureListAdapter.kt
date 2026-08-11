package dev.neffly.gesturelauncher.settings

import android.graphics.PointF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.GestureAction
import dev.neffly.gesturelauncher.data.GestureMapping
import dev.neffly.gesturelauncher.ui.FontEngine
import dev.neffly.gesturelauncher.ui.StrokePreviewView

/** Lists saved gesture -> app mappings with a stroke thumbnail and a per-row overflow. */
class GestureListAdapter(
    private val onOverflow: (GestureMapping, View) -> Unit
) : RecyclerView.Adapter<GestureListAdapter.VH>() {

    private val items = ArrayList<GestureMapping>()

    fun submit(list: List<GestureMapping>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gesture, parent, false)
        // Rows outlive the activity's one-shot pass over its content view — see FontEngine.
        FontEngine.applyTo(v)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val mapping = items[position]
        holder.label.text = mapping.label

        when (mapping.action) {
            GestureAction.LAUNCH_APP -> {
                val pm = holder.itemView.context.packageManager
                val installed = runCatching { pm.getApplicationInfo(mapping.packageName, 0) }.isSuccess
                holder.icon.setImageDrawable(
                    runCatching { pm.getApplicationIcon(mapping.packageName) }
                        .getOrNull() ?: pm.defaultActivityIcon
                )
                holder.warning.visibility = if (installed) View.GONE else View.VISIBLE
            }
            GestureAction.OPEN_DRAWER -> {
                holder.icon.setImageResource(R.drawable.ic_apps)
                holder.warning.visibility = View.GONE
            }
            GestureAction.OPEN_URL -> {
                holder.icon.setImageResource(R.drawable.ic_link)
                holder.warning.visibility = View.GONE
            }
        }

        val firstStroke = mapping.templates.firstOrNull().orEmpty().map { PointF(it.x, it.y) }
        holder.preview.setStroke(firstStroke, mapping.subStrokeLengths.firstOrNull().orEmpty())

        holder.overflow.setOnClickListener { onOverflow(mapping, it) }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
        val warning: ImageView = view.findViewById(R.id.warningIcon)
        val preview: StrokePreviewView = view.findViewById(R.id.strokePreview)
        val overflow: ImageButton = view.findViewById(R.id.overflow)
    }
}
