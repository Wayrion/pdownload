package com.wayrion.pdownload

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val BENCHMARK_SCHEMA_VERSION = "1.0.0"

fun benchmarkMain(args: Array<String>) {
    val options = BenchmarkOptions.parse(args)

    if (options.showHelp) {
        printBenchmarkUsage()
        return
    }

    val url = options.url ?: cliMissingRequired("--url")
    val outputJson = Path.of(options.outputJson ?: "build/benchmark-results.json")
    val workDir = Path.of(options.workDir ?: "build/benchmark-downloads")

    Files.createDirectories(workDir)
    outputJson.toAbsolutePath().parent?.let { Files.createDirectories(it) }

    val threadCounts = options.threadCounts ?: listOf(1, 2, 4, 8, 16, 32, 64)
    val modes = resolveModes(options)
    val warmupIterations = options.warmupIterations ?: 5
    val iterations = options.iterations ?: 5
    val chunkSizeBytes = options.chunkSizeBytes ?: (1L shl 20)
    val ioBufferBytes = options.ioBufferBytes ?: (16 * 1024)
    val connectTimeoutMs = options.connectTimeoutMs ?: 10_000L
    val requestTimeoutMs = options.requestTimeoutMs ?: 30_000L
    val maxRetries = options.maxRetries ?: 1
    val retryDelayMs = options.retryDelayMs ?: 50L

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    val downloader = ParallelFileDownloader { _ -> client }
    val metadata = downloader.fetchMetadata(url)
    val expectedSha256 = fetchSha256OverHttp(client, url, Duration.ofMillis(requestTimeoutMs))

    val warmupRows = mutableListOf<BenchmarkWarmupRow>()
    val runRows = mutableListOf<BenchmarkRunRow>()

    for (mode in modes) {
        for (threads in threadCounts) {
            for (warmupIteration in 1..warmupIterations) {
                val warmupOutput = workDir.resolve(
                    "warmup-${mode.name.lowercase()}-$threads-$warmupIteration-${UUID.randomUUID()}.bin",
                )
                val attempt = runDownloadAttempt(
                    downloader = downloader,
                    url = url,
                    destination = warmupOutput,
                    mode = mode,
                    threads = threads,
                    chunkSizeBytes = chunkSizeBytes,
                    ioBufferBytes = ioBufferBytes,
                    connectTimeoutMs = connectTimeoutMs,
                    requestTimeoutMs = requestTimeoutMs,
                    maxRetries = maxRetries,
                    retryDelayMs = retryDelayMs,
                    expectedSha256 = expectedSha256,
                )

                warmupRows += BenchmarkWarmupRow(
                    mode = mode.name.lowercase(),
                    threadCount = threads,
                    warmupIteration = warmupIteration,
                    chunkSizeBytes = chunkSizeBytes,
                    ioBufferBytes = ioBufferBytes,
                    elapsedMillis = attempt.elapsedMillis,
                    bytesDownloaded = attempt.bytesDownloaded,
                    sha256 = attempt.sha256,
                    checksumMatch = attempt.checksumMatch,
                    success = attempt.success,
                    error = attempt.error,
                )
            }

            for (iteration in 1..iterations) {
                val output = workDir.resolve("download-${mode.name.lowercase()}-$threads-$iteration-${UUID.randomUUID()}.bin")
                val attempt = runDownloadAttempt(
                    downloader = downloader,
                    url = url,
                    destination = output,
                    mode = mode,
                    threads = threads,
                    chunkSizeBytes = chunkSizeBytes,
                    ioBufferBytes = ioBufferBytes,
                    connectTimeoutMs = connectTimeoutMs,
                    requestTimeoutMs = requestTimeoutMs,
                    maxRetries = maxRetries,
                    retryDelayMs = retryDelayMs,
                    expectedSha256 = expectedSha256,
                )

                runRows += BenchmarkRunRow(
                    mode = mode.name.lowercase(),
                    threadCount = threads,
                    iteration = iteration,
                    chunkSizeBytes = chunkSizeBytes,
                    ioBufferBytes = ioBufferBytes,
                    elapsedMillis = attempt.elapsedMillis,
                    bytesDownloaded = attempt.bytesDownloaded,
                    sha256 = attempt.sha256,
                    checksumMatch = attempt.checksumMatch,
                    success = attempt.success,
                    error = attempt.error,
                )
            }
        }
    }

    val report = BenchmarkReport(
        schemaVersion = BENCHMARK_SCHEMA_VERSION,
        generatedAt = Instant.now().toString(),
        target = BenchmarkTarget(
            url = url,
            contentLength = metadata.contentLength,
            expectedSha256 = expectedSha256,
        ),
        host = HostMetadata.capture(),
        benchmark = BenchmarkConfigSection(
            threadCounts = threadCounts,
            modes = modes.map { it.name.lowercase() },
            warmupIterations = warmupIterations,
            iterations = iterations,
            chunkSizeBytes = chunkSizeBytes,
            ioBufferBytes = ioBufferBytes,
            maxRetriesPerChunk = maxRetries,
            retryDelayMillis = retryDelayMs,
            connectTimeoutMs = connectTimeoutMs,
            requestTimeoutMs = requestTimeoutMs,
        ),
        warmups = warmupRows,
        runs = runRows,
        summary = buildSummary(runRows, modes.map { it.name.lowercase() }, threadCounts),
    )

    Files.writeString(outputJson, report.toJson())
    println("Benchmark complete: warmups=${report.warmups.size} runs=${report.runs.size}")
    report.summary.perMode.forEach { modeSummary ->
        println(
            "mode=${modeSummary.mode} bestThreads=${modeSummary.bestThreadCount} " +
                "avgElapsedMillis=${modeSummary.bestAverageElapsedMillis}",
        )
    }
    report.summary.optimizedVsNaive?.let { diff ->
        println("optimized/naive time speedup at each mode optimum: ${diff.speedupAtModeOptimum}")
    }
    println("Output JSON: ${outputJson.toAbsolutePath()}")
}

private fun resolveModes(options: BenchmarkOptions): List<DownloadMode> {
    if (options.mode != null) {
        return when (options.mode) {
            "naive" -> listOf(DownloadMode.NAIVE)
            "optimized" -> listOf(DownloadMode.OPTIMIZED)
            "processes" -> listOf(DownloadMode.PROCESSES)
            "both" -> listOf(DownloadMode.NAIVE, DownloadMode.OPTIMIZED, DownloadMode.PROCESSES)
            else -> cliInvalidValue("--mode", options.mode, "naive|optimized|processes|both")
        }
    }
    return options.modes ?: listOf(DownloadMode.NAIVE, DownloadMode.OPTIMIZED, DownloadMode.PROCESSES)
}

private fun printBenchmarkUsage() {
    println(
        """
                |Usage:
                |  benchmark --url <URL> [options]
                |
                |Options:
                |  --output-json <PATH>       JSON output file (default: build/benchmark-results.json)
                |  --work-dir <PATH>          Temporary output directory (default: build/benchmark-downloads)
                |  --mode <VALUE>             Single mode: naive|optimized|processes|both (default: both)
                |  --threads <CSV>            Thread counts, e.g. 1,2,4,8,16,32,64
                |  --modes <CSV>              Modes: naive,optimized,processes
                |  --warmup-iterations <N>    Warm-up iterations per mode/thread (default: 5)
                |  --iterations <N>           Iterations per mode/thread (default: 5)
                |  --chunk-size-bytes <N>     Chunk size in bytes (default: 1048576)
                |  --io-buffer-bytes <N>      I/O buffer bytes (default: 16384)
                |  --max-retries <N>          Retries per chunk (default: 1)
                |  --retry-delay-ms <N>       Delay between retries (default: 50)
                |  --connect-timeout-ms <N>   Client connect timeout (default: 10000)
                |  --request-timeout-ms <N>   Per-request timeout (default: 30000)
                |  --help                     Print this help
                """.trimMargin(),
    )
}

private data class BenchmarkOptions(
    val url: String? = null,
    val outputJson: String? = null,
    val workDir: String? = null,
    val mode: String? = null,
    val threadCounts: List<Int>? = null,
    val modes: List<DownloadMode>? = null,
    val warmupIterations: Int? = null,
    val iterations: Int? = null,
    val chunkSizeBytes: Long? = null,
    val ioBufferBytes: Int? = null,
    val maxRetries: Int? = null,
    val retryDelayMs: Long? = null,
    val connectTimeoutMs: Long? = null,
    val requestTimeoutMs: Long? = null,
    val showHelp: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): BenchmarkOptions {
            var options = BenchmarkOptions()
            val parsed = parseCliArgs(
                args = args,
                flagsWithoutValue = setOf("--help", "-h"),
                sanitizeFlags = true,
            )

            parsed.forEach { arg ->
                when (arg.flag) {
                    "--url" -> options = options.copy(url = arg.value!!)
                    "--output-json" -> options = options.copy(outputJson = arg.value!!)
                    "--work-dir" -> options = options.copy(workDir = arg.value!!)
                    "--mode" -> options = options.copy(mode = arg.value!!.trim().lowercase())
                    "--threads" -> options = options.copy(threadCounts = parseThreads(arg.value!!))
                    "--modes" -> options = options.copy(modes = parseModes(arg.value!!))
                    "--warmup-iterations" -> options = options.copy(warmupIterations = arg.value!!.toInt())
                    "--iterations" -> options = options.copy(iterations = arg.value!!.toInt())
                    "--chunk-size-bytes" -> options = options.copy(chunkSizeBytes = arg.value!!.toLong())
                    "--io-buffer-bytes" -> options = options.copy(ioBufferBytes = arg.value!!.toInt())
                    "--max-retries" -> options = options.copy(maxRetries = arg.value!!.toInt())
                    "--retry-delay-ms" -> options = options.copy(retryDelayMs = arg.value!!.toLong())
                    "--connect-timeout-ms" -> options = options.copy(connectTimeoutMs = arg.value!!.toLong())
                    "--request-timeout-ms" -> options = options.copy(requestTimeoutMs = arg.value!!.toLong())
                    "--help", "-h" -> options = options.copy(showHelp = true)
                    else -> cliUnknownFlag(arg.raw)
                }
            }
            return options
        }

        private fun parseThreads(value: String): List<Int> {
            return value.split(',').map { it.trim().toInt() }
        }

        private fun parseModes(value: String): List<DownloadMode> {
            return value.split(',').map { token ->
                when (token.trim().lowercase()) {
                    "naive" -> DownloadMode.NAIVE
                    "optimized" -> DownloadMode.OPTIMIZED
                    "processes" -> DownloadMode.PROCESSES
                    else -> cliInvalidValue("--modes", token, "naive|optimized|processes")
                }
            }
        }
    }
}

private data class DownloadAttempt(
    val elapsedMillis: Long?,
    val bytesDownloaded: Long?,
    val sha256: String?,
    val checksumMatch: Boolean,
    val success: Boolean,
    val error: String?,
)

private fun runDownloadAttempt(
    downloader: ParallelFileDownloader,
    url: String,
    destination: Path,
    mode: DownloadMode,
    threads: Int,
    chunkSizeBytes: Long,
    ioBufferBytes: Int,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    maxRetries: Int,
    retryDelayMs: Long,
    expectedSha256: String,
): DownloadAttempt {
    var elapsedMillis: Long? = null
    var bytesDownloaded: Long? = null
    var checksum: String? = null
    var checksumMatch = false
    var success = false
    var errorMessage: String? = null

    try {
        val result = downloader.download(
            url = url,
            destination = destination,
            config = DownloadConfig(
                threadCount = threads,
                chunkSizeBytes = chunkSizeBytes,
                connectTimeout = Duration.ofMillis(connectTimeoutMs),
                requestTimeout = Duration.ofMillis(requestTimeoutMs),
                maxRetriesPerChunk = maxRetries,
                retryDelayMillis = retryDelayMs,
                mode = mode,
                ioBufferBytes = ioBufferBytes,
                expectedSha256 = expectedSha256,
            ),
        )
        elapsedMillis = result.elapsedMillis
        bytesDownloaded = result.bytesDownloaded
        checksum = sha256(destination)
        checksumMatch = checksum.equals(expectedSha256, ignoreCase = true)
        success = checksumMatch
    } catch (exception: Exception) {
        errorMessage = exception.message ?: exception::class.java.simpleName
    } finally {
        Files.deleteIfExists(destination)
    }

    return DownloadAttempt(
        elapsedMillis = elapsedMillis,
        bytesDownloaded = bytesDownloaded,
        sha256 = checksum,
        checksumMatch = checksumMatch,
        success = success,
        error = errorMessage,
    )
}

private fun fetchSha256OverHttp(client: HttpClient, url: String, timeout: Duration): String {
    val request = HttpRequest.newBuilder(URI.create(url))
        .GET()
        .timeout(timeout)
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() !in 200..299) {
        error("Download error: failed to fetch checksum source bytes: status=${response.statusCode()}")
    }

    val tempFile = Files.createTempFile("benchmark-source", ".bin")
    response.body().use { stream ->
        Files.newOutputStream(tempFile).use { output ->
            stream.copyTo(output)
        }
    }
    return try {
        sha256(tempFile)
    } finally {
        Files.deleteIfExists(tempFile)
    }
}
