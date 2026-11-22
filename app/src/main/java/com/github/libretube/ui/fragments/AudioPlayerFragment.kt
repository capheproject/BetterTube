package com.github.libretube.ui.fragments

import com.github.libretube.enums.ShareObjectType

import com.github.libretube.obj.ShareData

import com.github.libretube.ui.dialogs.ShareDialog

import com.github.libretube.helpers.DownloadHelper

import androidx.core.view.isInvisible
import androidx.constraintlayout.widget.ConstraintSet
import android.animation.ValueAnimator
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.WatchLater
import com.google.android.material.slider.Slider
import com.github.libretube.helpers.Haptics
import androidx.core.graphics.ColorUtils
import android.content.res.ColorStateList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.github.libretube.helpers.ArtworkColors
import android.graphics.drawable.TransitionDrawable
import com.github.libretube.helpers.PlayerModeMemory
import androidx.core.view.updateLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import android.content.res.Configuration
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
    private var isSwitchingMode = false

    // colours of the play button and progress bar (see updateControlColors)
    private var lastVibrant: Int? = null
    private var shownAccent: ArtworkColors.Accent? = null
    private var playButtonDrawable: ArtworkColors.PlayButton? = null
    private var accentAnimator: ValueAnimator? = null

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
            // the artwork floats above the content: below the status bar while expanded
            val startSet = binding.playerMotionLayout.getConstraintSet(R.id.start)
            startSet.setMargin(R.id.artwork_frame, ConstraintSet.TOP, systemBars.top)
            binding.playerMotionLayout.updateState(R.id.start, startSet)
        }

        // keep room for the artwork in the content below it (same size as the artwork when open)
        binding.audioPlayerMain.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val height = expandedArtworkHeight(right - left)
            val space = _binding?.artworkSpace ?: return@addOnLayoutChangeListener
            if (space.layoutParams.height != height) {
                space.post { _binding?.artworkSpace?.updateLayoutParams { this.height = height } }
            }
        }

        // the description panel clips its own text (cut cleanly at its rounded corners)
        binding.descriptionScroll.clipToOutline = true

        initializeTransitionLayout()
        limitArtworkHeightInLandscape()
        // the mini player's thumbnail sits flush at the card's left edge: round its left corners
        MiniPlayerShape.applyLeftCorners(binding.miniPlayerThumbnail, 1f)

        // the description scrolls on its own: it must not hand its scroll to the player's
        // swipe-to-minimise (set here, not in XML: NestedScrollView crashes when it's set while
        // inflating)
        binding.descriptionScroll.isNestedScrollingEnabled = false

        // fine grain keeps the large gradients smooth; the play button glows in the accent colour
        binding.grain.background = ArtworkColors.grain(requireContext())
        binding.playPause.elevation = PLAY_BUTTON_GLOW_DP * resources.displayMetrics.density
        playButtonDrawable = null
        shownAccent = null
        updateControlColors(animate = false)

        // until the stream's details arrive, show the title we already know (from the feed),
        // or a quiet placeholder
        binding.title.text = PlayingQueue.getCurrent()?.title ?: getString(R.string.loading)

        // like on the video page: tap the title for the description, long press to copy it
        binding.titleLayout.setOnClickListener { toggleDescription() }
        binding.titleLayout.setOnLongClickListener {
            ClipboardHelper.save(requireContext(), text = binding.title.text.toString())
            true
        }

        binding.minimizePlayer.setOnClickListener {
            // the screen around the player follows the transition's progress; moving it up front
            // made the expanded player jump before it started to shrink
            binding.playerMotionLayout.setTransitionDuration(COLLAPSE_MS)
            binding.playerMotionLayout.transitionToEnd()
        }

        // tapping the mini player expands it, as quickly as the video player does
        binding.miniPlayerControls.setOnClickListener {
            if (binding.playerMotionLayout.progress > 0f) {
                binding.playerMotionLayout.setTransitionDuration(EXPAND_MS)
                binding.playerMotionLayout.transitionToStart()
            }
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
            // ignore repeated taps while the switch is already under way
            if (isSwitchingMode) return@setOnClickListener
            isSwitchingMode = true
            Haptics.confirm(binding.openVideo)
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
                binding.playerMotionLayout.setTransitionDuration(COLLAPSE_MS)
                binding.playerMotionLayout.transitionToEnd()
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
        PlayerModeMemory.remember(audio = false)
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
                _binding?.let {
                    MiniPlayerShape.apply(it.audioPlayerContainer, progress.absoluteValue)
                    // the rim belongs to the card, which only appears at the end of the transition
                    it.miniRim.alpha = ((progress.absoluteValue - 0.9f) / 0.1f).coerceIn(0f, 1f)
                }
                updateArtworkForProgress(progress.absoluteValue)
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (currentId == transitionEndId) {
                    _binding?.let {
                        MiniPlayerShape.apply(it.audioPlayerContainer, 1f)
                        it.miniRim.alpha = 1f
                    }
                    updateArtworkForProgress(1f)
                    viewModel.isMiniPlayerVisible.value = true
                    activity.minimizePlayerContainerLayout()
                } else if (currentId == transitionStartId) {
                    _binding?.let {
                        MiniPlayerShape.apply(it.audioPlayerContainer, 0f)
                        it.miniRim.alpha = 0f
                    }
                    updateArtworkForProgress(0f)
                    viewModel.isMiniPlayerVisible.value = false
                    activity.maximizePlayerContainerLayout()
                }
            }
        })

        if (arguments?.getBoolean(IntentData.switchedPlayerMode, false) == true) {
            // switched from the video page: the picture is already in place, only the
            // audio controls rise in
            arguments?.putBoolean(IntentData.switchedPlayerMode, false)
            binding.playerMotionLayout.progress = 0.01f
            binding.playerMotionLayout.transitionToStart()
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
        applyArtworkColors(bitmap)
        backdrop.setImageBitmap(bitmap)
        val targetAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP))
            0.9f
        } else {
            0.25f
        }
        backdrop.animate().alpha(targetAlpha).setDuration(600).start()
    }

    /**
     * Gives the player the artwork's own colours: a deep wash from the artwork's most
     * characteristic colour down to the controls (also shown in the mini player card).
     */
    private fun applyArtworkColors(bitmap: Bitmap) {
        lifecycleScope.launch {
            val vibrant = withContext(Dispatchers.Default) {
                runCatching { ArtworkColors.dominantVibrant(bitmap) }.getOrNull()
            } ?: ThemeHelper.getThemeColor(requireContext(), androidx.appcompat.R.attr.colorPrimary)
            val binding = _binding ?: return@launch
            val scrim = binding.ambientScrim
            val wash = ArtworkColors.playerWash(requireContext(), vibrant)

            lastVibrant = vibrant
            updateControlColors()
            val current = scrim.background
            scrim.background = if (current != null) {
                TransitionDrawable(arrayOf(current, wash)).apply {
                    isCrossFadeEnabled = true
                    startTransition(ARTWORK_COLOR_FADE_MS)
                }
            } else {
                wash
            }
        }
    }

    /** The colours the play button and progress bar should have now. */
    private fun targetAccent(): ArtworkColors.Accent {
        val vibrant = lastVibrant
        val fromVideo = PreferenceHelper.getBoolean(PreferenceKeys.PLAYER_COLORS_FROM_VIDEO, true)
        return if (vibrant != null && fromVideo) ArtworkColors.accent(requireContext(), vibrant)
        else ArtworkColors.themeAccent(requireContext())
    }

    /**
     * Brings the play button and progress bar to their current colours (the video's, or the
     * accent colour when that's turned off), fading smoothly from the colours shown before.
     */
    private fun updateControlColors(animate: Boolean = true) {
        if (_binding == null) return
        val target = targetAccent()
        val from = shownAccent
        accentAnimator?.cancel()
        if (from == null || !animate || from == target) {
            showAccent(target)
            return
        }
        accentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CONTROL_COLOR_FADE_MS
            addUpdateListener { showAccent(ArtworkColors.blend(from, target, it.animatedFraction)) }
            start()
        }
    }

    private fun showAccent(accent: ArtworkColors.Accent) {
        val binding = _binding ?: return
        val playButton = playButtonDrawable ?: ArtworkColors.playButton(requireContext(), accent).also {
            playButtonDrawable = it
            binding.playPause.background = it.drawable
        }
        playButton.base.colors = intArrayOf(accent.light, accent.deep)
        binding.playPause.iconTint = ColorStateList.valueOf(accent.onAccent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.playPause.outlineSpotShadowColor = accent.main
            binding.playPause.outlineAmbientShadowColor = accent.main
        }
        binding.timeBar.trackActiveTintList = ColorStateList.valueOf(accent.main)
        binding.timeBar.thumbTintList = ColorStateList.valueOf(accent.main)
        binding.timeBar.trackInactiveTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent.main, 0x40))
        shownAccent = accent
    }

    private fun initializeSeekBar() {
        binding.timeBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) playerController?.seekTo(value.toLong() * 1000)
        }
        binding.timeBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Haptics.tick(slider)
            override fun onStopTrackingTouch(slider: Slider) = Haptics.tick(slider)
        })
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
            // placeholders while loading, so the row isn't empty
            binding.duration.text = TIME_PLACEHOLDER
            binding.currentPosition.text = TIME_PLACEHOLDER
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

    override fun onResume() {
        super.onResume()
        // e.g. back from the settings: "Play button in the video's colours" may have changed
        updateControlColors()
    }

    override fun onDestroyView() {
        accentAnimator?.cancel()
        super.onDestroyView()
        _binding = null
    }

    // tapping the artwork does nothing (the play button is right below); swipes and long press stay
    override fun onSingleTap() = Unit

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
        // the same gear menu as the video player: Watch later, Download, Share, speed
        val items = listOf(
            BottomSheetItem(BottomSheetItemTitle.StringResource(R.string.watch_later), R.drawable.ic_time_outlined),
            BottomSheetItem(BottomSheetItemTitle.StringResource(R.string.download), R.drawable.ic_download),
            BottomSheetItem(BottomSheetItemTitle.StringResource(R.string.share), R.drawable.ic_share),
            BottomSheetItem(
                BottomSheetItemTitle.StringResource(R.string.playback_speed),
                R.drawable.ic_speed
            )
        )

        BaseBottomSheet()
            .setItems(items) { index ->
                val current = PlayingQueue.getCurrent()
                val videoId = current?.url?.toID()
                when (index) {
                    0 -> current?.let {
                        Haptics.confirm(binding.showMore)
                        WatchLater.add(requireActivity(), viewLifecycleOwner.lifecycleScope, it)
                    }
                    1 -> videoId?.let {
                        DownloadHelper.startDownloadDialog(requireContext(), childFragmentManager, it)
                    }
                    2 -> if (current != null && videoId != null) {
                        ShareDialog().apply {
                            arguments = bundleOf(
                                IntentData.id to videoId,
                                IntentData.shareObjectType to ShareObjectType.VIDEO,
                                IntentData.shareData to ShareData(
                                    title = current.title,
                                    previewImageUrl = current.thumbnail
                                )
                            )
                        }.show(childFragmentManager, ShareDialog::class.java.name)
                    }
                    3 -> playerController?.let { PlaybackOptionsSheet(it).show(childFragmentManager) }
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

    // a downward swipe on the artwork now minimises the player (like the video), so the volume
    // swipe is off here; the phone's volume buttons do the same
    override fun onSwipe(distanceY: Float) = Unit

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

    /** In landscape a full-width 16:9 artwork would fill the screen: keep it a banner. */
    private fun limitArtworkHeightInLandscape(config: Configuration = resources.configuration) {
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.thumbnail.updateLayoutParams<ConstraintLayout.LayoutParams> {
            matchConstraintMaxHeight = if (isLandscape) {
                (config.screenHeightDp * resources.displayMetrics.density * LANDSCAPE_ARTWORK_MAX_HEIGHT).toInt()
            } else {
                0
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) limitArtworkHeightInLandscape(newConfig)
    }

    /** Height of the expanded 16:9 artwork for a given width (capped in landscape). */
    private fun expandedArtworkHeight(width: Int): Int {
        val config = resources.configuration
        val natural = width * 9 / 16
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) return natural
        val max = (config.screenHeightDp * resources.displayMetrics.density * LANDSCAPE_ARTWORK_MAX_HEIGHT).toInt()
        return minOf(natural, max)
    }

    /**
     * While minimising, the artwork travels into the mini player's thumbnail spot: its buttons
     * fade out early and its left corners take the card's rounding.
     */
    private fun updateArtworkForProgress(progress: Float) {
        val binding = _binding ?: return
        MiniPlayerShape.applyLeftCorners(binding.artworkFrame, progress)
        val overlayAlpha = (1f - progress * ARTWORK_OVERLAY_FADE_SPEED).coerceIn(0f, 1f)
        val overlays = listOf(binding.minimizePlayer, binding.showMore, binding.artworkScrim)
        overlays.forEach {
            it.alpha = overlayAlpha
            it.isInvisible = overlayAlpha == 0f
        }
        if (!isSwitchingMode) {
            binding.openVideo.alpha = overlayAlpha
            binding.openVideo.isInvisible = overlayAlpha == 0f
        }
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

        // the same speeds as the video player
        private const val EXPAND_MS = 160
        private const val COLLAPSE_MS = 200
        private const val ARTWORK_OVERLAY_FADE_SPEED = 3f
        private const val TIME_PLACEHOLDER = "0:00"
        private const val ARTWORK_COLOR_FADE_MS = 500
        private const val PLAY_BUTTON_GLOW_DP = 12
        private const val CONTROL_COLOR_FADE_MS = 450L
        private const val LANDSCAPE_ARTWORK_MAX_HEIGHT = 0.3f
        private const val DESCRIPTION_ANIMATION_MS = 220L
        private const val RISE_DISTANCE_DP = 16
        private const val MODE_SWITCH_OUT_MS = 140L
        private const val MODE_SWITCH_IN_MS = 320L
        private const val MODE_SWITCH_STAGGER_MS = 25L
    }
}
