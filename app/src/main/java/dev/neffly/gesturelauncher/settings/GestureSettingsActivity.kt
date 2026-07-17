package dev.neffly.gesturelauncher.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.GestureAction
import dev.neffly.gesturelauncher.data.GestureMapping
import dev.neffly.gesturelauncher.data.GestureStore

/** Management hub: list existing gestures, add new ones, edit (redraw / change app) and delete. */
class GestureSettingsActivity : AppCompatActivity() {

    private lateinit var adapter: GestureListAdapter
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gesture_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        emptyView = findViewById(R.id.emptyView)
        val list = findViewById<RecyclerView>(R.id.gestureList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = GestureListAdapter { mapping, anchor -> showRowMenu(mapping, anchor) }
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.addButton).setOnClickListener {
            startActivity(Intent(this, GestureActionChooserActivity::class.java))
            // The chooser animates its own slide-in; suppress the OS's default cross-activity
            // transition the same way MainActivity does when opening the drawer.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = GestureStore.all(this)
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showRowMenu(mapping: GestureMapping, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, ID_REDRAW, 0, R.string.redraw_gesture)
            when (mapping.action) {
                GestureAction.LAUNCH_APP -> menu.add(0, ID_CHANGE, 1, R.string.change_app)
                GestureAction.OPEN_URL -> menu.add(0, ID_CHANGE, 1, R.string.edit_url)
                GestureAction.OPEN_DRAWER -> {} // nothing to change beyond redrawing the shape
            }
            menu.add(0, ID_DELETE, 2, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ID_REDRAW -> {
                        startActivity(GestureTrainingActivity.redrawIntent(this@GestureSettingsActivity, mapping.id))
                        true
                    }
                    ID_CHANGE -> {
                        val intent = if (mapping.action == GestureAction.OPEN_URL) {
                            GestureUrlEntryActivity.editIntent(this@GestureSettingsActivity, mapping.id)
                        } else {
                            GestureTrainingActivity.changeAppIntent(this@GestureSettingsActivity, mapping.id)
                        }
                        startActivity(intent)
                        true
                    }
                    ID_DELETE -> { deleteWithUndo(mapping); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun deleteWithUndo(mapping: GestureMapping) {
        GestureStore.remove(this, mapping.id)
        refresh()
        Snackbar.make(findViewById(R.id.gestureList), R.string.gesture_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                GestureStore.add(this, mapping)
                refresh()
            }
            .show()
    }

    companion object {
        private const val ID_REDRAW = 1
        private const val ID_CHANGE = 2
        private const val ID_DELETE = 3
    }
}
