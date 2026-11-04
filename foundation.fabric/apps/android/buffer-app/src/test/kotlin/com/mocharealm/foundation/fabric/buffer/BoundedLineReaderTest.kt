package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.LineTooLongException
import com.mocharealm.foundation.fabric.buffer.domain.readBoundedLine

import java.io.EOFException
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class BoundedLineReaderTest {
    @Test fun readsSeparateLinesWithoutConsumingNextMessage() {
        val reader = StringReader("abc\n中文\r\n\n").buffered()
        assertEquals("abc", reader.readBoundedLine(3))
        assertEquals("中文", reader.readBoundedLine(2))
        assertEquals("", reader.readBoundedLine(3))
        assertNull(reader.readBoundedLine(3))
    }

    @Test fun exactLimitAcceptsLfAndCrLf() {
        for (limit in listOf(1, 16 * 1024, 1_200_000)) {
            val payload = "x".repeat(limit)
            for (ending in listOf("\n", "\r\n")) {
                assertEquals(payload, StringReader(payload + ending).readBoundedLine(limit))
            }
        }
    }

    @Test fun unterminatedUnlimitedInputStopsAtLimitPlusOne() {
        val reader = object : Reader() {
            var consumed = 0
            override fun read(target: CharArray, offset: Int, length: Int): Int {
                check(length == 1)
                target[offset] = 'x'
                consumed++
                check(consumed <= 16385) { "Read beyond allocation limit" }
                return 1
            }
            override fun close() = Unit
        }
        assertThrows(LineTooLongException::class.java) { reader.readBoundedLine(16384) }
        assertEquals(16385, reader.consumed)
    }

    @Test fun excessBeforeEitherLineEndingIsRejected() {
        for (suffix in listOf("\n", "\r\n", "")) {
            assertThrows(LineTooLongException::class.java) {
                StringReader("abcd" + suffix).readBoundedLine(3)
            }
        }
    }

    @Test fun eofOnlyBetweenCompleteMessages() {
        assertNull(StringReader("").readBoundedLine(3))
        assertThrows(EOFException::class.java) { StringReader("abc").readBoundedLine(3) }
    }

    @Test fun rejectsMalformedDelimiterAndInvalidLimit() {
        for (input in listOf("abc\r", "abc\rx")) {
            assertThrows(IOException::class.java) { StringReader(input).readBoundedLine(3) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            StringReader("\n").readBoundedLine(0)
        }
    }
}
