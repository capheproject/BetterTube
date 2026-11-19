package com.github.libretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat

/**
 * A [CoordinatorLayout] that also passes nested scrolls on to its own parents.
 *
 * A plain CoordinatorLayout consumes nested scrolling, so a screen with its own collapsing
 * header (e.g. Subscriptions) would never let the app's main toolbar scroll away. This one
 * first offers each scroll to the outer layout (the main toolbar), then to its own children.
 */
class NestedCoordinatorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CoordinatorLayout(context, attrs, defStyleAttr), NestedScrollingChild3 {

    private val childHelper = NestedScrollingChildHelper(this).apply {
        isNestedScrollingEnabled = true
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        val handledByParent = startNestedScroll(axes, type)
        val handledBySelf = super.onStartNestedScroll(child, target, axes, type)
        return handledByParent || handledBySelf
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        // let the outer layout (main toolbar) react first
        val parentConsumed = IntArray(2)
        dispatchNestedPreScroll(dx, dy, parentConsumed, null, type)
        val selfConsumed = IntArray(2)
        super.onNestedPreScroll(
            target, dx - parentConsumed[0], dy - parentConsumed[1], selfConsumed, type
        )
        consumed[0] = parentConsumed[0] + selfConsumed[0]
        consumed[1] = parentConsumed[1] + selfConsumed[1]
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        super.onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed)
        dispatchNestedScroll(
            dxConsumed, dyConsumed, dxUnconsumed - consumed[0], dyUnconsumed - consumed[1],
            null, type, consumed
        )
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        super.onStopNestedScroll(target, type)
        stopNestedScroll(type)
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean =
        dispatchNestedPreFling(velocityX, velocityY) || super.onNestedPreFling(target, velocityX, velocityY)

    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean =
        dispatchNestedFling(velocityX, velocityY, consumed) || super.onNestedFling(target, velocityX, velocityY, consumed)

    // NestedScrollingChild3, delegated to the helper
    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled() = childHelper.isNestedScrollingEnabled
    override fun startNestedScroll(axes: Int) = childHelper.startNestedScroll(axes)
    override fun startNestedScroll(axes: Int, type: Int) = childHelper.startNestedScroll(axes, type)
    override fun stopNestedScroll() = childHelper.stopNestedScroll()
    override fun stopNestedScroll(type: Int) = childHelper.stopNestedScroll(type)
    override fun hasNestedScrollingParent() = childHelper.hasNestedScrollingParent()
    override fun hasNestedScrollingParent(type: Int) = childHelper.hasNestedScrollingParent(type)

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ) = childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow)

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int
    ) = childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type)

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int, consumed: IntArray
    ) = childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed)

    override fun dispatchNestedPreScroll(dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?) =
        childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedPreScroll(dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?, type: Int) =
        childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean) =
        childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float) =
        childHelper.dispatchNestedPreFling(velocityX, velocityY)

    init {
        ViewCompat.setNestedScrollingEnabled(this, true)
    }
}
