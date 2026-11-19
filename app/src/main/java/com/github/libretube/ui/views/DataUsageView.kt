package com.github.libretube.ui.views

import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.github.libretube.R
import com.github.libretube.databinding.ViewDataUsageBinding
import com.github.libretube.extensions.toID
import com.github.libretube.player.SabrTrafficStats
import com.github.libretube.util.PlayingQueue

/**
 * Subtle data usage indicator for the player screens.
 *
 * Shows a small pill with the data received for the playing video. Tapping it reveals an
 * inline breakdown of audio vs. video data (see [SabrTrafficStats]); tapping again hides it.
 * Used by both the video and the audio player, so the interaction is the same in both.
 */
class DataUsageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val binding = ViewDataUsageBinding.inflate(LayoutInflater.from(context), this)

    private val updater = object : Runnable {
        override fun run() {
            update()
            postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    init {
        orientation = VERTICAL
        binding.dataUsageChip.contentDescription =
            context.getString(R.string.data_usage_toggle_description)
        binding.dataUsageChip.setOnClickListener { toggle() }
        binding.dataUsagePanel.setOnClickListener { toggle() }
        update()
    }

    private fun toggle() {
        val panel = binding.dataUsagePanel
        if (panel.isVisible) {
            // hide instantly: animating the surrounding layout made views overlap
            panel.animate().cancel()
            panel.isGone = true
        } else {
            panel.alpha = 0f
            panel.isVisible = true
            panel.animate().alpha(1f).setDuration(150).start()
        }
        update()
    }

    /**
     * Shows the breakdown panel in [container] instead of below the pill, so the pill can sit
     * inline in a row (e.g. next to the view count) while the panel spans the full width.
     */
    fun movePanelTo(container: ViewGroup) {
        val panel = binding.dataUsagePanel
        (panel.parent as? ViewGroup)?.removeView(panel)
        container.addView(panel)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(updater)
        post(updater)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(updater)
        super.onDetachedFromWindow()
    }

    /** What the hosting player plays; decides the icon and how the breakdown is described. */
    enum class Mode { VIDEO, AUDIO }

    var mode: Mode = Mode.VIDEO
        set(value) {
            field = value
            update()
        }

    private fun setChipIcon() {
        val size = (16 * resources.displayMetrics.density).toInt()
        val icon = AppCompatResources.getDrawable(
            context,
            if (mode == Mode.VIDEO) R.drawable.ic_video else R.drawable.ic_volume_up
        )?.mutate()?.apply { setBounds(0, 0, size, size) }
        binding.dataUsageChip.setCompoundDrawablesRelative(icon, null, null, null)
    }

    private fun update() {
        setChipIcon()
        val stats = SabrTrafficStats
        val currentVideoId = PlayingQueue.getCurrent()?.url?.toID()
        val modeLabel = context.getString(if (mode == Mode.VIDEO) R.string.video else R.string.audio)

        // the stats only cover SABR streams, other stream types aren't measured
        if (currentVideoId == null || stats.videoId != currentVideoId) {
            binding.dataUsageChip.text = context.getString(R.string.data_usage_not_measured_short)
            binding.dataUsageChip.contentDescription = context.getString(R.string.data_usage_unavailable)
            binding.dataUsageStatus.text = context.getString(R.string.data_usage_unavailable)
            binding.dataUsageRows.isGone = true
            return
        }
        binding.dataUsageRows.isVisible = true

        val audio = stats.audio
        val video = stats.video
        val total = audio + video
        val totalText = Formatter.formatShortFileSize(context, total)
        binding.dataUsageChip.text = totalText
        binding.dataUsageChip.contentDescription =
            context.getString(R.string.data_usage_description, modeLabel, totalText)

        // the breakdown only needs updating while it's shown
        if (!binding.dataUsagePanel.isVisible) return

        // Video arrives in bursts (the player buffers ahead), so a pause in video data doesn't
        // mean audio only. Only the audio player can be audio only.
        val now = System.currentTimeMillis()
        val lastVideo = stats.lastVideoDataMs
        val sinceVideoMs = now - (lastVideo.takeIf { it != 0L } ?: stats.sinceMs)
        binding.dataUsageStatus.text = when {
            total == 0L -> context.getString(R.string.data_usage_status_idle)
            mode == Mode.VIDEO -> context.getString(R.string.data_usage_status_both)
            lastVideo != 0L && sinceVideoMs < AUDIO_ONLY_THRESHOLD_MS ->
                context.getString(R.string.data_usage_status_switching)
            else -> context.getString(
                R.string.data_usage_status_audio_only,
                formatDuration(sinceVideoMs / 1000)
            )
        }
        binding.dataUsageAudio.text = Formatter.formatShortFileSize(context, audio)
        binding.dataUsageVideo.text = Formatter.formatShortFileSize(context, video)
        val max = binding.dataUsageAudioBar.max
        binding.dataUsageAudioBar.progress = if (total == 0L) 0 else (audio * max / total).toInt()
        binding.dataUsageVideoBar.progress = if (total == 0L) 0 else (video * max / total).toInt()
    }

    private fun formatDuration(seconds: Long): String = if (seconds < 60) {
        context.getString(R.string.data_usage_seconds, seconds)
    } else {
        context.getString(R.string.data_usage_minutes, seconds / 60)
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1000L

        /** Time without video data after which playback is considered audio only. */
        private const val AUDIO_ONLY_THRESHOLD_MS = 10_000L
    }
}
