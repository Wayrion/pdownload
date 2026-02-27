package com.wayrion.pdownload

import kotlin.math.round

internal fun buildSummary(
    rows: List<BenchmarkRunRow>,
    modes: List<String>,
    threadCounts: List<Int>,
): BenchmarkSummary {
    val modeSummaries = modes.mapNotNull { mode ->
        val threadSummaries = threadCounts.mapNotNull { threadCount ->
            val samples = rows.filter { it.mode == mode && it.threadCount == threadCount }
            if (samples.isEmpty()) return@mapNotNull null

            val successful = samples.filter { it.success && it.elapsedMillis != null }
            if (successful.isEmpty()) {
                return@mapNotNull BenchmarkThreadSummary(
                    threadCount = threadCount,
                    averageElapsedMillis = 0.0,
                    successRate = 0.0,
                )
            }

            val avgElapsed = successful.mapNotNull { it.elapsedMillis }.average()
            val successRate = successful.size.toDouble() / samples.size.toDouble()

            BenchmarkThreadSummary(
                threadCount = threadCount,
                averageElapsedMillis = round3(avgElapsed),
                successRate = round3(successRate),
            )
        }

        if (threadSummaries.isEmpty()) return@mapNotNull null
        val best = threadSummaries
            .filter { it.successRate > 0.0 }
            .minByOrNull { it.averageElapsedMillis }
            ?: threadSummaries.maxByOrNull { it.successRate }
            ?: return@mapNotNull null

        BenchmarkModeSummary(
            mode = mode,
            threadSummaries = threadSummaries,
            bestThreadCount = best.threadCount,
            bestAverageElapsedMillis = best.averageElapsedMillis,
        )
    }

    val naiveBest = modeSummaries.firstOrNull { it.mode == "naive" }?.bestAverageElapsedMillis
    val optimizedBest = modeSummaries.firstOrNull { it.mode == "optimized" }?.bestAverageElapsedMillis
    val diff = if (naiveBest != null && optimizedBest != null && optimizedBest > 0.0) {
        OptimizedVsNaiveSummary(speedupAtModeOptimum = round3(naiveBest / optimizedBest))
    } else {
        null
    }

    return BenchmarkSummary(perMode = modeSummaries, optimizedVsNaive = diff)
}

private fun round3(value: Double): Double = round(value * 1000.0) / 1000.0
