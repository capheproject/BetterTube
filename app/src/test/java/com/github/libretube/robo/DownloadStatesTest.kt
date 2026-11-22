package com.github.libretube.robo

import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.helpers.DownloadFailures
import com.github.libretube.helpers.DownloadStates
import com.github.libretube.helpers.DownloadStates.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

// when a download counts as complete, and how its state is kept
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class DownloadStatesTest {
    private fun item(id: Int, bytes: Int, announced: Long, sabrPositionMs: Long? = null): DownloadItem {
        val path = Files.createTempFile("dl$id", ".bin")
        Files.write(path, ByteArray(bytes))
        return DownloadItem(
            id = id, type = FileType.VIDEO, videoId = "v$id", fileName = "f$id", path = path,
            downloadSize = announced, currentDownloadPositionMillis = sabrPositionMs
        )
    }

    @Test fun newDownloadIsCompleteOnlyOnceVerified() {
        // a SABR download can pass the announced size long before it's finished
        val it = item(1, 2000, announced = 1000, sabrPositionMs = 30_000)
        DownloadStates.markTracked(1)
        assertFalse(DownloadStates.isComplete(it, expectedDurationSeconds = 600))
        DownloadStates.markComplete(1)
        assertTrue(DownloadStates.isComplete(it, expectedDurationSeconds = 600))
        assertEquals(State.Complete, DownloadStates.get(1))
    }

    @Test fun unknownSizeNeverCountsAsComplete() {
        val it = item(2, 500, announced = -1)
        assertFalse(DownloadStates.isComplete(it))
    }

    @Test fun olderDownloadsKeepTheSizeRuleUnlessTheyStoppedEarly() {
        val plain = item(3, 1000, announced = 1000)
        assertTrue(DownloadStates.isComplete(plain))

        val stoppedHalfway = item(4, 1000, announced = 1000, sabrPositionMs = 300_000)
        assertFalse(DownloadStates.isComplete(stoppedHalfway, expectedDurationSeconds = 600))

        val finished = item(5, 1000, announced = 1000, sabrPositionMs = 599_000)
        assertTrue(DownloadStates.isComplete(finished, expectedDurationSeconds = 600))
    }

    @Test fun deletingForgetsEverything() {
        val it = item(6, 1000, announced = 1000)
        DownloadStates.markTracked(6)
        DownloadStates.markComplete(6)
        DownloadFailures.markFailed(6)
        DownloadStates.set(6, State.Paused)
        DownloadStates.clear(6)
        assertEquals(null, DownloadStates.get(6))
        assertFalse(DownloadFailures.isFailed(6))
        // back to the plain rule, as if never downloaded by this version
        assertTrue(DownloadStates.isComplete(it))
    }

    @Test fun unfinishedStatesArePausedWhenTheServiceStops() {
        DownloadStates.set(7, State.Running(10, 100))
        DownloadStates.set(8, State.Queued)
        DownloadStates.set(9, State.Failed)
        DownloadStates.pauseUnfinished()
        assertEquals(State.Paused, DownloadStates.get(7))
        assertEquals(State.Paused, DownloadStates.get(8))
        assertEquals(State.Failed, DownloadStates.get(9))
    }

    @Test fun shortProgressLabel() {
        val mb = 1024L * 1024
        assertEquals("40/120\nMB", DownloadStates.progressLabel(40 * mb + 30_000, 120 * mb))
        assertEquals("1.5/3.0\nGB", DownloadStates.progressLabel(1536 * mb, 3072 * mb))
        assertEquals("40\nMB", DownloadStates.progressLabel(40 * mb, -1))
    }
}
