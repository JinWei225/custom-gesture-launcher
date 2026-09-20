package dev.neffly.gesturelauncher.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.ui.Glass
import dev.neffly.gesturelauncher.ui.SlidePanelActivity
import dev.neffly.gesturelauncher.ui.overrideOwnTransitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every widget installed on the device, grouped by app, for the widgets page to add one from.
 *
 * The launcher's own rather than the platform's `ACTION_APPWIDGET_PICK`: on HyperOS the Settings
 * activity behind that action crashes while inflating its list, so the platform picker is a
 * dead end on the very devices this launcher is used on. Hands the chosen provider back as
 * [EXTRA_PROVIDER]; binding it is the page's job.
 */
class WidgetPickerActivity : SlidePanelActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideOwnTransitions()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_widget_picker)

        val root = findViewById<View>(R.id.widgetPickerRoot)
        Glass.frost(window, root) { veil -> root.setBackgroundColor(veil) }
        slideIn(root, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.providerList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ProviderAdapter(rows())
    }

    /** App headers with their widgets under them, apps and widgets each in label order. */
    private fun rows(): List<Row> {
        val pm = packageManager
        val providers = AppWidgetManager.getInstance(this).installedProviders
        val byApp = providers.groupBy { it.provider.packageName }
        val apps = byApp.keys.mapNotNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        }.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        return buildList {
            for (app in apps) {
                add(Row.App(pm.getApplicationLabel(app).toString(), app.loadIcon(pm)))
                byApp.getValue(app.packageName)
                    .map { Row.Widget(it, it.loadLabel(pm)) }
                    .sortedBy { it.label.lowercase() }
                    .forEach { add(it) }
            }
        }
    }

    private sealed class Row {
        class App(val label: String, val icon: Drawable) : Row()
        class Widget(val info: AppWidgetProviderInfo, val label: String) : Row()
    }

    private inner class ProviderAdapter(private val rows: List<Row>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.App) TYPE_APP else TYPE_WIDGET

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_APP) {
                AppVH(inflater.inflate(R.layout.item_widget_pick_app, parent, false))
            } else {
                WidgetVH(inflater.inflate(R.layout.item_widget_pick, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.App -> (holder as AppVH).bind(row)
                is Row.Widget -> (holder as WidgetVH).bind(row)
            }
        }
    }

    private class AppVH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val label: TextView = view.findViewById(R.id.appLabel)

        fun bind(row: Row.App) {
            icon.setImageDrawable(row.icon)
            label.text = row.label
        }
    }

    private inner class WidgetVH(view: View) : RecyclerView.ViewHolder(view) {
        private val preview: ImageView = view.findViewById(R.id.widgetPreview)
        private val label: TextView = view.findViewById(R.id.widgetLabel)
        private val description: TextView = view.findViewById(R.id.widgetDescription)
        private var bound: AppWidgetProviderInfo? = null

        fun bind(row: Row.Widget) {
            val info = row.info
            bound = info
            label.text = row.label
            val text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.loadDescription(itemView.context) else null
            description.text = text
            description.isVisible = !text.isNullOrBlank()
            itemView.setOnClickListener { pick(info) }
            // Previews are full-size bitmaps decoded from the provider's package; off the main
            // thread, and dropped if the row has moved on to another widget by the time one lands.
            preview.setImageDrawable(null)
            lifecycleScope.launch {
                val drawable = withContext(Dispatchers.IO) { previewOf(info) }
                if (bound === info) preview.setImageDrawable(drawable)
            }
        }
    }

    private fun pick(info: AppWidgetProviderInfo) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PROVIDER, info.provider))
        finish()
    }

    /** The provider's preview, or its icon when it draws its preview from a layout instead
     *  (API 31+) or declares none at all. */
    private fun previewOf(info: AppWidgetProviderInfo): Drawable? =
        runCatching { info.loadPreviewImage(this, 0) }.getOrNull()
            ?: runCatching { info.loadIcon(this, 0) }.getOrNull()

    companion object {
        /** The chosen provider's [ComponentName], on a RESULT_OK result. */
        const val EXTRA_PROVIDER = "provider"

        private const val TYPE_APP = 0
        private const val TYPE_WIDGET = 1

        fun intent(context: Context) = Intent(context, WidgetPickerActivity::class.java)
    }
}
