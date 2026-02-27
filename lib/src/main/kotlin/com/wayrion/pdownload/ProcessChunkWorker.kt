package com.wayrion.pdownload

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    val values = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val key = args[index]
        if (!key.startsWith("--")) error("Invalid argument: $key")
        if (index + 1 >= args.size) error("Missing value for $key")
        values[key] = args[index + 1]
        index += 2
    }

    fun required(name: String): String = values[name] ?: error("Missing required $name")

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

    var attempt = 0
    while (true) {
        try {
            val request = HttpRequest.newBuilder(URI.create(options.url))
                .GET()
                .timeout(Duration.ofMillis(options.requestTimeoutMs))
                .header("Range", "bytes=${options.start}-${options.end}")
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 206) {
                error("Expected 206, got ${response.statusCode()}")
            }

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

            val expectedBytes = options.end - options.start + 1L
            val actualBytes = Files.size(options.output)
            if (actualBytes != expectedBytes) {
                error("Chunk size mismatch. expected=$expectedBytes actual=$actualBytes")
            }
            return
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
         } catch (exception: Exception) {
             if (attempt >= options.maxRetries) {
                 throw exception
             }
             attempt += 1
            try {
                Thread.sleep(options.retryDelayMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
         }
     }
 }
