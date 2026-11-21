package com.github.libretube.helpers

/**
 * Remembers which download items gave up after all retries, so the Downloads page can show them
 * as failed (with a retry) even after the app was closed. Cleared as soon as an item starts again.
 */
object DownloadFailures {
    private const val KEY = "bettertube_failed_download_items"

    private fun ids(): Set<String> = PreferenceHelper.getStringSet(KEY, emptySet())

    fun markFailed(itemId: Int) = PreferenceHelper.putStringSet(KEY, ids() + itemId.toString())

    fun clear(itemId: Int) {
        val current = ids()
        if (itemId.toString() in current) PreferenceHelper.putStringSet(KEY, current - itemId.toString())
    }

    fun isFailed(itemId: Int) = itemId.toString() in ids()
}
