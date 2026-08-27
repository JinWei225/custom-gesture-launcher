package dev.neffly.gesturelauncher.drawer

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.search.FileHit
import dev.neffly.gesturelauncher.search.FileSearcher
import dev.neffly.gesturelauncher.search.SearchResult
import dev.neffly.gesturelauncher.ui.FontEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Result list for the drawer, the floating quick-search window and the training app-picker.
 *
 * Two modes, one adapter. [submit] renders the plain alphabetical app list, optionally interleaving
 * alphabet section headers. [submitResults] renders a mixed search result list — apps, files and
 * the web row — grouped under their own section labels. Sharing one adapter is what keeps the
 * drawer and the floating window rendering identically, font handling included.
 *
 * Backed by [ListAdapter]/DiffUtil so only changed rows rebind. Icons load lazily per bound row
 * via [IconCache] on [scope] (pass the owning activity's lifecycleScope so loads die with the screen).
 */
class AppListAdapter(
    private val scope: CoroutineScope,
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: ((AppInfo, View) -> Unit)? = null,
    private val onFileClick: ((FileHit) -> Unit)? = null,
    private val onWebClick: ((SearchResult.Web) -> Unit)? = null,
    private val onSettingsClick: (() -> Unit)? = null,
    private val onCalculationClick: ((SearchResult.Calculation) -> Unit)? = null
) : ListAdapter<AppListAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed class Row {
        /** Alphabet bucket header, browse mode only. */
        data class Header(val letter: Char) : Row()
        /** Search-mode group label (Apps / Files / Web). */
        data class Section(@StringRes val titleRes: Int) : Row()
        data class Item(val app: AppInfo) : Row()
        data class FileRow(val hit: FileHit) : Row()
        data class WebRow(val web: SearchResult.Web) : Row()
        object SettingsRow : Row()
        data class CalculationRow(val calculation: SearchResult.Calculation) : Row()
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

    /**
     * Renders a mixed search result list, inserting a section label wherever the result kind
     * changes. [SearchEngine][dev.neffly.gesturelauncher.search.SearchEngine] already emits them in
     * Apps -> Files -> Web order, so a change of kind is always a section boundary.
     */
    fun submitResults(results: List<SearchResult>, onCommitted: (() -> Unit)? = null) {
        headersShown = false
        val rows = ArrayList<Row>(results.size + 3)
        var lastKind: Class<out SearchResult>? = null
        for (result in results) {
            if (result.javaClass != lastKind) {
                rows.add(Row.Section(sectionTitleFor(result)))
                lastKind = result.javaClass
            }
            rows.add(
                when (result) {
                    is SearchResult.App -> Row.Item(result.app)
                    is SearchResult.File -> Row.FileRow(result.hit)
                    is SearchResult.Web -> Row.WebRow(result)
                    is SearchResult.Settings -> Row.SettingsRow
                    is SearchResult.Calculation -> Row.CalculationRow(result)
                }
            )
        }
        submitList(rows, onCommitted)
    }

    @StringRes
    private fun sectionTitleFor(result: SearchResult): Int = when (result) {
        is SearchResult.App -> R.string.search_section_apps
        is SearchResult.File -> R.string.search_section_files
        is SearchResult.Web -> R.string.search_section_web
        is SearchResult.Settings -> R.string.search_section_launcher
        is SearchResult.Calculation -> R.string.search_section_calculator
    }

    /** The topmost app row currently shown, i.e. what Enter in the search bar should launch. */
    fun firstItem(): AppInfo? = currentList.firstNotNullOfOrNull { (it as? Row.Item)?.app }

    /** The topmost actionable row of any kind — what Enter activates in a mixed result list. */
    /** The openable thing at [position], or null when that row is a header, a section label, or
     *  out of range. Used by the swipe-to-float gesture, which works off adapter positions. */
    fun resultAt(position: Int): SearchResult? =
        currentList.getOrNull(position)?.let { row -> asResult(row) }

    /**
     * Splits the leading slice of the list that covers its first [count] openable results into
     * (openable rows, section/header rows). The floating window uses it to size itself so that
     * many results really are visible — counting only the results would leave the last one hidden
     * behind the section labels sitting above them.
     */
    fun leadingRowCounts(count: Int): Pair<Int, Int> {
        var results = 0
        var chrome = 0
        for (row in currentList) {
            if (asResult(row) != null) {
                results++
                if (results == count) break
            } else {
                chrome++
            }
        }
        return results to chrome
    }

    fun firstResult(): SearchResult? = currentList.firstNotNullOfOrNull { row -> asResult(row) }

    private fun asResult(row: Row): SearchResult? =
        when (row) {
            is Row.Item -> SearchResult.App(row.app)
            is Row.FileRow -> SearchResult.File(row.hit)
            is Row.WebRow -> row.web
            is Row.SettingsRow -> SearchResult.Settings
            is Row.CalculationRow -> row.calculation
            is Row.Header, is Row.Section -> null
        }

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

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Header -> VIEW_TYPE_HEADER
        is Row.Section -> VIEW_TYPE_SECTION
        is Row.Item -> VIEW_TYPE_APP
        // File, web and settings rows share one layout and holder; only the bind step differs.
        is Row.FileRow, is Row.WebRow, is Row.SettingsRow, is Row.CalculationRow -> VIEW_TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            VIEW_TYPE_HEADER -> R.layout.item_app_header
            VIEW_TYPE_SECTION -> R.layout.item_search_section
            VIEW_TYPE_ENTRY -> R.layout.item_search_entry
            else -> R.layout.item_app
        }
        val view = inflater.inflate(layout, parent, false)
        // Rows keep being created as the list scrolls, long after the activity applied the font to
        // its content view — so each new one needs it here.
        FontEngine.applyTo(view)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderVH(view)
            VIEW_TYPE_SECTION -> SectionVH(view)
            VIEW_TYPE_ENTRY -> EntryVH(view)
            else -> AppVH(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderVH).letter.text = row.letter.toString()
            is Row.Section -> (holder as SectionVH).title.setText(row.titleRes)
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
            is Row.FileRow -> {
                val hit = row.hit
                holder as EntryVH
                holder.icon.setImageResource(FileSearcher.iconFor(hit.mimeType))
                holder.title.text = hit.name
                holder.subtitle.ellipsize = TextUtils.TruncateAt.START
                holder.subtitle.text = hit.folder
                holder.subtitle.visibility = if (hit.folder.isEmpty()) View.GONE else View.VISIBLE
                holder.itemView.setOnClickListener { onFileClick?.invoke(hit) }
                holder.itemView.setOnLongClickListener(null)
            }
            is Row.WebRow -> {
                val web = row.web
                holder as EntryVH
                val isUrl = web.url != null
                holder.icon.setImageResource(if (isUrl) R.drawable.ic_link else R.drawable.ic_search)
                // The query is the title and the action is the subtitle, rather than the other way
                // round: a long query in a "Search Google for …" sentence gets ellipsized in the
                // middle, which hides the very thing being searched for.
                holder.title.text = web.query
                holder.subtitle.ellipsize = TextUtils.TruncateAt.END
                holder.subtitle.setText(
                    if (isUrl) R.string.search_open_url_subtitle else R.string.search_google_subtitle
                )
                holder.subtitle.visibility = View.VISIBLE
                holder.itemView.setOnClickListener { onWebClick?.invoke(web) }
                holder.itemView.setOnLongClickListener(null)
            }
            is Row.CalculationRow -> {
                val calculation = row.calculation
                holder as EntryVH
                holder.icon.setImageResource(R.drawable.ic_calculate)
                // The answer is the title because it is the thing being looked for; the expression
                // it came from stays visible in the search box directly above the row.
                holder.title.text = calculation.result
                holder.subtitle.ellipsize = TextUtils.TruncateAt.END
                holder.subtitle.setText(R.string.search_calculation_subtitle)
                holder.subtitle.visibility = View.VISIBLE
                holder.itemView.setOnClickListener { onCalculationClick?.invoke(calculation) }
                holder.itemView.setOnLongClickListener(null)
            }
            is Row.SettingsRow -> {
                holder as EntryVH
                holder.icon.setImageResource(R.drawable.ic_settings)
                holder.title.setText(R.string.search_launcher_settings)
                holder.subtitle.ellipsize = TextUtils.TruncateAt.END
                holder.subtitle.setText(R.string.search_launcher_settings_subtitle)
                holder.subtitle.visibility = View.VISIBLE
                holder.itemView.setOnClickListener { onSettingsClick?.invoke() }
                holder.itemView.setOnLongClickListener(null)
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

    class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sectionTitle)
    }

    class EntryVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.entryIcon)
        val title: TextView = view.findViewById(R.id.entryTitle)
        val subtitle: TextView = view.findViewById(R.id.entrySubtitle)
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_APP = 1
        private const val VIEW_TYPE_SECTION = 2
        private const val VIEW_TYPE_ENTRY = 3

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(old: Row, new: Row): Boolean = when {
                old is Row.Header && new is Row.Header -> old.letter == new.letter
                old is Row.Section && new is Row.Section -> old.titleRes == new.titleRes
                old is Row.Item && new is Row.Item -> old.app.key == new.app.key
                old is Row.FileRow && new is Row.FileRow -> old.hit.uri == new.hit.uri
                // Only ever one web row, so identity is the row type itself; the query it carries
                // is content, and changing it must rebind rather than replace.
                old is Row.WebRow && new is Row.WebRow -> true
                old is Row.SettingsRow && new is Row.SettingsRow -> true
                else -> false
            }

            override fun areContentsTheSame(old: Row, new: Row): Boolean = old == new
        }
    }
}
