package com.wayrion.pdownload

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

internal fun BenchmarkReport.toJson(): String {
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

    sb.append("\"warmups\":[")
    warmups.forEachIndexed { index, run ->
        if (index > 0) sb.append(',')
        sb.append('{')
        sb.field("mode", run.mode)
        sb.field("threadCount", run.threadCount)
        sb.field("warmupIteration", run.warmupIteration)
        sb.field("chunkSizeBytes", run.chunkSizeBytes)
        sb.field("ioBufferBytes", run.ioBufferBytes)
        sb.nullableField("elapsedMillis", run.elapsedMillis)
        sb.nullableField("bytesDownloaded", run.bytesDownloaded)
        sb.nullableField("sha256", run.sha256)
        sb.field("checksumMatch", run.checksumMatch)
        sb.field("success", run.success)
        sb.nullableField("error", run.error, comma = false)
        sb.append('}')
    }
    sb.append("],")

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
        sb.field("bestAverageElapsedMillis", modeSummary.bestAverageElapsedMillis)
        sb.append("\"threadSummaries\":[")
        modeSummary.threadSummaries.forEachIndexed { tIndex, threadSummary ->
            if (tIndex > 0) sb.append(',')
            sb.append('{')
            sb.field("threadCount", threadSummary.threadCount)
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
    return prettyPrintJson(sb.toString())
}

private fun prettyPrintJson(compactJson: String): String {
    val out = StringBuilder(compactJson.length + 1024)
    var indentLevel = 0
    var inString = false
    var escaped = false

    fun appendIndent() {
        repeat(indentLevel) { out.append("  ") }
    }

    compactJson.forEach { char ->
        when {
            escaped -> {
                out.append(char)
                escaped = false
            }

            char == '\\' -> {
                out.append(char)
                escaped = true
            }

            char == '"' -> {
                out.append(char)
                inString = !inString
            }

            inString -> out.append(char)

            char == '{' || char == '[' -> {
                out.append(char).append('\n')
                indentLevel += 1
                appendIndent()
            }

            char == '}' || char == ']' -> {
                out.append('\n')
                indentLevel -= 1
                appendIndent()
                out.append(char)
            }

            char == ',' -> {
                out.append(char).append('\n')
                appendIndent()
            }

            char == ':' -> out.append(": ")

            char.isWhitespace() -> Unit

            else -> out.append(char)
        }
    }

    return out.toString()
}
