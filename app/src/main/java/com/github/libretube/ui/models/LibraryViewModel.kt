package com.github.libretube.ui.models

import androidx.lifecycle.ViewModel
import com.github.libretube.ui.adapters.CarouselPlaylist

/**
 * Keeps the Library's playlists and bookmarks while switching tabs, so coming back shows them
 * instantly instead of loading again.
 */
class LibraryViewModel : ViewModel() {
    var playlists: List<CarouselPlaylist>? = null
    var bookmarks: List<CarouselPlaylist>? = null
}
