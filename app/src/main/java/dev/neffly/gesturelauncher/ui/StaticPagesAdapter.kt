package dev.neffly.gesturelauncher.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * A ViewPager2 adapter over views that already exist.
 *
 * The home screen's pages are built up front, so the activity can wire the clock, canvas, widget
 * column and note list the moment it is created rather than whenever the pager gets round to
 * binding them. Each page is its own view type, so the pager never asks for a second copy of
 * one; the activity keeps every page attached (see its offscreen page limit), so none is ever
 * recycled either.
 */
class StaticPagesAdapter(private val pages: List<View>) : RecyclerView.Adapter<StaticPagesAdapter.PageVH>() {

    class PageVH(view: View) : RecyclerView.ViewHolder(view)

    init {
        // ViewPager2 refuses anything less than a page that fills it.
        pages.forEach {
            it.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun getItemCount(): Int = pages.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH = PageVH(pages[viewType])

    override fun onBindViewHolder(holder: PageVH, position: Int) = Unit
}
