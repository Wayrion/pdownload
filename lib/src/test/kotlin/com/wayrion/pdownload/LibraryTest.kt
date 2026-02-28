package com.wayrion.pdownload

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LibraryTest : StringSpec({

    "parallel chunks: dynamic file size based on thread count downloads and content matches" {
        val threadCount = 8
        val chunkSizeBytes = 16 * 1024L
        val data = buildData(dynamicPayloadSizeBytes(threadCount, chunkSizeBytes))
        val server = TestRangeServer(data)
        server.start()

        try {
            val output = Files.createTempFile("parallel-200kb", ".bin")
            val downloader = ParallelFileDownloader()

            val result = downloader.download(
                url = server.url("/file"),
                destination = output,
                config = DownloadConfig(threadCount = threadCount, chunkSizeBytes = chunkSizeBytes),
            )

            result.bytesDownloaded shouldBe data.size.toLong()
            result.chunksDownloaded shouldBe 16
            Files.readAllBytes(output).toList() shouldBe data.toList()
        } finally {
            server.stop()
        }
    }

    "non-even boundaries: size not divisible by chunk size still matches" {
        val threadCount = 8
        val chunkSizeBytes = 8 * 1024L
        val data = buildData(dynamicPayloadSizeBytes(threadCount, chunkSizeBytes, remainderBytes = 123))
        val server = TestRangeServer(data)
        server.start()

        try {
            val output = Files.createTempFile("non-even", ".bin")
            val downloader = ParallelFileDownloader()

            val result = downloader.download(
                url = server.url("/file"),
                destination = output,
                config = DownloadConfig(threadCount = threadCount, chunkSizeBytes = chunkSizeBytes),
            )

            result.bytesDownloaded shouldBe data.size.toLong()
            result.chunksDownloaded shouldBe 17
            Files.readAllBytes(output).toList() shouldBe data.toList()
        } finally {
            server.stop()
        }
    }

    "optimized mode: content matches and chunking is correct" {
        val data = buildData(256 * 1024 + 17)
        val server = TestRangeServer(data)
        server.start()

        try {
            val output = Files.createTempFile("optimized-mode", ".bin")
            val downloader = ParallelFileDownloader()

            val result = downloader.download(
                url = server.url("/file"),
                destination = output,
                config = DownloadConfig(
                    threadCount = 8,
                    chunkSizeBytes = 32 * 1024,
                    mode = DownloadMode.OPTIMIZED,
                ),
            )

            result.bytesDownloaded shouldBe data.size.toLong()
            result.chunksDownloaded shouldBe 9
            Files.readAllBytes(output).toList() shouldBe data.toList()
        } finally {
            server.stop()
        }
    }

    "processes mode: content matches and chunking is correct" {
        val data = buildData(96 * 1024 + 5)
        val server = TestRangeServer(data)
        server.start()

        try {
            val output = Files.createTempFile("process-mode", ".bin")
            val downloader = ParallelFileDownloader()

            val result = downloader.download(
                url = server.url("/file"),
                destination = output,
                config = DownloadConfig(
                    threadCount = 4,
                    chunkSizeBytes = 16 * 1024,
                    mode = DownloadMode.PROCESSES,
                ),
            )

            result.bytesDownloaded shouldBe data.size.toLong()
            result.chunksDownloaded shouldBe 7
            Files.readAllBytes(output).toList() shouldBe data.toList()
        } finally {
            server.stop()
        }
    }

    "output from memory disabled: streamed write path still downloads accurately" {
        val data = buildData(180 * 1024 + 19)
        val server = TestRangeServer(data)
        server.start()

        try {
            val output = Files.createTempFile("streamed-write", ".bin")
            val downloader = ParallelFileDownloader()

            val result = downloader.download(
                url = server.url("/file"),
                destination = output,
                config = DownloadConfig(
                    threadCount = 8,
                    chunkSizeBytes = 24 * 1024,
                    outputFromMemoryToDisk = false,
                ),
            )

            result.bytesDownloaded shouldBe data.size.toLong()
            Files.readAllBytes(output).toList() shouldBe data.toList()
        } finally {
            server.stop()
        }
    }

    "retry: one chunk returns 500 once then succeeds" {
        val data = buildData(220 * 1024)
        val targetRange = "bytes=65536-131071"
        val server = TestRangeServer(data, failFirstForRange = targetRange)
        server.start()

        try {
            val output = Files.createTempFile("retry-success", ".bin")
            val downloader = ParallelFileDownloader()

            val result = downloader.download(
                url = server.url("/file"),
                destination = output,
                config = DownloadConfig(
                    threadCount = 4,
                    chunkSizeBytes = 64 * 1024,
                    maxRetriesPerChunk = 1,
                    retryDelayMillis = 10,
                ),
            )

            result.bytesDownloaded shouldBe data.size.toLong()
            Files.readAllBytes(output).toList() shouldBe data.toList()
        } finally {
            server.stop()
        }
    }

    "process mode retry: one chunk returns 500 once then succeeds" {
        val data = buildData(128 * 1024)
        val targetRange = "bytes=32768-65535"
        val server = TestRangeServer(data, failFirstForRange = targetRange)
        server.start()

        try {
            val output = Files.createTempFile("process-retry-success", ".bin")
            val downloader = ParallelFileDownloader()

            val result = downloader.download(
                url = server.url("/file"),
                destination = output,
                config = DownloadConfig(
                    threadCount = 4,
                    chunkSizeBytes = 32 * 1024,
                    maxRetriesPerChunk = 1,
                    retryDelayMillis = 10,
                    mode = DownloadMode.PROCESSES,
                ),
            )

            result.bytesDownloaded shouldBe data.size.toLong()
            Files.readAllBytes(output).toList() shouldBe data.toList()
        } finally {
            server.stop()
        }
    }

    "retry exhausted: downloader fails when a chunk keeps failing" {
        val data = buildData(100 * 1024)
        val targetRange = "bytes=0-32767"
        val server = TestRangeServer(data, alwaysFailForRange = targetRange)
        server.start()

        try {
            val output = Files.createTempFile("retry-fail", ".bin")
            val downloader = ParallelFileDownloader()

            shouldThrow<Exception> {
                downloader.download(
                    url = server.url("/file"),
                    destination = output,
                    config = DownloadConfig(
                        threadCount = 4,
                        chunkSizeBytes = 32 * 1024,
                        maxRetriesPerChunk = 1,
                        retryDelayMillis = 10,
                    ),
                )
            }
        } finally {
            server.stop()
        }
    }

    "checksum mismatch: downloader fails when expected hash is wrong" {
        val data = buildData(64 * 1024)
        val server = TestRangeServer(data)
        server.start()

        try {
            val output = Files.createTempFile("checksum-mismatch", ".bin")
            val downloader = ParallelFileDownloader()

            shouldThrow<IllegalStateException> {
                downloader.download(
                    url = server.url("/file"),
                    destination = output,
                    config = DownloadConfig(
                        threadCount = 4,
                        chunkSizeBytes = 16 * 1024,
                        expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                    ),
                )
            }
        } finally {
            server.stop()
        }
    }

    "metadata validation: missing Accept-Ranges header fails" {
        val data = buildData(8 * 1024)
        val server = TestRangeServer(data, acceptRangesHeader = null)
        server.start()

        try {
            val downloader = ParallelFileDownloader()

            shouldThrow<IllegalStateException> {
                downloader.fetchMetadata(server.url("/file"))
            }
        } finally {
            server.stop()
        }
    }

    "config validation: threadCount must be greater than zero" {
        val data = buildData(4 * 1024)
        val server = TestRangeServer(data)
        server.start()

        try {
            val output = Files.createTempFile("invalid-config", ".bin")
            val downloader = ParallelFileDownloader()

            shouldThrow<IllegalArgumentException> {
                downloader.download(
                    url = server.url("/file"),
                    destination = output,
                    config = DownloadConfig(threadCount = 0),
                )
            }
        } finally {
            server.stop()
        }
    }
})

private fun buildData(size: Int): ByteArray {
    val bytes = ByteArray(size)
    for (index in bytes.indices) {
        bytes[index] = ((index * 31 + 17) % 251).toByte()
    }
    return bytes
}

private fun dynamicPayloadSizeBytes(
    threadCount: Int,
    chunkSizeBytes: Long,
    remainderBytes: Int = 0,
): Int {
    val chunkCount = threadCount * 2
    return (chunkCount * chunkSizeBytes + remainderBytes.toLong()).toInt()
}

private class TestRangeServer(
    private val data: ByteArray,
    private val failFirstForRange: String? = null,
    private val alwaysFailForRange: String? = null,
    private val acceptRangesHeader: String? = "bytes",
) {
    private val failedOnce = ConcurrentHashMap.newKeySet<String>()
    private val server = HttpServer.create(InetSocketAddress(0), 0)

    init {
        server.createContext("/file", RangeHandler())
        server.executor = Executors.newCachedThreadPool()
    }

    fun start() {
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    fun url(path: String): String {
        return "http://127.0.0.1:${server.address.port}$path"
    }

    private inner class RangeHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                when (exchange.requestMethod.uppercase()) {
                    "HEAD" -> handleHead(exchange)
                    "GET" -> handleGet(exchange)
                    else -> sendStatus(exchange, 405)
                }
            } catch (_: Exception) {
                if (exchange.responseCode == -1) {
                    sendStatus(exchange, 500)
                }
            } finally {
                exchange.close()
            }
        }

        private fun handleHead(exchange: HttpExchange) {
            if (acceptRangesHeader != null) {
                exchange.responseHeaders.add("Accept-Ranges", acceptRangesHeader)
            }
            exchange.responseHeaders.add("Content-Length", data.size.toString())
            exchange.sendResponseHeaders(200, -1)
        }

        private fun handleGet(exchange: HttpExchange) {
            val rangeHeader = exchange.requestHeaders.getFirst("Range") ?: run {
                sendStatus(exchange, 416)
                return
            }

            if (alwaysFailForRange != null && rangeHeader == alwaysFailForRange) {
                sendStatus(exchange, 500)
                return
            }

            if (failFirstForRange != null && rangeHeader == failFirstForRange && failedOnce.add(rangeHeader)) {
                sendStatus(exchange, 500)
                return
            }

            val (start, end) = parseRange(rangeHeader, data.size)
            val length = end - start + 1

            if (acceptRangesHeader != null) {
                exchange.responseHeaders.add("Accept-Ranges", acceptRangesHeader)
            }
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
            exchange.responseHeaders.add("Content-Length", length.toString())
            exchange.sendResponseHeaders(206, length.toLong())

            val responseBody: OutputStream = exchange.responseBody
            responseBody.use { out ->
                out.write(data, start, length)
            }
        }

        private fun parseRange(range: String, fileSize: Int): Pair<Int, Int> {
            require(range.startsWith("bytes=")) { "Invalid range header: $range" }
            val raw = range.removePrefix("bytes=")
            val parts = raw.split("-", limit = 2)
            require(parts.size == 2) { "Invalid range header: $range" }

            val start = parts[0].toInt()
            val end = parts[1].toInt()
            require(start >= 0) { "Invalid start: $start" }
            require(end >= start) { "Invalid end: $end" }
            require(end < fileSize) { "Range exceeds size: $end >= $fileSize" }
            return start to end
        }

        private fun sendStatus(exchange: HttpExchange, status: Int) {
            exchange.sendResponseHeaders(status, -1)
        }
    }
}
