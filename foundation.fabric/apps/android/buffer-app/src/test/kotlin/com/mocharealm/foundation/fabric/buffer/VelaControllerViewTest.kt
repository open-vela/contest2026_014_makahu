package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.VelaControllerView
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaControllerViewTest {
    private lateinit var repository: BufferRepository
    private val local = "a".repeat(64)
    private val device = "b".repeat(64)
    // Cross-language vector: Buffer's view_digest, sorted IDs, revision 7.
    private val rosterHash = "0c5c9e8001acdb751a726f851a0afd3f9868e14081c664f37cfd5f4dd9590db2"
    private var sent: JSONObject? = null
    private var installed = 0
    private lateinit var view: VelaControllerView
    @Before fun open() {
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        view = VelaControllerView(repository, local, device,
            { sent = JSONObject(it.toString(Charsets.UTF_8)); true }, {},
            { members, hash ->
                assertEquals(listOf(local, device), members)
                assertEquals(rosterHash, hash)
                installed++
            })
        view.tick(0)
    }
    @After fun close() { repository.close() }
    private fun reply(type: String, hash: String = rosterHash) = JSONObject()
        .put("type", type).put("protocol", 1).put("owner_protocol", 1)
        .put("nonce", requireNotNull(sent).getString("nonce"))
        .put("device_id", device).put("revision", 7)
        .put("members", JSONArray(listOf(local, device)))
        .put("membership_hash", hash).toString().toByteArray(Charsets.UTF_8)
    @Test fun acceptsDeviceRosterThenRequiresMatchingAcknowledgment() {
        view.receive(reply("buffer.vela.members"))
        assertEquals(0, installed)
        view.tick(1)
        assertEquals("buffer.vela.members.accept", sent!!.getString("type"))
        assertEquals(rosterHash, sent!!.getString("membership_hash"))
        view.receive(reply("buffer.vela.members.accepted"))
        assertEquals(1, installed)
    }
    @Test fun rejectsOldMismatchedDigestWithoutInstallingAuthority() {
        assertThrows(IllegalArgumentException::class.java) {
            view.receive(reply("buffer.vela.members", "5535a4ee3fe96ce7c3ab6bee7d2fee15d0e337c8bc125a5bc162b4dcabc774d4"))
        }
        assertEquals(0, installed)
    }
}
