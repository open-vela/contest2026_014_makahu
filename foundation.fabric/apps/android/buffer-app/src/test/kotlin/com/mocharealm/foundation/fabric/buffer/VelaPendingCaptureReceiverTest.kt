package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPendingCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPendingCaptureReceiver
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaPendingCaptureReceiverTest {
    private lateinit var repository: BufferRepository
    private val peer = "a".repeat(64)

    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }

    @Test fun importsRawTextBySourceCaptureIdAndReturnsPartialThenFinalReceipt() {
        val raw = "请记住这个想法：" + "汉字和原始 UTF-8 字节。".repeat(240)
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val receiver = receiver()
        val first = part("vela-text-01", "text/plain; charset=utf-8", bytes, 0, 3072)
        val partial = JSONObject(receiver.receiveAuthenticated(peer, first, 1000).toString(Charsets.UTF_8))
        assertEquals("vela.capture.input.part.reply", partial.getString("type"))
        assertEquals(3072, partial.getInt("next_offset"))
        assertTrue(repository.captures().none { it.captureId == "vela-text-01" })

        var offset = partial.getInt("next_offset")
        var finalReply: JSONObject? = null
        while (offset < bytes.size) {
            val end = minOf(offset + 3072, bytes.size)
            finalReply = JSONObject(receiver.receiveAuthenticated(
                peer, part("vela-text-01", "text/plain; charset=utf-8", bytes, offset, end),
                1000L + offset,
            ).toString(Charsets.UTF_8))
            offset = if (end == bytes.size) end else finalReply.getInt("next_offset")
        }

        val result = requireNotNull(finalReply)
        assertEquals("vela.capture.input.reply", result.getString("type"))
        assertEquals("imported", result.getString("state"))
        assertEquals("vela-text-01", result.getString("capture_id"))
        assertEquals(VelaPendingCaptureProtocol.fingerprint(bytes), result.getString("record_sha256"))
        assertEquals(peer, repository.captures().single { it.captureId == "vela-text-01" }.sourceDevice)
        assertEquals(
            VelaPendingCaptureProtocol.deterministicCardId("vela-text-01", 0),
            repository.cardForCapture("vela-text-01")?.cardId,
        )
        assertEquals(1, cardCount("vela-text-01"))
        assertTrue(repository.pendingClassificationJobs().any { it.captureId == "vela-text-01" })
    }

    @Test fun exactReplayIsDuplicateAndSameIdWithDifferentBytesConflicts() {
        val captureId = "vela-text-02"
        val original = "提醒自己给 Sam 发消息".toByteArray(Charsets.UTF_8)
        assertEquals("imported", transfer(receiver(), captureId, original))
        val cardId = VelaPendingCaptureProtocol.deterministicCardId(captureId, 0)
        repository.applyRemoteClassification(captureId, listOf(proposal(String(original), "spark_card")))
        val card = requireNotNull(repository.findCard(cardId))
        repository.editCardContent(card, "本地编辑", "必须保留")
        assertEquals("duplicate", transfer(receiver(), captureId, original))
        assertEquals("本地编辑", repository.findCard(cardId)?.title)

        assertEquals("conflict", transfer(receiver(), captureId, "另一份内容".toByteArray()))
        assertEquals("本地编辑", repository.findCard(cardId)?.title)
    }

    @Test fun audioImportStaysPendingAndRequiresTheBoundFabricPeer() {
        val captureId = "vela-audio-01"
        val wav = ByteArray(48).also {
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
            "WAVE".toByteArray(Charsets.US_ASCII).copyInto(it, 8)
        }
        val receiver = receiver()
        val frame = part(captureId, "audio/wav", wav, 0, wav.size)
        assertThrows(IllegalArgumentException::class.java) {
            receiver.receiveAuthenticated("b".repeat(64), frame, 1000)
        }
        assertThrows(IllegalStateException::class.java) {
            VelaPendingCaptureReceiver(repository, peer) { false }
                .receiveAuthenticated(peer, frame, 1000)
        }

        val receipt = JSONObject(receiver.receiveAuthenticated(peer, frame, 1001).toString(Charsets.UTF_8))
        assertEquals("imported", receipt.getString("state"))
        val capture = repository.captures().single { it.captureId == captureId }
        assertEquals("audio/wav", capture.audioFormat)
        assertEquals("pending_transcription", repository.cardForCapture(captureId)?.state)
        assertEquals(
            VelaPendingCaptureProtocol.deterministicCardId(captureId, 0),
            repository.cardForCapture(captureId)?.cardId,
        )
        assertTrue(repository.pendingAudioCaptures().any { it.captureId == captureId })
        assertFalse(repository.pendingClassificationJobs().any { it.captureId == captureId })
        assertNotNull(repository.cardForCapture(captureId))
        repository.completeAudioTranscription(captureId, "第一句。第二句。")
        assertNotNull(repository.findCard(VelaPendingCaptureProtocol.deterministicCardId(captureId, 0)))
        assertNull(repository.findCard(VelaPendingCaptureProtocol.deterministicCardId(captureId, 1)))
        assertEquals(1, cardCount(captureId))
    }

    @Test fun processedSnapshotForExistingInputCaptureNeverReplacesEditedOrDeletedCards() {
        val captureId = "vela-text-03"
        assertEquals("imported", transfer(receiver(), captureId, "原始 Vela 文本".toByteArray()))
        val cardId = VelaPendingCaptureProtocol.deterministicCardId(captureId, 0)
        repository.applyRemoteClassification(captureId, listOf(proposal("原始 Vela 文本", "spark_card")))
        repository.editCardContent(requireNotNull(repository.findCard(cardId)), "手机编辑", "保留本地内容")

        assertEquals("conflict", repository.importVelaProcessedCapture(peer, processedSnapshot(captureId, cardId)))
        assertEquals("手机编辑", repository.findCard(cardId)?.title)
        assertEquals("保留本地内容", repository.findCard(cardId)?.summary)

        repository.deleteCards(listOf(cardId))
        assertEquals("conflict", repository.importVelaProcessedCapture(peer, processedSnapshot(captureId, cardId)))
        assertNull(repository.findCard(cardId))
    }

    @Test fun remoteClassificationUsesDeterministicIdsForNewVelaSegments() {
        val captureId = "vela-text-04"
        assertEquals("imported", transfer(receiver(), captureId, "一句原始记录".toByteArray()))
        repository.applyRemoteClassification(captureId, listOf(
            proposal("第一段", "spark_card"),
            proposal("第二段", "task_card"),
        ))

        assertNotNull(repository.findCard(VelaPendingCaptureProtocol.deterministicCardId(captureId, 0)))
        assertNotNull(repository.findCard(VelaPendingCaptureProtocol.deterministicCardId(captureId, 1)))
    }

    private fun receiver(authorized: () -> Boolean = { true }) =
        VelaPendingCaptureReceiver(repository, peer, authorized)

    private fun cardCount(captureId: String): Int = repository.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM cards WHERE capture_id = ?",
        arrayOf(captureId),
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun transfer(receiver: VelaPendingCaptureReceiver, captureId: String, bytes: ByteArray): String {
        var offset = 0
        var state = "partial"
        while (offset < bytes.size) {
            val end = minOf(offset + 3072, bytes.size)
            val reply = JSONObject(receiver.receiveAuthenticated(
                peer,
                part(captureId, "text/plain; charset=utf-8", bytes, offset, end),
                2000L + offset,
            ).toString(Charsets.UTF_8))
            if (reply.getString("type") == "vela.capture.input.reply") state = reply.getString("state")
            offset = if (end == bytes.size) end else reply.getInt("next_offset")
        }
        return state
    }

    private fun part(
        captureId: String,
        contentType: String,
        bytes: ByteArray,
        offset: Int,
        end: Int,
    ): ByteArray {
        val duration = if (contentType == "audio/wav") 1500L else 0L
        return JSONObject()
            .put("type", "vela.capture.input.part")
            .put("protocol", 1)
            .put("capture_id", captureId)
            .put("mode", "normal")
            .put("created_at", 1_700_000_000_000L)
            .put("duration_ms", duration)
            .put("content_type", contentType)
            .put("record_sha256", VelaPendingCaptureProtocol.fingerprint(bytes))
            .put("offset", offset)
            .put("total", bytes.size)
            .put("record_base64", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)))
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun processedSnapshot(captureId: String, cardId: String) = VelaProcessedCaptureProtocol.decode(
        VelaProcessedCaptureProtocol.encode(
            JSONObject()
                .put("capture_id", captureId)
                .put("transcript", "原始 Vela 文本")
                .put("mode", "normal")
                .put("created_at", 1_700_000_000_000L)
                .put("state", "ready")
                .put("segments", org.json.JSONArray().put(JSONObject()
                    .put("card_id", cardId)
                    .put("state", "active")
                    .put("text", "原始 Vela 文本")
                    .put("kind", "spark_card")
                    .put("title", "设备标题")
                    .put("summary", "设备摘要")
                    .put("confidence", 0.9)
                    .put("health_category", JSONObject.NULL)
                    .put("related_card_ids", org.json.JSONArray())))
                .toString(),
        ),
    )

    private fun proposal(text: String, kind: String) = BufferClassificationProposal(
        text = text,
        kind = kind,
        title = text,
        summary = text,
        healthCategory = null,
        confidence = 0.9,
    )
}
