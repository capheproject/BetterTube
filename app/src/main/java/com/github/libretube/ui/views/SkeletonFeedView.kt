package com.github.libretube.ui.views

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import com.github.libretube.R

/**
 * Loading placeholder: a few card-shaped blocks that gently pulse while content loads.
 * Replaces a plain spinner so the screen already has the shape of what's coming.
 */
class SkeletonFeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val pulse = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.45f).apply {
        duration = 900
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
    }

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        repeat(CARD_COUNT) {
            LayoutInflater.from(context).inflate(R.layout.view_skeleton_card, this, true)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // only animate while actually on screen
        if (isVisible) {
            if (!pulse.isStarted) pulse.start()
        } else {
            pulse.cancel()
            alpha = 1f
        }
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val CARD_COUNT = 3
    }
}
