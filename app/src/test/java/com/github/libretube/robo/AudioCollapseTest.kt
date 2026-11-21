package com.github.libretube.robo

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.motion.widget.MotionLayout
import com.github.libretube.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "w411dp-h891dp-port")
class AudioCollapseTest {
    // while the audio player shrinks, only the artwork travels: no backdrop panel or controls
    @Test fun onlyArtworkTravelsWhileMinimising() {
        val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        val host = FrameLayout(activity); activity.setContentView(host)
        val player = activity.layoutInflater.inflate(R.layout.fragment_audio_player, host, false) as MotionLayout
        host.addView(player, ViewGroup.LayoutParams(-1, -1))
        player.getConstraintSet(R.id.start).setMargin(R.id.artwork_frame, androidx.constraintlayout.widget.ConstraintSet.TOP, 40)
        host.measure(View.MeasureSpec.makeMeasureSpec(411, 1 shl 30), View.MeasureSpec.makeMeasureSpec(891, 1 shl 30))
        host.layout(0, 0, 411, 891); ShadowLooper.idleMainLooper()
        for (p in listOf(0f, 0.1f, 0.3f, 0.5f, 0.7f, 1f)) {
            player.progress = p
            player.requestLayout()
            host.measure(View.MeasureSpec.makeMeasureSpec(411, 1 shl 30), View.MeasureSpec.makeMeasureSpec(891, 1 shl 30)); host.layout(0, 0, 411, 891)
            host.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(411, 891, android.graphics.Bitmap.Config.ARGB_8888)))
            host.measure(View.MeasureSpec.makeMeasureSpec(411, 1 shl 30), View.MeasureSpec.makeMeasureSpec(891, 1 shl 30))
            host.layout(0, 0, 411, 891); ShadowLooper.idleMainLooper()
            fun r(id: Int): String { val v = player.findViewById<View>(id); return "[${v.left},${v.top}-${v.right},${v.bottom} a=${"%.2f".format(v.alpha)} vis=${v.visibility}]" }
            if (p in 0.4f..0.85f) {
                for (id in listOf(R.id.audio_player_container, R.id.audio_player_main)) {
                    org.junit.Assert.assertEquals("${player.resources.getResourceEntryName(id)} visible at $p", 0f, player.findViewById<View>(id).alpha, 0.01f)
                }
            }
            println("P=$p container=${r(R.id.audio_player_container)} artwork=${r(R.id.artwork_frame)} thumb=${r(R.id.thumbnail)} mini=${r(R.id.miniPlayerControls)} main=${r(R.id.audio_player_main)}")
        }
    }
}
