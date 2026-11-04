package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentProtocol

import android.app.Application
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaEnrollmentProtocolTest {
    private fun encode(ssid: String = "家庭网络", password: String = "password123") =
        VelaEnrollmentProtocol.encode("a".repeat(32), "phone-01", "b".repeat(64), ssid, password)
    @Test fun requestPreservesCredentialsAndStableIdentifiers() {
        val json = JSONObject(encode().toString(Charsets.UTF_8))
        assertEquals("家庭网络", json.getString("ssid"))
        assertEquals("a".repeat(32), json.getString("request_id"))
        assertEquals("b".repeat(64), json.getString("token"))
        assertEquals("buffer.configure", json.getString("type"))
        assertArrayEquals(encode(), encode())
    }
    @Test fun ssidLimitCountsUtf8Bytes() {
        encode("中".repeat(10) + "ab")
        assertThrows(IllegalArgumentException::class.java) { encode("中".repeat(11)) }
    }
    @Test fun rejectsControlCharactersAndMalformedUnicode() {
        listOf("ab\u0000cd", "a\nb", "\uD800").forEach {
            assertThrows(IllegalArgumentException::class.java) { encode(it) }
        }
        encode("literal\\u0000")
    }
    @Test fun distinguishesPassphraseFromRawPsk() {
        encode(password = "A".repeat(64))
        encode(password = "x".repeat(63))
        assertThrows(IllegalArgumentException::class.java) { encode(password = "g".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { encode(password = "short") }
    }
    @Test fun framesReconstructRequestAtEverySupportedMtu() {
        val bytes = encode()
        for (mtu in 23..517) {
            val output = mutableListOf<Byte>()
            VelaEnrollmentProtocol.fragments(bytes, mtu).forEach { frame ->
                assertTrue(frame.size <= mtu - 3)
                fun u16(i: Int) = ((frame[i].toInt() and 255) shl 8) or (frame[i+1].toInt() and 255)
                assertEquals(output.size, u16(0))
                assertEquals(bytes.size, u16(2))
                output.addAll(frame.drop(4))
            }
            assertArrayEquals(bytes, output.toByteArray())
        }
    }
    @Test fun rejectsInvalidFrameBoundsAndIdentities() {
        assertThrows(IllegalArgumentException::class.java) { VelaEnrollmentProtocol.fragments(ByteArray(769), 23) }
        assertThrows(IllegalArgumentException::class.java) { VelaEnrollmentProtocol.fragments(ByteArray(0), 23) }
        assertThrows(IllegalArgumentException::class.java) { VelaEnrollmentProtocol.fragments(encode(), 22) }
        assertThrows(IllegalArgumentException::class.java) {
            VelaEnrollmentProtocol.encode("a".repeat(32), "bad:id", "b".repeat(64), "wifi", "password")
        }
    }
}
