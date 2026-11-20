package com.github.libretube.robo

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.motion.widget.MotionLayout
import com.github.libretube.R
import com.github.libretube.ui.base.BaseActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

class HostActivity : BaseActivity()

/** Minimal app: only what the player layout needs. */
class TestApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        com.github.libretube.helpers.PreferenceHelper.initialize(this)
    }
}

// ROBO-ONLY: which views receive touches while the player is (almost) minimised
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "w411dp-h891dp-port")
class MiniPlayerTouchTest {
    private fun name(v: View) = runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault(v.javaClass.simpleName)

    private fun bounds(v: View): String {
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        return "${name(v)} [y=${loc[1]} h=${v.height}]"
    }

    private fun clickablesAt(root: View, x: Float, y: Float): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            if (x < loc[0] || x >= loc[0] + v.width || y < loc[1] || y >= loc[1] + v.height) return
            if (v.isClickable) out += name(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root); return out
    }

    private fun tap(root: View, x: Float, y: Float): Boolean {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0)
        val consumed = root.dispatchTouchEvent(down)
        root.dispatchTouchEvent(up)
        return consumed
    }

    private fun build(progress: Float): Pair<FrameLayout, MotionLayout> {
        val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        val host = FrameLayout(activity)
        activity.setContentView(host)
        val player = activity.layoutInflater.inflate(R.layout.fragment_player, host, false) as MotionLayout
        host.addView(player, ViewGroup.LayoutParams(-1, -1))
        player.progress = progress
        host.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 411, 891)
        ShadowLooper.idleMainLooper()
        return host to player
    }

    @Test
    fun touchesAroundMiniPlayer() {
        for (p in listOf(1f, 0.99f, 0.95f, 0.85f)) {
            val (_, player) = build(p)
            val leaks = mutableListOf<String>()
            player.findViewById<View>(R.id.linLayout).apply { isClickable = true; setOnClickListener { leaks += "player page" } }
            println("== progress=${player.progress}  ${bounds(player.findViewById(R.id.main_container))}  ${bounds(player.findViewById(R.id.player_scrollView))}")
            for (y in listOf(150f, 450f, 800f, 880f)) {
                // decide what is under the finger before tapping (tapping may change state)
                val under = clickablesAt(player, 200f, y)
                val consumed = tap(player, 200f, y)
                println("   tap y=$y consumedByPlayer=$consumed playerPageClicked=${leaks.isNotEmpty()}")
                // taps outside the (almost) minimised player must never reach the hidden player page
                org.junit.Assert.assertTrue("tap at y=$y reached the hidden player page", leaks.isEmpty() || p < 0.5f)
                leaks.clear()
            }
        }
    }
}
