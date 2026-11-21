package com.github.libretube.repo

import android.util.Log
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.IOException
import okio.source
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.math.min

/**
 * Download from RAW HTTP stream.
 */
class RawByteStreamDownloadProvider(val url: HttpUrl) : DownloadProvider {

    override suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
    ): DownloadProgressResult {
        val startByteOffset = item.path.fileSize()
        val source =
            startConnection(url, startByteOffset, item.downloadSize) ?: return DownloadProgressResult.Failed

        val sourceByte = source.byteStream().source()

        var totalRead = 0L
        var lastRead = 0L
        try {
            withContext(Dispatchers.IO) {
                // read in large blocks and write whole buffer segments to the file as they fill
                while (sourceByte
                        .read(sink.buffer, DownloadHelper.DOWNLOAD_CHUNK_SIZE)
                        .also { lastRead = it } != -1L
                ) {
                    sink.emitCompleteSegments()
                    totalRead += lastRead
                }
            }
        } catch (e: IOException) {
            // the connection dropped mid-request: keep what arrived, the next request resumes
            // from the end of the file
            Log.e(TAG(), "Download interrupted: ${e.message}")
        } finally {
            // everything read so far is on disk, so the file size is the resume offset
            withContext(Dispatchers.IO) {
                sink.emit()
                sourceByte.close()
                source.close()
            }
        }

        if (totalRead == 0L) return DownloadProgressResult.Failed

        return if (startByteOffset + totalRead < item.downloadSize) {
            DownloadProgressResult.Progressed(totalRead)
        } else {
            DownloadProgressResult.DownloadComplete
        }
    }

    private suspend fun startConnection(
        url: HttpUrl,
        alreadyRead: Long,
        readLimit: Long?
    ): ResponseBody? {
        val limit = readLimit?.let {
            min(readLimit, alreadyRead + BYTES_PER_REQUEST)
        }?.toString().orEmpty()

        val request = Request.Builder()
            .url(url)
            .method("GET", null)
            .header("Range", "bytes=$alreadyRead-$limit")
            .build()

        return withContext(Dispatchers.IO) {
            // Retry connecting to server for n times.
            try {
                val call = httpClient.newCall(request)
                val response = call.execute()

                if (response.code == 403) {
                    response.close()
                    Log.e(TAG(), "Got HTTP 403 while downloading: ${response.body.string()}")
                    return@withContext null
                } else if (response.code !in 200..299) {
                    response.close()
                    return@withContext null // TODO: print response.message
                }

                return@withContext response.body
            } catch (e: IOException) {
                Log.e(this.javaClass.name, e.printStackTrace().toString())
                // TODO: forward error message

                return@withContext null
            }
        }
    }

    companion object {
        // larger requests mean fewer round trips (each one paused the download briefly); the
        // maximum working tested chunk size is 3MB
        private const val BYTES_PER_REQUEST = 2 * 1024 * 1024L

        // shared, so connections are reused across requests and files
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
                .readTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}