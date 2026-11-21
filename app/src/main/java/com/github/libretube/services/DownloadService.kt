package com.github.libretube.services

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.SparseBooleanArray
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.core.util.contains
import androidx.core.util.keyIterator
import androidx.core.util.set
import androidx.core.util.valueIterator
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.libretube.LibreTubeApp.Companion.DOWNLOAD_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.enums.NotificationId
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.toLocalDate
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.DownloadFailures
import com.github.libretube.helpers.DownloadStates
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.DownloadHelper.getNotificationId
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.receivers.NotificationReceiver
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_PAUSE
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_RESUME
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_STOP
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.DownloadProvider
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.github.libretube.repo.SabrDownloadProvider
import com.github.libretube.ui.activities.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.sink
import java.net.HttpURLConnection
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize

/**
 * Download service with custom implementation of downloading using [HttpURLConnection].
 */
class DownloadService : LifecycleService() {
    private val binder = LocalBinder()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val coroutineContext = dispatcher + SupervisorJob()

    private lateinit var notificationManager: NotificationManager
    private lateinit var summaryNotificationBuilder: Builder

    /**
     * Maps all currently running downloads to `true`, and all paused or stopped downloads to `false`.
     */
    private val downloadQueue = SparseBooleanArray()
    private val _downloadFlow = MutableSharedFlow<Pair<Int, DownloadStatus>>()
    val downloadFlow: SharedFlow<Pair<Int, DownloadStatus>> = _downloadFlow

    /**
     * Cache that contains all the already-loaded video info.
     */
    private val cachedStreamsInfo: MutableMap<String, Streams> = mutableMapOf()

    override fun onCreate() {
        super.onCreate()
        IS_DOWNLOAD_RUNNING = true
        notifyForeground()
        sendBroadcast(Intent(ACTION_SERVICE_STARTED))
        registerNetworkChangedCallback()
    }

    /** Whether the last known default network was unmetered (null until the first report). */
    private var wasUnmetered: Boolean? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val unmetered =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val switchedToMetered = wasUnmetered == true && !unmetered
            wasUnmetered = unmetered
            // pause running downloads only when moving from e.g. Wi-Fi to mobile data, not
            // because a download started while already on mobile data (the user chose that)
            if (switchedToMetered) {
                lifecycleScope.launch(Dispatchers.Main) {
                    for (download in downloadQueue.keyIterator()) {
                        if (downloadQueue[download]) pause(download)
                    }
                }
            }
        }
    }

    /**
     * Listen for network changes and pause downloads if the connection switches to a metered one.
     * Registered once per service (it used to be registered again for every download, and each
     * registration reported the current network right away, pausing everything on mobile data).
     */
    private fun registerNetworkChangedCallback() {
        runCatching {
            getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val downloadId = intent?.getIntExtra("id", -1)
        when (intent?.action) {
            ACTION_DOWNLOAD_RESUME -> resume(downloadId!!)
            ACTION_DOWNLOAD_PAUSE -> pause(downloadId!!)
            ACTION_DOWNLOAD_STOP -> stop(downloadId!!)
            ACTION_RESUME_ALL -> resumeAll()
            ACTION_PAUSE_ALL -> pauseAll()
        }

        val downloadData = intent?.parcelableExtra<DownloadData>(IntentData.downloadData)
            ?: return START_NOT_STICKY
        val videoId = downloadData.videoId

        lifecycleScope.launch(coroutineContext) {
            try {
                // a new request always starts from fresh stream info (e.g. after deleting and
                // downloading the same video again)
                cachedStreamsInfo.remove(videoId)
                val streams = loadStreamsInfo(videoId)
                if (streams == null) {
                    stopServiceIfDone()
                    return@launch
                }

                val downloadItems = streams.toDownloadItems(downloadData)
                if (downloadItems.isEmpty()) {
                    // nothing matched the chosen format: say so instead of silently doing nothing
                    toastFromMainDispatcher(R.string.download_format_unavailable)
                    stopServiceIfDone()
                    return@launch
                }

                storeVideoMetadata(videoId, streams)
                for (downloadItem in downloadItems) {
                    start(downloadItem)
                }
                toastFromMainDispatcher(R.string.download_started)
            } catch (e: Exception) {
                Log.e(TAG(), e.stackTraceToString())
                toastFromMainDispatcher(
                    "${getString(R.string.download)}: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
                )
                stopServiceIfDone()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun loadStreamsInfo(videoId: String): Streams? {
        if (cachedStreamsInfo.contains(videoId))
            return cachedStreamsInfo[videoId]

        val streams = try {
            withContext(Dispatchers.IO) {
                MediaServiceRepository.instance.getStreams(videoId)
            }
        } catch (e: Exception) {
            Log.e(TAG(), e.stackTraceToString())
            toastFromMainDispatcher(e.localizedMessage.orEmpty())
            return null
        }

        cachedStreamsInfo[videoId] = streams
        return streams
    }

    private suspend fun storeVideoMetadata(videoId: String, streams: Streams) {
        val thumbnailTargetPath = getDownloadPath(DownloadHelper.THUMBNAIL_DIR, videoId)

        val download = Download(
            videoId,
            streams.title,
            streams.description,
            streams.uploader,
            streams.duration,
            streams.uploadTimestamp?.toLocalDate(),
            thumbnailTargetPath,
            streams.uploaderUrl,
            streams.views,
            streams.likes,
            streams.dislikes,
        )
        Database.downloadDao().insertDownload(download)

        for (chapter in streams.chapters) {
            val downloadChapter = DownloadChapter(
                videoId = videoId,
                name = chapter.title,
                start = chapter.start,
                thumbnailUrl = chapter.image
            )
            Database.downloadDao().insertDownloadChapter(downloadChapter)
        }

        // asynchronously load the remaining metadata
        // this allows the main thread to already start the actual download items (i.e. video/audio)
        // while the thumbnail and SponsorBlock segments are loaded in the background
        coroutineScope {
            launch(Dispatchers.IO) {
                downloadExtraVideoMetadata(videoId, streams.thumbnailUrl, thumbnailTargetPath)
            }
        }
    }

    /**
     * Download the thumbnail and SponsorBlock segments for the given [videoId].
     */
    private suspend fun downloadExtraVideoMetadata(
        videoId: String,
        thumbnailUrl: String,
        thumbnailTargetPath: Path
    ) {
        coroutineScope {
            launch {
                val segmentData = try {
                    val categories = PlayerHelper.getSponsorBlockCategories()
                    MediaServiceRepository.instance.getSegments(videoId, categories.map { it.key })
                } catch (e: Exception) {
                    Log.e(TAG(), "failed to download SponsorBlock segments for $videoId")
                    Log.e(TAG(), e.stackTraceToString())
                    return@launch
                }

                Database.downloadDao().insertSponsorBlockSegments(
                    segmentData.segments.map { it.toDownloadSegment(videoId) }
                )
            }

            launch {
                try {
                    ImageHelper.downloadImage(
                        this@DownloadService,
                        thumbnailUrl,
                        thumbnailTargetPath
                    )
                } catch (e: Exception) {
                    Log.e(TAG(), "failed to download image $thumbnailUrl")
                    Log.e(TAG(), e.stackTraceToString())
                }
            }
        }
    }

    /**
     * Download file and emit [DownloadStatus] to the collectors of [downloadFlow]
     * and notification.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun selectFormatAndDownloadFile(item: DownloadItem) {
        val provider = createDownloadProvider(item) ?: return
        downloadFile(item, provider) {
            // stream links expire after a few hours (e.g. a download paused overnight): fetch
            // fresh ones and carry on from where the file ends
            cachedStreamsInfo.remove(item.videoId)
            createDownloadProvider(item)
        }
    }

    private suspend fun createDownloadProvider(item: DownloadItem): DownloadProvider? {
        val streams = loadStreamsInfo(item.videoId) ?: return null
        return if (item.type == FileType.SUBTITLE) {
            // subtitles are always plain files and don't use SABR
            val subtitle = streams.subtitles.firstOrNull { it.code == item.language } ?: return null
            RawByteStreamDownloadProvider(subtitle.url!!.toHttpUrl())
        } else {
            val selectedStream = selectMatchingStream(streams, item) ?: return null
            if (selectedStream.url?.startsWith("http") == true) {
                RawByteStreamDownloadProvider(selectedStream.url!!.toHttpUrl())
            } else {
                SabrDownloadProvider(item, streams, selectedStream)
            }
        }
    }

    /**
     * Starts and progresses until the download is canceled or finished.
     *
     * You should probably not call this directly, call [selectFormatAndDownloadFile].
     */
    private suspend fun downloadFile(
        item: DownloadItem,
        initialProvider: DownloadProvider,
        refreshProvider: (suspend () -> DownloadProvider?)? = null,
    ) {
        var downloadProvider = initialProvider
        var refreshed = false
        DownloadFailures.clear(item.id)
        queuedIds.remove(item.id)
        downloadQueue[item.id] = true
        DownloadStates.set(item.id, DownloadStates.State.Running(item.path.fileSize(), item.downloadSize))
        val notificationBuilder = getNotificationBuilder(item)
        setResumeNotification(notificationBuilder, item)

        val sink = item.path.sink(StandardOpenOption.APPEND).buffer()
        var totalRead = item.path.fileSize()
        var numberOfTries = 0
        while (downloadQueue[item.id]) {
            try {
                // a plain file that already has all its bytes is checked instead of requested
                val alreadyComplete = downloadProvider is RawByteStreamDownloadProvider &&
                    item.downloadSize > 0 && item.path.fileSize() >= item.downloadSize
                val result = if (alreadyComplete) {
                    DownloadProgressResult.DownloadComplete
                } else {
                    downloadProvider.downloadNextChunk(item, sink)
                }
                when (result) {
                    DownloadProgressResult.DownloadComplete -> {
                        withContext(Dispatchers.IO) { sink.flush() }
                        if (!verifyComplete(item, downloadProvider)) {
                            // the provider stopped but the file is short: carry on
                            numberOfTries++
                            if (numberOfTries > MAX_SEGMENT_RETRIES) {
                                failDownload(item, notificationBuilder, null)
                                break
                            }
                            continue
                        }
                        DownloadStates.markComplete(item.id)
                        setPauseNotification(notificationBuilder, item, true)
                        _downloadFlow.emit(item.id to DownloadStatus.Completed)
                        downloadQueue[item.id] = false
                        break
                    }
                    DownloadProgressResult.Failed -> {
                        if (numberOfTries < MAX_SEGMENT_RETRIES) {
                            // try again with a growing pause (0.5s, 1s, 2s … up to 8s), so a
                            // short drop in signal doesn't stop the download
                            delay(
                                (RETRY_BASE_DELAY_MS shl numberOfTries)
                                    .coerceAtMost(RETRY_MAX_DELAY_MS)
                            )
                            numberOfTries++
                        } else if (!refreshed && refreshProvider != null) {
                            // still failing: the stream link has probably expired
                            refreshed = true
                            val fresh = withContext(Dispatchers.IO) {
                                runCatching { refreshProvider() }.getOrNull()
                            }
                            if (fresh != null) {
                                downloadProvider = fresh
                                numberOfTries = 0
                            }
                        } else {
                            failDownload(item, notificationBuilder, null)
                            break
                        }
                    }
                    is DownloadProgressResult.Progressed -> {
                        numberOfTries = 0
                        totalRead += result.bytes
                        DownloadStates.set(
                            item.id, DownloadStates.State.Running(totalRead, item.downloadSize)
                        )
                        _downloadFlow.emit(
                            item.id to DownloadStatus.Progress(
                                result.bytes,
                                totalRead,
                                item.downloadSize
                            )
                        )
                        updateNotification(notificationBuilder, item, totalRead.toInt())
                    }
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e(this@DownloadService::class.java.name, e.stackTraceToString())
                // an unexpected error (e.g. a dropped connection) is retried like a failed chunk,
                // then with fresh stream links, before giving up
                if (numberOfTries < MAX_SEGMENT_RETRIES) {
                    delay((RETRY_BASE_DELAY_MS shl numberOfTries).coerceAtMost(RETRY_MAX_DELAY_MS))
                    numberOfTries++
                    continue
                }
                if (!refreshed && refreshProvider != null) {
                    refreshed = true
                    val fresh = withContext(Dispatchers.IO) {
                        runCatching { refreshProvider() }.getOrNull()
                    }
                    if (fresh != null) {
                        downloadProvider = fresh
                        numberOfTries = 0
                        continue
                    }
                }
                // name the error type too: a bare message such as "closed" says too little
                val reason = listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": ")
                toastFromMainThread("${getString(R.string.download)}: $reason")
                failDownload(item, notificationBuilder, e)
                break
            }
        }

        withContext(Dispatchers.IO) {
            sink.flush()
            sink.close()
        }

        // start the next download if there are any remaining ones enqueued
        startNextEnqueueDownload()

        // explicitly send a pause event if the user paused the download, although it's not yet finished
        // (a failed item keeps its "failed" state instead of showing as paused)
        if (!item.isFinished && !DownloadFailures.isFailed(item.id)) pause(item.id)

        // if no new download was enqueued (i.e. there's no paused/stopped download left),
        // look if any downloads are still running, and if not, stop the service
        stopServiceIfDone()
    }

    /**
     * Gave up after all retries: kept on the Downloads page as failed, to retry later (e.g. on
     * another network or with a VPN).
     */
    private suspend fun failDownload(item: DownloadItem, builder: Builder, cause: Exception?) {
        setPauseNotification(builder, item, false)
        DownloadFailures.markFailed(item.id)
        DownloadStates.set(item.id, DownloadStates.State.Failed)
        _downloadFlow.emit(item.id to DownloadStatus.Error(cause?.message ?: "failed", cause))
        downloadQueue[item.id] = false
    }

    /**
     * Whether a download the provider reported as finished really is: a plain file must have all
     * its announced bytes; a SABR download must have reached the end (checked by its provider).
     */
    private fun verifyComplete(item: DownloadItem, provider: DownloadProvider): Boolean {
        val size = runCatching { item.path.fileSize() }.getOrDefault(0L)
        if (size <= 0L) return false
        return provider !is RawByteStreamDownloadProvider ||
            item.downloadSize <= 0L || size >= item.downloadSize
    }

    /** Items waiting for a free download slot (not the ones the user paused). */
    private val queuedIds = linkedSetOf<Int>()

    private suspend fun startNextEnqueueDownload() {
        while (queuedIds.isNotEmpty() && mayStartNewDownload()) {
            val id = queuedIds.first()
            queuedIds.remove(id)
            val dbItem = Database.downloadDao().findDownloadItemById(id) ?: continue
            if (!dbItem.isFinished) {
                lifecycleScope.launch(coroutineContext) { selectFormatAndDownloadFile(dbItem) }
            }
        }
    }

    private fun updateNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        totalRead: Int
    ) {
        notificationBuilder
            .setContentText(
                totalRead.formatAsFileSize() + " / " +
                        item.downloadSize.formatAsFileSize()
            )
            .setProgress(
                item.downloadSize.toInt(),
                totalRead,
                false
            )
        notificationManager.notify(
            item.getNotificationId(),
            notificationBuilder.build()
        )
    }

    /**
     * Returns true if the current amount of downloads is still less than the maximum amount of
     * concurrent downloads.
     */
    private fun mayStartNewDownload(): Boolean {
        val downloadCount = downloadQueue.valueIterator().asSequence().count { it }
        return downloadCount < DownloadHelper.MAX_CONCURRENT_DOWNLOADS
    }

    /**
     * Initiate download [Job] using [DownloadItem] by creating file according to [FileType]
     * for the requested file.
     */
    private fun start(item: DownloadItem) {
        item.path = when (item.type) {
            FileType.AUDIO -> getDownloadPath(DownloadHelper.AUDIO_DIR, item.fileName)
            FileType.VIDEO -> getDownloadPath(DownloadHelper.VIDEO_DIR, item.fileName)
            FileType.SUBTITLE -> getDownloadPath(DownloadHelper.SUBTITLE_DIR, item.fileName)
        }.apply { deleteIfExists() }.createFile()

        lifecycleScope.launch(coroutineContext) {
            item.id = Database.downloadDao().insertDownloadItem(item).toInt()
            // complete only once verified (see DownloadStates)
            DownloadStates.markTracked(item.id)

            if (mayStartNewDownload()) {
                selectFormatAndDownloadFile(item)
            } else {
                queue(item.id)
            }
        }
    }

    /**
     * Resume download which may have been paused.
     */
    fun resume(id: Int) {
        // If file is already downloading then avoid new download job.
        if (downloadQueue[id]) return

        if (!mayStartNewDownload()) {
            // waits for a free slot instead of being dropped
            queue(id)
            return
        }

        lifecycleScope.launch(coroutineContext) {
            val file = Database.downloadDao().findDownloadItemById(id) ?: return@launch
            selectFormatAndDownloadFile(file)
        }
    }

    /**
     * Pause downloading job for given [id]. If no downloads are active, stop the service.
     */
    fun pause(id: Int) {
        downloadQueue[id] = false
        queuedIds.remove(id)
        if (DownloadStates.get(id) != DownloadStates.State.Complete &&
            DownloadStates.get(id) != DownloadStates.State.Failed
        ) {
            DownloadStates.set(id, DownloadStates.State.Paused)
        }

        lifecycleScope.launch(coroutineContext) {
            _downloadFlow.emit(id to DownloadStatus.Paused)
        }

        stopServiceIfDone()
    }

    /**
     * Resume all downloads: Queue them all, then fill empty slots.
     */
    private fun resumeAll() {
        lifecycleScope.launch(coroutineContext) {
            val incompleteItems = withContext(Dispatchers.IO) {
                Database.downloadDao().getAll()
                    .flatMap { it.downloadItems }
                    .filter { !it.isFinished }
            }

            // everything not yet running waits in the queue; free slots start right away
            incompleteItems.filter { !downloadQueue[it.id] }.forEach { queue(it.id) }
            startNextEnqueueDownload()
            stopServiceIfDone()
        }
    }

    /** Pause every running or queued download. */
    private fun pauseAll() {
        (downloadQueue.keyIterator().asSequence().filter { downloadQueue[it] } + queuedIds.toList())
            .toSet()
            .forEach { pause(it) }
    }

    private fun queue(id: Int) {
        queuedIds.add(id)
        DownloadFailures.clear(id)
        DownloadStates.set(id, DownloadStates.State.Queued)
    }

    /**
     * Stop downloading job for given [id]. If no downloads are active, stop the service.
     */
    private fun stop(id: Int) = lifecycleScope.launch(coroutineContext) {
        downloadQueue[id] = false
        queuedIds.remove(id)
        DownloadStates.clear(id)
        _downloadFlow.emit(id to DownloadStatus.Stopped)

        DownloadFailures.clear(id)
        val item = Database.downloadDao().findDownloadItemById(id) ?: return@launch
        notificationManager.cancel(item.getNotificationId())
        Database.downloadDao().deleteDownloadItemById(id)
        stopServiceIfDone()
    }

    /**
     * Stop service if no downloads are active
     */
    private fun stopServiceIfDone() {
        if (downloadQueue.valueIterator().asSequence().none { it } && queuedIds.isEmpty()) {
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
            sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
            stopSelf()
        }
    }

    private fun selectMatchingStream(streams: Streams, item: DownloadItem): PipedStream? {
        val stream = when (item.type) {
            FileType.AUDIO -> streams.audioStreams
            FileType.VIDEO -> streams.videoStreams
            FileType.SUBTITLE -> null
        }
        return stream?.find {
            it.format == item.format && it.quality == item.quality && it.audioTrackLocale == item.language
        }
    }

    /**
     * Check whether the file downloading or not.
     */
    fun isDownloading(id: Int): Boolean {
        return downloadQueue[id]
    }

    private fun notifyForeground() {
        notificationManager = getSystemService()!!

        summaryNotificationBuilder = Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(getString(R.string.downloading))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setGroupSummary(true)

        ServiceCompat.startForeground(
            this, NotificationId.DOWNLOAD_IN_PROGRESS.id, summaryNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun getNotificationBuilder(item: DownloadItem): Builder {
        val intent = Intent(this@DownloadService, MainActivity::class.java)
            .putExtra(IntentData.OPEN_DOWNLOADS, true)
        val activityIntent = PendingIntentCompat
            .getActivity(this@DownloadService, 0, intent, FLAG_CANCEL_CURRENT, false)

        return Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setContentTitle("[${item.type}] ${item.fileName}")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(activityIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
    }

    private fun setResumeNotification(
        notificationBuilder: Builder,
        item: DownloadItem
    ) {
        notificationBuilder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .clearActions()
            .addAction(getPauseAction(item.id))
            .addAction(getStopAction(item.id))

        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun setPauseNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        isCompleted: Boolean = false
    ) {
        notificationBuilder
            .setProgress(0, 0, false)
            .setOngoing(false)
            .clearActions()

        if (isCompleted) {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_done)
                .setContentText(getString(R.string.download_completed))
        } else {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_pause)
                .setContentText(getString(R.string.download_paused))
                .addAction(getResumeAction(item.id))
                .addAction(getStopAction(item.id))
        }
        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun getResumeAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_RESUME)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_play,
            getString(R.string.resume),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getPauseAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_PAUSE)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_pause,
            getString(R.string.pause),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getStopAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_DOWNLOAD_STOP
            putExtra("id", id)
        }

        // the request code must differ from the one of the pause/resume action
        val requestCode = Int.MAX_VALUE / 2 - id
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stop,
            getString(R.string.stop),
            PendingIntentCompat.getBroadcast(this, requestCode, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    /**
     * Get a [Path] from the corresponding download directory and the file name
     */
    private fun getDownloadPath(directory: String, fileName: String): Path {
        return DownloadHelper.getDownloadDir(this, directory) / fileName
    }

    override fun onDestroy() {
        runCatching {
            getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback)
        }
        downloadQueue.clear()
        queuedIds.clear()
        DownloadStates.pauseUnfinished()
        IS_DOWNLOAD_RUNNING = false
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        intent.getIntArrayExtra("ids")?.forEach { resume(it) }
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    companion object {
        private const val DOWNLOAD_NOTIFICATION_GROUP = "download_notification_group"
        const val ACTION_SERVICE_STARTED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STOPPED"
        const val ACTION_RESUME_ALL =
            "com.github.libretube.services.DownloadService.ACTION_RESUME_ALL"
        const val ACTION_PAUSE_ALL =
            "com.github.libretube.services.DownloadService.ACTION_PAUSE_ALL"

        private const val MAX_SEGMENT_RETRIES = 7
        private const val RETRY_BASE_DELAY_MS = 500L
        private const val RETRY_MAX_DELAY_MS = 8_000L
        var IS_DOWNLOAD_RUNNING = false
    }
}