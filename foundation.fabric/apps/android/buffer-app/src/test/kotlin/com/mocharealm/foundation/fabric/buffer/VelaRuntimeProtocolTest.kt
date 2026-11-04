package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.VelaModeProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaRuntimeSender
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaRuntimeProtocolTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }

    @Test fun modeChangeReceiptIsDurableAndDoesNotReplayAnOldSelection() {
        val change = JSONObject().put("type", "buffer.mode.change").put("protocol", 1)
            .put("mode", "focus").put("mode_id", "vela-mode-r1").toString().toByteArray()
        val first = JSONObject(VelaModeProtocol.apply(change, repository).toString(Charsets.UTF_8))
        assertEquals("applied", first.getString("state"))
        assertEquals("focus", repository.mode())
        repository.close(); open()
        assertEquals("focus", repository.mode())
        repository.setMode("normal", "phone-mode-r2")
        val duplicate = JSONObject(VelaModeProtocol.apply(change, repository).toString(Charsets.UTF_8))
        assertEquals("applied", duplicate.getString("state"))
        assertEquals("normal", repository.mode())
        assertThrows(IllegalArgumentException::class.java) {
            VelaModeProtocol.apply(JSONObject(change.toString(Charsets.UTF_8)).put("mode", "bedtime")
                .toString().toByteArray(), repository)
        }
    }

    @Test fun runtimeSenderRetriesIdenticalSnapshotAndOnlyAdvancesAfterCurrentAck() {
        val sent = mutableListOf<ByteArray>()
        val sender = VelaRuntimeSender({ VelaModeProtocol.snapshot(repository) }) {
            sent += it.copyOf(); true
        }
        sender.tick(1000)
        val first = sent.single()
        val state = JSONObject(first.toString(Charsets.UTF_8))
        assertEquals("buffer.runtime.state", state.getString("type"))
        assertEquals(1, state.getInt("protocol"))
        assertEquals(1L, state.getLong("revision"))
        assertEquals(setOf("type", "protocol", "revision", "mode", "research_session", "focus_reminder", "focus_session"),
            state.keys().asSequence().toSet())
        sender.tick(5999)
        assertEquals(1, sent.size)
        sender.tick(6000)
        assertArrayEquals(first, sent.last())

        sender.acknowledge(JSONObject().put("type", "buffer.runtime.reply").put("protocol", 1)
            .put("revision", 1).put("state", "deferred").toString().toByteArray(), 6000)
        sender.tick(6499)
        assertEquals(2, sent.size)
        sender.tick(6500)
        assertEquals(3, sent.size)
        sender.acknowledge(JSONObject().put("type", "buffer.runtime.reply").put("protocol", 1)
            .put("revision", 1).put("state", "applied").toString().toByteArray(), 6500)
        sender.tick(8500)
        assertEquals(3, sent.size)
        sender.acknowledge(JSONObject().put("type", "buffer.runtime.reply").put("protocol", 1)
            .put("revision", 2).put("state", "applied").toString().toByteArray(), 8500)
        sender.tick(10499)
        assertEquals(3, sent.size)
        sender.tick(10500)
        assertEquals(4, sent.size)
        assertEquals(3L, JSONObject(sent.last().toString(Charsets.UTF_8)).getLong("revision"))
        sender.close()
    }
}
