package dev.neffly.gesturelauncher.settings

import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.PieStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppInfo
import dev.neffly.gesturelauncher.drawer.AppListAdapter
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.drawer.IconCache
import dev.neffly.gesturelauncher.search.SearchScoring
import dev.neffly.gesturelauncher.ui.BaseActivity
import dev.neffly.gesturelauncher.ui.FontEngine
import dev.neffly.gesturelauncher.ui.PieMenuView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chooses and orders the apps on the home screen's pie (see [PieStore]): + adds one from a
 * searchable list, a long-press drags a row to reorder, and the row's button removes it.
 */
class PieAppsActivity : BaseActivity() {

    private val apps = ArrayList<AppInfo>()
    private val adapter = PieAdapter()
    private lateinit var emptyView: View
    private lateinit var addButton: FloatingActionButton
    private var installed: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pie_apps)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        emptyView = findViewById(R.id.emptyView)
        addButton = findViewById(R.id.addButton)
        addButton.setOnClickListener { pickApp() }
        // Until load() has the installed apps there would be nothing to pick from.
        addButton.isEnabled = false

        findViewById<Slider>(R.id.iconSizeSlider).apply {
            valueFrom = PieMenuView.MIN_ICON_DP.toFloat()
            valueTo = PieMenuView.MAX_ICON_DP.toFloat()
            stepSize = PieMenuView.ICON_STEP_DP.toFloat()
            value = Prefs.pieIconDp(this@PieAppsActivity).toFloat()
            addOnChangeListener { _, size, fromUser ->
                if (fromUser) Prefs.setPieIconDp(this@PieAppsActivity, size.toInt())
            }
        }

        val list = findViewById<RecyclerView>(R.id.pieList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                // A row mid-removal has no position any more.
                if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false
                apps.add(b, apps.removeAt(a))
                adapter.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}

            // Saved once the row is dropped, not on every step it passes.
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                save()
            }
        }).attachToRecyclerView(list)

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            installed = withContext(Dispatchers.IO) { AppRepository.load(this@PieAppsActivity) }
                .filter { it.user == Process.myUserHandle() }
            apps.clear()
            apps += PieStore.resolve(PieStore.all(this@PieAppsActivity), installed)
            adapter.notifyDataSetChanged()
            updateChrome()
        }
    }

    private fun save() {
        PieStore.replaceAll(this, apps.map { it.componentName.flattenToString() })
        updateChrome()
    }

    private fun updateChrome() {
        emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        addButton.isEnabled = apps.size < PieStore.MAX_APPS
    }

    /** A searchable list of the apps not on the pie yet; tapping one adds it to the end. */
    private fun pickApp() {
        if (apps.size >= PieStore.MAX_APPS) {
            Toast.makeText(this, getString(R.string.pie_full, PieStore.MAX_APPS), Toast.LENGTH_SHORT).show()
            return
        }
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_app_picker, null)
        FontEngine.applyTo(view)
        val search = view.findViewById<TextInputEditText>(R.id.searchInput)
        val list = view.findViewById<RecyclerView>(R.id.appList)
        val onPie = apps.map { it.key }.toSet()
        val candidates = installed.filter { it.key !in onPie }
        val pickerAdapter = AppListAdapter(scope = lifecycleScope, onClick = { app ->
            apps += app
            adapter.notifyItemInserted(apps.size - 1)
            save()
            sheet.dismiss()
        })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = pickerAdapter
        pickerAdapter.submit(candidates)
        search.doOnTextChanged { text, _, _, _ ->
            pickerAdapter.submit(SearchScoring.rankApps(candidates, text?.toString().orEmpty()))
        }
        sheet.setContentView(view)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    private inner class PieAdapter : RecyclerView.Adapter<PieAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val label: TextView = view.findViewById(R.id.appLabel)
            val remove: View = view.findViewById(R.id.removeButton)
        }

        override fun getItemCount() = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pie_app, parent, false)
            FontEngine.applyTo(view)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            holder.label.text = app.label
            holder.icon.setImageDrawable(IconCache.cached(app))
            if (IconCache.cached(app) == null) {
                lifecycleScope.launch {
                    val icon = withContext(Dispatchers.IO) { IconCache.load(this@PieAppsActivity, app) }
                    if (holder.bindingAdapterPosition in apps.indices && apps[holder.bindingAdapterPosition].key == app.key) {
                        holder.icon.setImageDrawable(icon)
                    }
                }
            }
            holder.remove.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index !in apps.indices) return@setOnClickListener
                apps.removeAt(index)
                notifyItemRemoved(index)
                save()
            }
        }
    }
}
