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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.ui.BaseActivity
import dev.neffly.gesturelauncher.ui.FontEngine
import dev.neffly.gesturelauncher.ui.Glass
import dev.neffly.gesturelauncher.ui.overrideNextTransition
import dev.neffly.gesturelauncher.ui.overrideOwnTransitions
import dev.neffly.gesturelauncher.ui.showWithFont
import kotlin.math.roundToInt

/**
 * A page of app widgets, stacked top to bottom and scrolling as one column.
 *
 * Each widget is as wide as the page and as tall as its grip has been dragged to; a long-press on
 * it offers to move it up or down the column, or remove it. Adding goes through
 * [WidgetPickerActivity], then a bind the system has to have allowed this launcher to make.
 *
 * The host is created fresh with each page; the widget ids it allocated are what persist (see
 * [WidgetStore]), and a host with the same id picks them up again. Listening is confined to the
 * time the page is on screen, so widgets that update often cost nothing while it isn't.
 */
class WidgetPageActivity : BaseActivity() {

    private lateinit var root: View
    private lateinit var column: LinearLayout
    private lateinit var emptyLabel: TextView
    private lateinit var host: WidgetHost
    private lateinit var manager: AppWidgetManager

    /** The context every widget view is built with — see [WidgetContext]. */
    private lateinit var widgetContext: Context

    private var entries: MutableList<WidgetEntry> = mutableListOf()

    /** The id allocated for the widget being added, until binding, or its configuration screen,
     *  says whether it is wanted. */
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val pickWidget =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val provider: ComponentName? = result.data?.let { data ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data.getParcelableExtra(WidgetPickerActivity.EXTRA_PROVIDER, ComponentName::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelableExtra(WidgetPickerActivity.EXTRA_PROVIDER)
                }
            }
            if (result.resultCode != Activity.RESULT_OK || provider == null) return@registerForActivityResult
            bind(provider)
        }

    /** The system's "allow this launcher to create widgets" dialog, for the first bind. */
    private val bindWidget =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val id = pendingId
            pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return@registerForActivityResult
            if (result.resultCode == Activity.RESULT_OK) onBound(id) else host.deleteAppWidgetId(id)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same arrangement as the drawer: the page animates its own root in, so no OEM "app
        // opening" zoom can replace the slide, and hands the slide-out to the window so it plays
        // for Home as well as Back.
        overrideOwnTransitions(closeExit = R.anim.drawer_slide_out)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_widget_page)

        root = findViewById(R.id.widgetPageRoot)
        Glass.frost(window, root) { veil -> root.setBackgroundColor(veil) }
        if (savedInstanceState == null) {
            root.translationY = resources.displayMetrics.heightPixels.toFloat()
            root.animate()
                .translationY(0f)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.widget_page_menu)
        FontEngine.applyTo(toolbar.menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_widget -> { pick(); true }
                else -> false
            }
        }

        column = findViewById(R.id.widgetColumn)
        emptyLabel = findViewById(R.id.emptyLabel)
        manager = AppWidgetManager.getInstance(this)
        widgetContext = WidgetContext(this)
        host = WidgetHost(widgetContext)

        entries = WidgetStore.load(this).toMutableList()
        showAll()
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        super.onStop()
        host.stopListening()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        val id = pendingId
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val info = manager.getAppWidgetInfo(id)
        if (resultCode == Activity.RESULT_OK && info != null) add(id, info) else host.deleteAppWidgetId(id)
    }

    // --- the column ---------------------------------------------------------

    /** Builds the column from [entries], dropping any whose provider has gone. */
    private fun showAll() {
        column.removeAllViews()
        val gone = entries.filter { manager.getAppWidgetInfo(it.id) == null }
        if (gone.isNotEmpty()) {
            gone.forEach { host.deleteAppWidgetId(it.id) }
            entries.removeAll(gone)
            WidgetStore.save(this, entries)
        }
        for (entry in entries) column.addView(itemFor(entry, manager.getAppWidgetInfo(entry.id)))
        emptyLabel.isVisible = entries.isEmpty()
    }

    private fun itemFor(entry: WidgetEntry, info: AppWidgetProviderInfo): View {
        val item = LayoutInflater.from(this).inflate(R.layout.item_widget, column, false)
        val frame = item.findViewById<FrameLayout>(R.id.widgetFrame)
        frame.updateLayoutParams<ViewGroup.LayoutParams> { height = dp(entry.heightDp) }

        // Through the host, not built by hand: the host only delivers a provider's updates to the
        // views it created itself.
        val view = host.createView(widgetContext, entry.id, info) as WidgetHostView
        view.setOnLongClickListener { showMenu(entry.id); true }
        frame.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        reportSize(view, entry.heightDp)

        item.findViewById<View>(R.id.resizeHandle).setOnTouchListener(Resizer(frame, view, entry.id))
        return item
    }

    private fun showMenu(id: Int) {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return
        val actions = buildList<Pair<Int, () -> Unit>> {
            if (index > 0) add(R.string.widget_move_up to { moveWidget(index, index - 1) })
            if (index < entries.lastIndex) add(R.string.widget_move_down to { moveWidget(index, index + 1) })
            add(R.string.widget_remove to { removeWidget(index) })
        }
        AlertDialog.Builder(this)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .showWithFont()
    }

    private fun moveWidget(from: Int, to: Int) {
        entries.add(to, entries.removeAt(from))
        val item = column.getChildAt(from)
        column.removeViewAt(from)
        column.addView(item, to)
        WidgetStore.save(this, entries)
    }

    private fun removeWidget(index: Int) {
        host.deleteAppWidgetId(entries[index].id)
        entries.removeAt(index)
        column.removeViewAt(index)
        WidgetStore.save(this, entries)
        emptyLabel.isVisible = entries.isEmpty()
    }

    // --- adding -------------------------------------------------------------

    private fun pick() {
        pickWidget.launch(WidgetPickerActivity.intent(this))
        overrideNextTransition()
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
            Toast.makeText(this, R.string.widget_bind_failed, Toast.LENGTH_SHORT).show()
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
        // The configuration screen reports back through onActivityResult: the host API predates
        // the result contracts and has no other way to be started.
        pendingId = id
        runCatching {
            host.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE, null)
        }.onFailure {
            pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
            host.deleteAppWidgetId(id)
            Toast.makeText(this, R.string.widget_bind_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun add(id: Int, info: AppWidgetProviderInfo) {
        // The provider's minimum is a floor, not a preference: most declare a height sized for a
        // launcher cell, and a widget that is all header at that size is what the grip is for.
        val heightDp = (info.minHeight / resources.displayMetrics.density).roundToInt()
            .coerceIn(MIN_HEIGHT_DP, maxHeightDp())
        val entry = WidgetEntry(id, heightDp)
        entries.add(entry)
        WidgetStore.save(this, entries)
        column.addView(itemFor(entry, info))
        emptyLabel.isVisible = false
    }

    // --- sizing -------------------------------------------------------------

    /** Tells the widget the size it has, so providers that adapt their layout to it can. */
    private fun reportSize(view: WidgetHostView, heightDp: Int) {
        val widthDp = ((column.width - column.paddingLeft - column.paddingRight) /
            resources.displayMetrics.density).roundToInt()
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
    private fun maxHeightDp(): Int =
        ((resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION) / resources.displayMetrics.density).roundToInt()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    /**
     * Drags the widget's frame taller or shorter with the grip under it, and keeps what it lands
     * on. The scroll view is told to keep its hands off for the duration, or a drag downward
     * would scroll the page as well as grow the widget.
     */
    private inner class Resizer(
        private val frame: View,
        private val view: WidgetHostView,
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
                    val heightDp = (frame.layoutParams.height / resources.displayMetrics.density).roundToInt()
                    val index = entries.indexOfFirst { it.id == id }
                    if (index >= 0 && heightDp != entries[index].heightDp) {
                        entries[index] = entries[index].copy(heightDp = heightDp)
                        WidgetStore.save(this@WidgetPageActivity, entries)
                        reportSize(view, heightDp)
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                }
            }
            return true
        }
    }

    /**
     * This activity, with a plain layout inflater.
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

    /** The host whose views are [WidgetHostView]s, so every widget on the page can be long-pressed. */
    private class WidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo?
        ): AppWidgetHostView = WidgetHostView(context)
    }

    private companion object {
        /** Identifies this host's widget ids to the system; must never change once widgets exist. */
        const val HOST_ID = 0x4753

        /** Widget configuration screens report back by request code, see [onActivityResult]. */

        const val REQUEST_CONFIGURE = 1
        const val SLIDE_DURATION_MS = 260L
        const val MIN_HEIGHT_DP = 56
        const val MAX_HEIGHT_FRACTION = 0.85f
    }
}
