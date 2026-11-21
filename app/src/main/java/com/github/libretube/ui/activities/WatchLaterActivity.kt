package com.github.libretube.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.WatchLater
import com.github.libretube.ui.base.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Watch later" in the system share menu: adds a shared YouTube link to the Watch later playlist
 * in one tap and closes again, so the user stays in the app they shared from.
 */
class WatchLaterActivity : BaseActivity() {
    override val isDialogActivity: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoId = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            IntentHelper.resolveType(it.toUri())
        }?.getStringExtra(IntentData.videoId)

        if (videoId == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            // local playlists store the video's details, so they're fetched first (like
            // "Add to playlist" from the share menu)
            val video = withContext(Dispatchers.IO) {
                if (PreferenceHelper.getToken().isEmpty()) {
                    runCatching {
                        MediaServiceRepository.instance.getStreams(videoId).toStreamItem(videoId)
                    }.getOrNull()
                } else {
                    StreamItem(videoId)
                }
            }
            if (video == null) {
                toastFromMainDispatcher(R.string.unknown_error)
                finish()
                return@launch
            }
            WatchLater.addFromShare(this@WatchLaterActivity, lifecycleScope, video) { finish() }
        }
    }
}
