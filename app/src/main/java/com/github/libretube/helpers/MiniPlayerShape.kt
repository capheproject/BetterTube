package com.github.libretube.helpers

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.github.libretube.R

/**
 * Rounds the player container as it shrinks into the floating mini player card:
 * square when fully open, fully rounded once minimised.
 */
object MiniPlayerShape {
    fun apply(container: View, progress: Float) {
        val radius = container.resources.getDimension(R.dimen.mini_player_corner) * progress.coerceIn(0f, 1f)
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        container.clipToOutline = radius > 0f
        container.invalidateOutline()
    }
}
