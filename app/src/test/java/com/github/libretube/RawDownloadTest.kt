package com.github.libretube

import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.sink
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/** Downloads a file in ranged requests from a local server, like a YouTube stream. */
class RawDownloadTest {
    @Test fun downloadsWholeFileInRanges() {
        val data = Random(1).nextBytes(5_300_000)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v") { ex ->
            val range = ex.requestHeaders.getFirst("Range")!!.removePrefix("bytes=").split("-")
            val start = range[0].toInt()
            val end = (range.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toInt() ?: (data.size - 1)).coerceAtMost(data.size - 1)
            val len = end - start + 1
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
            ex.sendResponseHeaders(206, len.toLong())
            ex.responseBody.use { it.write(data, start, len) }
        }
        server.start()
        try {
            val path = Files.createTempFile("dl", ".bin")
            val item = DownloadItem(type = FileType.VIDEO, videoId = "x", fileName = "x", path = path, downloadSize = data.size.toLong())
            val provider = RawByteStreamDownloadProvider("http://127.0.0.1:${server.address.port}/v".toHttpUrl())
            val sink = path.sink(StandardOpenOption.APPEND).buffer()
            val results = mutableListOf<String>()
            runBlocking {
                var guard = 0
                while (Files.size(path) < data.size && guard++ < 50) {
                    val r = provider.downloadNextChunk(item, sink)
                    results += r.javaClass.simpleName
                    if (r is DownloadProgressResult.DownloadComplete || r is DownloadProgressResult.Failed) break
                }
            }
            sink.close()
            println("RESULTS $results size=${Files.size(path)}")
            assertArrayEquals(data, Files.readAllBytes(path))
        } finally {
            server.stop(0)
        }
    }
}
