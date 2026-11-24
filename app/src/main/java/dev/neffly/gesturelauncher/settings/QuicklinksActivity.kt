package dev.neffly.gesturelauncher.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.Quicklink
import dev.neffly.gesturelauncher.data.QuicklinkStore
import dev.neffly.gesturelauncher.search.Links
import dev.neffly.gesturelauncher.ui.BaseActivity
import dev.neffly.gesturelauncher.ui.FontEngine
import dev.neffly.gesturelauncher.ui.GlassMenu
import dev.neffly.gesturelauncher.ui.showWithFont
import java.util.UUID

/** Add, edit and delete the quicklinks search finds (see [QuicklinkStore]). */
class QuicklinksActivity : BaseActivity() {

    private val adapter = LinkAdapter(onClick = ::edit, onMenu = ::showRowMenu)
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quicklinks)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        emptyView = findViewById(R.id.emptyView)
        list = findViewById(R.id.linkList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<FloatingActionButton>(R.id.addButton).setOnClickListener { edit(null) }
        refresh()
    }

    private fun refresh() {
        val links = QuicklinkStore.all(this)
        adapter.submitList(links)
        emptyView.visibility = if (links.isEmpty()) View.VISIBLE else View.GONE
    }

    /** The add/edit form; [link] null adds a new one. Stays open until the address is filled in. */
    private fun edit(link: Quicklink?) {
        val view = layoutInflater.inflate(R.layout.dialog_quicklink, null)
        val name = view.findViewById<TextInputEditText>(R.id.nameInput)
        val url = view.findViewById<TextInputEditText>(R.id.urlInput)
        val urlLayout = view.findViewById<TextInputLayout>(R.id.urlLayout)
        val alias = view.findViewById<TextInputEditText>(R.id.aliasInput)
        link?.let {
            name.setText(it.name)
            url.setText(it.url)
            alias.setText(it.alias.orEmpty())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (link == null) R.string.quicklink_add else R.string.quicklink_edit)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .showWithFont()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typedUrl = url.text?.toString()?.trim().orEmpty()
            if (typedUrl.isEmpty()) {
                urlLayout.error = getString(R.string.gesture_url_empty)
                return@setOnClickListener
            }
            val address = Links.normalize(typedUrl)
            QuicklinkStore.save(
                this,
                Quicklink(
                    id = link?.id ?: UUID.randomUUID().toString(),
                    name = name.text?.toString()?.trim()?.ifEmpty { null } ?: Links.host(address),
                    url = address,
                    alias = alias.text?.toString()?.trim()?.ifEmpty { null }
                )
            )
            refresh()
            dialog.dismiss()
        }
    }

    private fun showRowMenu(link: Quicklink, anchor: View) {
        GlassMenu.show(
            anchor,
            listOf(
                GlassMenu.item(anchor, R.string.edit, R.drawable.ic_edit) { edit(link) },
                GlassMenu.item(anchor, R.string.delete, R.drawable.ic_delete, destructive = true) {
                    deleteWithUndo(link)
                }
            ),
            xOffsetDp = MENU_INSET_DP
        )
    }

    private fun deleteWithUndo(link: Quicklink) {
        val index = QuicklinkStore.all(this).indexOfFirst { it.id == link.id }
        QuicklinkStore.remove(this, link.id)
        refresh()
        Snackbar.make(list, R.string.quicklink_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                QuicklinkStore.restore(this, link, index)
                refresh()
            }
            .also { FontEngine.applyTo(it.view) }
            .show()
    }

    private class LinkAdapter(
        private val onClick: (Quicklink) -> Unit,
        private val onMenu: (Quicklink, View) -> Unit
    ) : ListAdapter<Quicklink, LinkAdapter.VH>(DIFF) {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.linkName)
            val subtitle: TextView = view.findViewById(R.id.linkDetail)
            val overflow: View = view.findViewById(R.id.overflow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quicklink, parent, false)
            FontEngine.applyTo(view)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val link = getItem(position)
            holder.title.text = link.name
            holder.subtitle.text = link.alias?.let { "$it · ${link.url}" } ?: link.url
            holder.itemView.setOnClickListener { onClick(link) }
            holder.overflow.setOnClickListener { onMenu(link, holder.itemView) }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<Quicklink>() {
                override fun areItemsTheSame(old: Quicklink, new: Quicklink) = old.id == new.id
                override fun areContentsTheSame(old: Quicklink, new: Quicklink) = old == new
            }
        }
    }

    private companion object {
        /** The menu's start edge sits in from the row's, under the name rather than the icon. */
        const val MENU_INSET_DP = 20
    }
}
