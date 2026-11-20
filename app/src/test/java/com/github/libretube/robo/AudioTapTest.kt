package com.github.libretube.robo

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.motion.widget.MotionLayout
import com.github.libretube.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LibApp::class, qualifiers = "w411dp-h891dp-port")
class AudioTapTest {
    private fun tap(root: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0)); ShadowLooper.idleMainLooper()
    }

    private fun setup(layout: Int, progress: Float): Triple<FrameLayout, MotionLayout, BooleanArray> {
        val activity = Robolectric.buildActivity(LibHost::class.java).setup().get()
        val host = FrameLayout(activity)
        activity.setContentView(host)
        val behind = BooleanArray(1)
        host.addView(View(activity).apply { setOnClickListener { behind[0] = true } }, ViewGroup.LayoutParams(-1, -1))
        val player = activity.layoutInflater.inflate(layout, host, false) as MotionLayout
        host.addView(player, ViewGroup.LayoutParams(-1, -1))
        player.progress = progress
        host.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 411, 891)
        ShadowLooper.idleMainLooper()
        return Triple(host, player, behind)
    }

    @Test fun dataLabelTapStaysInAudioPlayer() {
        val (host, player, behind) = setup(R.layout.fragment_audio_player, 0f)
        val label = player.findViewById<View>(R.id.data_usage)
        val loc = IntArray(2).also { label.getLocationInWindow(it) }
        println("label at ${loc[0]},${loc[1]} size ${label.width}x${label.height}")
        tap(host, loc[0] + label.width / 2f, loc[1] + label.height / 2f)
        println("BEHIND CLICKED=${behind[0]}")
        assertFalse(behind[0])
    }

    @Test fun minimisedPlayerStillLetsTouchesThrough() {
        val (host, _, behind) = setup(R.layout.fragment_audio_player, 1f)
        val c = host.findViewById<View>(R.id.audio_player_container); val r = android.graphics.Rect(); c.getHitRect(r); println("CONTAINER $r progress=${(host.getChildAt(1) as MotionLayout).progress}")
        tap(host, 200f, 150f)
        println("MINIMISED BEHIND CLICKED=${behind[0]}")
        assertTrue(behind[0])
    }
}
