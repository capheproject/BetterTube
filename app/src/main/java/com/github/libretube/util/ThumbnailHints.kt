package com.github.libretube.util

import android.graphics.drawable.Drawable
import android.widget.ImageView

/**
 * Remembers the thumbnail of a video the user just tapped, so the player can show exactly that
 * image in place while the video loads, instead of a black screen.
 *
 * Keeps the image that was on screen (e.g. a DeArrow thumbnail) and its URL as a fallback.
 */
object ThumbnailHints {
    data class Hint(val url: String?, val image: Drawable.ConstantState?)

    private const val MAX_ENTRIES = 20
    private val hints = LinkedHashMap<String, Hint>()

    @Synchronized
    fun put(videoId: String?, thumbnailUrl: String?, shownIn: ImageView? = null) {
        if (videoId.isNullOrEmpty()) return
        val image = shownIn?.drawable?.constantState
        if (thumbnailUrl.isNullOrEmpty() && image == null) return
        hints.remove(videoId)
        hints[videoId] = Hint(thumbnailUrl, image)
        while (hints.size > MAX_ENTRIES) hints.remove(hints.keys.first())
    }

    @Synchronized
    fun get(videoId: String?): Hint? = videoId?.let { hints[it] }
}
