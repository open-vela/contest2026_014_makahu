package com.mocharealm.foundation.fabric.buffer.domain
import java.io.EOFException
import java.io.IOException
import java.io.Reader

internal class LineTooLongException : IOException("Protocol line exceeds its size limit")

/** Read one LF/CRLF-delimited protocol line, bounding allocation before append. */
internal fun Reader.readBoundedLine(maxChars: Int): String? {
    require(maxChars > 0)
    val line = StringBuilder(minOf(maxChars, 1024))
    while (true) {
        val next = read()
        when (next) {
            -1 -> {
                if (line.isEmpty()) return null
                throw EOFException("Protocol line ended before newline")
            }
            '\n'.code -> return line.toString()
            '\r'.code -> {
                if (read() != '\n'.code) throw IOException("Expected LF after CR")
                return line.toString()
            }
            else -> {
                if (line.length >= maxChars) throw LineTooLongException()
                line.append(next.toChar())
            }
        }
    }
}
