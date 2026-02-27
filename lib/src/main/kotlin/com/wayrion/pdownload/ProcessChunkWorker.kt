package com.wayrion.pdownload

import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

private data class WorkerOptions(
    val url: String,
    val start: Long,
    val end: Long,
    val output: Path,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long,
    val maxRetries: Int,
    val retryDelayMs: Long,
)

fun main(args: Array<String>) {
    val options = parseWorkerOptions(args)
    downloadChunk(options)
}

private fun parseWorkerOptions(args: Array<String>): WorkerOptions {
    val values = parseCliArgs(args, sanitizeFlags = false).associate { parsed ->
        parsed.flag to (parsed.value ?: "")
    }

    fun required(name: String): String = values[name]?.takeIf { it.isNotEmpty() } ?: cliMissingRequired(name)

    return WorkerOptions(
        url = required("--url"),
        start = required("--start").toLong(),
        end = required("--end").toLong(),
        output = Path.of(required("--output")),
        connectTimeoutMs = required("--connect-timeout-ms").toLong(),
        requestTimeoutMs = required("--request-timeout-ms").toLong(),
        maxRetries = required("--max-retries").toInt(),
        retryDelayMs = required("--retry-delay-ms").toLong(),
    )
}

private fun downloadChunk(options: WorkerOptions) {
    require(options.end >= options.start) { "end must be >= start" }

    options.output.toAbsolutePath().parent?.let { Files.createDirectories(it) }

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(options.connectTimeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fetchChunkWithRetry(
        client = client,
        url = options.url,
        startInclusive = options.start,
        endInclusive = options.end,
        requestTimeout = Duration.ofMillis(options.requestTimeoutMs),
        maxRetries = options.maxRetries,
        retryDelayMillis = options.retryDelayMs,
    ) { response ->
        Files.newOutputStream(
            options.output,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { out ->
            response.body().use { input ->
                input.copyTo(out)
            }
        }
    }

    val expectedBytes = expectedChunkBytes(options.start, options.end)
    val actualBytes = Files.size(options.output)
    if (actualBytes != expectedBytes) {
        error("Download error: chunk size mismatch. expected=$expectedBytes actual=$actualBytes")
    }
}
