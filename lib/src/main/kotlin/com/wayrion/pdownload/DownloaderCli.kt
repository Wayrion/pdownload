package com.wayrion.pdownload

import java.nio.file.Path
import java.time.Duration

private const val DEFAULT_THREADS = 8

fun main(args: Array<String>) {
    val options = CliOptions.parse(args)

    if (options.showHelp) {
        printUsage()
        return
    }

    val url = options.url ?: cliMissingRequired("--url")
    val output = options.output ?: cliMissingRequired("--output")

    val config = DownloadConfig(
        threadCount = options.threads ?: DEFAULT_THREADS,
        chunkSizeBytes = options.chunkSizeBytes ?: (1L shl 20),
        connectTimeout = Duration.ofMillis(options.connectTimeoutMs ?: 10_000L),
        requestTimeout = Duration.ofMillis(options.requestTimeoutMs ?: 30_000L),
        maxRetriesPerChunk = options.maxRetries ?: 0,
        retryDelayMillis = options.retryDelayMs ?: 100L,
        mode = options.mode ?: DownloadMode.NAIVE,
        ioBufferBytes = options.ioBufferBytes ?: (16 * 1024),
        expectedSha256 = options.expectedSha256,
        outputFromMemoryToDisk = options.outputFromMemoryToDisk ?: true,
    )

    val downloader = ParallelFileDownloader()
    val result = downloader.download(url = url, destination = Path.of(output), config = config)

    println("Downloaded ${result.bytesDownloaded} bytes in ${result.elapsedMillis} ms")
    println("chunks=${result.chunksDownloaded} threads=${config.threadCount} output=${result.destination}")
}

private fun printUsage() {
    println(
        """
                |Usage:
                |  downloader --url <URL> --output <PATH> [options]
                |
                |Options:
                |  --threads <N>             Worker thread count (default: 8)
                |  --chunk-size-bytes <N>    Chunk size in bytes (default: 1048576)
                |  --max-retries <N>         Retries per chunk on failure (default: 0)
                |  --retry-delay-ms <N>      Delay between retries (default: 100)
                |  --mode <naive|optimized|processes>  Download strategy (default: naive)
                |  --io-buffer-bytes <N>     Per-thread I/O buffer bytes (default: 16384)
                |  --expected-sha256 <HEX>   Optional expected SHA-256 for verification
                |  --output-from-memory <true|false>  Assemble in memory then write once (default: true)
                |  --connect-timeout-ms <N>  Client connection timeout (default: 10000)
                |  --request-timeout-ms <N>  Per-request timeout (default: 30000)
                |  --help                    Print this help
                """.trimMargin(),
    )
}

private data class CliOptions(
    val url: String? = null,
    val output: String? = null,
    val threads: Int? = null,
    val chunkSizeBytes: Long? = null,
    val maxRetries: Int? = null,
    val retryDelayMs: Long? = null,
    val mode: DownloadMode? = null,
    val ioBufferBytes: Int? = null,
    val expectedSha256: String? = null,
    val outputFromMemoryToDisk: Boolean? = null,
    val connectTimeoutMs: Long? = null,
    val requestTimeoutMs: Long? = null,
    val showHelp: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): CliOptions {
            var options = CliOptions()
            val parsed = parseCliArgs(
                args = args,
                flagsWithoutValue = setOf("--help", "-h"),
                sanitizeFlags = true,
            )

            parsed.forEach { arg ->
                when (arg.flag) {
                    "--url" -> options = options.copy(url = arg.value!!)
                    "--output" -> options = options.copy(output = arg.value!!)
                    "--threads" -> options = options.copy(threads = arg.value!!.toInt())
                    "--chunk-size-bytes" -> options = options.copy(chunkSizeBytes = arg.value!!.toLong())
                    "--max-retries" -> options = options.copy(maxRetries = arg.value!!.toInt())
                    "--retry-delay-ms" -> options = options.copy(retryDelayMs = arg.value!!.toLong())
                    "--mode" -> options = options.copy(mode = parseMode(arg.value!!))
                    "--io-buffer-bytes" -> options = options.copy(ioBufferBytes = arg.value!!.toInt())
                    "--expected-sha256" -> options = options.copy(expectedSha256 = arg.value!!)
                    "--output-from-memory" -> options = options.copy(outputFromMemoryToDisk = parseBoolean(arg.flag, arg.value!!))
                    "--connect-timeout-ms" -> options = options.copy(connectTimeoutMs = arg.value!!.toLong())
                    "--request-timeout-ms" -> options = options.copy(requestTimeoutMs = arg.value!!.toLong())
                    "--help", "-h" -> options = options.copy(showHelp = true)
                    else -> cliUnknownFlag(arg.raw)
                }
            }

            return options
        }

        private fun parseMode(value: String): DownloadMode {
            return when (value.lowercase()) {
                "naive" -> DownloadMode.NAIVE
                "optimized" -> DownloadMode.OPTIMIZED
                "processes" -> DownloadMode.PROCESSES
                else -> cliInvalidValue("--mode", value, "naive|optimized|processes")
            }
        }

        private fun parseBoolean(flag: String, value: String): Boolean {
            return when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> cliInvalidValue(flag, value, "true|false")
            }
        }
    }
}
