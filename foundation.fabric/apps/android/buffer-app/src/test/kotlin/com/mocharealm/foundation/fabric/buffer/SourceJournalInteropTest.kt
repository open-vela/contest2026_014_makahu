package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.HealthQuant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/** Run with tests/interop/android.init.gradle and -PbufferSourceInterop=<directory>.
 * The native contract consumes these actual Android originals and exports its
 * own actual journal plus WAV. A second run verifies native -> Android too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class SourceJournalInteropTest {
    @Test fun actualRuntimeOriginalsRemainExactAndHistoryNeverBecomesExecution() {
        val root=System.getProperty("buffer.sourceInterop")?.let(::File)
            ?: File(RuntimeEnvironment.getApplication().cacheDir,"source-journal-interop")
        root.mkdirs()
        val repository=BufferRepository(RuntimeEnvironment.getApplication())
        try {
            repository.finishSourceRecovery()
            val card=repository.createTextCapture("我今天午饭吃了200克米饭")
            repository.reviewClassification(requireNotNull(repository.findCard(card.cardId)),ClassificationDecision.EDIT,"care_card","nutrition")
            val data=HealthQuant(LocalDate.now().toString(),ZoneId.systemDefault().id,listOf(
                HealthQuant.Entry("米饭",200.0,"g","estimated","按熟米饭估算",mapOf("energy_kcal" to 240.0))))
            repository.confirmHealthQuant(card.cardId,0,data)
            val exported=File(root,"android").apply {mkdirs()}
            val entries=JSONArray();var after=0L
            while(true) {
                val page=repository.sourceEventPage(after,8){true}
                if(page.isEmpty())break
                page.forEach { raw ->
                    val record=BufferSourceEvent.decode(raw)
                    val name="${record.sequence}.json"
                    File(exported,name).writeText(raw,Charsets.UTF_8)
                    entries.put(JSONObject().put("file",name).put("sha256",record.fingerprint))
                    after=record.sequence
                }
            }
            assertTrue(entries.length()>0)
            assertNull(repository.claimHealthQuant())
            assertEquals(240.0,repository.healthQuantTotals(LocalDate.now(),LocalDate.now()).single{it.metric=="energy_kcal"}.estimated!!,0.0)
            File(exported,"manifest.json").writeText(JSONObject().put("events",entries).toString())
            val manifest=File(root,"native/manifest.json")
            if(!manifest.isFile) {
                check(System.getProperty("buffer.sourceInteropRequireNative")!="true") { "native export is required" }
                File(root,"android-export.json").writeText(JSONObject().put("count",entries.length()).toString())
                return
            }
            val before=repository.readableDatabase.rawQuery("SELECT COUNT(*) FROM events",null).use {it.moveToFirst();it.getLong(0)}
            val native=JSONObject(manifest.readText());val originals=native.getJSONArray("events")
            val peer="c".repeat(64)
            for(i in 0 until originals.length()) {
                val entry=originals.getJSONObject(i)
                val bytes=File(manifest.parentFile,entry.getString("file")).readBytes()
                val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                assertEquals(entry.getString("sha256"),hash)
                val raw=bytes.toString(Charsets.UTF_8);val original=BufferSourceEvent.decode(raw)
                assertEquals("stored",repository.importSourceEvent(peer,raw){true}.state)
                assertEquals("duplicate",repository.importSourceEvent(peer,raw){true}.state)
                assertEquals(raw,repository.receivedSourceEvent(peer,original.source,original.sequence){true}.raw)
            }
            repository.finishSourceRecovery()
            val candidate=requireNotNull(repository.nextSourceBlob(peer,System.currentTimeMillis(),null){true})
            assertFalse(repository.resumeSourceBlob(peer,candidate.transfer){true})
            val audio=File(manifest.parentFile,native.getString("audio_file")).readBytes()
            repository.storeSourceBlob(peer,candidate.transfer,audio){true}
            repository.confirmSourceBlob(peer,candidate.transfer){true}
            assertTrue(repository.resumeSourceBlob(peer,candidate.transfer){true})
            assertEquals("normal",repository.mode());assertTrue(repository.pendingAudioCaptures().isEmpty())
            assertNull(repository.claimHealthQuant())
            val afterImport=repository.readableDatabase.rawQuery("SELECT COUNT(*) FROM events",null).use {it.moveToFirst();it.getLong(0)}
            assertEquals(before,afterImport)
            File(root,"android-received.json").writeText(JSONObject().put("native_events",originals.length())
                .put("blob_hash",candidate.transfer.blobHash).put("native_facts_before",before).put("native_facts_after",afterImport).toString())
        } finally {repository.close()}
    }
}
