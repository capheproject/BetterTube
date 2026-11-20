package com.github.libretube.robo

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.libretube.R
import com.github.libretube.ui.views.CustomExoPlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LibApp::class, qualifiers = "w411dp-h891dp-port")
class RetryOverlayTest {
    @Test fun retryOverlayIsTappable() {
        val activity = Robolectric.buildActivity(LibHost::class.java).setup().get()
        val host = FrameLayout(activity); activity.setContentView(host)
        val root = activity.layoutInflater.inflate(R.layout.fragment_player, host, false)
        host.addView(root, ViewGroup.LayoutParams(-1, -1))
        val player = root.findViewById<CustomExoPlayerView>(R.id.player)
        var retries = 0
        player.showPlaybackError { retries++ }
        host.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 411, 891)
        ShadowLooper.idleMainLooper()
        val btn = player.findViewById<View>(R.id.playback_retry)
        val loc = IntArray(2).also { btn.getLocationInWindow(it) }
        println("RETRY at ${loc[0]},${loc[1]} ${btn.width}x${btn.height} visible=${btn.isShown}")
        val x = loc[0] + btn.width / 2f; val y = loc[1] + btn.height / 2f
        val t = SystemClock.uptimeMillis()
        host.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        host.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()
        println("RETRIES=$retries overlayShown=${player.findViewById<View>(R.id.playback_error).isShown}")
        assertTrue(btn.width > 0)
        assertEquals(1, retries)
    }
}
