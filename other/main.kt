// -Xmx2G 
// -XX:MaxDirectMemorySize=1G 
// -XX:+UseZGC
// -Xmx2G -XX:MaxDirectMemorySize=1G -XX:+UseZGC

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.min
import kotlin.system.measureTimeMillis

// --- TOGGLES ---
const val BUFFER_SIZE = 64 * 1024 // 64KB
const val USE_MEMORY_MAPPED_FILES = true
const val USE_DIRECT_BUFFERS = true
// ---------------

class AsyncParallelDownloader(private val url: String, private val destination: Path, private val numCoroutines: Int) {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    suspend fun download() = coroutineScope {
        val headRequest = HttpRequest.newBuilder().uri(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
        val headResponse = client.sendAsync(headRequest, HttpResponse.BodyHandlers.discarding()).await()
        
        val contentLength = headResponse.headers().firstValue("Content-Length").map { it.toLong() }.orElseThrow()
        
        FileChannel.open(destination, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { fileChannel ->
            val chunkSize = (contentLength + numCoroutines - 1) / numCoroutines

            val jobs = (0 until numCoroutines).map { i ->
                async(Dispatchers.IO) {
                    val start = i * chunkSize
                    val end = min(start + chunkSize - 1, contentLength - 1)
                    if (start <= end) {
                        downloadChunk(start, end, fileChannel)
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    private suspend fun downloadChunk(start: Long, end: Long, fileChannel: FileChannel) {
        val request = HttpRequest.newBuilder().uri(URI.create(url)).header("Range", "bytes=$start-$end").GET().build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        
        if (USE_MEMORY_MAPPED_FILES) {
            val mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, start, end - start + 1)
            response.body().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    mappedBuffer.put(buffer, 0, bytesRead)
                }
            }
        } else {
            response.body().use { input ->
                val buffer = if (USE_DIRECT_BUFFERS) ByteBuffer.allocateDirect(BUFFER_SIZE) else ByteBuffer.allocate(BUFFER_SIZE)
                val byteArray = ByteArray(BUFFER_SIZE)
                var currentPos = start
                var bytesRead: Int
                
                while (input.read(byteArray).also { bytesRead = it } != -1) {
                    buffer.clear()
                    buffer.put(byteArray, 0, bytesRead)
                    buffer.flip()
                    fileChannel.write(buffer, currentPos)
                    currentPos += bytesRead
                }
            }
        }
    }
}

fun main() = runBlocking {
    val url = "http://localhost:8080/testfile" 
    val powersOfTwo = listOf(1, 2, 4, 8, 16, 32, 64)
    val results = mutableMapOf<Int, Long>()

    for (coroutines in powersOfTwo) {
        val destFile = Files.createTempFile("dest_async_$coroutines", ".dat")
        val downloader = AsyncParallelDownloader(url, destFile, coroutines)
        
        val time = measureTimeMillis {
            try {
                downloader.download()
            } catch (e: Exception) {
                println("Failed with $coroutines coroutines: ${e.message}")
            }
        }
        results[coroutines] = time
        Files.deleteIfExists(destFile)
    }

    val optimal = results.minByOrNull { it.value }
    println("Optimal coroutine count: ${optimal?.key} (${optimal?.value}ms)\n")
    
    val maxTime = results.values.maxOrNull()?.toDouble() ?: 1.0
    results.forEach { (coroutines, time) ->
        val bar = "█".repeat(((time / maxTime) * 40).toInt().coerceAtLeast(1))
        println("${coroutines.toString().padStart(2)} coroutines | $bar ($time ms)")
    }
}