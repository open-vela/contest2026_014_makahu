package com.mocharealm.foundation.fabric.buffer
import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.TaskSchedule
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrdtCalendarTest {
    private lateinit var repo: BufferRepository
    @Before fun open() { repo = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repo.close() }
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    @Test fun replicasConvergeAcrossOrderReplayAndIndependentEdits() {
        val base = BufferCrdt.update(null, "task-one", "card", JSONObject().put("title", "old").put("state", "active"), a)
        val title = BufferCrdt.update(base, "task-one", "card", JSONObject().put("title", "edited"), a)
        val state = BufferCrdt.update(base, "task-one", "card", JSONObject().put("state", "done"), b)
        val date = BufferCrdt.update(base, "task-one", "card", JSONObject().put("schedule", JSONObject().put("start", 1000)), c)
        val expected = BufferCrdt.canonical(BufferCrdt.merge(BufferCrdt.merge(title, state), date))
        for (first in listOf(title, state, date)) for (second in listOf(title, state, date)) {
            val result = listOf(first, second, title, state, date, first).fold(base) { value, next -> BufferCrdt.merge(value, next) }
            assertEquals(expected, BufferCrdt.canonical(result))
        }
        val values = BufferCrdt.values(BufferCrdt.merge(title, state))
        assertEquals("edited", values.getString("title")); assertEquals("done", values.getString("state"))
    }
    @Test fun deletionCannotBeResurrectedByOldOrConcurrentEdits() {
        val base = BufferCrdt.update(null, "one", "card", JSONObject().put("title", "old"), a)
        val removed = BufferCrdt.update(base, "one", "card", JSONObject(), b, true)
        val changed = BufferCrdt.update(base, "one", "card", JSONObject().put("title", "new"), c)
        assertTrue(BufferCrdt.merge(removed, changed).getBoolean("deleted"))
        assertEquals(BufferCrdt.canonical(BufferCrdt.merge(removed, changed)), BufferCrdt.canonical(BufferCrdt.merge(changed, removed)))
        assertTrue(BufferCrdt.update(removed, "one", "card", JSONObject().put("title", "again"), a).getBoolean("deleted"))
    }
    @Test fun corruptEqualStampAndInvalidClockAreRejected() {
        val base = BufferCrdt.update(null, "one", "card", JSONObject().put("title", "old"), a)
        val corrupt = JSONObject(base.toString()).apply { getJSONObject("fields").getJSONObject("title").put("value", "bad") }
        assertThrows(IllegalArgumentException::class.java) { BufferCrdt.merge(base, corrupt) }
        corrupt.getJSONObject("fields").getJSONObject("title").put("counter", 1.5)
        assertThrows(IllegalArgumentException::class.java) { BufferCrdt.validate(corrupt) }
    }
    @Test fun datedTaskAppearsWithoutCalendarPermissionOrDuplicateEvent() {
        val card = repo.createTextCapture("明天下午三点提交作业")
        val schedule = requireNotNull(repo.taskSchedule(card))
        val local = Instant.ofEpochMilli(schedule.start).atZone(ZoneId.systemDefault())
        assertEquals(15, local.hour)
        assertEquals(1, repo.calendarEntries(schedule.start, schedule.end).size)
        assertEquals(card.cardId, repo.calendarEntries(schedule.start, schedule.end).single().id)
        repo.setTaskSchedule(card.cardId, null)
        assertTrue(repo.calendarEntries(schedule.start, schedule.end).isEmpty())
    }
    @Test fun localTitleAndRemoteCompletionMergeWithoutLosingSchedule() {
        val card = repo.createTextCapture("明天09:30记得写作业")
        val original = repo.velaReplicaPage("").map(::JSONObject).single { it.getString("card_id") == card.cardId }.getJSONObject("crdt")
        val finished = BufferCrdt.update(original, card.cardId, "card", JSONObject().put("state", "done"), b)
        repo.editCardContent(card, "新的标题", card.summary)
        val merged = repo.mergeCrdtDocument(finished)
        assertEquals("新的标题", BufferCrdt.values(merged).getString("title"))
        assertEquals("done", repo.findCard(card.cardId)!!.state)
        val schedule = requireNotNull(repo.taskSchedule(repo.findCard(card.cardId)!!))
        assertTrue(repo.calendarEntries(schedule.start, schedule.end).single().done)
    }
    @Test fun documentFragmentsMergeAfterHashVerificationAndSurviveReplay() {
        val card = repo.createTextCapture("明天下午三点交作业")
        val original = repo.velaReplicaPage("").map(::JSONObject).single { it.getString("card_id") == card.cardId }.getJSONObject("crdt")
        val doc = BufferCrdt.update(original, card.cardId, "card", JSONObject().put("state", "done"), b)
        val raw = JSONObject().put("card_id", card.cardId).put("crdt", doc).toString().toByteArray(Charsets.UTF_8)
        val receiver = com.mocharealm.foundation.fabric.buffer.data.device.VelaCrdtReceiver(repo)
        val digest = com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol.fingerprint(raw.toString(Charsets.UTF_8))
        fun frame(offset: Int, part: ByteArray, hash: String = digest) = JSONObject().put("type", "buffer.crdt.part").put("protocol", 1)
            .put("record_sha256", hash).put("offset", offset).put("total", raw.size)
            .put("record_base64", java.util.Base64.getEncoder().encodeToString(part)).toString().toByteArray()
        val first = frame(0, raw.copyOfRange(0, 100))
        assertEquals(100, JSONObject(receiver.receive(first).toString(Charsets.UTF_8)).getInt("next_offset"))
        assertEquals(100, JSONObject(receiver.receive(first).toString(Charsets.UTF_8)).getInt("next_offset"))
        assertNotEquals("done", repo.findCard(card.cardId)!!.state)
        assertThrows(IllegalArgumentException::class.java) { receiver.receive(frame(101, raw.copyOfRange(101, 200))) }
        var last = byteArrayOf()
        for (offset in 100 until raw.size step 100) last = receiver.receive(frame(offset, raw.copyOfRange(offset, minOf(offset + 100, raw.size))))
        assertEquals("merged", JSONObject(last.toString(Charsets.UTF_8)).getString("state"))
        assertEquals("done", repo.findCard(card.cardId)!!.state)
        receiver.close()
        assertThrows(IllegalArgumentException::class.java) { receiver.receive(frame(0, raw, "0".repeat(64))) }
        receiver.close()
        assertEquals("done", repo.findCard(card.cardId)!!.state)
    }
    @Test fun colonTimeRespectsChinesePeriodAndDeletedLinkedTaskDoesNotReappear() {
        val captured = LocalDate.of(2026, 10, 20).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val parsed = requireNotNull(TaskSchedule.fromText("明天下午3:30交作业", captured))
        assertEquals(15, Instant.ofEpochMilli(parsed.start).atZone(ZoneId.systemDefault()).hour)
        val card = repo.createTextCapture("明天下午三点交作业")
        val schedule = requireNotNull(repo.taskSchedule(card))
        repo.upsertCalendarDocument("cal-linked", JSONObject().put("title", "Linked system event").put("schedule", schedule.json())
            .put("location", "").put("linked_card_id", card.cardId).put("source", "android_calendar"))
        assertEquals(1, repo.calendarEntries(schedule.start, schedule.end).size)
        val old = repo.velaReplicaPage("").map(::JSONObject).single { it.getString("card_id") == card.cardId }.getJSONObject("crdt")
        repo.deleteCards(listOf(card.cardId))
        val lateEdit = BufferCrdt.update(old, card.cardId, "card", JSONObject().put("title", "Late device edit"), b)
        assertTrue(repo.mergeCrdtDocument(lateEdit).getBoolean("deleted"))
        assertTrue(repo.findCard(card.cardId) == null || repo.findCard(card.cardId)?.state == "deleted")
        assertTrue(repo.calendarEntries(schedule.start, schedule.end).isEmpty())
    }
    @Test fun mixedInputDatesStayWithTheirOwnTasks() {
        val card = repo.createTextCapture("明天交作业，后天交报告")
        repo.applyRemoteClassification(requireNotNull(card.captureId), listOf(
            BufferClassificationProposal("明天交作业", "task_card", "作业", "提交作业", null, 0.9),
            BufferClassificationProposal("后天交报告", "task_card", "报告", "提交报告", null, 0.9)))
        val tasks = repo.cards(100).filter { it.kind == "task_card" }
        assertEquals(2, tasks.size)
        val dates = tasks.map { Instant.ofEpochMilli(requireNotNull(repo.taskSchedule(it)).start).atZone(ZoneId.systemDefault()).toLocalDate() }.sorted()
        assertEquals(1L, java.time.temporal.ChronoUnit.DAYS.between(dates[0], dates[1]))
    }
    @Test fun relativeDateUsesCaptureTimeAndAllDayRespectsDst() {
        val zone = ZoneId.of("America/New_York")
        val captured = LocalDate.of(2026, 3, 7).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val schedule = requireNotNull(TaskSchedule.fromText("明天交作业", captured, zone))
        assertTrue(schedule.allDay)
        assertEquals(23 * 60 * 60 * 1000L, schedule.end - schedule.start)
        assertNull(TaskSchedule.fromText("有空的时候交作业", captured, zone))
    }
    @Test fun providerMoveTombstonesOldOccurrenceAndKeepsNewOne() {
        val values = JSONObject().put("title", "会议").put("location", "").put("source", "android_calendar")
            .put("schedule", TaskSchedule(1000, 2000, false, "UTC").json())
        repo.upsertCalendarDocument("cal_old", values)
        repo.reconcileCalendarDocuments(500, 3000, emptySet())
        assertTrue(repo.calendarEntries(500, 3000).isEmpty())
        val incarnation = repo.calendarSourceDocumentId("cal_old")
        assertNotEquals("cal_old", incarnation)
        repo.upsertCalendarDocument(incarnation, values)
        assertEquals(incarnation, repo.calendarEntries(500, 3000).single().id)
    }
}
