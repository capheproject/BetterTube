package com.github.libretube.ui.fragments

import com.github.libretube.ui.models.LibraryViewModel
import androidx.fragment.app.activityViewModels
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentLibraryBinding
import com.github.libretube.databinding.LibraryHeaderBinding
import com.github.libretube.enums.PlaylistType
import com.github.libretube.enums.menuoption.PlayListSortModeBottomSheetItem
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.adapters.CarouselPlaylist
import com.github.libretube.ui.adapters.CarouselPlaylistAdapter
import com.github.libretube.ui.adapters.WatchHistoryAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.dialogs.CreatePlaylistDialog
import com.github.libretube.ui.dialogs.CreatePlaylistDialog.Companion.CREATE_PLAYLIST_DIALOG_REQUEST_KEY
import com.github.libretube.ui.extensions.addOnBottomReachedListener
import com.github.libretube.ui.models.WatchHistoryModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library: a carousel of your playlists (and bookmarks) at the top, then your watch history.
 * Creating a playlist lives in the playlists header, so it's never covered by the dock or the
 * mini player. "See all" opens the full watch history (filters, clear, play all).
 */
class LibraryFragment : DynamicLayoutManagerFragment(R.layout.fragment_library) {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private var _header: LibraryHeaderBinding? = null
    private val header get() = _header!!

    // activity scope: the history stays loaded while switching tabs
    private val historyModel: WatchHistoryModel by activityViewModels()
    private val libraryModel: LibraryViewModel by activityViewModels()

    private val playlistsAdapter = CarouselPlaylistAdapter(PlaylistType.PRIVATE)
    private val bookmarksAdapter = CarouselPlaylistAdapter(PlaylistType.PUBLIC)
    private lateinit var historyAdapter: WatchHistoryAdapter
    private lateinit var headerAdapter: HeaderAdapter

    private val watchHistoryEnabled
        get() = PreferenceHelper.getBoolean(PreferenceKeys.WATCH_HISTORY_TOGGLE, true)

    override fun setLayoutManagers(gridItems: Int) {
        val spans = gridItems.ceilHalf()
        _binding?.libraryList?.layoutManager = GridLayoutManager(context, spans).apply {
            // the header spans the full width, history items follow the grid setting
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (position == 0) spans else 1
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLibraryBinding.bind(view)
        // sets the list's layout manager, which inflating the header against the list needs
        super.onViewCreated(view, savedInstanceState)
        _header = LibraryHeaderBinding.inflate(layoutInflater, binding.libraryList, false)
        historyAdapter = WatchHistoryAdapter(
            historyModel::isVideoDownloaded,
            historyModel::getWatchPosition,
            historyModel::onWatchStatusChanged
        )
        headerAdapter = HeaderAdapter(header.root)

        binding.libraryList.adapter = ConcatAdapter(headerAdapter, historyAdapter)

        setupCarousel(header.playlistsCarousel, playlistsAdapter)
        setupCarousel(header.bookmarksCarousel, bookmarksAdapter)

        setupPlaylistActions()
        setupHistory()

        // downloads only need a way in when they aren't a tab of their own
        val navBarItems = NavBarHelper.getNavBarItemPreference(requireContext())
        header.downloads.isGone =
            navBarItems.any { (itemId, isVisible) -> isVisible && itemId == R.id.downloadsFragment }
        header.downloads.setOnClickListener {
            findNavController().navigate(R.id.action_libraryFragment_to_downloadsFragment)
        }

        binding.playlistRefresh.isEnabled = true
        binding.playlistRefresh.setOnRefreshListener {
            fetchPlaylists(showSpinner = true)
            fetchBookmarks()
            if (watchHistoryEnabled) historyModel.refresh()
        }

        // returning to the tab: show what was there right away (no loading, no list entrance)
        val cachedPlaylists = libraryModel.playlists
        if (cachedPlaylists != null) {
            binding.libraryList.layoutAnimation = null
            showPlaylists(cachedPlaylists)
        }
        libraryModel.bookmarks?.let(::showBookmarks)
    }

    override fun onResume() {
        super.onResume()
        // quietly picks up playlists created, renamed or deleted elsewhere (no spinner: the
        // content that's already shown stays until the update arrives)
        fetchPlaylists(showSpinner = libraryModel.playlists == null)
        fetchBookmarks()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _header = null
        _binding = null
    }

    private fun setupCarousel(recyclerView: RecyclerView, adapter: CarouselPlaylistAdapter) {
        // a plain horizontal row: no carousel parallax
        recyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        recyclerView.adapter = adapter
    }

    private fun setupPlaylistActions() {
        childFragmentManager.setFragmentResultListener(
            CREATE_PLAYLIST_DIALOG_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, resultBundle ->
            if (resultBundle.getBoolean(IntentData.playlistTask)) fetchPlaylists()
        }
        header.createPlaylist.setOnClickListener {
            CreatePlaylistDialog().show(childFragmentManager, CreatePlaylistDialog::class.java.name)
        }

        val sortOptions = listOf(
            PlayListSortModeBottomSheetItem.MostRecent,
            PlayListSortModeBottomSheetItem.MostRecent,
            PlayListSortModeBottomSheetItem.Alphabetic,
            PlayListSortModeBottomSheetItem.AlphabeticReversed
        )
        val sortOptionValues = resources.getStringArray(R.array.playlistSortingOptionsValues)
        val order = PreferenceHelper.getString(PreferenceKeys.PLAYLISTS_ORDER, sortOptionValues.first())
        header.sortTV.text = sortOptions.getOrNull(sortOptionValues.indexOf(order))
            ?.title?.getText(requireContext())
        header.sortTV.setOnClickListener {
            BaseBottomSheet().apply {
                setItems(sortOptions) { index ->
                    header.sortTV.text = sortOptions[index].title.getText(requireContext())
                    PreferenceHelper.putString(PreferenceKeys.PLAYLISTS_ORDER, sortOptionValues[index])
                    fetchPlaylists()
                }
            }.show(childFragmentManager)
        }
    }

    private fun setupHistory() {
        if (!watchHistoryEnabled) {
            header.historyHeader.isGone = true
            return
        }

        header.watchHistory.setOnClickListener {
            findNavController().navigate(R.id.action_libraryFragment_to_watchHistoryFragment)
        }

        historyModel.filteredWatchHistory.observe(viewLifecycleOwner) { history ->
            _header?.historyEmpty?.isVisible = history.isEmpty()
            _header?.watchHistory?.isVisible = history.isNotEmpty()
            historyAdapter.submitList(history) {
                if (_binding?.libraryList?.canScrollVertically(1) == false) historyModel.fetchNextPage()
            }
        }
        binding.libraryList.addOnBottomReachedListener(prefetchDistance = 20) {
            historyModel.fetchNextPage()
        }
    }

    private fun fetchPlaylists(showSpinner: Boolean = false) {
        if (showSpinner) _binding?.playlistRefresh?.isRefreshing = true
        lifecycleScope.launch {
            val playlists = try {
                withContext(Dispatchers.IO) { PlaylistsHelper.getPlaylists() }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                context?.let { Toast.makeText(it, R.string.unknown_error, Toast.LENGTH_SHORT).show() }
                _binding?.playlistRefresh?.isRefreshing = false
                return@launch
            }

            _binding?.playlistRefresh?.isRefreshing = false
            val items = playlists.map {
                CarouselPlaylist(id = it.id!!, title = it.name, thumbnail = it.thumbnail)
            }
            libraryModel.playlists = items
            showPlaylists(items)
        }
    }

    private fun showPlaylists(playlists: List<CarouselPlaylist>) {
        val header = _header ?: return
        header.nothingHere.isVisible = playlists.isEmpty()
        header.playlistsCarousel.isGone = playlists.isEmpty()
        header.sortTV.isVisible = playlists.isNotEmpty()
        playlistsAdapter.submitList(playlists)
    }

    private fun showBookmarks(bookmarks: List<CarouselPlaylist>) {
        val header = _header ?: return
        header.bookmarksContainer.isVisible = bookmarks.isNotEmpty()
        bookmarksAdapter.submitList(bookmarks)
    }

    private fun fetchBookmarks() {
        lifecycleScope.launch {
            val bookmarks = try {
                withContext(Dispatchers.IO) {
                    UserDataRepositoryHelper.userDataRepository.getPlaylistBookmarks()
                }
            } catch (e: Exception) {
                context?.toastFromMainDispatcher(e.message.orEmpty())
                return@launch
            }

            val items = bookmarks.map {
                CarouselPlaylist(id = it.playlistId, title = it.playlistName, thumbnail = it.thumbnailUrl)
            }
            libraryModel.bookmarks = items
            showBookmarks(items)
        }
    }

    /** Shows the (already inflated) header view as the first item of the list. */
    private class HeaderAdapter(private val headerView: View) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun getItemViewType(position: Int) = R.layout.library_header
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            (headerView.parent as? ViewGroup)?.removeView(headerView)
            return object : RecyclerView.ViewHolder(headerView) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }
}
