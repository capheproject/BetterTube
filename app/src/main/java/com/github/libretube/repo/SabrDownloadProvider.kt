package com.github.libretube.repo

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.player.manifest.Representation
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.player.parser.PlaybackRequest
import com.github.libretube.player.parser.SabrClient
import com.github.libretube.player.parser.Segment
import okio.BufferedSink

data class SabrDownloaderHandle(
    val sabrClient: SabrClient,
    @SuppressLint("UnsafeOptInUsageError")
    val streamRepresentation: Representation,
    var initSegment: Segment? = null,
    var nextSegmentNumber: Long = 0L
)

@OptIn(UnstableApi::class)
class SabrDownloadProvider(
    downloadItem: DownloadItem,
    streams: Streams,
    stream: PipedStream,
) : DownloadProvider {
    private val downloadHandle: SabrDownloaderHandle

    /** The video's length, to tell a finished download from one whose end isn't known yet. */
    private val expectedDurationMs = streams.duration.takeIf { it > 0 }?.times(1000)

    init {
        val sabrManifest = SabrManifest(downloadItem.videoId, streams)
        val sabrClient = SabrClient(sabrManifest)

        val streamRepresentation = Representation(stream)
        sabrClient.selectFormat(streamRepresentation)

        downloadHandle = SabrDownloaderHandle(sabrClient, streamRepresentation)
    }

    override suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
    ): DownloadProgressResult {
        var currentPositionMillis = item.currentDownloadPositionMillis ?: 0L

        if (downloadHandle.initSegment == null) {
            val initRequest = PlaybackRequest.initRequest(
                format = downloadHandle.streamRepresentation.formatId(),
                playerPosition = currentPositionMillis,
                playbackSpeed = 1f
            )
            val initSegment = downloadHandle.sabrClient
                .getNextSegment(initRequest) ?: return DownloadProgressResult.Failed
            for (chunk in initSegment.data) {
                sink.write(chunk)
            }
            downloadHandle.initSegment = initSegment

            downloadHandle.nextSegmentNumber = initSegment.sequenceNumber + 1
        }

        val request = PlaybackRequest(
            format = downloadHandle.streamRepresentation.formatId(),
            playerPosition = currentPositionMillis,
            segment = downloadHandle.nextSegmentNumber,
            segmentStartTimeMs = currentPositionMillis,
            playbackSpeed = 1f,
            bufferedSegments = emptyList()
        )
        val segment = downloadHandle.sabrClient.getNextSegment(request)
            ?: return DownloadProgressResult.Failed

        for (chunk in segment.data) {
            sink.write(chunk)
        }

        downloadHandle.nextSegmentNumber = segment.sequenceNumber + 1
        currentPositionMillis += segment.duration

        // persist current download position in millis in the database
        // this is used to restore the download position when pausing and resuming the download
        item.currentDownloadPositionMillis = currentPositionMillis
        DatabaseHolder.Database.downloadDao().updateDownloadItem(item)

        val endSegmentNumber = downloadHandle.sabrClient.getEndSegmentNumber(
            downloadHandle.streamRepresentation.formatId()
        )
        val downloadedBytesLength = segment.data.sumOf { it.size }.toLong()
        val finished = when {
            // the server told us where the stream ends
            endSegmentNumber != null -> downloadHandle.nextSegmentNumber >= endSegmentNumber
            // otherwise only when we've reached the video's length (an unknown end used to count
            // as finished, leaving half-downloaded files marked complete)
            expectedDurationMs != null ->
                currentPositionMillis >= expectedDurationMs - END_TOLERANCE_MS
            else -> segment.duration <= 0L && downloadedBytesLength == 0L
        }
        return if (finished) {
            DownloadProgressResult.DownloadComplete
        } else if (segment.duration <= 0L && downloadedBytesLength == 0L) {
            // no progress at all: let the service retry (and eventually mark it failed)
            DownloadProgressResult.Failed
        } else {
            DownloadProgressResult.Progressed(downloadedBytesLength)
        }
    }

    private companion object {
        const val END_TOLERANCE_MS = 1_500L
    }
}
