package com.github.libretube.ui.fragments

import com.github.libretube.util.ThumbnailHints
import com.github.libretube.ui.views.DescriptionLayout
import com.github.libretube.api.obj.Streams
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.github.libretube.util.TextUtils.toTimeInSeconds
import com.github.libretube.util.TextUtils
import com.github.libretube.ui.sheets.CommentsSheet
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.helpers.IntentHelper
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.math.MathUtils.clamp
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentAudioPlayerBinding
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.extensions.navigateVideo
import com.github.libretube.extensions.normalize
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.AudioHelper
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.MiniPlayerShape
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.obj.bottomsheetitem.BottomSheetItem
import com.github.libretube.obj.bottomsheetitem.BottomSheetItemTitle
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.extensions.getSystemInsets
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.interfaces.AudioPlayerOptions
import com.github.libretube.ui.listeners.AudioPlayerThumbnailListener
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.ChaptersBottomSheet
import com.github.libretube.ui.sheets.PlaybackOptionsSheet
import com.github.libretube.ui.sheets.PlayingQueueSheet
import com.github.libretube.ui.sheets.SleepTimerSheet
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.ui.views.DataUsageView
import com.github.libretube.util.DataSaverMode
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@UnstableApi
class AudioPlayerFragment : Fragment(R.layout.fragment_audio_player), AudioPlayerOptions {
    private var _binding: FragmentAudioPlayerBinding? = null
    val binding get() = _binding!!

    private lateinit var audioHelper: AudioHelper
    private val activity get() = context as AbstractPlayerHostActivity
    private val viewModel: CommonPlayerViewModel by activityViewModels()
    private val chaptersModel: ChaptersViewModel by activityViewModels()
    private val commentsViewModel: CommentsViewModel by activityViewModels()

    // for the transition
    private var transitionStartId = 0
    private var transitionEndId = 0

    private var handler = Handler(Looper.getMainLooper())
    private var isPaused = !PlayerHelper.playAutomatically

    var isOffline: Boolean = false
        private set
    private var playerController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioHelper = AudioHelper(requireContext())

        isOffline = requireArguments().getBoolean(IntentData.offlinePlayer)

        BackgroundHelper.startMediaService(
            requireContext(),
            if (isOffline) OfflinePlayerService::class.java else OnlinePlayerService::class.java,
        ) {
            if (_binding == null) {
                it.sendCustomCommand(AbstractPlayerService.stopServiceCommand, Bundle.EMPTY)
                it.release()
                return@startMediaService
            }

            playerController = it
            handleServiceConnection()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentAudioPlayerBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // manually apply additional padding for edge-to-edge compatibility
        activity.getSystemInsets()?.let { systemBars ->
            with(binding.audioPlayerMain) {
                setPadding(
                    paddingLeft,
                    paddingTop + systemBars.top,
                    paddingRight,
                    paddingBottom + systemBars.bottom
                )
            }
        }

        initializeTransitionLayout()

        // like on the video page: tap the title for the description, long press to copy it
        binding.titleLayout.setOnClickListener { toggleDescription() }
        binding.titleLayout.setOnLongClickListener {
            ClipboardHelper.save(requireContext(), text = binding.title.text.toString())
            true
        }

        binding.minimizePlayer.setOnClickListener {
            activity.minimizePlayerContainerLayout()
            binding.playerMotionLayout.transitionToEnd()
        }

        binding.rewindBTN.setOnClickListener {
            playerController?.seekBy(-PlayerHelper.seekIncrement)
        }
        binding.forwardBTN.setOnClickListener {
            playerController?.seekBy(PlayerHelper.seekIncrement)
        }

        childFragmentManager.setFragmentResultListener(
            PlayingQueueSheet.PLAYING_QUEUE_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, args ->
            playerController?.navigateVideo(
                args.getString(IntentData.videoId) ?: return@setFragmentResultListener
            )
        }
        binding.openVideo.setOnClickListener {
            val current = PlayingQueue.getCurrent()
            val currentId = current?.url?.toID() ?: return@setOnClickListener
            // the video page opens with this exact picture in the same place
            ThumbnailHints.put(currentId, current.thumbnail, binding.thumbnail)
            animateAudioOnlyViews(show = false) { switchToVideoMode(currentId) }
        }

        childFragmentManager.setFragmentResultListener(
            ChaptersBottomSheet.SEEK_TO_POSITION_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            playerController?.seekTo(bundle.getLong(IntentData.currentPosition))
        }

        chaptersModel.chaptersLiveData.observe(viewLifecycleOwner) { updateChapterName() }
        chaptersModel.currentChapterIndex.observe(viewLifecycleOwner) { updateChapterName() }

        binding.chapterName.setOnClickListener {
            ChaptersBottomSheet()
                .apply {
                    arguments = bundleOf(
                        IntentData.duration to playerController?.duration?.div(1000)
                    )
                }
                .show(childFragmentManager)
        }

        setupVideoActions()

        binding.playbackError.playbackRetry.setOnClickListener {
            binding.playbackError.root.isGone = true
            playerController?.sendCustomCommand(
                AbstractPlayerService.runPlayerActionCommand,
                bundleOf(PlayerCommand.RETRY_PLAYBACK.name to true)
            )
        }

        binding.miniPlayerClose.setOnClickListener {
            killFragment(true)
        }

        val listener = AudioPlayerThumbnailListener(requireContext(), this)
        binding.thumbnail.setOnTouchListener(listener)

        binding.playPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.miniPlayerPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.showMore.setOnClickListener {
            showPlayerOptions()
        }

        // the data label shows a speaker icon here
        binding.dataUsage.mode = DataUsageView.Mode.AUDIO

        // update the currently shown volume
        binding.volumeProgressBar.let { bar ->
            bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
        }

        if (!PlayerHelper.playAutomatically) updatePlayPauseButton()

        updateChapterIndex()

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                binding.audioPlayerContainer.isClickable = false
                binding.playerMotionLayout.transitionToEnd()
                activity.minimizePlayerContainerLayout()
                activity.requestOrientationChange()
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                binding.playerMotionLayout.progress = backEvent.progress
            }

            override fun handleOnBackCancelled() {
                binding.playerMotionLayout.transitionToStart()
            }
        }
        setOnBackPressed(onBackPressedCallback)

        viewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isMiniPlayerVisible ->
            // re-add the callback on top of the back pressed dispatcher listeners stack,
            // so that it's the first one to become called while the full player is visible
            if (!isMiniPlayerVisible) {
                onBackPressedCallback.remove()
                setOnBackPressed(onBackPressedCallback)
            }

            // if the player is minimized, the fragment behind the player should handle the event
            onBackPressedCallback.isEnabled = isMiniPlayerVisible != true
        }
    }

    fun switchToVideoMode(videoId: String) {
        playerController?.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name to false)
        )

        killFragment(false)

        NavigationHelper.openVideoPlayerFragment(
            context = requireContext(),
            playerData = PlayerData(
                videoId = videoId,
                isOffline = isOffline
            ),
            alreadyStarted = true,
            switchedFromAudio = true
        )
    }

    private fun killFragment(stopPlayer: Boolean) {
        viewModel.isMiniPlayerVisible.value = false

        if (stopPlayer) playerController?.sendCustomCommand(
            AbstractPlayerService.stopServiceCommand,
            Bundle.EMPTY
        )
        playerController?.release()
        playerController = null

        viewModel.isFullscreen.value = false
        binding.playerMotionLayout.transitionToEnd()
        activity.supportFragmentManager.commit {
            remove(this@AudioPlayerFragment)
        }
    }

    fun playNextVideo(videoId: String) {
        playerController?.navigateVideo(videoId)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout() {
        activity.setPlayerContainerProgress(0f)

        binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                activity.setPlayerContainerProgress(progress.absoluteValue)
                _binding?.let { MiniPlayerShape.apply(it.audioPlayerContainer, progress.absoluteValue) }
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (currentId == transitionEndId) {
                    _binding?.let { MiniPlayerShape.apply(it.audioPlayerContainer, 1f) }
                    viewModel.isMiniPlayerVisible.value = true
                    activity.minimizePlayerContainerLayout()
                } else if (currentId == transitionStartId) {
                    _binding?.let { MiniPlayerShape.apply(it.audioPlayerContainer, 0f) }
                    viewModel.isMiniPlayerVisible.value = false
                    activity.maximizePlayerContainerLayout()
                }
            }
        })

        if (arguments?.getBoolean(IntentData.switchedPlayerMode, false) == true) {
            // switched from the video page: the picture is already in place, only the
            // audio controls rise in
            arguments?.putBoolean(IntentData.switchedPlayerMode, false)
            binding.playerMotionLayout.progress = 0f
            animateAudioOnlyViews(show = true)
        } else if (arguments?.getBoolean(IntentData.minimizeByDefault, false) != true) {
            binding.playerMotionLayout.progress = 1f
            binding.playerMotionLayout.transitionToStart()
        } else {
            binding.playerMotionLayout.progress = 0f
            binding.playerMotionLayout.transitionToEnd()
        }
    }

    /**
     * Load the information from a new stream into the UI
     */
    private fun updateStreamInfo(metadata: MediaMetadata) {
        val binding = _binding ?: return

        binding.title.text = metadata.title
        binding.miniPlayerTitle.text = metadata.title

        val description = metadata.extras?.getString(IntentData.streams)?.let {
            runCatching { JsonHelper.json.decodeFromString<Streams>(it).description }.getOrNull()
        }.orEmpty()
        DescriptionLayout.bindDescription(binding.description, description, ::handleDescriptionLink)
        binding.descriptionArrow.isVisible = description.isNotBlank()
        if (description.isBlank()) setDescriptionExpanded(false)

        metadata.artworkUri?.let { updateThumbnailAsync(it) }

        initializeSeekBar()
    }

    private fun updateThumbnailAsync(thumbnailUri: Uri) {
        if (DataSaverMode.isEnabled(requireContext()) && !isOffline) {
            binding.progress.isVisible = false
            binding.thumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
            binding.thumbnail.setImageResource(R.drawable.ic_launcher_monochrome)
            val primaryColor = ThemeHelper.getThemeColor(
                requireContext(),
                androidx.appcompat.R.attr.colorPrimary
            )
            binding.thumbnail.setColorFilter(primaryColor)
            return
        }

        // the artwork keeps its 16:9 slot while loading; show the picture the user came from
        // (e.g. the video page) right away if we have it
        binding.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
        binding.thumbnail.setColorFilter(Color.TRANSPARENT)
        val hint = ThumbnailHints.get(PlayingQueue.getCurrent()?.url?.toID())?.image
        binding.thumbnail.setImageDrawable(hint?.newDrawable(resources)?.mutate())
        binding.progress.isVisible = hint == null

        lifecycleScope.launch {
            val binding = _binding ?: return@launch
            val bitmap = ImageHelper.getImage(requireContext(), thumbnailUri)
            binding.thumbnail.setImageBitmap(bitmap)
            binding.miniPlayerThumbnail.setImageBitmap(bitmap)
            binding.progress.isGone = true
            showAmbientBackdrop(bitmap)
        }
    }

    /**
     * Shows the artwork blurred behind the player, giving every video its own colours.
     * Blurring needs Android 12; older versions show the artwork faintly instead.
     */
    private fun showAmbientBackdrop(bitmap: Bitmap?) {
        val backdrop = _binding?.ambientBackdrop ?: return
        if (bitmap == null) {
            backdrop.animate().alpha(0f).setDuration(300).start()
            return
        }
        backdrop.setImageBitmap(bitmap)
        val targetAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP))
            0.9f
        } else {
            0.25f
        }
        backdrop.animate().alpha(targetAlpha).setDuration(600).start()
    }

    private fun initializeSeekBar() {
        binding.timeBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) playerController?.seekTo(value.toLong() * 1000)
        }
        updateSeekBar()
    }

    /**
     * Update the position, duration and text views belonging to the seek bar
     */
    private fun updateSeekBar() {
        val binding = _binding ?: return
        val duration = playerController?.duration?.takeIf { it > 0 } ?: let {
            // if there's no duration available, clear everything
            binding.timeBar.value = 0f
            binding.duration.text = ""
            binding.currentPosition.text = ""
            handler.postDelayed(this::updateSeekBar, 100)
            return
        }
        val currentPosition = playerController?.currentPosition?.toFloat() ?: 0f

        // set the text for the indicators
        binding.duration.text = DateUtils.formatElapsedTime(duration / 1000)
        binding.currentPosition.text = DateUtils.formatElapsedTime(
            (currentPosition / 1000).toLong()
        )

        // update the time bar current value and maximum value
        binding.timeBar.valueTo = (duration / 1000).toFloat()
        binding.timeBar.value = clamp(
            currentPosition / 1000,
            binding.timeBar.valueFrom,
            binding.timeBar.valueTo
        )

        handler.postDelayed(this::updateSeekBar, 200)
    }

    private fun updatePlayPauseButton() {
        playerController?.let {
            val binding = _binding ?: return

            val iconRes = PlayerHelper.getPlayPauseActionIcon(it)
            binding.playPause.setIconResource(iconRes)
            binding.miniPlayerPause.setImageResource(iconRes)
        }
    }

    private fun handleServiceConnection() {
        playerController?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                // the service retries on its own; Retry continues from the same position now
                _binding?.playbackError?.root?.isVisible = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                if (playbackState != Player.STATE_IDLE) _binding?.playbackError?.root?.isGone = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)

                updatePlayPauseButton()
                isPaused = !isPlaying
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)

                updateStreamInfo(mediaMetadata)
                // JSON-encode as work-around for https://github.com/androidx/media/issues/564
                val chapters: List<ChapterSegment>? =
                    mediaMetadata.extras?.getString(IntentData.chapters)?.let {
                        JsonHelper.json.decodeFromString(it)
                    }
                chaptersModel.chaptersLiveData.value = chapters
            }
        })
        playerController?.mediaMetadata?.let { updateStreamInfo(it) }
        // JSON-encode as work-around for https://github.com/androidx/media/issues/564
        chaptersModel.chaptersLiveData.value =
            playerController?.mediaMetadata?.extras?.getString(IntentData.chapters)?.let {
                JsonHelper.json.decodeFromString(it)
            }

        updatePlayPauseButton()
        initializeSeekBar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onSingleTap() {
        playerController?.togglePlayPauseState()
    }

    /**
     * Comments, as on the video page (sharing, saving and downloading are in the overflow menu).
     */
    private fun setupVideoActions() {
        binding.commentsToggle.setOnClickListener {
            val current = PlayingQueue.getCurrent() ?: return@setOnClickListener
            val videoId = current.url?.toID() ?: return@setOnClickListener
            // leave the title and controls above the sheet visible
            viewModel.maxSheetHeightPx = (binding.root.height * COMMENTS_SHEET_HEIGHT).toInt()
            commentsViewModel.videoIdLiveData.updateIfChanged(videoId)
            CommentsSheet()
                .apply { arguments = bundleOf(IntentData.channelAvatar to current.uploaderAvatar) }
                .show(childFragmentManager)
        }

        // links in comments: timestamps seek in the current video, other links open as usual
        activity?.supportFragmentManager?.setFragmentResultListener(
            CommentsSheet.HANDLE_LINK_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val link = bundle.getString(IntentData.url) ?: return@setFragmentResultListener
            val linkedVideoId = TextUtils.getVideoIdFromUri(link.toUri())
            val time = link.toUri().getQueryParameter("t")?.toTimeInSeconds()
            if (linkedVideoId != null && linkedVideoId == PlayingQueue.getCurrent()?.url?.toID() && time != null) {
                playerController?.seekTo(time * 1000)
            } else {
                IntentHelper.openLinkFromHref(requireContext(), childFragmentManager, link)
            }
        }
    }

    /**
     * Overflow menu with the less frequently used player actions, keeping the player itself minimal.
     */
    private fun showPlayerOptions() {
        val items = listOf(
            BottomSheetItem(BottomSheetItemTitle.StringResource(R.string.queue), R.drawable.ic_queue),
            BottomSheetItem(
                BottomSheetItemTitle.StringResource(R.string.playback_speed),
                R.drawable.ic_speed
            ),
            BottomSheetItem(
                BottomSheetItemTitle.StringResource(R.string.sleep_timer),
                R.drawable.ic_sleep
            ),
            BottomSheetItem(
                BottomSheetItemTitle.StringResource(R.string.player_autoplay),
                R.drawable.ic_playlist,
                getCurrent = {
                    BottomSheetItemTitle.StringResource(
                        if (PlayerHelper.autoPlayEnabled) R.string.enabled else R.string.disabled
                    )
                }
            ),
            BottomSheetItem(
                BottomSheetItemTitle.StringResource(R.string.video_options_more),
                R.drawable.ic_share
            )
        )

        BaseBottomSheet()
            .setItems(items) { index ->
                when (index) {
                    0 -> PlayingQueueSheet().show(childFragmentManager)
                    1 -> playerController?.let { PlaybackOptionsSheet(it).show(childFragmentManager) }
                    2 -> SleepTimerSheet().show(childFragmentManager)
                    3 -> PlayerHelper.autoPlayEnabled = !PlayerHelper.autoPlayEnabled
                    4 -> onLongTap()
                }
            }
            .show(childFragmentManager)
    }

    override fun onLongTap() {
        val current = PlayingQueue.getCurrent() ?: return
        VideoOptionsBottomSheet()
            .apply {
                arguments = bundleOf(IntentData.streamItem to current)
            }
            .show(childFragmentManager)
    }

    override fun onSwipe(distanceY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isVisible = true
        updateVolume(distanceY)
    }

    override fun onSwipeEnd() {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isGone = true
    }

    private fun updateVolume(distance: Float) {
        val bar = binding.volumeProgressBar
        binding.volumeControls.apply {
            if (isGone) {
                isVisible = true
                // Volume could be changed using other mediums, sync progress
                // bar with new value.
                bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
            }
        }

        if (bar.progress == 0) {
            binding.volumeImageView.setImageResource(
                when {
                    distance > 0 -> R.drawable.ic_volume_up
                    else -> R.drawable.ic_volume_off
                }
            )
        }
        bar.incrementProgressBy(distance.toInt() / 3)
        audioHelper.deviceVolume = bar.progress.toFloat() / bar.max

        binding.volumeTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
    }

    private fun toggleDescription() {
        setDescriptionExpanded(!binding.descriptionScroll.isVisible)
    }

    /**
     * Opens the description in the space under the title (it scrolls there), so the
     * controls and the comments row stay where they are.
     */
    private fun setDescriptionExpanded(expanded: Boolean) {
        val binding = _binding ?: return
        if (expanded && binding.description.text.isNullOrBlank()) return
        binding.descriptionArrow.animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(DESCRIPTION_ANIMATION_MS)
            .start()
        binding.title.maxLines = if (expanded) Int.MAX_VALUE else 2
        if (expanded) {
            binding.descriptionScroll.scrollTo(0, 0)
            binding.descriptionScroll.alpha = 0f
            binding.descriptionScroll.translationY = -RISE_DISTANCE_DP * resources.displayMetrics.density
            binding.descriptionScroll.isVisible = true
            binding.descriptionScroll.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(DESCRIPTION_ANIMATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            binding.descriptionScroll.animate().cancel()
            binding.descriptionScroll.isGone = true
        }
    }

    /** Timestamps in the description seek within this video, other links open as usual. */
    private fun handleDescriptionLink(link: String) {
        val uri = link.toUri()
        val linkedVideoId = TextUtils.getVideoIdFromUri(uri)
        val time = uri.getQueryParameter("t")?.toTimeInSeconds()
        if (linkedVideoId == PlayingQueue.getCurrent()?.url?.toID() && time != null) {
            playerController?.seekTo(time * 1000)
        } else {
            IntentHelper.openLinkFromHref(requireContext(), childFragmentManager, link)
        }
    }

    /** The current chapter under the title; only shown when the video has chapters. */
    private fun updateChapterName() {
        val binding = _binding ?: return
        val chapters = chaptersModel.chaptersLiveData.value.orEmpty()
        binding.chapterName.isVisible = chapters.isNotEmpty()
        if (chapters.isEmpty()) return
        val index = chaptersModel.currentChapterIndex.value ?: 0
        binding.chapterName.text = chapters.getOrNull(index)?.title ?: getString(R.string.chapters)
    }

    /**
     * The parts that only the audio player has (everything under the artwork except the
     * comments row). Hiding them before switching to the video page, and showing them after
     * switching from it, makes the two modes feel like one screen with the picture swapped.
     */
    private fun animateAudioOnlyViews(show: Boolean, onEnd: () -> Unit = {}) {
        val binding = _binding ?: return onEnd()
        val views = listOf(
            binding.openVideo, binding.titleLayout, binding.chapterName, binding.descriptionScroll,
            binding.timeBar, binding.timesRow, binding.transportRow
        )
        val distance = RISE_DISTANCE_DP * resources.displayMetrics.density
        views.forEachIndexed { index, view ->
            view.animate().cancel()
            if (show) {
                view.alpha = 0f
                view.translationY = distance
            }
            view.animate()
                .alpha(if (show) 1f else 0f)
                .translationY(if (show) 0f else distance / 2)
                .setStartDelay(if (show) MODE_SWITCH_STAGGER_MS * index else 0L)
                .setDuration(if (show) MODE_SWITCH_IN_MS else MODE_SWITCH_OUT_MS)
                .setInterpolator(DecelerateInterpolator())
                .apply { if (index == views.lastIndex) withEndAction(onEnd) }
                .start()
        }
    }

    private fun updateChapterIndex() {
        if (_binding == null) return
        handler.postDelayed(this::updateChapterIndex, 100)

        val currentIndex =
            PlayerHelper.getCurrentChapterIndex(
                playerController?.currentPosition ?: return,
                chaptersModel.chapters
            )
        chaptersModel.currentChapterIndex.updateIfChanged(currentIndex ?: return)
    }

    companion object {
        private const val COMMENTS_SHEET_HEIGHT = 0.75f
        private const val DESCRIPTION_ANIMATION_MS = 220L
        private const val RISE_DISTANCE_DP = 16
        private const val MODE_SWITCH_OUT_MS = 140L
        private const val MODE_SWITCH_IN_MS = 320L
        private const val MODE_SWITCH_STAGGER_MS = 25L
    }
}
