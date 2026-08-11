package dev.neffly.gesturelauncher.drawer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.AppTagStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.settings.SettingsHubActivity
import dev.neffly.gesturelauncher.ui.AlphabetIndexView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The guaranteed fallback: a plain, dependency-free list of all apps with a type-to-filter search
 * bar. Kept intentionally simple so it's the least crash-prone screen in the app.
 */
class AppDrawerActivity : AppCompatActivity() {

    private lateinit var adapter: AppListAdapter
    private lateinit var appList: RecyclerView
    private lateinit var alphabetIndex: AlphabetIndexView
    private lateinit var letterBubble: TextView
    private lateinit var searchInput: TextInputEditText
    private var allApps: List<AppInfo> = emptyList()

    // May be invoked from the LauncherApps callback thread — hop to the main thread first.
    private val onAppsChanged: () -> Unit = { runOnUiThread { loadApps() } }

    private lateinit var drawerRoot: View
    private var isClosing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Suppress the OS's cross-activity transition: on some OEM skins (e.g. HyperOS) it gets
        // replaced with the device's own "app opening" zoom regardless of what's requested here,
        // which reads as popping open from the middle of the screen rather than a drawer sliding
        // up. Animating the drawer's own root view below happens purely inside this activity's
        // view hierarchy, so no OEM transition override can intercept or replace it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        // Edge-to-edge is what makes IME WindowInsets dispatch reliable — adjustNothing alone
        // (see manifest) doesn't guarantee the keyboard's height is actually delivered to an
        // insets listener while decorFitsSystemWindows is left at its non-edge-to-edge default.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_app_drawer)

        drawerRoot = findViewById(R.id.drawerRoot)
        // Only slide up on a genuine open, not on a recreate (e.g. a theme change), where
        // replaying the entry animation would look like the drawer re-opening itself.
        if (savedInstanceState == null) {
            drawerRoot.translationY = resources.displayMetrics.heightPixels.toFloat()
            drawerRoot.animate()
                .translationY(0f)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.drawer_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_gesture_settings -> {
                    startActivity(Intent(this, SettingsHubActivity::class.java))
                    // The hub animates its own slide-in from the right; suppress the OS's default
                    // cross-activity transition the same way MainActivity does when opening this
                    // drawer, for the same OEM-zoom-avoidance reason (see SettingsHubActivity).
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        overridePendingTransition(0, 0)
                    }
                    true
                }
                else -> false
            }
        }

        appList = findViewById(R.id.appList)
        appList.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(
            scope = lifecycleScope,
            onClick = { app -> launchAndClearSearch(app) },
            onLongClick = { app, anchor -> showAppMenu(app, anchor) }
        )
        appList.adapter = adapter

        alphabetIndex = findViewById(R.id.alphabetIndex)
        alphabetIndex.onLetterSelected = { letter -> scrollToLetter(letter) }
        letterBubble = findViewById(R.id.letterBubble)
        alphabetIndex.onLetterTouched = { letter ->
            if (letter == null) {
                letterBubble.visibility = View.GONE
            } else {
                letterBubble.text = letter.toString()
                letterBubble.visibility = View.VISIBLE
            }
        }

        // drawerRoot no longer uses android:fitsSystemWindows — it consumes insets via the legacy
        // combined systemWindowInsets bridge (folding keyboard into system-bar insets) before a
        // child listener ever sees them, so the IME inset the list needs would always read zero.
        // Applied manually instead: system bars/cutout as root padding, keyboard height as extra
        // list bottom padding, so the list stays scrollable above the keyboard (window no longer
        // resizes for it at all — see manifest: adjustNothing).
        val listBasePaddingBottom = appList.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(drawerRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            appList.updatePadding(bottom = listBasePaddingBottom + imeBottom)
            insets
        }

        searchInput = findViewById(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                submitList(AppRepository.filter(allApps, s?.toString().orEmpty()), resetScroll = true)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        // Search-bar Enter/Go launches the top result, same as tapping the first row.
        searchInput.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (actionId == EditorInfo.IME_ACTION_UNSPECIFIED &&
                    event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSearchAction) {
                adapter.firstItem()?.let { launchAndClearSearch(it) }
                true
            } else {
                false
            }
        }

        if (Prefs.autoKeyboard(this)) showKeyboardOnSearch()
    }

    override fun onStart() {
        super.onStart()
        AppRepository.addListener(onAppsChanged)
    }

    override fun onStop() {
        super.onStop()
        AppRepository.removeListener(onAppsChanged)
    }

    override fun onResume() {
        super.onResume()
        // Cheap when the cache is warm — LauncherApps callbacks (see App) invalidate it whenever
        // packages change, so no per-open full rescan is needed anymore.
        loadApps()
    }

    /** Launches [app] and clears the search bar, so a leftover query from this search doesn't
     *  greet the user the next time this same drawer instance resurfaces (e.g. via back/recents —
     *  this activity isn't finished just because another app was launched from it). */
    private fun launchAndClearSearch(app: AppInfo) {
        AppRepository.launch(this, app)
        searchInput.text = null
    }

    private fun showKeyboardOnSearch() {
        searchInput.requestFocus()
        searchInput.post {
            WindowInsetsControllerCompat(window, searchInput)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun finish() {
        if (isClosing || isFinishing) { super.finish(); return }
        isClosing = true
        drawerRoot.animate()
            .translationY(resources.displayMetrics.heightPixels.toFloat())
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { super.finish() }
            .start()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
    }

    private fun loadApps() {
        // Show whatever's cached instantly (usually everything — the warm-up in App primes it),
        // then reconcile against a fresh load off the main thread. lifecycleScope cancels the
        // load if the drawer is closed before it lands.
        AppRepository.cached().takeIf { it.isNotEmpty() }?.let { apps ->
            allApps = apps
            submitList(AppRepository.filter(allApps, searchInput.text?.toString().orEmpty()))
        }
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { AppRepository.load(this@AppDrawerActivity) }
            allApps = apps
            submitList(AppRepository.filter(allApps, searchInput.text?.toString().orEmpty()))
        }
    }

    private fun submitList(items: List<AppInfo>, resetScroll: Boolean = false) {
        // Section headers only make sense over the plain alphabetical order — fuzzy search results
        // are sorted by relevance, so grouping by letter there would read as arbitrarily jumbled.
        adapter.submit(items, showHeaders = searchInput.text.isNullOrBlank()) {
            // Deferred to the diff's commit callback: scrolling right after calling submit() would
            // race the async DiffUtil dispatch and could land against the still-old row count.
            // Only done for search-driven updates — a background app-list refresh shouldn't yank
            // the user back to the top of whatever they were scrolled to.
            if (resetScroll) appList.scrollToPosition(0)
        }
        alphabetIndex.setActiveLetters(items.mapTo(sortedSetOf()) { it.indexLetter() })
        alphabetIndex.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Jumps straight to the first row starting with [letter] (or the closest one after it),
     *  aligned to the top of the list — plain scrollToPosition only guarantees the row becomes
     *  visible, which for a downward jump leaves it stuck at the bottom of the viewport instead. */
    private fun scrollToLetter(letter: Char) {
        val target = adapter.indexOfFirstLabelAtOrAfter(letter) ?: return
        // Kill any in-flight fling first: scrollToPositionWithOffset doesn't cancel scroll
        // momentum, so mid-fling the jump would land and immediately get dragged away again.
        appList.stopScroll()
        (appList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, 0)
    }

    private fun showAppMenu(app: AppInfo, anchor: View) {
        val isSystemApp = runCatching {
            packageManager.getApplicationInfo(app.packageName, 0).flags and
                ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)
        val shortcuts = AppShortcutHelper.queryShortcuts(this, app.packageName)

        PopupMenu(this, anchor).apply {
            menu.add(0, ID_APP_INFO, 0, R.string.app_info).icon = menuIcon(R.drawable.ic_info)
            menu.add(0, ID_LABEL, 1, R.string.add_alias).icon = menuIcon(R.drawable.ic_label)
            if (!isSystemApp) {
                menu.add(0, ID_UNINSTALL, 2, R.string.uninstall).icon = menuIcon(R.drawable.ic_delete)
            }
            shortcuts.forEachIndexed { index, shortcut ->
                val label = shortcut.longLabel ?: shortcut.shortLabel
                val icon = AppShortcutHelper.icon(this@AppDrawerActivity, shortcut)
                menu.add(1, ID_SHORTCUT_BASE + index, index + 3, label).icon =
                    menuIcon(icon ?: ContextCompat.getDrawable(this@AppDrawerActivity, R.drawable.ic_arrow_forward)!!)
            }
            forceShowIcons()
            setOnMenuItemClickListener { item ->
                when {
                    item.itemId == ID_APP_INFO -> { openAppInfo(app); true }
                    item.itemId == ID_LABEL -> { editLabel(app); true }
                    item.itemId == ID_UNINSTALL -> { uninstall(app); true }
                    item.itemId >= ID_SHORTCUT_BASE -> {
                        shortcuts.getOrNull(item.itemId - ID_SHORTCUT_BASE)
                            ?.let { AppShortcutHelper.launch(this@AppDrawerActivity, it) }
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun menuIcon(resId: Int): Drawable? =
        ContextCompat.getDrawable(this, resId)?.let { menuIcon(it) }

    /** Menu-item icons don't get a uniform size for free: our vector drawables are tight 24dp
     *  glyphs, but a real app shortcut's icon from LauncherApps.getShortcutIconDrawable() is a
     *  full adaptive-icon-sized bitmap with its own opaque background baked in — left as-is, the
     *  two groups render at wildly different sizes and read as misaligned. Rasterize every menu
     *  icon into an identical square bitmap so they all occupy the same bounds regardless of
     *  source size. */
    private fun menuIcon(source: Drawable): Drawable {
        val sizePx = (MENU_ICON_DP * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        source.setBounds(0, 0, sizePx, sizePx)
        source.draw(canvas)
        return BitmapDrawable(resources, bitmap)
    }

    /** AppCompat's PopupMenu never renders MenuItem icons unless this (unfortunately internal-only)
     *  flag is set — there's no public API for it. Preferred path: MenuBuilder's restricted-API
     *  setter (stable across AppCompat releases, no reflection). Fallback: the old reflection into
     *  mPopup. On any failure the menu still works fine, just without icons. */
    @SuppressLint("RestrictedApi")
    private fun PopupMenu.forceShowIcons() {
        (menu as? MenuBuilder)?.let {
            it.setOptionalIconsVisible(true)
            return
        }
        runCatching {
            val field = PopupMenu::class.java.getDeclaredField("mPopup")
            field.isAccessible = true
            val menuPopupHelper = field.get(this)
            menuPopupHelper.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                .invoke(menuPopupHelper, true)
        }
    }

    private fun editLabel(app: AppInfo) {
        val view = layoutInflater.inflate(R.layout.dialog_app_label, null)
        val input = view.findViewById<TextInputEditText>(R.id.labelInput)
        input.setText(app.tag.orEmpty())
        input.setSelection(input.text?.length ?: 0)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.app_alias_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    AppTagStore.clearTag(this, app.componentName)
                } else {
                    AppTagStore.setTag(this, app.componentName, text)
                }
                AppRepository.invalidate()
            }
            .setNegativeButton(R.string.cancel, null)
        if (app.tag != null) {
            builder.setNeutralButton(R.string.remove_alias) { _, _ ->
                AppTagStore.clearTag(this, app.componentName)
                AppRepository.invalidate()
            }
        }
        builder.show()
    }

    private fun openAppInfo(app: AppInfo) {
        val uri = Uri.parse("package:${app.packageName}")
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
    }

    private fun uninstall(app: AppInfo) {
        val uri = Uri.parse("package:${app.packageName}")
        runCatching { startActivity(Intent(Intent.ACTION_DELETE, uri)) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val ID_APP_INFO = 1
        private const val ID_UNINSTALL = 2
        private const val ID_LABEL = 3
        private const val ID_SHORTCUT_BASE = 100
        private const val SLIDE_DURATION_MS = 260L
        private const val MENU_ICON_DP = 24
    }
}
