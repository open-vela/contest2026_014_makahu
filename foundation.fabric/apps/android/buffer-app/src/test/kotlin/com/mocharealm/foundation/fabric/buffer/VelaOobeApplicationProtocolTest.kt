package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.VelaOobeApplicationProtocol
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaOobeApplicationProtocolTest {
    private val requestId = "0123456789abcdef0123456789abcdef"

    @Test
    fun agentSetupReusesPhoneProfileAndIncludesAsrModel() {
        val secret = "test-key-never-log"
        val config = BufferLlmConfig("https://llm.example/v1/", "mimo-v2.5", secret)

        val message = JSONObject(VelaOobeApplicationProtocol.encodeAgentConfiguration(
            requestId, 7, config, "Asia/Shanghai", "zh-CN",
        ).toString(Charsets.UTF_8))

        assertEquals("vela.oobe.request", message.getString("type"))
        assertEquals("agent.configure", message.getString("operation"))
        assertEquals(7, message.getLong("revision"))
        val payload = message.getJSONObject("payload")
        assertEquals("https://llm.example/v1", payload.getString("base_url"))
        assertEquals("mimo-v2.5", payload.getString("completion_model"))
        assertEquals(BufferMiMo.asrModel, payload.getString("transcription_model"))
        assertEquals(secret, payload.getString("api_key"))
        assertEquals("Asia/Shanghai", payload.getString("time_zone"))
        assertTrue(config.toString().contains("redacted"))
        assertFalse(config.toString().contains(secret))
    }

    @Test
    fun stateRequestContainsNoCredentialFields() {
        val message = JSONObject(VelaOobeApplicationProtocol.encodeStateRequest(requestId, 2)
            .toString(Charsets.UTF_8))

        assertEquals("oobe.status", message.getString("operation"))
        assertEquals(0, message.getJSONObject("payload").length())
        assertFalse(message.toString().contains("api_key"))
    }

    @Test
    fun agentSetupRejectsPlainHttpAndUntrustedUrlComponents() {
        listOf(
            "http://llm.example/v1",
            "https://user:pass@llm.example/v1",
            "https://llm.example/v1?token=x",
            "https://llm.example/v1#fragment",
        ).forEach { baseUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                VelaOobeApplicationProtocol.encodeAgentConfiguration(
                    requestId, 1, BufferLlmConfig(baseUrl, "mimo-v2.5", "key"), "UTC", "en-US",
                )
            }
        }
    }

    @Test
    fun messageSizeAndRevisionAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            VelaOobeApplicationProtocol.encodeStateRequest(requestId, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VelaOobeApplicationProtocol.encodeStateRequest(requestId, Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VelaOobeApplicationProtocol.encodeAgentConfiguration(
                requestId, 1, BufferLlmConfig("https://llm.example", "mimo-v2.5", "k".repeat(8193)),
                "UTC", "en-US",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VelaOobeApplicationProtocol.encodeAgentConfiguration(
                requestId, 1, BufferLlmConfig("https://llm.example", "mimo-v2.5", "é".repeat(4097)),
                "UTC", "en-US",
            )
        }
    }

    @Test
    fun readyServiceCannotStandInForConfirmedOnboarding() {
        val json = JSONObject().put("type", "vela.oobe.reply").put("protocol", 1)
            .put("request_id", requestId).put("revision", 3).put("state", "ready")
        fun reply() = VelaOobeApplicationProtocol.decodeReply(json.toString().toByteArray(), requestId, 3)
        assertFalse(reply().setupComplete)
        json.put("capabilities", JSONObject().put("setup_complete", false))
        assertFalse(reply().setupComplete)
        json.getJSONObject("capabilities").put("setup_complete", true)
        assertTrue(reply().setupComplete)
        json.getJSONObject("capabilities").put("setup_complete", "true")
        assertThrows(IllegalArgumentException::class.java) { reply() }
    }

    @Test
    fun deviceReplyMustMatchRequestAndCannotReturnSecrets() {
        val valid = JSONObject().put("type", "vela.oobe.reply").put("protocol", 1)
            .put("request_id", requestId).put("revision", 3).put("state", "ready")
            .put("capabilities", JSONObject().put("asr", true).put("llm", true)).toString()
        val reply = VelaOobeApplicationProtocol.decodeReply(valid.toByteArray(), requestId, 3)
        assertEquals("ready", reply.state)
        assertEquals(true, reply.capabilities["asr"])

        assertThrows(IllegalArgumentException::class.java) {
            VelaOobeApplicationProtocol.decodeReply(valid.toByteArray(), requestId, 4)
        }
        val leaking = JSONObject(valid).put("api_key", "should-not-return").toString()
        assertThrows(IllegalArgumentException::class.java) {
            VelaOobeApplicationProtocol.decodeReply(leaking.toByteArray(), requestId, 3)
        }
        val fractional = JSONObject(valid).put("revision", 3.5).toString()
        assertThrows(IllegalArgumentException::class.java) {
            VelaOobeApplicationProtocol.decodeReply(fractional.toByteArray(), requestId, 3)
        }
        val unknown = JSONObject(valid).put("token", "must-not-be-returned").toString()
        assertThrows(IllegalArgumentException::class.java) {
            VelaOobeApplicationProtocol.decodeReply(unknown.toByteArray(), requestId, 3)
        }
        val invalidCapabilities = JSONObject(valid).put("capabilities", "asr").toString()
        assertThrows(IllegalArgumentException::class.java) {
            VelaOobeApplicationProtocol.decodeReply(invalidCapabilities.toByteArray(), requestId, 3)
        }
    }
}
