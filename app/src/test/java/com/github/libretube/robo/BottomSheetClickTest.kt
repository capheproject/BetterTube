package com.github.libretube.robo

import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.enums.menuoption.VideoBottomSheetItem
import com.github.libretube.ui.adapters.BottomSheetAdapter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

// the video options sheet adds options to the top after it's shown; tapping a row must still
// pick that row ("Watch later" used to start playback instead)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class BottomSheetClickTest {
    @Test fun tapAfterOptionsWereAddedOnTop() {
        val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        val clicked = mutableListOf<Int>()
        val adapter = BottomSheetAdapter { clicked += it }
        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
        }
        activity.setContentView(FrameLayout(activity).apply { addView(recycler, -1, -1) })
        fun layout() {
            ShadowLooper.idleMainLooper()
            recycler.measure(View.MeasureSpec.makeMeasureSpec(400, 1 shl 30), View.MeasureSpec.makeMeasureSpec(1600, 1 shl 30))
            recycler.layout(0, 0, 400, 1600)
        }
        adapter.submitList(listOf(VideoBottomSheetItem.WatchLater, VideoBottomSheetItem.Share)); layout()
        val later = listOf(VideoBottomSheetItem.PlayOnBackground, VideoBottomSheetItem.WatchLater, VideoBottomSheetItem.Share)
        adapter.submitList(later); layout()
        val watchLaterRow = (0 until recycler.childCount).map { recycler.getChildAt(it) }
            .first { recycler.getChildAdapterPosition(it) == 1 }
        watchLaterRow.performClick()
        assertEquals(VideoBottomSheetItem.WatchLater, later[clicked.single()])
    }
}
