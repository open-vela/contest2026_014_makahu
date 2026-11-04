package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResult
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResultReader

import android.app.Application
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaEnrollmentResultTest {
    private fun json() = JSONObject().put("protocol",1).put("device_id","vela-test")
        .put("request_id","a".repeat(32)).put("state","network_ready").put("error",0)
    private fun frames(bytes: ByteArray) = VelaEnrollmentProtocol.fragments(bytes,23)
    private fun decode(value: JSONObject): VelaEnrollmentResult? {
        val reader=VelaEnrollmentResultReader()
        var result: VelaEnrollmentResult?=null
        frames(value.toString().toByteArray()).forEach { result=reader.accept(it) }
        return result
    }
    @Test fun readsFrozenResultAtDefaultMtu() {
        val reader=VelaEnrollmentResultReader()
        var result: VelaEnrollmentResult?=null
        frames(json().toString().toByteArray()).forEach { frame ->
            assertArrayEquals(frame.copyOfRange(0,2),reader.selector())
            result=reader.accept(frame)
        }
        assertEquals(VelaEnrollmentResult("vela-test","a".repeat(32),"network_ready",0),result)
    }
    @Test fun refusesGapsAndChangingSnapshotLengths() {
        val pages=frames(json().toString().toByteArray())
        assertThrows(IOException::class.java){VelaEnrollmentResultReader().accept(pages[1])}
        val reader=VelaEnrollmentResultReader();reader.accept(pages[0])
        val changed=pages[1].clone();changed[3]=(changed[3]+1).toByte()
        assertThrows(IOException::class.java){reader.accept(changed)}
    }
    @Test fun rejectsOversizeAndAuthenticationFailureFrames() {
        assertThrows(IOException::class.java){VelaEnrollmentResultReader().accept(byteArrayOf(0,0,1,65,1))}
        assertThrows(IOException::class.java){VelaEnrollmentResultReader().accept(ByteArray(4))}
    }
    @Test fun checksResultSemanticsAndIdentity() {
        listOf(json().put("error",-1),json().put("state","failed"),json().put("device_id","bad:id"),
            json().put("request_id",""),json().put("state","added"),json().put("protocol","1"),
            json().put("error",false)).forEach { invalid ->
            assertThrows(IOException::class.java){decode(invalid)}
        }
        assertEquals("failed",decode(json().put("state","failed").put("error",-5))?.state)
        assertEquals("idle",decode(json().put("state","idle").put("request_id",""))?.state)
    }
    @Test fun rejectsDuplicateFinalPageAndMalformedUtf8() {
        val pages=frames(json().toString().toByteArray())
        val reader=VelaEnrollmentResultReader();pages.forEach { reader.accept(it) }
        assertThrows(IOException::class.java){reader.accept(pages.last())}
        assertThrows(IOException::class.java){VelaEnrollmentResultReader().accept(byteArrayOf(0,0,0,1,0xff.toByte()))}
    }
}
