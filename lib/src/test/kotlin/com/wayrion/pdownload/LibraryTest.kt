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

    "parallel chunks: 200KB file downloads and content matches" {
        val data = buildData(200 * 1024)
        val server = TestRangeServer(data)
        server.start()

        try {
            val output = Files.createTempFile("parallel-200kb", ".bin")
            val downloader = ParallelFileDownloader()

            val result = downloader.download(
                url = server.url("/file"),
                destination = output,
                config = DownloadConfig(threadCount = 8, chunkSizeBytes = 16 * 1024),
            )

            result.bytesDownloaded shouldBe data.size.toLong()
            Files.readAllBytes(output).toList() shouldBe data.toList()
        } finally {
            server.stop()
        }
    }

    "non-even boundaries: size not divisible by chunk size still matches" {
        val data = buildData(200 * 1024 + 123)
        val server = TestRangeServer(data)
        server.start()

        try {
            val output = Files.createTempFile("non-even", ".bin")
            val downloader = ParallelFileDownloader()

            val result = downloader.download(
                url = server.url("/file"),
                destination = output,
                config = DownloadConfig(threadCount = 8, chunkSizeBytes = 8 * 1024),
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
})

private fun buildData(size: Int): ByteArray {
    val bytes = ByteArray(size)
    for (index in bytes.indices) {
        bytes[index] = ((index * 31 + 17) % 251).toByte()
    }
    return bytes
}

private class TestRangeServer(
    private val data: ByteArray,
    private val failFirstForRange: String? = null,
    private val alwaysFailForRange: String? = null,
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
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
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

            exchange.responseHeaders.add("Accept-Ranges", "bytes")
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
