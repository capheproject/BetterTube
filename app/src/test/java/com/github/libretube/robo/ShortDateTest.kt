package com.github.libretube.robo

import com.github.libretube.util.TextUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Short ages in video lists: now, 5m, 7h, 3d, 2w, 4mo, 1y. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LibApp::class)
class ShortDateTest {
    private fun ago(ms: Long) = TextUtils.formatRelativeDateShort(System.currentTimeMillis() - ms).toString()

    @Test fun shortAges() {
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        assertEquals("now", ago(10_000))
        assertEquals("5m", ago(5 * minute + 1000))
        assertEquals("7h", ago(7 * hour + minute))
        assertEquals("3d", ago(3 * day + hour))
        assertEquals("2w", ago(15 * day))
        assertEquals("4mo", ago(125 * day))
        assertEquals("1y", ago(400 * day))
    }
}
