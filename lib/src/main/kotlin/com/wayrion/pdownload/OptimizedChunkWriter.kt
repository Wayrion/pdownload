package com.wayrion.pdownload

import java.io.BufferedInputStream
import java.net.http.HttpResponse
import java.nio.channels.Channels
import java.nio.channels.FileChannel

internal object optimizedChunkWriter : ChunkWriter {
    override fun write(
        response: HttpResponse<java.io.InputStream>,
        chunk: ChunkRange,
        fileChannel: FileChannel,
        config: DownloadConfig,
    ): Long {
        var writePosition = chunk.startInclusive
        var remaining = chunk.endInclusive - chunk.startInclusive + 1L

        response.body().use { stream ->
            BufferedInputStream(stream, config.ioBufferBytes).use { input ->
                Channels.newChannel(input).use { readable ->
                    while (remaining > 0L) {
                        val transferred = fileChannel.transferFrom(readable, writePosition, remaining)
                        if (transferred <= 0L) {
                            break
                        }
                        writePosition += transferred
                        remaining -= transferred
                    }
                }
            }
        }

        return writePosition
    }
}
