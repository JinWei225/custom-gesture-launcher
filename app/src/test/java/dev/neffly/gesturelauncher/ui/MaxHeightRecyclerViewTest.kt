package dev.neffly.gesturelauncher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The floating card's list against its ceiling. The regression this guards: rows that arrive
 * after the list was first sized — the file section lands a beat after the apps — must get room,
 * which a trim computed from the rows already on screen could never give them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaxHeightRecyclerViewTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    /** Rows of one fixed height each. */
    private class Rows(var count: Int, private val rowHeight: Int) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = View(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
            return object : RecyclerView.ViewHolder(view) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        override fun getItemCount(): Int = count
    }

    private fun list(rows: Rows, maxHeight: Int): MaxHeightRecyclerView {
        val list = MaxHeightRecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = rows
        list.maxHeightPx = maxHeight
        val parent = FrameLayout(context)
        parent.addView(list, FrameLayout.LayoutParams(400, ViewGroup.LayoutParams.WRAP_CONTENT))
        measure(parent)
        return list
    }

    /** One pass of the parent's measure and layout, as a frame would run it. */
    private fun measure(parent: View) {
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST)
        )
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
    }

    @Test
    fun `hugs a short list`() {
        val list = list(Rows(count = 3, rowHeight = 100), maxHeight = 1000)
        assertEquals(300, list.height)
    }

    @Test
    fun `stops at the last whole row under the ceiling`() {
        val list = list(Rows(count = 10, rowHeight = 100), maxHeight = 450)
        assertEquals(400, list.height)
    }

    @Test
    fun `grows for rows that arrive after it was sized`() {
        val rows = Rows(count = 2, rowHeight = 100)
        val list = list(rows, maxHeight = 450)
        assertEquals(200, list.height)
        rows.count = 10
        rows.notifyItemRangeInserted(2, 8)
        measure(list.parent as View)
        assertEquals(400, list.height)
    }

    @Test
    fun `is unbounded without a ceiling`() {
        val list = list(Rows(count = 10, rowHeight = 100), maxHeight = 0)
        assertEquals(1000, list.height)
    }
}
