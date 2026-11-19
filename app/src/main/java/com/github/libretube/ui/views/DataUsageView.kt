package com.github.libretube.ui.views

import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import com.github.libretube.R
import com.github.libretube.databinding.ViewDataUsageBinding
import com.github.libretube.extensions.toID
import com.github.libretube.player.SabrTrafficStats
import com.github.libretube.util.PlayingQueue

/**
 * Quiet data usage label for the player screens: a video or speaker icon (depending on the
 * player) and the data received so far for the playing video (see [SabrTrafficStats]).
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

    /** What the hosting player plays; decides the icon. */
    enum class Mode { VIDEO, AUDIO }

    var mode: Mode = Mode.VIDEO
        set(value) {
            field = value
            update()
        }

    init {
        orientation = VERTICAL
        update()
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

    private fun setIcon() {
        val size = (16 * resources.displayMetrics.density).toInt()
        val icon = AppCompatResources.getDrawable(
            context,
            if (mode == Mode.VIDEO) R.drawable.ic_video else R.drawable.ic_volume_up
        )?.mutate()?.apply { setBounds(0, 0, size, size) }
        binding.dataUsageChip.setCompoundDrawablesRelative(icon, null, null, null)
    }

    private fun update() {
        setIcon()
        val stats = SabrTrafficStats
        val currentVideoId = PlayingQueue.getCurrent()?.url?.toID()

        // the stats only cover SABR streams, other stream types aren't measured
        if (currentVideoId == null || stats.videoId != currentVideoId) {
            binding.dataUsageChip.text = context.getString(R.string.data_usage_not_measured_short)
            binding.dataUsageChip.contentDescription = context.getString(R.string.data_usage_unavailable)
            return
        }

        val totalText = Formatter.formatShortFileSize(context, stats.audio + stats.video)
        val modeLabel = context.getString(if (mode == Mode.VIDEO) R.string.video else R.string.audio)
        binding.dataUsageChip.text = totalText
        binding.dataUsageChip.contentDescription =
            context.getString(R.string.data_usage_description, modeLabel, totalText)
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1000L
    }
}
