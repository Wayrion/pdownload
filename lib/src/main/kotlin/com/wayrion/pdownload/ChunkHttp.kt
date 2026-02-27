package com.wayrion.pdownload

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal fun expectedChunkBytes(startInclusive: Long, endInclusive: Long): Long {
    require(endInclusive >= startInclusive) { "end must be >= start" }
    return endInclusive - startInclusive + 1L
}

internal fun <T> fetchChunkWithRetry(
    client: HttpClient,
    url: String,
    startInclusive: Long,
    endInclusive: Long,
    requestTimeout: Duration,
    maxRetries: Int,
    retryDelayMillis: Long,
    handler: (HttpResponse<java.io.InputStream>) -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(requestTimeout)
                .header("Range", "bytes=$startInclusive-$endInclusive")
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 206) {
                throw IllegalStateException(
                    "Download error: expected HTTP 206 for range bytes=$startInclusive-$endInclusive, " +
                        "got ${response.statusCode()}",
                )
            }
            return handler(response)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (exception: Exception) {
            if (attempt >= maxRetries) {
                throw exception
            }
            attempt += 1
            try {
                Thread.sleep(retryDelayMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }
}
