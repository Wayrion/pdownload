package org.example

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.system.measureTimeMillis

enum class DownloadMode {
    NAIVE,
    OPTIMIZED,
}

data class DownloadConfig(
    val threadCount: Int = 8,
    val chunkSizeBytes: Long = 1L shl 20,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val maxRetriesPerChunk: Int = 0,
    val retryDelayMillis: Long = 100,
    val mode: DownloadMode = DownloadMode.NAIVE,
    val ioBufferBytes: Int = 16 * 1024,
    val expectedSha256: String? = null,
)

data class FileMetadata(
    val contentLength: Long,
    val acceptRanges: String,
)

data class ChunkRange(
    val index: Int,
    val startInclusive: Long,
    val endInclusive: Long,
)

data class DownloadResult(
    val url: String,
    val destination: Path,
    val bytesDownloaded: Long,
    val chunksDownloaded: Int,
    val elapsedMillis: Long,
)

class ParallelFileDownloader(
    private val clientFactory: (DownloadConfig) -> HttpClient = { config ->
        HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    },
) {

    fun fetchMetadata(url: String, config: DownloadConfig = DownloadConfig()): FileMetadata {
        val client = clientFactory(config)
        return fetchMetadata(client, url, config)
    }

    fun download(url: String, destination: Path, config: DownloadConfig = DownloadConfig()): DownloadResult {
        require(config.threadCount > 0) { "threadCount must be > 0" }
        require(config.chunkSizeBytes > 0) { "chunkSizeBytes must be > 0" }
        require(config.maxRetriesPerChunk >= 0) { "maxRetriesPerChunk must be >= 0" }
        require(config.retryDelayMillis >= 0) { "retryDelayMillis must be >= 0" }
        require(config.ioBufferBytes > 0) { "ioBufferBytes must be > 0" }

        val client = clientFactory(config)
        val metadata = fetchMetadata(client, url, config)
        val chunkRanges = splitIntoRanges(metadata.contentLength, config.chunkSizeBytes)
        val workerCount = max(1, minOf(config.threadCount, chunkRanges.size))

        val parent = destination.toAbsolutePath().parent
        if (parent != null) {
            Files.createDirectories(parent)
        }

        FileChannel.open(
            destination,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.READ,
        ).use { fileChannel ->
            fileChannel.truncate(metadata.contentLength)

            val executor = Executors.newFixedThreadPool(workerCount)
            val elapsedMillis = try {
                measureTimeMillis {
                    val tasks = chunkRanges.map { range ->
                        Callable {
                            downloadSingleChunk(client, url, range, fileChannel, config)
                        }
                    }
                    val futures = executor.invokeAll(tasks)
                    futures.forEach { future -> future.get() }
                }
            } finally {
                executor.shutdown()
                executor.awaitTermination(30, TimeUnit.SECONDS)
            }

            val downloadedSize = Files.size(destination)
            if (downloadedSize != metadata.contentLength) {
                throw IllegalStateException(
                    "Downloaded file size mismatch. expected=${metadata.contentLength} actual=$downloadedSize",
                )
            }

            val actualSha = sha256(destination)
            if (config.expectedSha256 != null && !actualSha.equals(config.expectedSha256, ignoreCase = true)) {
                throw IllegalStateException(
                    "SHA-256 mismatch. expected=${config.expectedSha256} actual=$actualSha",
                )
            }

            return DownloadResult(
                url = url,
                destination = destination,
                bytesDownloaded = downloadedSize,
                chunksDownloaded = chunkRanges.size,
                elapsedMillis = elapsedMillis,
            )
        }
    }

    private fun fetchMetadata(client: HttpClient, url: String, config: DownloadConfig): FileMetadata {
        val request = HttpRequest.newBuilder(URI.create(url))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .timeout(config.requestTimeout)
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HEAD request failed with status ${response.statusCode()}")
        }

        val contentLength = response.headers().firstValue("Content-Length")
            .orElseThrow { IllegalStateException("Missing Content-Length header") }
            .toLongOrNull()
            ?: throw IllegalStateException("Invalid Content-Length header")

        val acceptRanges = response.headers().firstValue("Accept-Ranges").orElse("")
        if (!acceptRanges.equals("bytes", ignoreCase = true)) {
            throw IllegalStateException("Server does not expose Accept-Ranges: bytes")
        }

        if (contentLength <= 0) {
            throw IllegalStateException("Content-Length must be > 0")
        }

        return FileMetadata(contentLength = contentLength, acceptRanges = acceptRanges)
    }

    private fun splitIntoRanges(contentLength: Long, chunkSizeBytes: Long): List<ChunkRange> {
        val chunkCount = ceil(contentLength.toDouble() / chunkSizeBytes.toDouble()).toInt()
        return List(chunkCount) { index ->
            val start = index.toLong() * chunkSizeBytes
            val end = minOf(start + chunkSizeBytes - 1L, contentLength - 1L)
            ChunkRange(index = index, startInclusive = start, endInclusive = end)
        }
    }

    private fun downloadSingleChunk(
        client: HttpClient,
        url: String,
        chunk: ChunkRange,
        fileChannel: FileChannel,
        config: DownloadConfig,
    ) {
        var attempt = 0
        while (true) {
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(config.requestTimeout)
                    .header("Range", "bytes=${chunk.startInclusive}-${chunk.endInclusive}")
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() != 206) {
                    throw IllegalStateException(
                        "Expected 206 for chunk ${chunk.index}, got ${response.statusCode()}",
                    )
                }

                val writePosition = when (config.mode) {
                    DownloadMode.NAIVE -> naiveChunkWriter.write(response, chunk, fileChannel, config)
                    DownloadMode.OPTIMIZED -> optimizedChunkWriter.write(response, chunk, fileChannel, config)
                }

                val expectedBytes = chunk.endInclusive - chunk.startInclusive + 1L
                val actualBytes = writePosition - chunk.startInclusive
                if (actualBytes != expectedBytes) {
                    throw IllegalStateException(
                        "Chunk ${chunk.index} size mismatch. expected=$expectedBytes actual=$actualBytes",
                    )
                }

                return
            } catch (exception: Exception) {
                if (attempt >= config.maxRetriesPerChunk) {
                    throw exception
                }
                attempt += 1
                try {
                    Thread.sleep(config.retryDelayMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
    }
}

fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
