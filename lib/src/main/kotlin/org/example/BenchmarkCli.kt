package org.example

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.round

private const val BENCHMARK_SCHEMA_VERSION = "1.0.0"

fun benchmarkMain(args: Array<String>) {
    val options = BenchmarkOptions.parse(args)

    if (options.showHelp) {
        printBenchmarkUsage()
        return
    }

    val url = options.url ?: error("Missing required --url")
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

    val runRows = mutableListOf<BenchmarkRunRow>()

    for (mode in modes) {
        for (threads in threadCounts) {
            for (warmupIteration in 1..warmupIterations) {
                val warmupOutput = workDir.resolve(
                    "warmup-${mode.name.lowercase()}-$threads-$warmupIteration-${UUID.randomUUID()}.bin",
                )
                try {
                    downloader.download(
                        url = url,
                        destination = warmupOutput,
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
                } finally {
                    Files.deleteIfExists(warmupOutput)
                }
            }

            for (iteration in 1..iterations) {
                val output = workDir.resolve("download-${mode.name.lowercase()}-$threads-$iteration-${UUID.randomUUID()}.bin")
                var elapsedMillis: Long? = null
                var bytesDownloaded: Long? = null
                var checksum: String? = null
                var checksumMatch = false
                var success = false
                var errorMessage: String? = null

                try {
                    val result = downloader.download(
                        url = url,
                        destination = output,
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
                    checksum = sha256(output)
                    checksumMatch = checksum.equals(expectedSha256, ignoreCase = true)
                    success = checksumMatch
                } catch (exception: Exception) {
                    errorMessage = exception.message ?: exception::class.java.simpleName
                } finally {
                    Files.deleteIfExists(output)
                }

                val throughputMiBps = if (success && elapsedMillis != null && bytesDownloaded != null && elapsedMillis > 0) {
                    round(((bytesDownloaded.toDouble() / (1024.0 * 1024.0)) / (elapsedMillis.toDouble() / 1000.0)) * 1000.0) / 1000.0
                } else {
                    null
                }

                runRows += BenchmarkRunRow(
                    mode = mode.name.lowercase(),
                    threadCount = threads,
                    iteration = iteration,
                    chunkSizeBytes = chunkSizeBytes,
                    ioBufferBytes = ioBufferBytes,
                    elapsedMillis = elapsedMillis,
                    bytesDownloaded = bytesDownloaded,
                    throughputMiBps = throughputMiBps,
                    sha256 = checksum,
                    checksumMatch = checksumMatch,
                    success = success,
                    error = errorMessage,
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
        runs = runRows,
        summary = buildSummary(runRows, modes.map { it.name.lowercase() }, threadCounts),
    )

    Files.writeString(outputJson, report.toJson())
    println("Benchmark complete: ${report.runs.size} runs")
    report.summary.perMode.forEach { modeSummary ->
        println(
            "mode=${modeSummary.mode} bestThreads=${modeSummary.bestThreadCount} " +
                "avgThroughputMiBps=${modeSummary.bestAverageThroughputMiBps}",
        )
    }
    report.summary.optimizedVsNaive?.let { diff ->
        println("optimized/naive speedup at each mode optimum: ${diff.speedupAtModeOptimum}")
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
            else -> error("Invalid --mode value: ${options.mode}")
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
            var index = 0

            fun requireValue(flag: String): String {
                if (index + 1 >= args.size) error("Missing value for $flag")
                index += 1
                return args[index]
            }

            while (index < args.size) {
                val rawArg = args[index]
                val arg = rawArg.trim().trimEnd('.', ',', ';', ':')
                when (arg) {
                    "--url" -> options = options.copy(url = requireValue(arg))
                    "--output-json" -> options = options.copy(outputJson = requireValue(arg))
                    "--work-dir" -> options = options.copy(workDir = requireValue(arg))
                    "--mode" -> options = options.copy(mode = requireValue(arg).trim().lowercase())
                    "--threads" -> options = options.copy(threadCounts = parseThreads(requireValue(arg)))
                    "--modes" -> options = options.copy(modes = parseModes(requireValue(arg)))
                    "--warmup-iterations" -> options = options.copy(warmupIterations = requireValue(arg).toInt())
                    "--iterations" -> options = options.copy(iterations = requireValue(arg).toInt())
                    "--chunk-size-bytes" -> options = options.copy(chunkSizeBytes = requireValue(arg).toLong())
                    "--io-buffer-bytes" -> options = options.copy(ioBufferBytes = requireValue(arg).toInt())
                    "--max-retries" -> options = options.copy(maxRetries = requireValue(arg).toInt())
                    "--retry-delay-ms" -> options = options.copy(retryDelayMs = requireValue(arg).toLong())
                    "--connect-timeout-ms" -> options = options.copy(connectTimeoutMs = requireValue(arg).toLong())
                    "--request-timeout-ms" -> options = options.copy(requestTimeoutMs = requireValue(arg).toLong())
                    "--help", "-h" -> options = options.copy(showHelp = true)
                    else -> error("Unknown flag: $rawArg")
                }
                index += 1
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
                    else -> error("Invalid mode: $token")
                }
            }
        }
    }
}

data class BenchmarkTarget(
    val url: String,
    val contentLength: Long,
    val expectedSha256: String,
)

data class HostMetadata(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val availableProcessors: Int,
    val jvmVendor: String,
    val jvmVersion: String,
    val kotlinVersion: String,
) {
    companion object {
        fun capture(): HostMetadata {
            return HostMetadata(
                osName = System.getProperty("os.name", "unknown"),
                osVersion = System.getProperty("os.version", "unknown"),
                osArch = System.getProperty("os.arch", "unknown"),
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                jvmVendor = System.getProperty("java.vendor", "unknown"),
                jvmVersion = System.getProperty("java.version", "unknown"),
                kotlinVersion = KotlinVersion.CURRENT.toString(),
            )
        }
    }
}

data class BenchmarkConfigSection(
    val threadCounts: List<Int>,
    val modes: List<String>,
    val warmupIterations: Int,
    val iterations: Int,
    val chunkSizeBytes: Long,
    val ioBufferBytes: Int,
    val maxRetriesPerChunk: Int,
    val retryDelayMillis: Long,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long,
)

data class BenchmarkRunRow(
    val mode: String,
    val threadCount: Int,
    val iteration: Int,
    val chunkSizeBytes: Long,
    val ioBufferBytes: Int,
    val elapsedMillis: Long?,
    val bytesDownloaded: Long?,
    val throughputMiBps: Double?,
    val sha256: String?,
    val checksumMatch: Boolean,
    val success: Boolean,
    val error: String?,
)

data class BenchmarkReport(
    val schemaVersion: String,
    val generatedAt: String,
    val target: BenchmarkTarget,
    val host: HostMetadata,
    val benchmark: BenchmarkConfigSection,
    val runs: List<BenchmarkRunRow>,
    val summary: BenchmarkSummary,
)

data class BenchmarkThreadSummary(
    val threadCount: Int,
    val averageThroughputMiBps: Double,
    val averageElapsedMillis: Double,
    val successRate: Double,
)

data class BenchmarkModeSummary(
    val mode: String,
    val threadSummaries: List<BenchmarkThreadSummary>,
    val bestThreadCount: Int,
    val bestAverageThroughputMiBps: Double,
)

data class OptimizedVsNaiveSummary(
    val speedupAtModeOptimum: Double,
)

data class BenchmarkSummary(
    val perMode: List<BenchmarkModeSummary>,
    val optimizedVsNaive: OptimizedVsNaiveSummary?,
)

private fun buildSummary(
    rows: List<BenchmarkRunRow>,
    modes: List<String>,
    threadCounts: List<Int>,
): BenchmarkSummary {
    val modeSummaries = modes.mapNotNull { mode ->
        val threadSummaries = threadCounts.mapNotNull { threadCount ->
            val samples = rows.filter { it.mode == mode && it.threadCount == threadCount }
            if (samples.isEmpty()) return@mapNotNull null

            val successful = samples.filter { it.success && it.throughputMiBps != null && it.elapsedMillis != null }
            if (successful.isEmpty()) {
                return@mapNotNull BenchmarkThreadSummary(
                    threadCount = threadCount,
                    averageThroughputMiBps = 0.0,
                    averageElapsedMillis = 0.0,
                    successRate = 0.0,
                )
            }

            val avgThroughput = successful.mapNotNull { it.throughputMiBps }.average()
            val avgElapsed = successful.mapNotNull { it.elapsedMillis }.average()
            val successRate = successful.size.toDouble() / samples.size.toDouble()

            BenchmarkThreadSummary(
                threadCount = threadCount,
                averageThroughputMiBps = round3(avgThroughput),
                averageElapsedMillis = round3(avgElapsed),
                successRate = round3(successRate),
            )
        }

        if (threadSummaries.isEmpty()) return@mapNotNull null
        val best = threadSummaries.maxByOrNull { it.averageThroughputMiBps }
            ?: return@mapNotNull null

        BenchmarkModeSummary(
            mode = mode,
            threadSummaries = threadSummaries,
            bestThreadCount = best.threadCount,
            bestAverageThroughputMiBps = best.averageThroughputMiBps,
        )
    }

    val naiveBest = modeSummaries.firstOrNull { it.mode == "naive" }?.bestAverageThroughputMiBps
    val optimizedBest = modeSummaries.firstOrNull { it.mode == "optimized" }?.bestAverageThroughputMiBps
    val diff = if (naiveBest != null && optimizedBest != null && naiveBest > 0.0) {
        OptimizedVsNaiveSummary(speedupAtModeOptimum = round3(optimizedBest / naiveBest))
    } else {
        null
    }

    return BenchmarkSummary(perMode = modeSummaries, optimizedVsNaive = diff)
}

private fun round3(value: Double): Double = round(value * 1000.0) / 1000.0

private fun fetchSha256OverHttp(client: HttpClient, url: String, timeout: Duration): String {
    val request = HttpRequest.newBuilder(URI.create(url))
        .GET()
        .timeout(timeout)
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() !in 200..299) {
        error("Failed to fetch checksum source bytes: status=${response.statusCode()}")
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

private fun String.jsonEscape(): String {
    val builder = StringBuilder(length + 16)
    for (char in this) {
        when (char) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> builder.append(char)
        }
    }
    return builder.toString()
}

private fun StringBuilder.field(name: String, value: String, comma: Boolean = true) {
    append("\"").append(name).append("\":\"").append(value.jsonEscape()).append("\"")
    if (comma) append(',')
}

private fun StringBuilder.field(name: String, value: Number, comma: Boolean = true) {
    append("\"").append(name).append("\":").append(value)
    if (comma) append(',')
}

private fun StringBuilder.field(name: String, value: Boolean, comma: Boolean = true) {
    append("\"").append(name).append("\":").append(value)
    if (comma) append(',')
}

private fun StringBuilder.nullableField(name: String, value: String?, comma: Boolean = true) {
    append("\"").append(name).append("\":")
    if (value == null) append("null") else append("\"").append(value.jsonEscape()).append("\"")
    if (comma) append(',')
}

private fun StringBuilder.nullableField(name: String, value: Long?, comma: Boolean = true) {
    append("\"").append(name).append("\":")
    if (value == null) append("null") else append(value)
    if (comma) append(',')
}

private fun StringBuilder.nullableField(name: String, value: Double?, comma: Boolean = true) {
    append("\"").append(name).append("\":")
    if (value == null) append("null") else append(value)
    if (comma) append(',')
}

private fun BenchmarkReport.toJson(): String {
    val sb = StringBuilder(8192)
    sb.append('{')

    sb.field("schemaVersion", schemaVersion)
    sb.field("generatedAt", generatedAt)

    sb.append("\"target\":{")
    sb.field("url", target.url)
    sb.field("contentLength", target.contentLength)
    sb.field("expectedSha256", target.expectedSha256, comma = false)
    sb.append("},")

    sb.append("\"host\":{")
    sb.field("osName", host.osName)
    sb.field("osVersion", host.osVersion)
    sb.field("osArch", host.osArch)
    sb.field("availableProcessors", host.availableProcessors)
    sb.field("jvmVendor", host.jvmVendor)
    sb.field("jvmVersion", host.jvmVersion)
    sb.field("kotlinVersion", host.kotlinVersion, comma = false)
    sb.append("},")

    sb.append("\"benchmark\":{")
    sb.append("\"threadCounts\":[")
    benchmark.threadCounts.forEachIndexed { index, value ->
        if (index > 0) sb.append(',')
        sb.append(value)
    }
    sb.append("],")
    sb.append("\"modes\":[")
    benchmark.modes.forEachIndexed { index, value ->
        if (index > 0) sb.append(',')
        sb.append('"').append(value.jsonEscape()).append('"')
    }
    sb.append("],")
    sb.field("warmupIterations", benchmark.warmupIterations)
    sb.field("iterations", benchmark.iterations)
    sb.field("chunkSizeBytes", benchmark.chunkSizeBytes)
    sb.field("ioBufferBytes", benchmark.ioBufferBytes)
    sb.field("maxRetriesPerChunk", benchmark.maxRetriesPerChunk)
    sb.field("retryDelayMillis", benchmark.retryDelayMillis)
    sb.field("connectTimeoutMs", benchmark.connectTimeoutMs)
    sb.field("requestTimeoutMs", benchmark.requestTimeoutMs, comma = false)
    sb.append("},")

    sb.append("\"runs\":[")
    runs.forEachIndexed { index, run ->
        if (index > 0) sb.append(',')
        sb.append('{')
        sb.field("mode", run.mode)
        sb.field("threadCount", run.threadCount)
        sb.field("iteration", run.iteration)
        sb.field("chunkSizeBytes", run.chunkSizeBytes)
        sb.field("ioBufferBytes", run.ioBufferBytes)
        sb.nullableField("elapsedMillis", run.elapsedMillis)
        sb.nullableField("bytesDownloaded", run.bytesDownloaded)
        sb.nullableField("throughputMiBps", run.throughputMiBps)
        sb.nullableField("sha256", run.sha256)
        sb.field("checksumMatch", run.checksumMatch)
        sb.field("success", run.success)
        sb.nullableField("error", run.error, comma = false)
        sb.append('}')
    }
    sb.append("],")

    sb.append("\"summary\":{")
    sb.append("\"perMode\":[")
    summary.perMode.forEachIndexed { index, modeSummary ->
        if (index > 0) sb.append(',')
        sb.append('{')
        sb.field("mode", modeSummary.mode)
        sb.field("bestThreadCount", modeSummary.bestThreadCount)
        sb.field("bestAverageThroughputMiBps", modeSummary.bestAverageThroughputMiBps)
        sb.append("\"threadSummaries\":[")
        modeSummary.threadSummaries.forEachIndexed { tIndex, threadSummary ->
            if (tIndex > 0) sb.append(',')
            sb.append('{')
            sb.field("threadCount", threadSummary.threadCount)
            sb.field("averageThroughputMiBps", threadSummary.averageThroughputMiBps)
            sb.field("averageElapsedMillis", threadSummary.averageElapsedMillis)
            sb.field("successRate", threadSummary.successRate, comma = false)
            sb.append('}')
        }
        sb.append(']')
        sb.append('}')
    }
    sb.append("],")
    sb.append("\"optimizedVsNaive\":")
    if (summary.optimizedVsNaive == null) {
        sb.append("null")
    } else {
        sb.append('{')
        sb.field("speedupAtModeOptimum", summary.optimizedVsNaive.speedupAtModeOptimum, comma = false)
        sb.append('}')
    }
    sb.append('}')

    sb.append('}')
    return sb.toString()
}

fun main(args: Array<String>) {
    benchmarkMain(args)
}
