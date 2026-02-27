package org.example

import java.io.BufferedInputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal interface ChunkWriter {
    fun write(
        response: HttpResponse<java.io.InputStream>,
        chunk: ChunkRange,
        fileChannel: FileChannel,
        config: DownloadConfig,
    ): Long
}

internal object naiveChunkWriter : ChunkWriter {
    override fun write(
        response: HttpResponse<java.io.InputStream>,
        chunk: ChunkRange,
        fileChannel: FileChannel,
        config: DownloadConfig,
    ): Long {
        var writePosition = chunk.startInclusive
        val buffer = ByteArray(config.ioBufferBytes)

        response.body().use { stream ->
            BufferedInputStream(stream, config.ioBufferBytes).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    var byteBuffer = ByteBuffer.wrap(buffer, 0, read)
                    while (byteBuffer.hasRemaining()) {
                        val written = fileChannel.write(byteBuffer, writePosition)
                        writePosition += written.toLong()
                    }
                }
            }
        }

        return writePosition
    }
}
