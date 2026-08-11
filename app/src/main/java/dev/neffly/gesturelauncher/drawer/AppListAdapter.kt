package dev.neffly.gesturelauncher.drawer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.ui.FontEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App list for the drawer and the training app-picker. Optionally interleaves alphabet section
 * headers (see [submit]'s `showHeaders`) when browsing the full list — skipped during search since
 * fuzzy-sorted results aren't grouped by letter.
 *
 * Backed by [ListAdapter]/DiffUtil so only changed rows rebind. Icons load lazily per bound row
 * via [IconCache] on [scope] (pass the owning activity's lifecycleScope so loads die with the screen).
 */
class AppListAdapter(
    private val scope: CoroutineScope,
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: ((AppInfo, View) -> Unit)? = null
) : ListAdapter<AppListAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed class Row {
        data class Header(val letter: Char) : Row()
        data class Item(val app: AppInfo) : Row()
    }

    private var headersShown = false

    // Rebinds every bound row whenever IconCache is cleared — DiffUtil won't do this on its own
    // since a row's AppInfo (label/tag) is typically unchanged when only its icon is (theme swap,
    // or an update that swaps the icon without touching the label).
    private val iconListener: () -> Unit = { notifyItemRangeChanged(0, itemCount) }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        IconCache.addListener(iconListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        IconCache.removeListener(iconListener)
    }

    fun submit(list: List<AppInfo>, showHeaders: Boolean = false, onCommitted: (() -> Unit)? = null) {
        headersShown = showHeaders
        val rows = ArrayList<Row>(list.size + if (showHeaders) 28 else 0)
        if (showHeaders) {
            var lastLetter: Char? = null
            for (app in list) {
                val letter = app.indexLetter()
                if (letter != lastLetter) {
                    rows.add(Row.Header(letter))
                    lastLetter = letter
                }
                rows.add(Row.Item(app))
            }
        } else {
            list.mapTo(rows) { Row.Item(it) }
        }
        // The commit callback fires once this list has actually been dispatched (DiffUtil runs
        // async off the main thread), so a caller scrolling in response — e.g. jumping back to the
        // top after a filter change — lands after the rows are in place instead of racing them.
        submitList(rows, onCommitted)
    }

    /** The topmost app row currently shown, i.e. what Enter in the search bar should launch. */
    fun firstItem(): AppInfo? = currentList.firstNotNullOfOrNull { (it as? Row.Item)?.app }

    /** Row for the alphabet fast-scroll index's [letter]. When headers are shown, this is always
     *  the section header itself (guaranteed to exist for any letter the index allows selecting —
     *  see AppDrawerActivity.submitList/AlphabetIndexView.setActiveLetters). Without headers (a
     *  search is active), falls back to the first matching row directly; '#' isn't a contiguous
     *  block in plain string sort order (e.g. CJK labels sort after 'Z'), so it's handled as
     *  "first item bucketed to '#'," not a ">=" comparison like the A-Z letters. */
    fun indexOfFirstLabelAtOrAfter(letter: Char): Int? {
        val rows = currentList
        if (headersShown) {
            val headerIndex = rows.indexOfFirst { it is Row.Header && it.letter == letter }
            if (headerIndex >= 0) return headerIndex
        }
        val index = if (letter == '#') {
            rows.indexOfFirst { it is Row.Item && it.app.indexLetter() == '#' }
        } else {
            rows.indexOfFirst {
                it is Row.Item && (it.app.label.firstOrNull()?.uppercaseChar() ?: Char.MIN_VALUE) >= letter
            }
        }
        return index.takeIf { it >= 0 }
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is Row.Header) VIEW_TYPE_HEADER else VIEW_TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = if (viewType == VIEW_TYPE_HEADER) {
            inflater.inflate(R.layout.item_app_header, parent, false)
        } else {
            inflater.inflate(R.layout.item_app, parent, false)
        }
        // Rows keep being created as the list scrolls, long after the activity applied the font to
        // its content view — so each new one needs it here.
        FontEngine.applyTo(view)
        return if (viewType == VIEW_TYPE_HEADER) HeaderVH(view) else AppVH(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderVH).letter.text = row.letter.toString()
            is Row.Item -> {
                val app = row.app
                holder as AppVH
                bindIcon(holder, app)
                holder.label.text = app.label
                if (app.tag != null) {
                    holder.tag.text = app.tag
                    holder.tag.visibility = View.VISIBLE
                } else {
                    holder.tag.visibility = View.GONE
                }
                holder.itemView.setOnClickListener { onClick(app) }
                holder.itemView.setOnLongClickListener(
                    onLongClick?.let { cb -> { v: View -> cb(app, v); true } }
                )
            }
        }
    }

    /** Cached icon synchronously if available; otherwise show a placeholder and fetch off-thread,
     *  applying only if the holder still shows the same app by the time the load lands. */
    private fun bindIcon(holder: AppVH, app: AppInfo) {
        holder.iconJob?.cancel()
        holder.boundKey = app.key
        val cached = IconCache.cached(app)
        if (cached != null) {
            holder.icon.setImageDrawable(cached)
        } else {
            // A placeholder rather than nothing: on a cold start IconCache is empty, so an
            // otherwise-complete list would render as labels beside a column of holes.
            holder.icon.setImageResource(R.drawable.bg_icon_placeholder)
        }
        if (cached == null) {
            val context = holder.itemView.context.applicationContext
            holder.iconJob = scope.launch {
                val icon = withContext(Dispatchers.IO) { IconCache.load(context, app) }
                if (icon != null && holder.boundKey == app.key) {
                    holder.icon.setImageDrawable(icon)
                }
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? AppVH)?.iconJob?.cancel()
        super.onViewRecycled(holder)
    }

    class AppVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
        val tag: TextView = view.findViewById(R.id.appTag)
        var iconJob: Job? = null
        var boundKey: String? = null
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val letter: TextView = view.findViewById(R.id.headerLetter)
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_APP = 1

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(old: Row, new: Row): Boolean = when {
                old is Row.Header && new is Row.Header -> old.letter == new.letter
                old is Row.Item && new is Row.Item -> old.app.key == new.app.key
                else -> false
            }

            override fun areContentsTheSame(old: Row, new: Row): Boolean = old == new
        }
    }
}
