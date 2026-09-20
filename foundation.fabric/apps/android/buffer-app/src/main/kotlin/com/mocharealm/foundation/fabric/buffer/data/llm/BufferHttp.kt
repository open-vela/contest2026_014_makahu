package com.mocharealm.foundation.fabric.buffer.data.llm
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal object BufferHttp {
    fun readText(stream: InputStream?, maxChars: Int): String {
        if (stream == null) return ""
        require(maxChars > 0) { "maxChars must be positive" }
        stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4_096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (count > maxChars - result.length) {
                    throw IOException("HTTP response is too large")
                }
                result.append(buffer, 0, count)
            }
            return result.toString()
        }
    }
}
