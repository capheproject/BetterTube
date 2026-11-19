package com.github.libretube.player

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts the media data the SABR server sends for the video that is currently playing,
 * split into audio and video.
 *
 * Bytes are counted as soon as the server announces a segment, even when the client
 * ignores it afterwards, so this reflects what was actually sent over the network.
 * This makes it possible to verify on a device that audio only mode stops video traffic.
 */
object SabrTrafficStats {
    private val audioBytes = AtomicLong()
    private val videoBytes = AtomicLong()

    @Volatile
    var videoId: String? = null
        private set

    /** Time of the last received audio data, 0 if none. */
    @Volatile
    var lastAudioDataMs: Long = 0
        private set

    /** Time of the last received video data, 0 if none. */
    @Volatile
    var lastVideoDataMs: Long = 0
        private set

    /** Time the counters were last reset. */
    @Volatile
    var sinceMs: Long = System.currentTimeMillis()
        private set

    val audio: Long get() = audioBytes.get()
    val video: Long get() = videoBytes.get()

    fun reset(videoId: String? = this.videoId) {
        this.videoId = videoId
        audioBytes.set(0)
        videoBytes.set(0)
        lastAudioDataMs = 0
        lastVideoDataMs = 0
        sinceMs = System.currentTimeMillis()
    }

    fun record(isAudio: Boolean, bytes: Long) {
        val now = System.currentTimeMillis()
        if (isAudio) {
            audioBytes.addAndGet(bytes)
            lastAudioDataMs = now
        } else {
            videoBytes.addAndGet(bytes)
            lastVideoDataMs = now
        }
    }
}
