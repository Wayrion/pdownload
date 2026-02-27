package com.wayrion.pdownload

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
    val sha256: String?,
    val checksumMatch: Boolean,
    val success: Boolean,
    val error: String?,
)

data class BenchmarkWarmupRow(
    val mode: String,
    val threadCount: Int,
    val warmupIteration: Int,
    val chunkSizeBytes: Long,
    val ioBufferBytes: Int,
    val elapsedMillis: Long?,
    val bytesDownloaded: Long?,
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
    val warmups: List<BenchmarkWarmupRow>,
    val runs: List<BenchmarkRunRow>,
    val summary: BenchmarkSummary,
)

data class BenchmarkThreadSummary(
    val threadCount: Int,
    val averageElapsedMillis: Double,
    val successRate: Double,
)

data class BenchmarkModeSummary(
    val mode: String,
    val threadSummaries: List<BenchmarkThreadSummary>,
    val bestThreadCount: Int,
    val bestAverageElapsedMillis: Double,
)

data class OptimizedVsNaiveSummary(
    val speedupAtModeOptimum: Double,
)

data class BenchmarkSummary(
    val perMode: List<BenchmarkModeSummary>,
    val optimizedVsNaive: OptimizedVsNaiveSummary?,
)
