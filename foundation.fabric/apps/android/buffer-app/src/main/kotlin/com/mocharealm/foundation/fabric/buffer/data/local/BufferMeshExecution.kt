package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.coordinator.*
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationCodec
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferAnswerCodec
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyCodec
import org.json.JSONObject

internal data class MeshExecutionLease(val scope: String, val election: BufferCoordinatorElection, val lease: CoordinatorLease)

/** Only locally computed results are cached. A fresh authenticated assignment
 * and current lease are still required to reuse one; no lease is restored. */
internal object BufferMeshExecution {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS mesh_task_execution_results (job_id TEXT PRIMARY KEY,contract TEXT NOT NULL,event_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS mesh_task_execution_failures (lease_id TEXT PRIMARY KEY,contract TEXT NOT NULL,event_id TEXT NOT NULL)")
    }
    fun result(db: SQLiteDatabase, contract: JSONObject): JSONObject? {
        check(db.inTransaction()); BufferMeshTaskContract.validate(contract)
        return db.rawQuery("SELECT contract,event_id FROM mesh_task_execution_results WHERE job_id=?", arrayOf(contract.getString("job_id"))).use {
            if (!it.moveToFirst()) return@use null
            check(it.getString(0) == BufferCrdt.canonical(contract))
            val event = BufferEventJournal.localRecord(db, it.getString(1)).event
            val p = event.getJSONObject("payload")
            check(event.getString("type") == "MeshTaskExecuted" && BufferCrdt.canonical(p.getJSONObject("contract")) == BufferCrdt.canonical(contract))
            val result = p.getJSONObject("result"); validateResult(contract, result, localComputation = true)
            check(p.getString("result_sha256") == BufferMeshTaskContract.digest(result))
            result
        }
    }
    fun validateResult(contract: JSONObject, value: JSONObject, localComputation: Boolean = false) {
        BufferMeshTaskContract.validate(contract)
        when (contract.getString("stage")) {
            "classify" -> if (BufferClassificationCodec.assistantDecision(value)) require(contract.get("skill_version") == "buffer-classify-v2")
                else BufferClassificationCodec.validateStructure(value)
            "answer" -> BufferAnswerCodec.decode(value)
            "generate" -> require(BufferCrdt.canonical(BufferWeeklyCodec.result(value)) == BufferCrdt.canonical(value))
            "transcribe" -> {
                validateResult(value)
                // A v1 executor may cache its deterministic local failure to
                // avoid another model call, but must never transmit that v2
                // result variant to the legacy coordinator/source protocol.
                require(localComputation || !value.has("failure") || contract.getString("skill_version") == "buffer-asr-v2")
            }
            else -> error("unsupported mesh result stage")
        }
    }
    fun validateResult(value: JSONObject) {
        if (value.keys().asSequence().toSet() == setOf("failure")) {
            require(value.get("failure") in setOf("no_signal", "invalid_audio", "no_transcript"))
            return
        }
        val text = value.get("transcript")
        require(value.keys().asSequence().toSet() == setOf("transcript") && text is String && text.isNotBlank() &&
            text == text.trim() && text.length <= 32_000 && '\u0000' !in text)
    }
    fun failure(db: SQLiteDatabase, contract: JSONObject, lease: CoordinatorLeaseGrant, reason: String, scope: String, coordinator: String,
                emit: (String, JSONObject) -> String) {
        check(db.inTransaction()); require(reason in setOf("service_unavailable", "codec_unavailable", "storage_unavailable", "legacy_failure_unsupported"))
        val prior = db.rawQuery("SELECT contract,event_id FROM mesh_task_execution_failures WHERE lease_id=?", arrayOf(lease.leaseId)).use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }
        if (prior != null) {
            check(prior.first == BufferCrdt.canonical(contract))
            val event = BufferEventJournal.localRecord(db, prior.second).event
            val p = event.getJSONObject("payload")
            check(event.getString("type") == "MeshTaskAttemptFailed" && p.getString("lease_id") == lease.leaseId &&
                p.getLong("term") == lease.coordinatorTerm && p.getString("owner_incarnation_id") == lease.ownerIncarnationId &&
                BufferCrdt.canonical(p.getJSONObject("contract")) == BufferCrdt.canonical(contract))
            return
        }
        val event = emit("MeshTaskAttemptFailed", JSONObject().put("contract", contract).put("lease_id", lease.leaseId)
            .put("term", lease.coordinatorTerm).put("owner_device_id", lease.ownerDeviceId).put("owner_incarnation_id", lease.ownerIncarnationId)
            .put("scope_revision", scope).put("coordinator", coordinator).put("reason", reason))
        db.insertOrThrow("mesh_task_execution_failures", null, ContentValues().apply {
            put("lease_id", lease.leaseId); put("contract", BufferCrdt.canonical(contract)); put("event_id", event)
        })
    }
    fun save(db: SQLiteDatabase, contract: JSONObject, result: JSONObject, lease: CoordinatorLeaseGrant, scope: String, coordinator: String,
             emit: (String, JSONObject) -> String): JSONObject {
        check(db.inTransaction()); validateResult(contract, result, localComputation = true)
        this.result(db, contract)?.let { return it }
        val event = emit("MeshTaskExecuted", JSONObject().put("contract", contract).put("result", result)
            .put("lease_id", lease.leaseId).put("term", lease.coordinatorTerm).put("owner_device_id", lease.ownerDeviceId)
            .put("owner_incarnation_id", lease.ownerIncarnationId).put("scope_revision", scope).put("coordinator", coordinator)
            .put("result_sha256", BufferMeshTaskContract.digest(result)))
        db.insertOrThrow("mesh_task_execution_results", null, ContentValues().apply {
            put("job_id", contract.getString("job_id")); put("contract", BufferCrdt.canonical(contract)); put("event_id", event)
        })
        return result
    }
}
