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
import dev.neffly.gesturelauncher.search.Command
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
 * alphabet section headers. [submitResults] renders a mixed search result list — answers, apps
 * and files — grouped under their own section labels. Sharing one adapter is what keeps the
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
    private val onSettingsClick: (() -> Unit)? = null,
    private val onCalculationClick: ((SearchResult.Calculation) -> Unit)? = null,
    private val onTimeClick: ((SearchResult.Time) -> Unit)? = null,
    private val onActionClick: ((SearchResult.Action) -> Unit)? = null
) : ListAdapter<AppListAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed class Row {
        /** Alphabet bucket header, browse mode only. */
        data class Header(val letter: Char) : Row()
        /** Search-mode group label (Actions / Apps / Files). Explicitly `@param:` because Kotlin 2.2
         *  is on its way to applying a bare annotation to the backing field as well, and the
         *  resource check this one carries belongs on the value. */
        data class Section(@param:StringRes val titleRes: Int) : Row()
        data class Item(val app: AppInfo) : Row()
        data class FileRow(val hit: FileHit) : Row()
        object SettingsRow : Row()
        data class CalculationRow(val calculation: SearchResult.Calculation) : Row()
        data class TimeRow(val time: SearchResult.Time) : Row()
        data class ActionRow(val action: SearchResult.Action) : Row()
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
     * changes. [SearchController][dev.neffly.gesturelauncher.search.SearchController] already
     * emits them grouped by kind, so a change of kind is always a section boundary.
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
                    is SearchResult.Settings -> Row.SettingsRow
                    is SearchResult.Calculation -> Row.CalculationRow(result)
                    is SearchResult.Time -> Row.TimeRow(result)
                    is SearchResult.Action -> Row.ActionRow(result)
                }
            )
        }
        submitList(rows, onCommitted)
    }

    @StringRes
    private fun sectionTitleFor(result: SearchResult): Int = when (result) {
        is SearchResult.App -> R.string.search_section_apps
        is SearchResult.File -> R.string.search_section_files
        is SearchResult.Settings -> R.string.search_section_launcher
        is SearchResult.Calculation -> R.string.search_section_calculator
        is SearchResult.Time -> R.string.search_section_time
        is SearchResult.Action -> R.string.search_section_actions
    }

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

    /** The topmost actionable row of any kind — what Enter activates in a mixed result list. */
    fun firstResult(): SearchResult? = currentList.firstNotNullOfOrNull { row -> asResult(row) }

    private fun asResult(row: Row): SearchResult? =
        when (row) {
            is Row.Item -> SearchResult.App(row.app)
            is Row.FileRow -> SearchResult.File(row.hit)
            is Row.SettingsRow -> SearchResult.Settings
            is Row.CalculationRow -> row.calculation
            is Row.TimeRow -> row.time
            is Row.ActionRow -> row.action
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
        // File, web, settings and answer rows share one layout and holder; only the bind differs.
        is Row.FileRow, is Row.SettingsRow, is Row.CalculationRow, is Row.TimeRow, is Row.ActionRow ->
            VIEW_TYPE_ENTRY
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
                holder.showSubtitle(hit.folder, TextUtils.TruncateAt.START)
                if (hit.folder.isEmpty()) holder.subtitle.visibility = View.GONE
                holder.itemView.setOnClickListener { onFileClick?.invoke(hit) }
                holder.itemView.setOnLongClickListener(null)
            }
            is Row.CalculationRow -> {
                val calculation = row.calculation
                holder as EntryVH
                holder.icon.setImageResource(R.drawable.ic_calculate)
                // The answer is the title because it is the thing being looked for; the expression
                // it came from stays visible in the search box directly above the row.
                holder.title.text = calculation.result
                holder.showSubtitle(
                    holder.itemView.context.getString(R.string.search_calculation_subtitle),
                    TextUtils.TruncateAt.END
                )
                holder.itemView.setOnClickListener { onCalculationClick?.invoke(calculation) }
                holder.itemView.setOnLongClickListener(null)
            }
            is Row.TimeRow -> {
                val time = row.time
                holder as EntryVH
                holder.icon.setImageResource(R.drawable.ic_schedule)
                holder.title.text = time.time
                // Two lines: "3:00 PM Los Angeles → Kuala Lumpur · 15 h ahead" is the answer's
                // working, and a wide typeface would otherwise ellipsize the half that matters.
                holder.showSubtitle(time.detail, TextUtils.TruncateAt.END, lines = 2)
                holder.itemView.setOnClickListener { onTimeClick?.invoke(time) }
                holder.itemView.setOnLongClickListener(null)
            }
            is Row.ActionRow -> {
                val action = row.action
                holder as EntryVH
                holder.icon.setImageResource(
                    when (action.command) {
                        is Command.Alarm -> R.drawable.ic_alarm
                        is Command.Timer -> R.drawable.ic_timer
                        is Command.Event -> R.drawable.ic_event
                        is Command.Web -> if (action.command.url != null) R.drawable.ic_link else R.drawable.ic_search
                        is Command.Map -> R.drawable.ic_place
                    }
                )
                holder.title.text = action.title
                // Two lines, as for the time row: the date and time span is the part to check
                // before tapping, and it must not be the part that gets cut.
                holder.showSubtitle(action.detail, TextUtils.TruncateAt.END, lines = 2)
                holder.itemView.setOnClickListener { onActionClick?.invoke(action) }
                holder.itemView.setOnLongClickListener(null)
            }
            is Row.SettingsRow -> {
                holder as EntryVH
                holder.icon.setImageResource(R.drawable.ic_settings)
                holder.title.setText(R.string.search_launcher_settings)
                holder.showSubtitle(
                    holder.itemView.context.getString(R.string.search_launcher_settings_subtitle),
                    TextUtils.TruncateAt.END
                )
                holder.itemView.setOnClickListener { onSettingsClick?.invoke() }
                holder.itemView.setOnLongClickListener(null)
            }
        }
    }

    /** Every property is set on each bind, not just the text: the holder is shared by every entry
     *  kind, so a line count or ellipsis left over from the last row would leak into this one. */
    private fun EntryVH.showSubtitle(text: CharSequence, ellipsize: TextUtils.TruncateAt, lines: Int = 1) {
        subtitle.maxLines = lines
        subtitle.ellipsize = ellipsize
        subtitle.text = text
        subtitle.visibility = View.VISIBLE
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
                // Only ever one of each answer row, so identity is the row type itself; what it
                // carries is content, and changing it must rebind rather than replace. Load-bearing:
                // these rows are pinned to the top and change on every keystroke, so treating each
                // answer as a new item would remove and re-insert the row — an animated flicker
                // on the one row that must hold still.
                old is Row.CalculationRow && new is Row.CalculationRow -> true
                old is Row.TimeRow && new is Row.TimeRow -> true
                old is Row.ActionRow && new is Row.ActionRow -> true
                old is Row.SettingsRow && new is Row.SettingsRow -> true
                else -> false
            }

            override fun areContentsTheSame(old: Row, new: Row): Boolean = old == new
        }
    }
}
