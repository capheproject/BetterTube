package com.github.libretube.helpers

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-tap "Watch later": adds a video to the playlist the user picked once (e.g. their own
 * "Watch later" playlist), with Undo, or offers to remove it if it's already there.
 */
object WatchLater {
    private var playlistId: String?
        get() = PreferenceHelper.getString(PreferenceKeys.WATCH_LATER_PLAYLIST, "").ifEmpty { null }
        set(value) = PreferenceHelper.putString(PreferenceKeys.WATCH_LATER_PLAYLIST, value.orEmpty())

    fun add(activity: Activity, scope: LifecycleCoroutineScope, video: StreamItem) {
        val id = playlistId
        if (id == null) {
            choosePlaylist(activity, scope) { add(activity, scope, video) }
            return
        }
        val videoId = video.url?.toID() ?: return
        scope.launch {
            val playlist = withContext(Dispatchers.IO) {
                runCatching { PlaylistsHelper.getPlaylist(id, PlaylistType.PRIVATE) }.getOrNull()
            }
            if (playlist == null) {
                // the playlist is gone (deleted or a different account): pick again
                playlistId = null
                choosePlaylist(activity, scope) { add(activity, scope, video) }
                return@launch
            }
            val existingIndex = playlist.relatedStreams.indexOfFirst { it.url?.toID() == videoId }
            if (existingIndex >= 0) {
                snackbar(activity, R.string.watch_later_already, R.string.watch_later_remove) {
                    remove(activity, scope, id, videoId, existingIndex)
                }
                return@launch
            }
            val added = withContext(Dispatchers.IO) {
                runCatching { PlaylistsHelper.addToPlaylist(id, video) }.isSuccess
            }
            if (!added) {
                activity.toastFromMainDispatcher(R.string.unknown_error)
                return@launch
            }
            val newIndex = playlist.relatedStreams.size
            snackbar(activity, R.string.watch_later_added, R.string.undo) {
                remove(activity, scope, id, videoId, newIndex, confirm = false)
            }
        }
    }

    /**
     * For the share menu ("Watch later" when sharing a link to BetterTube): adds the video and
     * confirms with a toast, since the share screen closes right away. [onDone] runs at the end,
     * also when the user backs out of choosing a playlist.
     */
    fun addFromShare(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        video: StreamItem,
        onDone: () -> Unit
    ) {
        val id = playlistId
        if (id == null) {
            choosePlaylist(activity, scope, onCancel = onDone) {
                addFromShare(activity, scope, video, onDone)
            }
            return
        }
        val videoId = video.url?.toID() ?: return onDone()
        scope.launch {
            val playlist = withContext(Dispatchers.IO) {
                runCatching { PlaylistsHelper.getPlaylist(id, PlaylistType.PRIVATE) }.getOrNull()
            }
            if (playlist == null) {
                playlistId = null
                choosePlaylist(activity, scope, onCancel = onDone) {
                    addFromShare(activity, scope, video, onDone)
                }
                return@launch
            }
            val message = when {
                playlist.relatedStreams.any { it.url?.toID() == videoId } ->
                    R.string.watch_later_already
                withContext(Dispatchers.IO) {
                    runCatching { PlaylistsHelper.addToPlaylist(id, video) }.isSuccess
                } -> R.string.watch_later_added
                else -> R.string.unknown_error
            }
            activity.toastFromMainDispatcher(message)
            onDone()
        }
    }

    private fun remove(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        id: String,
        videoId: String,
        index: Int,
        confirm: Boolean = true
    ) {
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { PlaylistsHelper.removeFromPlaylist(id, videoId, index) }.isSuccess
            }
            if (removed && confirm) snackbar(activity, R.string.watch_later_removed)
        }
    }

    /**
     * Lets the user pick the Watch later playlist (playlists named like "watch later" first)
     * or create one. Also used by the setting.
     */
    fun choosePlaylist(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        onCancel: () -> Unit = {},
        onChosen: () -> Unit = {}
    ) {
        scope.launch {
            val playlists = withContext(Dispatchers.IO) {
                runCatching { PlaylistsHelper.getPlaylists() }.getOrDefault(emptyList())
            }.filter { it.id != null }
                .sortedByDescending { it.name.orEmpty().contains("watch later", ignoreCase = true) }
            val names = playlists.map { it.name.orEmpty() } + activity.getString(R.string.watch_later_new)
            val current = playlists.indexOfFirst { it.id == playlistId }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.watch_later_choose)
                .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                    dialog.dismiss()
                    if (which < playlists.size) {
                        playlistId = playlists[which].id
                        onChosen()
                    } else {
                        scope.launch {
                            val newId = withContext(Dispatchers.IO) {
                                runCatching {
                                    PlaylistsHelper.createPlaylist(activity.getString(R.string.watch_later))
                                }.getOrNull()
                            }
                            if (newId != null) {
                                playlistId = newId
                                onChosen()
                            } else {
                                activity.toastFromMainDispatcher(R.string.unknown_error)
                                onCancel()
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
                .show()
        }
    }

    private fun snackbar(activity: Activity, message: Int, action: Int? = null, onAction: () -> Unit = {}) {
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).apply {
            if (action != null) setAction(action) { onAction() }
            show()
        }
    }
}
