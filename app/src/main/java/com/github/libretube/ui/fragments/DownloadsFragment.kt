package com.github.libretube.ui.fragments

import androidx.lifecycle.repeatOnLifecycle

import androidx.lifecycle.Lifecycle

import com.github.libretube.receivers.NotificationReceiver

import com.github.libretube.helpers.DownloadStates

import com.github.libretube.helpers.ThemeHelper

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentDownloadContentBinding
import com.github.libretube.databinding.FragmentDownloadsBinding
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadPlaylistWithDownload
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.db.obj.filterByTab
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.serializable
import com.github.libretube.extensions.setOnDismissListener
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.obj.bottomsheetitem.BottomSheetItem
import com.github.libretube.obj.bottomsheetitem.BottomSheetItemTitle
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.receivers.DownloadReceiver
import com.github.libretube.services.DownloadService
import com.github.libretube.ui.adapters.DownloadPlaylistAdapter
import com.github.libretube.ui.adapters.DownloadsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.DownloadsViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.viewholders.DownloadsViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlin.io.path.fileSize

enum class DownloadTab {
    VIDEO,
    AUDIO,
    PLAYLIST
}

@Parcelize
sealed class DownloadSortingOrder(
    @get:StringRes @field:StringRes
    val titleStringRes: Int,
    @get:DrawableRes @field:DrawableRes
    val drawableRes: Int,
    val ordinal: Int,
) : BottomSheetItem(
    title = BottomSheetItemTitle.StringResource(titleStringRes),
    drawable = drawableRes,
), Parcelable {
    companion object {
        val entries = listOf(
            Oldest, Newest, Alphabetic, Duration, Channel, Size
        ).sortedBy(DownloadSortingOrder::ordinal)
    }

    data object Oldest : DownloadSortingOrder(
        titleStringRes = R.string.least_recent,
        drawableRes = R.drawable.ic_sort_by_oldest,
        ordinal = 1,
    )

    data object Newest : DownloadSortingOrder(
        titleStringRes = R.string.most_recent,
        drawableRes = R.drawable.ic_sort_by_newest,
        ordinal = 2,
    )

    data object Alphabetic : DownloadSortingOrder(
        titleStringRes = R.string.alphabetic,
        drawableRes = R.drawable.ic_sort_by_alpha,
        ordinal = 3,
    )

    data object Duration : DownloadSortingOrder(
        titleStringRes = R.string.duration,
        drawableRes = R.drawable.ic_time_outlined,
        ordinal = 4,
    )

    data object Channel : DownloadSortingOrder(
        titleStringRes = R.string.sort_channel,
        drawableRes = R.drawable.ic_person,
        ordinal = 5,
    )

    data object Size : DownloadSortingOrder(
        titleStringRes = R.string.sort_size,
        drawableRes = R.drawable.ic_sort_by_file_size,
        ordinal = 6,
    )
}

class DownloadsFragment : Fragment(R.layout.fragment_downloads) {
    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.downloadsPager.adapter = DownloadsFragmentAdapter(this)
        // the sub-tabs (Videos / Audio / Playlists) switch by tapping their labels only, so a
        // sideways swipe always moves between the main tabs
        binding.downloadsPager.isUserInputEnabled = false

        TabLayoutMediator(binding.tabLayout, binding.downloadsPager) { tab, position ->
            tab.text = when (position) {
                DownloadTab.VIDEO.ordinal -> getString(R.string.video)
                DownloadTab.AUDIO.ordinal -> getString(R.string.audio)
                DownloadTab.PLAYLIST.ordinal -> getString(R.string.playlists)
                else -> throw IllegalArgumentException()
            }
        }.attach()
    }

    fun bindDownloadService() {
        childFragmentManager.fragments.filterIsInstance<DownloadsFragmentPage>().forEach {
            it.bindDownloadService()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class DownloadsFragmentAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = DownloadTab.entries.size

    override fun createFragment(position: Int): Fragment {
        if (position == DownloadTab.PLAYLIST.ordinal) {
            return PlaylistDownloadsFragmentPage()
        }

        return DownloadsFragmentPage().apply {
            arguments = bundleOf(IntentData.downloadTab to DownloadTab.entries[position])
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
class DownloadsFragmentPage : DynamicLayoutManagerFragment(R.layout.fragment_download_content) {
    private lateinit var adapter: DownloadsAdapter
    private var _binding: FragmentDownloadContentBinding? = null
    private val binding get() = _binding!!

    private val playerViewModel: CommonPlayerViewModel by activityViewModels()
    private val downloadsModel: DownloadsViewModel by activityViewModels()

    // Either downloadTab or downloadPlaylistId are set, never both at the same time!
    private lateinit var downloadTab: DownloadTab
    private var downloadPlaylistId: String? = null

    /** Everything shown on this page (before sorting/searching). */
    private var allDownloads: List<DownloadWithItems> = emptyList()

    private var selectedSortType
        get() = PreferenceHelper.getInt(
            PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE, DownloadSortingOrder.Oldest.ordinal
        )
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE, value)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.downloadTab = requireArguments().serializable(IntentData.downloadTab)!!
        this.downloadPlaylistId = requireArguments().getString(IntentData.playlistId)

        if (downloadTab == DownloadTab.PLAYLIST && downloadPlaylistId == null)
            throw IllegalArgumentException("downloadTab unspecified or missing playlist id")
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.downloadsRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        adapter =
            DownloadsAdapter(
                requireContext(), downloadTab, downloadPlaylistId,
                currentSortOrder = {
                    DownloadSortingOrder.entries[selectedSortType]
                }
            ) { !toggleDownload(it) }
        binding.downloadsRecView.adapter = adapter

        val filterOptions = DownloadSortingOrder.entries
        binding.sortType.text = filterOptions[selectedSortType].title.getText(requireContext())

        lifecycleScope.launch(Dispatchers.Main) {
            reloadDownloads()

            binding.sortType.setOnClickListener {
                BaseBottomSheet().setItems(filterOptions.toList()) { index ->
                    if (index == selectedSortType) return@setItems
                    selectedSortType = index

                    binding.sortType.text = filterOptions[index].title.getText(requireContext())
                    submitDownloadList(allDownloads)
                }.show(childFragmentManager)
            }

            downloadsModel.searchQuery.observe(viewLifecycleOwner) {
                submitDownloadList(allDownloads)
            }

            binding.downloadsRecView.setOnDismissListener { position ->
                adapter.showDeleteDialog(requireContext(), position)
                // put the item back to the center, as it's currently out of the screen
                adapter.restoreItem(position)
            }

            binding.downloadsRecView.adapter?.registerAdapterDataObserver(
                object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                        super.onItemRangeRemoved(positionStart, itemCount)
                        toggleVisibilities()
                    }

                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        super.onItemRangeInserted(positionStart, itemCount)
                        // ensure that after searching with no results,
                        // the nothing here placeholder is hidden again so that
                        // results are visible for the queries made afterwards
                        toggleVisibilities()
                    }
                }
            )

            toggleVisibilities()
        }

        // one button for all downloads, showing what it will do: resume or pause them all
        binding.resumeAll.setOnClickListener {
            val anyActive = DownloadStates.states.value.values.any {
                it is DownloadStates.State.Running || it == DownloadStates.State.Queued
            }
            DownloadHelper.sendServiceAction(
                requireContext(),
                if (anyActive) DownloadService.ACTION_PAUSE_ALL else DownloadService.ACTION_RESUME_ALL
            )
        }

        // every row, its button and the button for all follow the same states
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadStates.states.collect { states ->
                    val previous = adapter.liveStates
                    adapter.liveStates = states
                    _binding?.let { adapter.refreshVisibleStatus(it.downloadsRecView) }
                    toggleVisibilities()
                    // a download added elsewhere, or one that just finished: reload the list
                    val shownIds = allDownloads.flatMap { d -> d.downloadItems.map { it.id } }
                    val changed = states.keys.any { it !in shownIds } ||
                        states.any { (id, state) ->
                            state == DownloadStates.State.Complete &&
                                previous[id] != DownloadStates.State.Complete
                        }
                    if (changed) reloadDownloads()
                }
            }
        }

        binding.deleteAll.setOnClickListener {
            showDeleteAllDialog(binding.root.context, adapter)
        }

        binding.shuffleAll.setOnClickListener {
            NavigationHelper.navigateVideo(
                requireContext(),
                playerData = PlayerData(
                    videoId = null,
                    playlistId = downloadPlaylistId,
                    downloadTab = downloadTab,
                    shuffle = true,
                    isOffline = true,
                ),
                audioOnlyPlayerRequested = downloadTab == DownloadTab.AUDIO
            )
        }

        playerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isMiniPlayerVisible ->
            binding.shuffleAll.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = (if (isMiniPlayerVisible) 64f else 16f).dpToPx()
            }
        }
    }

    /** Pause the row's downloads if any is running or queued, otherwise resume (or retry) them. */
    private fun toggleDownload(download: DownloadWithItems): Boolean {
        val ids = download.downloadItems
            .filter { item -> !DownloadStates.isComplete(item, download.download.duration) }
            .map { item -> item.id }
        val active = ids.any { id ->
            val state = DownloadStates.get(id)
            state is DownloadStates.State.Running || state == DownloadStates.State.Queued
        }
        ids.forEach { id ->
            DownloadHelper.sendServiceAction(
                requireContext(),
                if (active) NotificationReceiver.ACTION_DOWNLOAD_PAUSE
                else NotificationReceiver.ACTION_DOWNLOAD_RESUME,
                id
            )
        }
        return !active
    }

    /** Loads this page's downloads from the database (also when they changed elsewhere). */
    private suspend fun reloadDownloads() {
        val playlistItems = downloadPlaylistId?.let { playlistId ->
            val playlist = withContext(Dispatchers.IO) {
                Database.downloadDao().getDownloadPlaylistById(playlistId)
            }
            _binding?.playlistName?.text = playlist.downloadPlaylist.title
            _binding?.playlistName?.isVisible = true
            playlist.downloadVideos.map { it.videoId }
        }

        val downloads = withContext(Dispatchers.IO) {
            Database.downloadDao().getAll().also { DownloadStates.auditLegacy(it) }
        }.let { downloads ->
            if (downloadTab != DownloadTab.PLAYLIST) downloads.filterByTab(downloadTab)
            else downloads.filter { playlistItems.orEmpty().contains(it.download.videoId) }
        }
        allDownloads = downloads
        if (_binding != null) submitDownloadList(downloads)
    }

    private fun submitDownloadList(items: List<DownloadWithItems>) {
        val sortOrder = DownloadSortingOrder.entries[selectedSortType]
        var sortedItems = sortDownloadWithItemsList(items, sortOrder)
        val query = downloadsModel.searchQuery.value
        if (!query.isNullOrEmpty()) {
            sortedItems = sortedItems.filter {
                it.download.title.contains(query, ignoreCase = true)
                        || it.download.uploader.contains(query, ignoreCase = true)
            }
        }

        adapter.submitList(sortedItems)
    }

    private fun toggleVisibilities() {
        val binding = _binding ?: return

        val isEmpty = adapter.itemCount == 0
        val incompleteIds = adapter.currentList.flatMap { download ->
            download.downloadItems
                .filter { !DownloadStates.isComplete(it, download.download.duration) }
                .map { it.id }
        }
        val anyActive = incompleteIds.any {
            val state = DownloadStates.get(it)
            state is DownloadStates.State.Running || state == DownloadStates.State.Queued
        }
        binding.downloadsEmpty.isVisible = isEmpty
        binding.downloadsContainer.isGone = isEmpty
        binding.deleteAll.isGone = isEmpty
        binding.resumeAll.isVisible = incompleteIds.isNotEmpty()
        binding.resumeAll.setText(if (anyActive) R.string.pause_all else R.string.resume_all)
        binding.resumeAll.setIconResource(if (anyActive) R.drawable.ic_pause else R.drawable.ic_play)
        binding.shuffleAll.isGone = isEmpty
    }

    private fun showDeleteAllDialog(context: Context, adapter: DownloadsAdapter) {
        var onlyDeleteWatchedVideos = false

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_all)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.delete_only_watched_videos)),
                null
            ) { _, _, selected ->
                onlyDeleteWatchedVideos = selected
            }
            .setPositiveButton(R.string.okay) { _, _ ->
                adapter.deleteAllDownloads(onlyDeleteWatchedVideos)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // this page stays alive in the tab pager: show downloads added or deleted meanwhile
        viewLifecycleOwner.lifecycleScope.launch { reloadDownloads() }
    }

    /** Called when downloads may have changed (kept for callers of the old service binding). */
    fun bindDownloadService(@Suppress("UNUSED_PARAMETER") ids: IntArray? = null) {
        if (view != null) viewLifecycleOwner.lifecycleScope.launch { reloadDownloads() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun sortDownloadWithItemsList(
            items: List<DownloadWithItems>,
            selectedSortType: DownloadSortingOrder
        ): List<DownloadWithItems> {
            return when (selectedSortType) {
                DownloadSortingOrder.Oldest -> items
                DownloadSortingOrder.Newest -> items.reversed()
                DownloadSortingOrder.Alphabetic -> items.sortedBy { it.download.title }
                DownloadSortingOrder.Duration -> items.sortedBy { it.download.duration }
                DownloadSortingOrder.Channel -> items.sortedBy { it.download.uploader }
                DownloadSortingOrder.Size -> items.sortedBy { it.downloadItems.sumOf { o -> o.downloadSize } }
            }
        }

        // ugly HACK: should probably be refactored in the future
        fun sortDownloadList(
            items: List<Download>,
            selectedSortType: DownloadSortingOrder
        ): List<Download> {
            return when (selectedSortType) {
                DownloadSortingOrder.Oldest -> items
                DownloadSortingOrder.Newest -> items.reversed()
                DownloadSortingOrder.Alphabetic -> items.sortedBy { it.title }
                DownloadSortingOrder.Duration -> items.sortedBy { it.duration }
                DownloadSortingOrder.Channel -> items.sortedBy { it.uploader }
                DownloadSortingOrder.Size -> items
            }
        }
    }
}

class PlaylistDownloadsFragmentPage : Fragment(R.layout.fragment_download_content) {
    private var selectedSortType
        get() = PreferenceHelper.getInt(PreferenceKeys.SELECTED_DOWNLOAD_PLAYLIST_SORT_TYPE, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.SELECTED_DOWNLOAD_PLAYLIST_SORT_TYPE, value)
        }
    private val downloadsModel: DownloadsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentDownloadContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.shuffleAll.isGone = true

        var backPressedCallback: OnBackPressedCallback? = null
        backPressedCallback = setOnBackPressed {
            childFragmentManager.fragments.firstOrNull()?.let {
                childFragmentManager.commit { remove(it) }
            }
            backPressedCallback?.isEnabled = false
        }
        backPressedCallback.isEnabled = false

        val adapter = DownloadPlaylistAdapter { playlist ->
            childFragmentManager.commit {
                replace<DownloadsFragmentPage>(
                    binding.fragment.id,
                    args = bundleOf(
                        IntentData.downloadTab to DownloadTab.PLAYLIST,
                        IntentData.playlistId to playlist.downloadPlaylist.playlistId
                    )
                )
            }
            backPressedCallback.isEnabled = true
        }
        binding.downloadsRecView.setOnDismissListener { position ->
            adapter.showDeleteDialog(requireContext(), position)
            // put the item back to the center, as it's currently out of the screen
            adapter.restoreItem(position)
        }
        binding.downloadsRecView.adapter = adapter

        val filterOptions = DownloadSortingOrder.entries
        val optionTitle = filterOptions[selectedSortType].title.getText(requireContext())
        binding.sortType.text = optionTitle

        lifecycleScope.launch(Dispatchers.Main) {
            val downloadPlaylists = withContext(Dispatchers.IO) {
                Database.downloadDao().getDownloadPlaylists()
            }

            binding.downloadsEmpty.isVisible = downloadPlaylists.isEmpty()
            if (downloadPlaylists.isNotEmpty()) {
                submitPlaylists(adapter, downloadPlaylists)

                binding.sortType.setOnClickListener {
                    BaseBottomSheet().setItems(filterOptions.toList()) { index ->
                        if (index == selectedSortType) return@setItems
                        selectedSortType = index

                        binding.sortType.text = optionTitle
                        submitPlaylists(adapter, downloadPlaylists)
                    }.show(childFragmentManager)
                }

                downloadsModel.searchQuery.observe(viewLifecycleOwner) {
                    submitPlaylists(adapter, downloadPlaylists)
                }

                // the amount of visible items can change while searching, hence
                // we have to make sure to only show the placeholder if there are currently no results
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                        super.onItemRangeRemoved(positionStart, itemCount)
                        binding.downloadsEmpty.isVisible = adapter.itemCount == 0
                        binding.downloadsRecView.isVisible = adapter.itemCount != 0
                    }

                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        super.onItemRangeInserted(positionStart, itemCount)
                        binding.downloadsEmpty.isVisible = adapter.itemCount == 0
                        binding.downloadsRecView.isVisible = adapter.itemCount != 0
                    }
                })
            } else {
                binding.sortType.isGone = true
            }
        }
    }

    private fun submitPlaylists(
        adapter: DownloadPlaylistAdapter,
        playlists: List<DownloadPlaylistWithDownload>
    ) {
        var sorted = applySortOrder(playlists)
        val query = downloadsModel.searchQuery.value
        if (!query.isNullOrEmpty()) {
            sorted = sorted.filter {
                it.downloadPlaylist.title.contains(query, ignoreCase = true)
            }
        }

        adapter.submitList(sorted)
    }

    fun applySortOrder(items: List<DownloadPlaylistWithDownload>): List<DownloadPlaylistWithDownload> {
        return when (selectedSortType) {
            0 -> items
            else -> items.reversed()
        }
    }
}