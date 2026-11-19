package com.github.libretube.helpers

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * The bottom dock floats on top of the content. Each screen's main scrolling list gets extra
 * bottom space (without clipping), so content scrolls underneath the dock but the last items
 * can still be scrolled above it. Floating action buttons are lifted above the dock.
 */
object DockSpaceHelper {
    private const val APPLIED_TAG = "dock_space_applied"

    fun apply(root: View, space: Int) {
        if (space <= 0) return
        visit(root, space)
    }

    private fun visit(view: View, space: Int) {
        when {
            view is FloatingActionButton || view is ExtendedFloatingActionButton -> {
                view.translationY = -space.toFloat()
                return
            }

            isVerticalScroller(view) -> {
                addBottomSpace(view as ViewGroup, space)
                // nested lists (e.g. horizontal rows inside a scrolling page) are left alone,
                // but a page can still contain floating buttons
                liftButtons(view, space)
                return
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), space)
    }

    private fun liftButtons(view: View, space: Int) {
        if (view is FloatingActionButton || view is ExtendedFloatingActionButton) {
            view.translationY = -space.toFloat()
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) liftButtons(view.getChildAt(i), space)
        }
    }

    private fun isVerticalScroller(view: View) = when (view) {
        is RecyclerView -> view.layoutManager?.canScrollVertically() ?: true
        is NestedScrollView, is ScrollView -> true
        else -> false
    }

    private fun addBottomSpace(view: ViewGroup, space: Int) {
        if (view.getTag(APPLIED_TAG.hashCode()) == true) return
        view.setTag(APPLIED_TAG.hashCode(), true)
        view.clipToPadding = false
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom + space)
    }
}
