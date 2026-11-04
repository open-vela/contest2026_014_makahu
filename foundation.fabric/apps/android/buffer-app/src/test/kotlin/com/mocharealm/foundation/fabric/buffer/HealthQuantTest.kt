package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import androidx.health.connect.client.records.NutritionRecord
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferHealthQuantExtractor
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.system.HealthConnectExporter
import com.mocharealm.foundation.fabric.buffer.domain.*
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class HealthQuantTest {
    private lateinit var repo:BufferRepository
    private val now=System.currentTimeMillis()
    private val date=LocalDate.now()
    private val zone=ZoneId.systemDefault().id
    @Before fun open(){repo=BufferRepository(RuntimeEnvironment.getApplication())}
    @After fun close(){repo.close()}
    private fun confirmed(text:String="我今天午饭吃了200克米饭",category:String="nutrition"):BufferCard {
        val card=repo.createTextCapture(text)
        return requireNotNull(repo.reviewClassification(requireNotNull(repo.findCard(card.cardId)),ClassificationDecision.EDIT,"care_card",category))
    }
    private fun meal(estimated:Boolean=true)=HealthQuant(date.toString(),zone,listOf(HealthQuant.Entry("米饭",200.0,"g",if(estimated)"estimated" else "reported",
        if(estimated)"按用户报告的200克熟米饭估算，实际烹饪可能不同" else "包装标示",
        mapOf("energy_kcal" to 240.0,"protein_g" to 5.0,"carbs_g" to 52.0,"fat_g" to 0.6))))
    @Test fun voiceTranscriptToModelProposalToConfirmedQuantitiesPreservesOriginalAudio() {
        val bytes=byteArrayOf(1,3,7,9)
        val card=repo.createAudioCapture("voice-meal","android",now,"normal","audio/wav",1000,bytes,localOriginal=true)
        val transcript="我今天午饭吃了200克米饭"
        val transcribed=repo.completeAudioTranscription(requireNotNull(card.captureId),transcript)
        repo.reviewClassification(requireNotNull(repo.findCard(transcribed.cardId)),ClassificationDecision.EDIT,"care_card","nutrition")
        val job=requireNotNull(repo.claimHealthQuant())
        assertEquals(transcript,JSONObject(job.input).getString("transcript"))
        val response=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop")
            .put("message",JSONObject().put("content",meal().json().toString())))).toString().toByteArray()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val requests=mutableListOf<JSONObject>()
        server.createContext("/v1/chat/completions") { exchange ->
            requests+=JSONObject(exchange.requestBody.bufferedReader().use {it.readText()})
            exchange.sendResponseHeaders(200,response.size.toLong());exchange.responseBody.use {it.write(response)};exchange.close()
        }
        server.start()
        try {
            val result=BufferHealthQuantExtractor.extract(BufferLlmConfig("http://127.0.0.1:${server.address.port}/v1","test-model","test-key"),job)
            assertTrue(repo.completeHealthQuant(job,result));assertFalse(repo.completeHealthQuant(job,result))
        }finally{server.stop(0)}
        assertEquals(1,requests.size)
        assertTrue(repo.healthQuantTotals(date,date).isEmpty())
        val proposal=requireNotNull(repo.healthQuantRecord(card.cardId));assertEquals("proposed",proposal.state)
        repo.confirmHealthQuant(card.cardId,proposal.revision,proposal.data)
        assertEquals(240.0,repo.healthQuantTotals(date,date).single {it.metric=="energy_kcal"}.estimated!!,0.0)
        val capture=requireNotNull(repo.findCapture(requireNotNull(card.captureId)))
        assertEquals(transcript,capture.transcript);assertArrayEquals(bytes,File(requireNotNull(capture.blobPath)).readBytes())
    }
    @Test fun unknownQuantitiesAreAbsentAndEstimatesNeverMergeWithReported() {
        val first=confirmed();val second=confirmed("午饭包装标示240千卡")
        repo.confirmHealthQuant(first.cardId,0,meal());repo.confirmHealthQuant(second.cardId,0,meal(false))
        val energy=repo.healthQuantTotals(date,date).single{it.metric=="energy_kcal"}
        assertEquals(240.0,energy.estimated!!,0.0);assertEquals(240.0,energy.reported!!,0.0)
        assertFalse(repo.healthQuantTotals(date,date).any {it.metric=="water_ml"})
        val facts=repo.weeklyLlmFacts(now).getJSONObject("health_quantified")
        assertEquals(2,facts.getJSONArray("source_card_ids").length())
        assertTrue(facts.getJSONArray("totals").length()>0)
    }
    @Test fun staleModelAndUserEditsCannotOverwriteConfirmedData() {
        val card=confirmed();val job=requireNotNull(repo.claimHealthQuant())
        repo.confirmHealthQuant(card.cardId,0,meal(false))
        assertFalse(repo.completeHealthQuant(job,meal()))
        assertThrows(IllegalArgumentException::class.java){repo.confirmHealthQuant(card.cardId,0,meal())}
        assertEquals("confirmed",repo.healthQuantRecord(card.cardId)?.state)
    }
    @Test fun expiredLeaseAndInputChangesRejectModelResult() {
        val card=confirmed();val job=requireNotNull(repo.claimHealthQuant(now))
        assertFalse(repo.completeHealthQuant(job,meal(),now+300001))
        val next=requireNotNull(repo.claimHealthQuant(now+300001))
        assertFalse(repo.completeHealthQuant(job,meal(),now+300002))
        repo.writableDatabase.execSQL("UPDATE captures SET transcript='用户已更正的午饭' WHERE capture_id=?",arrayOf(card.captureId))
        assertFalse(repo.completeHealthQuant(next,meal(),now+300002))
    }
    @Test fun retractionPersistsAndExcludesTotalsWithoutDeletingSource() {
        val card=confirmed();repo.confirmHealthQuant(card.cardId,0,meal());repo.retractHealthQuant(card.cardId,1)
        repo.close();repo=BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals("retracted",repo.healthQuantRecord(card.cardId)?.state);assertTrue(repo.healthQuantTotals(date,date).isEmpty())
        assertNotNull(repo.findCapture(requireNotNull(card.captureId)));assertNull(repo.claimHealthQuant())
    }
    @Test fun transactionFailureRollsBackAndAllowsRetry() {
        val card=confirmed()
        repo.writableDatabase.execSQL("CREATE TRIGGER fail_health BEFORE INSERT ON events WHEN NEW.type='HealthQuantRecorded' BEGIN SELECT RAISE(ABORT,'injected');END")
        assertThrows(android.database.sqlite.SQLiteException::class.java){repo.confirmHealthQuant(card.cardId,0,meal())}
        assertNull(repo.healthQuantRecord(card.cardId))
        repo.writableDatabase.execSQL("DROP TRIGGER fail_health");repo.confirmHealthQuant(card.cardId,0,meal())
        assertEquals(1L,repo.healthQuantRecord(card.cardId)?.revision)
    }
    @Test fun forgedProjectionCannotAuthorizeModelOrBecomeConfirmedFact() {
        val card=repo.createTextCapture("我吃了米饭")
        repo.writableDatabase.execSQL("UPDATE cards SET kind='care_card',health_category='nutrition',classification_confirmed=1 WHERE card_id=?",arrayOf(card.cardId))
        assertNull(repo.claimHealthQuant())
        val approved=confirmed();repo.confirmHealthQuant(approved.cardId,0,meal())
        repo.writableDatabase.execSQL("UPDATE health_quant_records SET body=? WHERE card_id=?",arrayOf(meal(false).json().toString(),approved.cardId))
        assertEquals("estimated",repo.healthQuantRecord(approved.cardId)?.data?.entries?.single()?.basis)
        assertEquals(240.0,repo.healthQuantTotals(date,date).single{it.metric=="energy_kcal"}.estimated!!,0.0)
    }
    @Test fun badUnitsNumbersAndUnknownPortionsRejectFictionalNutrition() {
        val raw=meal().json();raw.getJSONArray("entries").getJSONObject(0).put("amount",JSONObject.NULL)
        assertThrows(IllegalArgumentException::class.java){HealthQuant.parse(raw.toString())}
        val wrong=meal().json();wrong.getJSONArray("entries").getJSONObject(0).getJSONObject("values").put("energy_kcal","240")
        assertThrows(IllegalArgumentException::class.java){HealthQuant.parse(wrong.toString())}
        val unknown=HealthQuant(date.toString(),zone,listOf(HealthQuant.Entry("米饭",null,"","reported","未说明份量",emptyMap())))
        assertTrue(HealthQuant.parse(unknown.json().toString()).entries.single().values.isEmpty())
    }
    @Test fun quantifiedNutritionExportsActualMacrosAndMarksEstimates() {
        val card=confirmed();val exporter=HealthConnectExporter(RuntimeEnvironment.getApplication())
        val record=exporter.createRecord(card,"nutrition",meal()) as NutritionRecord
        assertEquals(240.0,record.energy!!.inKilocalories,0.0)
        assertEquals(5.0,record.protein!!.inGrams,0.0);assertEquals(52.0,record.totalCarbohydrate!!.inGrams,0.0)
        assertEquals(0.6,record.totalFat!!.inGrams,0.0);assertTrue(record.name!!.contains("含估算"))
        assertNull(exporter.createRecord(card,"nutrition"))
    }
    @Test fun incompleteMealIsReportedAsSubtotalInsteadOfInventingZero() {
        val card=confirmed()
        val data=meal().copy(entries=meal().entries+HealthQuant.Entry("青菜",null,"","reported","份量未记录",emptyMap()))
        repo.confirmHealthQuant(card.cardId,0,data)
        val energy=repo.healthQuantTotals(date,date).single { it.metric=="energy_kcal" }
        assertEquals(240.0,energy.estimated!!,0.0);assertEquals(1,energy.missingEntries)
    }
    @Test fun version62UpgradeRetainsExistingResearchAppointmentAndCaptures() {
        val card=repo.createTextCapture("探索一个课题")
        val plan=repo.saveResearchWeeklyPlan(card.cardId,7,"14:00",90)
        repo.writableDatabase.execSQL("DROP TABLE health_quant_records")
        repo.writableDatabase.execSQL("DROP TABLE health_quant_jobs")
        repo.writableDatabase.version=62;repo.close();repo=BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals(64,repo.writableDatabase.version)
        assertEquals(plan.id,repo.researchWeeklyPlans().single().id);assertNotNull(repo.findCard(card.cardId))
        assertNull(repo.healthQuantRecord(card.cardId))
    }
    @Test fun quantifiedDatesAndWeightUseRecordedDayAndLatestValue() {
        val a=confirmed("我昨天睡了八小时","sleep")
        repo.confirmHealthQuant(a.cardId,0,HealthQuant(date.minusDays(1).toString(),zone,listOf(HealthQuant.Entry("睡眠",480.0,"分钟","reported","",mapOf("sleep_min" to 480.0)))))
        assertTrue(repo.healthQuantTotals(date,date).isEmpty())
        assertEquals(480.0,repo.healthQuantTotals(date.minusDays(6),date).single().reported!!,0.0)
    }
    @Test fun deletedAndStaleQuantCachesRecoverExactConfirmationWithoutNewModelWork() {
        val card=confirmed();repo.confirmHealthQuant(card.cardId,0,meal())
        val before=repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM events",null).use {it.moveToFirst();it.getLong(0)}
        repo.writableDatabase.execSQL("DELETE FROM health_quant_records")
        repo.close();repo=BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals(240.0,repo.healthQuantTotals(date,date).single{it.metric=="energy_kcal"}.estimated!!,0.0)
        assertNull(repo.claimHealthQuant())
        assertEquals(1L,repo.healthQuantRecord(card.cardId)?.revision)
        repo.retractHealthQuant(card.cardId,1)
        repo.writableDatabase.execSQL("UPDATE health_quant_records SET revision=1,state='confirmed'")
        assertEquals("retracted",repo.healthQuantRecord(card.cardId)?.state)
        assertTrue(repo.healthQuantTotals(date,date).isEmpty());assertNull(repo.claimHealthQuant())
        val after=repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM events",null).use {it.moveToFirst();it.getLong(0)}
        assertEquals(before+1,after)
    }
    @Test fun cacheRecoveryNeverAcceptsCorruptedOriginal() {
        val card=confirmed();repo.confirmHealthQuant(card.cardId,0,meal())
        repo.writableDatabase.execSQL("UPDATE source_events SET fingerprint=? WHERE event_id IN (SELECT event_id FROM health_quant_records)",arrayOf("0".repeat(64)))
        repo.writableDatabase.execSQL("DELETE FROM health_quant_records")
        assertThrows(IllegalStateException::class.java){repo.healthQuantRecord(card.cardId)}
        assertThrows(IllegalStateException::class.java){repo.healthQuantTotals(date,date)}
        assertThrows(IllegalStateException::class.java){repo.claimHealthQuant()}
    }

    @Test fun remoteConfirmationArrivingDuringModelCallRejectsLateProposal() {
        val card=confirmed();val job=requireNotNull(repo.claimHealthQuant())
        val event=JSONObject().put("event_id","remote-meal").put("app_namespace","app.buffer").put("actor_id","user")
            .put("schema_version",1).put("parent_event_ids",JSONArray()).put("logical_clock",1000)
            .put("type","HealthQuantRecorded").put("capture_id",card.captureId).put("created_at",now)
            .put("payload",JSONObject().put("card_id",card.cardId).put("revision",1).put("state","confirmed").put("data",meal(false).json()))
        val record=BufferSourceEvent.encode("remote-meals",1,event)
        repo.importSourceEvent("b".repeat(64),record.raw){true}
        assertFalse(repo.completeHealthQuant(job,meal()))
        assertEquals(240.0,repo.healthQuantTotals(date,date).single{it.metric=="energy_kcal"}.reported!!,0.0)
        assertNull(repo.claimHealthQuant())
        // A concurrent proposal from another source, even with a larger clock,
        // is not allowed to hide a user's already confirmed measurement.
        val proposal=JSONObject(event.toString()).put("event_id","concurrent-proposal").put("logical_clock",2000)
        proposal.getJSONObject("payload").put("state","proposed")
        repo.importSourceEvent("c".repeat(64),BufferSourceEvent.encode("other-meals",1,proposal).raw){true}
        assertEquals("confirmed",repo.healthQuantView(card.cardId)?.record?.state)
        assertEquals(240.0,repo.healthQuantTotals(date,date).single{it.metric=="energy_kcal"}.reported!!,0.0)
    }

    @Test fun version63UpgradeRetainsConfirmedOriginalsAndCreatesSelectiveFactIndex() {
        val card=confirmed();repo.confirmHealthQuant(card.cardId,0,meal())
        repo.writableDatabase.execSQL("DROP INDEX source_health_quant_facts")
        repo.writableDatabase.version=63;repo.close();repo=BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals(64,repo.writableDatabase.version)
        assertTrue(repo.readableDatabase.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name='source_health_quant_facts'",null).use{it.moveToFirst()})
        assertEquals(1L,repo.healthQuantRecord(card.cardId)?.revision)
        assertNotNull(repo.healthQuantForExport(card.cardId))
        assertEquals(240.0,repo.healthQuantTotals(date,date).single{it.metric=="energy_kcal"}.estimated!!,0.0)
        assertNull(repo.claimHealthQuant())
    }

}
