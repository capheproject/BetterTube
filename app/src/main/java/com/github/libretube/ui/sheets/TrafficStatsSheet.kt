package com.github.libretube.ui.sheets

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.DialogTrafficStatsBinding
import com.github.libretube.player.SabrTrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live view of [SabrTrafficStats], used to verify that audio only mode stops video traffic.
 */
class TrafficStatsSheet : ExpandedBottomSheet(R.layout.dialog_traffic_stats) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = DialogTrafficStatsBinding.bind(view)
        val videoId = arguments?.getString(IntentData.videoId)

        binding.trafficReset.setOnClickListener {
            SabrTrafficStats.reset()
            update(binding, videoId)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    update(binding, videoId)
                    delay(1000)
                }
            }
        }
    }

    private fun update(binding: DialogTrafficStatsBinding, videoId: String?) {
        val stats = SabrTrafficStats
        val context = requireContext()

        if (videoId != null && stats.videoId != videoId) {
            binding.trafficStatus.text = getString(R.string.network_traffic_unavailable)
            binding.trafficDetails.text = ""
            return
        }

        val now = System.currentTimeMillis()
        val lastVideo = stats.lastVideoDataMs
        val secondsWithoutVideo = ((now - (lastVideo.takeIf { it != 0L } ?: stats.sinceMs)) / 1000).toInt()

        binding.trafficStatus.text = when {
            stats.audio == 0L && stats.video == 0L ->
                getString(R.string.network_traffic_status_idle)
            secondsWithoutVideo >= NO_VIDEO_THRESHOLD_S ->
                getString(R.string.network_traffic_status_audio_only, secondsWithoutVideo)
            else -> getString(R.string.network_traffic_status_video)
        }

        binding.trafficDetails.text = getString(
            R.string.network_traffic_details,
            Formatter.formatShortFileSize(context, stats.audio),
            Formatter.formatShortFileSize(context, stats.video),
            if (lastVideo == 0L) getString(R.string.network_traffic_never)
            else getString(R.string.network_traffic_ago, ((now - lastVideo) / 1000).toInt())
        )
    }

    companion object {
        /** Seconds without video data after which playback is considered audio only. */
        private const val NO_VIDEO_THRESHOLD_S = 10
    }
}
