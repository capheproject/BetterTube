package com.github.libretube.helpers

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Light, consistent haptic feedback for key moments. Uses the system's haptic constants, so it
 * follows the phone's "touch feedback" setting.
 */
object Haptics {
    /** A confirming tap: switching audio/video, adding to Watch later. */
    fun confirm(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    /** A subtle tick: grabbing and releasing the progress bar. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
