package com.github.libretube.robo

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.motion.widget.MotionLayout
import com.github.libretube.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

// the minimised audio player: artwork stays inside the card, and swiping up is picked up
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "w411dp-h891dp-port")
class AudioMiniPlayerTest {
    private fun build(): MotionLayout {
        val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        val host = FrameLayout(activity)
        activity.setContentView(host)
        val player = activity.layoutInflater
            .inflate(R.layout.fragment_audio_player, host, false) as MotionLayout
        host.addView(player, ViewGroup.LayoutParams(-1, -1))
        player.progress = 1f
        host.measure(
            View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, 411, 891)
        ShadowLooper.idleMainLooper()
        return player
    }

    private fun rect(v: View) = Rect().also {
        val loc = IntArray(2).also { l -> v.getLocationOnScreen(l) }
        it.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    @Test
    fun artworkStaysInsideMiniPlayer() {
        val player = build()
        val card = rect(player.findViewById(R.id.audio_player_container))
        val artwork = rect(player.findViewById(R.id.artwork_frame))
        println("card=$card artwork=$artwork")
        assertTrue("artwork $artwork sticks out of the mini player $card", card.contains(artwork))
    }

    @Test
    fun swipeUpOnMiniPlayerIsTakenByThePlayer() {
        val player = build()
        val card = rect(player.findViewById(R.id.audio_player_container))
        val x = card.centerX().toFloat()
        val y = card.centerY().toFloat()
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val move = MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_MOVE, x, y - 60f, 0)
        player.onInterceptTouchEvent(down)
        assertTrue("an upward drag on the mini player isn't intercepted", player.onInterceptTouchEvent(move))
    }
}
