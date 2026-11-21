package com.github.libretube.robo

import com.github.libretube.helpers.DownloadFailures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// failed downloads are remembered (to show "Failed · retry") until they start again
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class DownloadFailuresTest {
    @Test fun rememberedUntilCleared() {
        assertFalse(DownloadFailures.isFailed(7))
        DownloadFailures.markFailed(7)
        DownloadFailures.markFailed(9)
        assertTrue(DownloadFailures.isFailed(7))
        DownloadFailures.clear(7)
        assertFalse(DownloadFailures.isFailed(7))
        assertTrue(DownloadFailures.isFailed(9))
    }
}
