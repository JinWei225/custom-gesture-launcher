package dev.neffly.gesturelauncher.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.ui.overrideNextTransition
import kotlin.math.roundToInt

/**
 * The home screen's widgets page: app widgets stacked top to bottom, scrolling as one column.
 *
 * Each widget is as wide as the page and as tall as its grip has been dragged to. Rearranging is
 * an edit mode: Edit shows a row under every widget with move up, move down, the grip and
 * remove, and Done hides them again. Adding goes through [WidgetPickerActivity], then a bind the
 * system has to have allowed this launcher to make.
 *
 * Lives inside the home activity rather than being a screen of its own: it is a page of the home
 * screen, reached by paging sideways from it. The activity forwards the lifecycle moments the host
 * needs ([onStart], [onStop]) and the configuration result it can only receive itself
 * ([onActivityResult]). The widget ids the host allocates are what persist (see [WidgetStore]);
 * a host with the same id picks them up again.
 */
class WidgetPage(private val activity: AppCompatActivity, page: View) {

    private val column: LinearLayout = page.findViewById(R.id.widgetColumn)
    private val addButton: View = page.findViewById(R.id.addWidgetButton)
    private val editButton: TextView = page.findViewById(R.id.editWidgetsButton)
    private val manager: AppWidgetManager = AppWidgetManager.getInstance(activity)

    /** The context every widget view is built with — see [WidgetContext]. */
    private val widgetContext: Context = WidgetContext(activity)
    private val host = AppWidgetHost(widgetContext, HOST_ID)

    private val entries: MutableList<WidgetEntry> = WidgetStore.load(activity).toMutableList()

    /** Whether the edit rows are showing. Rearranging is rare next to reading, and a row of
     *  controls between every pair of widgets is clutter the rest of the time. */
    private var editing = false

    /** The id allocated for the widget being added, until binding, or its configuration screen,
     *  says whether it is wanted. */
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val pickWidget: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val provider: ComponentName? = result.data?.let { data ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data.getParcelableExtra(WidgetPickerActivity.EXTRA_PROVIDER, ComponentName::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelableExtra(WidgetPickerActivity.EXTRA_PROVIDER)
                }
            }
            if (result.resultCode == Activity.RESULT_OK && provider != null) bind(provider)
        }

    /** The system's "allow this launcher to create widgets" dialog, for the first bind. */
    private val bindWidget: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val id = pendingId
            pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return@registerForActivityResult
            if (result.resultCode == Activity.RESULT_OK) onBound(id) else host.deleteAppWidgetId(id)
        }

    init {
        addButton.setOnClickListener { pick() }
        editButton.setOnClickListener { setEditing(!editing) }
        showAll()
    }

    private fun setEditing(on: Boolean) {
        editing = on
        for (i in 0 until entries.size) {
            column.getChildAt(i).findViewById<View>(R.id.editRow).isVisible = on
        }
        editButton.setText(if (on) R.string.widgets_done else R.string.widgets_edit)
        editButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (on) R.drawable.ic_check else R.drawable.ic_edit, 0, 0, 0
        )
    }

    /** The first widget can't move up and the last can't move down: those arrows dim. */
    private fun refreshEditRows() {
        for (i in 0 until entries.size) {
            val row = column.getChildAt(i)
            row.findViewById<View>(R.id.moveUpButton).setEnabledLook(i > 0)
            row.findViewById<View>(R.id.moveDownButton).setEnabledLook(i < entries.lastIndex)
        }
    }

    private fun View.setEnabledLook(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    fun onStart() = host.startListening()

    fun onStop() = host.stopListening()

    /** Returns true when the result was a widget configuration screen's, see [REQUEST_CONFIGURE]. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CONFIGURE) return false
        val id = pendingId
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return true
        val info = manager.getAppWidgetInfo(id)
        if (resultCode == Activity.RESULT_OK && info != null) add(id, info) else host.deleteAppWidgetId(id)
        return true
    }

    // --- the column ---------------------------------------------------------

    /** Builds the column from [entries], dropping any whose provider has gone. */
    private fun showAll() {
        val gone = entries.filter { manager.getAppWidgetInfo(it.id) == null }
        if (gone.isNotEmpty()) {
            gone.forEach { host.deleteAppWidgetId(it.id) }
            entries.removeAll(gone)
            WidgetStore.save(activity, entries)
        }
        for ((index, entry) in entries.withIndex()) {
            column.addView(itemFor(entry, manager.getAppWidgetInfo(entry.id)), index)
        }
        refreshEditRows()
    }

    private fun itemFor(entry: WidgetEntry, info: AppWidgetProviderInfo): View {
        val item = LayoutInflater.from(activity).inflate(R.layout.item_widget, column, false)
        val frame = item.findViewById<FrameLayout>(R.id.widgetFrame)
        frame.updateLayoutParams<ViewGroup.LayoutParams> { height = dp(entry.heightDp) }

        // Through the host, not built by hand: the host only delivers a provider's updates to the
        // views it created itself.
        val view = host.createView(widgetContext, entry.id, info)
        frame.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        reportSize(view, entry.heightDp)

        item.findViewById<View>(R.id.editRow).isVisible = editing
        item.findViewById<View>(R.id.resizeHandle).setOnTouchListener(Resizer(frame, view, entry.id))
        // Looked up at the tap, not captured now: the row's place in the column changes as
        // widgets move around it.
        item.findViewById<View>(R.id.moveUpButton).setOnClickListener {
            indexOf(entry.id)?.let { moveWidget(it, it - 1) }
        }
        item.findViewById<View>(R.id.moveDownButton).setOnClickListener {
            indexOf(entry.id)?.let { moveWidget(it, it + 1) }
        }
        item.findViewById<View>(R.id.removeButton).setOnClickListener {
            indexOf(entry.id)?.let { removeWidget(it) }
        }
        return item
    }

    private fun indexOf(id: Int): Int? = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    private fun moveWidget(from: Int, to: Int) {
        if (to !in entries.indices) return
        entries.add(to, entries.removeAt(from))
        val item = column.getChildAt(from)
        column.removeViewAt(from)
        column.addView(item, to)
        WidgetStore.save(activity, entries)
        refreshEditRows()
    }

    private fun removeWidget(index: Int) {
        host.deleteAppWidgetId(entries[index].id)
        entries.removeAt(index)
        column.removeViewAt(index)
        WidgetStore.save(activity, entries)
        refreshEditRows()
    }

    // --- adding -------------------------------------------------------------

    private fun pick() {
        pickWidget.launch(WidgetPickerActivity.intent(activity))
        activity.overrideNextTransition()
    }

    /**
     * Binds a freshly allocated id to [provider]. The direct bind only succeeds once the user has
     * allowed this launcher to create widgets; until then the system asks them through
     * [bindWidget], which then binds on their behalf.
     */
    private fun bind(provider: ComponentName) {
        val id = host.allocateAppWidgetId()
        if (manager.bindAppWidgetIdIfAllowed(id, provider)) {
            onBound(id)
            return
        }
        pendingId = id
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
        runCatching { bindWidget.launch(intent) }.onFailure {
            pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
            host.deleteAppWidgetId(id)
            Toast.makeText(activity, R.string.widget_bind_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** A bound widget is either added outright or sent through its configuration screen first. */
    private fun onBound(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info == null) {
            host.deleteAppWidgetId(id)
            return
        }
        if (info.configure == null) {
            add(id, info)
            return
        }
        // The configuration screen reports back through the activity's onActivityResult: the
        // host API predates the result contracts and has no other way to be started.
        pendingId = id
        runCatching {
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
        }.onFailure {
            pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
            host.deleteAppWidgetId(id)
            Toast.makeText(activity, R.string.widget_bind_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun add(id: Int, info: AppWidgetProviderInfo) {
        // The provider's minimum is a floor, not a preference: most declare a height sized for a
        // launcher cell, and a widget that is all header at that size is what the grip is for.
        val heightDp = (info.minHeight / activity.resources.displayMetrics.density).roundToInt()
            .coerceIn(MIN_HEIGHT_DP, maxHeightDp())
        val entry = WidgetEntry(id, heightDp)
        entries.add(entry)
        WidgetStore.save(activity, entries)
        // Above the buttons, which stay the column's last child.
        column.addView(itemFor(entry, info), entries.lastIndex)
        refreshEditRows()
    }

    // --- sizing -------------------------------------------------------------

    /** Tells the widget the size it has, so providers that adapt their layout to it can. */
    private fun reportSize(view: AppWidgetHostView, heightDp: Int) {
        val density = activity.resources.displayMetrics.density
        val widthDp = ((column.width - column.paddingLeft - column.paddingRight) / density).roundToInt()
        if (widthDp <= 0) {
            // Not laid out yet — the first widgets are built before the column has a width.
            column.post { reportSize(view, heightDp) }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
        }
    }

    /** Tall enough to fill the page and no taller: a widget that outgrows the screen has nowhere
     *  left to show its bottom. */
    private fun maxHeightDp(): Int {
        val metrics = activity.resources.displayMetrics
        return ((metrics.heightPixels * MAX_HEIGHT_FRACTION) / metrics.density).roundToInt()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    /**
     * Drags the widget's frame taller or shorter with the grip under it, and keeps what it lands
     * on. Everything above is told to keep its hands off for the duration — the scroll view, and
     * the pager it sits in — or a drag would scroll the page, or turn it, as well as grow the
     * widget.
     */
    private inner class Resizer(
        private val frame: View,
        private val view: AppWidgetHostView,
        private val id: Int
    ) : View.OnTouchListener {
        private var startY = 0f
        private var startHeight = 0

        override fun onTouch(handle: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startHeight = frame.height
                    handle.parent.requestDisallowInterceptTouchEvent(true)
                    handle.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val height = (startHeight + (event.rawY - startY)).roundToInt()
                        .coerceIn(dp(MIN_HEIGHT_DP), dp(maxHeightDp()))
                    frame.updateLayoutParams<ViewGroup.LayoutParams> { this.height = height }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handle.isPressed = false
                    val heightDp = (frame.layoutParams.height / activity.resources.displayMetrics.density).roundToInt()
                    val index = entries.indexOfFirst { it.id == id }
                    if (index >= 0 && heightDp != entries[index].heightDp) {
                        entries[index] = entries[index].copy(heightDp = heightDp)
                        WidgetStore.save(activity, entries)
                        reportSize(view, heightDp)
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                }
            }
            return true
        }
    }

    /**
     * The activity, with a plain layout inflater.
     *
     * AppCompat installs a factory on the activity's inflater that swaps every `ImageView` for an
     * `AppCompatImageView`, and so on. A widget's layout is inflated through the context its host
     * view was given, and RemoteViews can only drive the platform classes it names: its
     * `setImageResource` on an AppCompat view throws, and the widget shows "Couldn't add widget"
     * in place of every row that sets an image. The application's inflater has no such factory;
     * this hands it out in place of the activity's, and leaves everything else — theme, window,
     * starting activities from a widget's tap — to the activity.
     */
    private class WidgetContext(activity: Activity) : ContextWrapper(activity) {
        private val inflater: LayoutInflater by lazy {
            LayoutInflater.from(baseContext.applicationContext).cloneInContext(this)
        }

        override fun getSystemService(name: String): Any? =
            if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
    }

    private companion object {
        /** Identifies this host's widget ids to the system; must never change once widgets exist. */
        const val HOST_ID = 0x4753

        /** Widget configuration screens report back by request code, see [onActivityResult]. */
        const val REQUEST_CONFIGURE = 1
        const val MIN_HEIGHT_DP = 56
        const val MAX_HEIGHT_FRACTION = 0.85f
        const val DISABLED_ALPHA = 0.35f
    }
}
