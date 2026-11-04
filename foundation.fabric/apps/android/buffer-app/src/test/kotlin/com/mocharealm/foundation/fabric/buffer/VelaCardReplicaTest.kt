package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.VelaCardReplicaSender
import com.mocharealm.foundation.fabric.buffer.data.device.VelaCardActionProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
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
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaCardReplicaTest {
    private lateinit var repo: BufferRepository
    @Before fun open() { repo = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repo.close() }
    @Test fun busyReceiptRetriesSameRecordPromptlyWithoutAdvancingOrCompleting() {
        val raw=JSONObject().put("card_id","calendar-one").toString();val sent=mutableListOf<JSONObject>();val saved=mutableListOf<String>()
        val sender=VelaCardReplicaSender({if(it.isEmpty())listOf(raw) else emptyList()},documentMode=true,
            saveProgress={saved+=it},send={sent+=JSONObject(it.toString(Charsets.UTF_8));true})
        sender.tick(1);val first=sent.single()
        fun reply(state:String)=JSONObject().put("type","buffer.crdt.reply").put("protocol",1)
            .put("record_sha256",first.getString("record_sha256")).put("next_offset",first.getInt("total"))
            .put("state",state).toString().toByteArray()
        sender.acknowledge(reply("busy"),2);assertTrue(saved.isEmpty())
        sender.tick(1001);assertEquals(1,sent.size)
        sender.tick(1002);assertEquals(2,sent.size);assertEquals(first.toString(),sent.last().toString())
        assertTrue(saved.isEmpty());sender.acknowledge(reply("stored"),1003)
        assertEquals(listOf("calendar-one"),saved);sender.close()
    }

    @Test fun currentCalendarTransfersBeforeLegacyCleanupWithoutInterleavingFragments() {
        fun raw(id: String, deleted: Boolean=false, text: String="")=JSONObject().put("card_id",id).put("deleted",deleted).put("text",text).toString()
        val old=raw("a_legacy",true);val calendar=raw("b_calendar",text="x".repeat(7000));val task=raw("c_task")
        val records=listOf(old,calendar,task);val sent=mutableListOf<JSONObject>();val normalProgress=mutableListOf<String>();val priorityProgress=mutableListOf<String>()
        var finished=false
        val sender=VelaCardReplicaSender({after->records.filter{JSONObject(it).getString("card_id")>after}},documentMode=true,
            saveProgress={normalProgress+=it},syncFinished={_,complete->assertTrue(complete);finished=true},
            priorityPage={after->listOf(calendar).filter{JSONObject(it).getString("card_id")>after}},
            savePriorityProgress={priorityProgress+=it},send={sent+=JSONObject(it.toString(Charsets.UTF_8));true})
        sender.requestSync(JSONObject().put("type","buffer.sync.request").put("protocol",1).put("request_id","d".repeat(32)).toString().toByteArray(),1)
        var activeHash="";var offset=0;val assembled=java.io.ByteArrayOutputStream();val delivered=mutableListOf<String>()
        for(now in 1L..40L){
            val count=sent.size;sender.tick(now)
            if(sent.size==count)continue
            val part=sent.last()
            if(part.getString("type")=="buffer.sync.reply"){assertTrue(finished);continue}
            val hash=part.getString("record_sha256")
            if(activeHash.isBlank()){activeHash=hash;offset=0;assembled.reset()}
            assertEquals(activeHash,hash);assertEquals(offset,part.getInt("offset"))
            val bytes=Base64.getDecoder().decode(part.getString("record_base64"));assembled.write(bytes);offset+=bytes.size
            val complete=offset==part.getInt("total")
            if(complete){delivered+=JSONObject(assembled.toString("UTF-8")).getString("card_id");activeHash=""}
            sender.acknowledge(JSONObject().put("type","buffer.crdt.reply").put("protocol",1).put("record_sha256",hash)
                .put("next_offset",offset).put("state",if(complete)"stored" else "partial").toString().toByteArray(),now)
            if(delivered==listOf("b_calendar"))assertFalse(finished)
            if(delivered.contains("a_legacy"))assertFalse(sender.prioritizingCurrentCalendar)
            if(finished)break
        }
        assertEquals(listOf("b_calendar","a_legacy","c_task"),delivered)
        assertEquals(listOf("b_calendar"),priorityProgress);assertEquals(listOf("a_legacy","c_task"),normalProgress)
        assertTrue(finished);assertFalse(sender.prioritizingCurrentCalendar);sender.close()
    }

    @Test fun resumedCalendarPassVisitsTailThenPrefixBeforeNormalScan() {
        val sent=mutableListOf<JSONObject>();val finished=mutableListOf<String>()
        val records=listOf("b_calendar","c_calendar").map{JSONObject().put("card_id",it).toString()}
        val sender=VelaCardReplicaSender({emptyList()},documentMode=true,
            priorityPage={after->records.filter{JSONObject(it).getString("card_id")>after}},priorityResumeAfter="b_calendar",
            savePriorityProgress={finished+=it},send={sent+=JSONObject(it.toString(Charsets.UTF_8));true})
        for(now in 1L..12L){
            val n=sent.size;sender.tick(now);if(sent.size==n)continue
            val part=sent.last();sender.acknowledge(JSONObject().put("type","buffer.crdt.reply").put("protocol",1)
                .put("record_sha256",part.getString("record_sha256")).put("next_offset",part.getInt("total"))
                .put("state","stored").toString().toByteArray(),now)
        }
        assertEquals(listOf("c_calendar","b_calendar"),finished);sender.close()
    }

    @Test fun calendarReadyOnlyAfterReplicaReceiptAndCompletionSend() {
        val sent = mutableListOf<JSONObject>();val finished = mutableListOf<String>()
        val raw = JSONObject().put("card_id", "cal_series_one").put("deleted", false).toString()
        val sender = VelaCardReplicaSender({ if (it.isEmpty()) listOf(raw) else emptyList() }, documentMode = true,
            syncFinished = { id, complete -> assertTrue(complete);finished += id },
            send = { sent += JSONObject(it.toString(Charsets.UTF_8));true })
        val id = "a".repeat(32)
        sender.requestSync(JSONObject().put("type", "buffer.sync.request").put("protocol", 1).put("request_id", id).toString().toByteArray(), 1)
        sender.tick(1);assertTrue(finished.isEmpty())
        val part = sent.single()
        sender.acknowledge(JSONObject().put("type", "buffer.crdt.reply").put("protocol", 1)
            .put("record_sha256", part.getString("record_sha256")).put("next_offset", part.getInt("total")).put("state", "stored").toString().toByteArray(), 2)
        assertTrue(finished.isEmpty());sender.tick(3);assertTrue(finished.isEmpty())
        sender.tick(4);assertEquals(listOf(id), finished);assertEquals("buffer.sync.reply", sent.last().getString("type"))
        sender.close()
    }

    @Test fun unshareableRecordDoesNotBlockLaterCalendarDocuments() {
        val records = listOf("a_private", "b_calendar", "c_calendar").map {
            JSONObject().put("card_id", it).put("deleted", false).toString()
        }
        val sent = mutableListOf<JSONObject>()
        val sender = VelaCardReplicaSender(
            { after -> records.filter { JSONObject(it).getString("card_id") > after }.take(2) },
            documentMode = true,
            authorizeSnapshot = { JSONObject(it.toString(Charsets.UTF_8)).getString("card_id") != "a_private" },
        ) { sent += JSONObject(it.toString(Charsets.UTF_8)); true }
        sender.requestSync(JSONObject().put("type", "buffer.sync.request").put("protocol", 1)
            .put("request_id", "e".repeat(32)).toString().toByteArray(), 1)
        val delivered = mutableListOf<String>()
        for (now in 1L..12L) {
            val before = sent.size
            sender.tick(now)
            if (sent.size == before || sent.last().getString("type") != "buffer.crdt.part") continue
            val part = sent.last()
            val raw = Base64.getDecoder().decode(part.getString("record_base64"))
            delivered += JSONObject(raw.toString(Charsets.UTF_8)).getString("card_id")
            sender.acknowledge(JSONObject().put("type", "buffer.crdt.reply").put("protocol", 1)
                .put("record_sha256", part.getString("record_sha256"))
                .put("next_offset", part.getInt("total")).put("state", "stored").toString().toByteArray(), now)
        }
        assertEquals(listOf("b_calendar", "c_calendar"), delivered)
        assertEquals("complete", sent.last().getString("state"))
        sender.close()
    }

    @Test fun snapshotsSurviveReopenAndDeletionRemainsAnExplicitTombstone() {
        val cards = (0 until 12).map { repo.createTextCapture("Replica test $it", "normal") }
        fun all(): Map<String, String> {
            val records = linkedMapOf<String, String>()
            var after = ""
            while (true) {
                val page = repo.velaReplicaPage(after)
                if (page.isEmpty()) break
                page.forEach { records[JSONObject(it).getString("card_id")] = it }
                after = records.keys.last()
            }
            return records
        }
        val initial = all()
        assertEquals(cards.map { it.cardId }.toSet(), initial.keys)
        assertEquals(12, initial.size)
        repo.close(); open()
        assertEquals(initial, all())
        val edited = cards.first()
        repo.editCardContent(edited, "Edited title", "Edited summary")
        val changed = JSONObject(requireNotNull(all()[edited.cardId]))
        assertEquals("Edited summary", changed.getJSONObject("card").getString("summary"))
        assertTrue(changed.getLong("revision") > JSONObject(requireNotNull(initial[edited.cardId])).getLong("revision"))
        repo.deleteCards(listOf(edited.cardId))
        repo.close(); open()
        val tombstone = JSONObject(requireNotNull(all()[edited.cardId]))
        assertTrue(tombstone.getBoolean("deleted"))
        assertFalse(tombstone.has("card"))
    }

    @Test fun fragmentedSnapshotRetriesIdenticallyAndOnlyAdvancesAfterMatchingAck() {
        val raw = JSONObject().put("card_id", "phone_test").put("revision", 1)
            .put("deleted", false).put("card", JSONObject().put("summary", "汉字".repeat(3000))).toString()
        val sent = mutableListOf<ByteArray>()
        val sender = VelaCardReplicaSender({ if (it.isEmpty()) listOf(raw) else emptyList() }) {
            sent += it.copyOf(); true
        }
        sender.tick(1000)
        sender.tick(6000)
        assertEquals(1, sent.size)
        sender.tick(31_000)
        assertArrayEquals(sent[0], sent[1])
        var assembled = byteArrayOf()
        var now = 31_000L
        while (assembled.size < raw.toByteArray().size) {
            val part = JSONObject(sent.last().toString(Charsets.UTF_8))
            assertTrue(sent.last().size <= 12 * 1024)
            assertEquals(assembled.size, part.getInt("offset"))
            assembled += Base64.getDecoder().decode(part.getString("record_base64"))
            val reply = JSONObject().put("type", "buffer.card.replica.reply").put("protocol", 1)
                .put("record_sha256", part.getString("record_sha256"))
                .put("next_offset", assembled.size)
                .put("state", if (assembled.size == part.getInt("total")) "stored" else "partial")
                .toString().toByteArray()
            sender.acknowledge(reply, now)
            now++
            sender.tick(now)
        }
        assertArrayEquals(raw.toByteArray(), assembled)
        assertEquals(VelaProcessedCaptureProtocol.fingerprint(raw), JSONObject(sent.first().toString(Charsets.UTF_8)).getString("record_sha256"))
        sender.close()
    }

    @Test fun manualSyncRequiresReceiptsAndDuplicateRequestsDoNotRewind() {
        val raw = JSONObject().put("card_id", "sync_note").put("revision", 1).put("deleted", false).toString()
        val sent = mutableListOf<JSONObject>()
        var accept = true
        val sender = VelaCardReplicaSender({ if (it.isEmpty()) listOf(raw) else emptyList() }) {
            if (accept) sent += JSONObject(it.toString(Charsets.UTF_8))
            accept
        }
        fun request(id: String) = JSONObject().put("type", "buffer.sync.request").put("protocol", 1)
            .put("request_id", id).toString().toByteArray()
        fun ack(now: Long) {
            val part = sent.last()
            sender.acknowledge(JSONObject().put("type", "buffer.card.replica.reply").put("protocol", 1)
                .put("record_sha256", part.getString("record_sha256"))
                .put("next_offset", part.getInt("total")).put("state", "stored").toString().toByteArray(), now)
        }
        val id = "a".repeat(32)
        sender.requestSync(request(id), 1); sender.tick(1)
        sender.requestSync(request(id), 2); sender.tick(2)
        assertEquals(1, sent.size)
        assertFalse(sent.any { it.optString("state") == "complete" })
        ack(3); sender.tick(3)
        accept = false; sender.tick(4)
        assertEquals(1, sent.size)
        accept = true; sender.tick(5)
        assertEquals("complete", sent.last().getString("state"))
        assertEquals(id, sent.last().getString("request_id"))
        sender.requestSync(request(id), 6); sender.tick(6)
        assertEquals("buffer.sync.reply", sent.last().getString("type"))
        sender.requestSync(request("b".repeat(32)), 7); sender.tick(7)
        assertEquals("buffer.card.replica.part", sent.last().getString("type"))
        ack(8); sender.tick(8); sender.tick(9)
        assertEquals("b".repeat(32), sent.last().getString("request_id"))
        sender.close()
    }

    @Test fun manualSyncPreservesInFlightFragmentsAndRejectsInvalidRequest() {
        val raw = JSONObject().put("card_id", "sync_large").put("summary", "汉".repeat(2000)).toString()
        val sent = mutableListOf<JSONObject>()
        val sender = VelaCardReplicaSender({ if (it.isEmpty()) listOf(raw) else emptyList() }) {
            sent += JSONObject(it.toString(Charsets.UTF_8)); true
        }
        sender.tick(1)
        val first = sent.single()
        sender.requestSync(JSONObject().put("type", "buffer.sync.request").put("protocol", 1)
            .put("request_id", "c".repeat(32)).toString().toByteArray(), 2)
        sender.acknowledge(JSONObject().put("type", "buffer.card.replica.reply").put("protocol", 1)
            .put("record_sha256", first.getString("record_sha256"))
            .put("next_offset", 3072).put("state", "partial").toString().toByteArray(), 3)
        sender.tick(3)
        assertEquals(3072, sent.last().getInt("offset"))
        assertThrows(IllegalArgumentException::class.java) {
            sender.requestSync(JSONObject().put("type", "buffer.sync.request").put("protocol", 1)
                .put("request_id", "invalid").toString().toByteArray(), 4)
        }
        sender.close()
    }

    @Test fun newerDeviceRevisionIsReportedAsConflictInsteadOfSuccessfulConvergence() {
        val raw = JSONObject().put("card_id", "conflict_note").toString()
        val sent = mutableListOf<JSONObject>()
        val sender = VelaCardReplicaSender({ if (it.isEmpty()) listOf(raw) else emptyList() }) {
            sent += JSONObject(it.toString(Charsets.UTF_8)); true
        }
        sender.requestSync(JSONObject().put("type", "buffer.sync.request").put("protocol", 1)
            .put("request_id", "d".repeat(32)).toString().toByteArray(), 1)
        sender.tick(1)
        val part = sent.single()
        sender.acknowledge(JSONObject().put("type", "buffer.card.replica.reply").put("protocol", 1)
            .put("record_sha256", part.getString("record_sha256"))
            .put("next_offset", part.getInt("total")).put("state", "stale").toString().toByteArray(), 2)
        sender.tick(2); sender.tick(3)
        assertEquals("conflict", sent.last().getString("state"))
        sender.close()
    }

    @Test fun deviceActionsHaveDurableReceiptsAndCannotRestoreDeletedCards() {
        val card = repo.createTextCapture("Remember a prototype idea", "normal")
        val request = JSONObject().put("type", "buffer.card.action").put("protocol", 1)
            .put("card_id", card.cardId).put("action_id", "vela-action-test-1").put("action", "archive")
            .toString().toByteArray()
        assertEquals("applied", JSONObject(VelaCardActionProtocol.apply(request, repo).toString(Charsets.UTF_8)).getString("state"))
        repo.close(); open()
        assertEquals("applied", JSONObject(VelaCardActionProtocol.apply(request, repo).toString(Charsets.UTF_8)).getString("state"))
        assertEquals("archived", repo.findCard(card.cardId)?.state)
        repo.writableDatabase.rawQuery("SELECT count(*) FROM action_receipts WHERE action_id=?", arrayOf("vela-action-test-1")).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        repo.deleteCards(listOf(card.cardId))
        assertEquals("absent", JSONObject(VelaCardActionProtocol.apply(request, repo).toString(Charsets.UTF_8)).getString("state"))
        assertNull(repo.findCard(card.cardId))
    }
    @Test fun reconnectContinuesAfterConfirmedRecordAndFullPassWrapsBeforeCompletion() {
        val peer = "a".repeat(64)
        val records = (0 until 12).map { JSONObject().put("card_id", "cal_%02d".format(it)).toString() }
        val delivered = mutableSetOf<String>()
        fun sender(sent: MutableList<JSONObject>) = VelaCardReplicaSender(
            page = { after -> records.filter { JSONObject(it).getString("card_id") > after }.take(4) },
            documentMode = true, resumeAfter = repo.velaReplicaResume(peer),
            saveProgress = { repo.saveVelaReplicaResume(peer,it) },
        ) { sent += JSONObject(it.toString(Charsets.UTF_8)); true }
        fun acknowledge(sender: VelaCardReplicaSender, part: JSONObject, now: Long): String {
            val id = JSONObject(String(Base64.getDecoder().decode(part.getString("record_base64")), Charsets.UTF_8)).getString("card_id")
            sender.acknowledge(JSONObject().put("type", "buffer.crdt.reply").put("protocol", 1)
                .put("record_sha256",part.getString("record_sha256")).put("next_offset",part.getInt("total"))
                .put("state","stored").toString().toByteArray(),now)
            return id
        }
        repeat(6) {
            val sent = mutableListOf<JSONObject>();val tx = sender(sent)
            for (now in 1L..2L) { tx.tick(now); delivered += acknowledge(tx,sent.last(),now) }
            tx.close();repo.close();open()
        }
        assertEquals(12,delivered.size)
        assertEquals("",repo.velaReplicaResume("b".repeat(64)))
        repo.saveVelaReplicaResume(peer,"cal_05")
        val sent = mutableListOf<JSONObject>();val tx = sender(sent);val pass = mutableListOf<String>()
        tx.requestSync(JSONObject().put("type","buffer.sync.request").put("protocol",1)
            .put("request_id","f".repeat(32)).toString().toByteArray(),1)
        var complete = false
        for(now in 1L..40L) {
            val before=sent.size;tx.tick(now)
            if(sent.size==before)continue
            val message=sent.last()
            if(message.getString("type")=="buffer.crdt.part")pass+=acknowledge(tx,message,now)
            else { assertEquals(12,pass.size);complete=true;break }
        }
        assertTrue(complete)
        assertEquals("cal_06",pass.first());assertEquals("cal_05",pass.last())
        assertEquals(12,pass.toSet().size);tx.close()
    }

}
