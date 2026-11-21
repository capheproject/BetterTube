package com.github.libretube.helpers

import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadWithItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * The single source of truth for the state of download items.
 *
 * - The live state (queued, downloading, paused, failed, complete) is kept for the running app
 *   and set by the download service; the Downloads page renders every row and button from it.
 * - Whether an item is complete is remembered across restarts: an item only counts as complete
 *   once the service verified it, never just because the file reached the announced size (for
 *   SABR downloads the stored bytes don't match the announced size, so half-finished files used to
 *   look complete).
 */
object DownloadStates {
    sealed interface State {
        data object Queued : State
        data class Running(val downloaded: Long, val total: Long) : State
        data object Paused : State
        data object Failed : State
        data object Complete : State
    }

    private val _states = MutableStateFlow<Map<Int, State>>(emptyMap())
    val states: StateFlow<Map<Int, State>> = _states.asStateFlow()

    fun set(id: Int, state: State) = _states.update { it + (id to state) }

    fun get(id: Int): State? = _states.value[id]

    /** Downloads still marked as running or queued when the service goes away are paused. */
    fun pauseUnfinished() = _states.update { states ->
        states.mapValues { (_, state) ->
            if (state is State.Running || state == State.Queued) State.Paused else state
        }
    }

    // items started by this version are "tracked": they're complete only when marked complete
    private const val TRACKED_KEY = "bettertube_tracked_download_items"
    private const val COMPLETE_KEY = "bettertube_complete_download_items"

    private fun set(key: String) = PreferenceHelper.getStringSet(key, emptySet())

    fun markTracked(id: Int) {
        PreferenceHelper.putStringSet(TRACKED_KEY, set(TRACKED_KEY) + id.toString())
        PreferenceHelper.putStringSet(COMPLETE_KEY, set(COMPLETE_KEY) - id.toString())
    }

    fun markComplete(id: Int) {
        PreferenceHelper.putStringSet(COMPLETE_KEY, set(COMPLETE_KEY) + id.toString())
        DownloadFailures.clear(id)
        set(id, State.Complete)
    }

    /** Forget everything about an item (deleted or stopped). */
    fun clear(id: Int) {
        val key = id.toString()
        if (key in set(TRACKED_KEY)) PreferenceHelper.putStringSet(TRACKED_KEY, set(TRACKED_KEY) - key)
        if (key in set(COMPLETE_KEY)) PreferenceHelper.putStringSet(COMPLETE_KEY, set(COMPLETE_KEY) - key)
        DownloadFailures.clear(id)
        _states.update { it - id }
    }

    /**
     * Whether the item is fully downloaded.
     *
     * Tracked items need the verified-complete mark and a non-empty file. Older (untracked) items
     * keep the old size rule, except SABR items whose saved position shows they stopped early.
     */
    fun isComplete(item: DownloadItem, expectedDurationSeconds: Long? = null): Boolean {
        val size = runCatching { if (item.path.exists()) item.path.fileSize() else -1L }
            .getOrDefault(-1L)
        if (size <= 0L) return false
        val key = item.id.toString()
        if (key in set(COMPLETE_KEY)) {
            // a known size must still be reached (e.g. the file was replaced or truncated)
            return item.currentDownloadPositionMillis != null ||
                item.downloadSize <= 0L || size >= item.downloadSize
        }
        if (key in set(TRACKED_KEY)) return false
        if (item.downloadSize <= 0L || size < item.downloadSize) return false
        return !stoppedEarly(item, expectedDurationSeconds)
    }

    /** A SABR download whose saved position is clearly short of the video's length. */
    fun stoppedEarly(item: DownloadItem, expectedDurationSeconds: Long?): Boolean {
        val position = item.currentDownloadPositionMillis ?: return false
        val duration = expectedDurationSeconds?.takeIf { it > 0 } ?: return false
        return position < duration * 1000 - EARLY_STOP_TOLERANCE_MS
    }

    /**
     * Older downloads that only looked complete (SABR, stopped early) are marked as tracked, so
     * they show as incomplete with a resume button from now on.
     */
    fun auditLegacy(downloads: List<DownloadWithItems>) {
        val tracked = set(TRACKED_KEY)
        downloads.forEach { download ->
            download.downloadItems.forEach { item ->
                if (item.id.toString() !in tracked &&
                    stoppedEarly(item, download.download.duration)
                ) {
                    markTracked(item.id)
                }
            }
        }
    }

    fun fileSizeOf(item: DownloadItem): Long =
        runCatching { if (item.path.exists()) item.path.fileSize() else 0L }.getOrDefault(0L)

    /** Short progress label that fits the row, e.g. "40/120 MB" or "1.2/3.4 GB". */
    fun progressLabel(downloaded: Long, total: Long): String {
        val gb = 1024L * 1024 * 1024
        val mb = 1024L * 1024
        return when {
            total <= 0L -> "${downloaded / mb} MB"
            total >= gb -> "%.1f/%.1f GB".format(downloaded.toDouble() / gb, total.toDouble() / gb)
            else -> "${downloaded / mb}/${total / mb} MB"
        }
    }

    private const val EARLY_STOP_TOLERANCE_MS = 3_000L
}
