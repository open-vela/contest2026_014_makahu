package com.mocharealm.foundation.fabric.buffer.data.coordinator

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Bounded JSON codec for application-election ReliableMessages payloads. */
object CoordinatorWireCodec {
    private const val HEARTBEAT_TYPE = "coordinator_heartbeat"
    private const val LEASE_GRANT_TYPE = "coordinator_lease_grant"
    private const val MAX_PAYLOAD_BYTES = 16 * 1024

    fun messageType(payload: ByteArray): String? = runCatching {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES)
        JSONObject(payload.toString(StandardCharsets.UTF_8)).get("type") as? String
    }.getOrNull()

    fun encodeHeartbeat(heartbeat: CoordinatorHeartbeat): ByteArray {
        require(heartbeat.protocolVersion == BufferCoordinatorElection.PROTOCOL_VERSION)
        require(heartbeat.sessionId.isNotBlank() && heartbeat.membershipVersion.isNotBlank())
        require(heartbeat.deviceId.isNotBlank() && heartbeat.incarnationId.isNotBlank())
        require(heartbeat.stateRevision >= 0L && heartbeat.heartbeatSequence > 0L)
        require(heartbeat.coordinatorTerm >= 0L)
        require(heartbeat.coordinatorDeviceId == null || heartbeat.coordinatorDeviceId.isNotBlank())
        val readiness = JSONObject()
            .put("configuration_valid", heartbeat.readiness.configurationValid)
            .put("service_reachable", heartbeat.readiness.serviceReachable)
            .put("background_execution_available", heartbeat.readiness.backgroundExecutionAvailable)
            .put("storage_available", heartbeat.readiness.storageAvailable)
            .put("can_coordinate", heartbeat.readiness.canCoordinate)
            .put("capabilities", JSONArray().also { array ->
                heartbeat.readiness.capabilities.map { it.name }.sorted().forEach { array.put(it) }
            })
        val json = JSONObject()
            .put("type", HEARTBEAT_TYPE)
            .put("protocol_version", heartbeat.protocolVersion)
            .put("session_id", heartbeat.sessionId)
            .put("membership_version", heartbeat.membershipVersion)
            .put("device_id", heartbeat.deviceId)
            .put("incarnation_id", heartbeat.incarnationId)
            .put("state_revision", heartbeat.stateRevision)
            .put("heartbeat_sequence", heartbeat.heartbeatSequence)
            .put("readiness", readiness)
            .put("coordinator_term", heartbeat.coordinatorTerm)
            .put("coordinator_device_id", heartbeat.coordinatorDeviceId ?: JSONObject.NULL)
        return json.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Coordinator heartbeat exceeds the message limit" }
        }
    }

    fun decodeHeartbeat(payload: ByteArray): CoordinatorHeartbeat? = runCatching {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES)
        val json = JSONObject(payload.toString(StandardCharsets.UTF_8))
        require(json.string("type") == HEARTBEAT_TYPE)
        val readinessJson = json.getJSONObject("readiness")
        val capabilitiesJson = readinessJson.getJSONArray("capabilities")
        val capabilities = buildSet {
            for (index in 0 until capabilitiesJson.length()) {
                val name = capabilitiesJson.get(index) as? String ?: error("invalid capability")
                add(CoordinatorCapability.valueOf(name))
            }
        }
        CoordinatorHeartbeat(
            sessionId = json.string("session_id"),
            membershipVersion = json.string("membership_version"),
            deviceId = json.string("device_id"),
            incarnationId = json.string("incarnation_id"),
            protocolVersion = json.int("protocol_version"),
            stateRevision = json.long("state_revision"),
            heartbeatSequence = json.long("heartbeat_sequence"),
            readiness = CoordinatorReadiness(
                configurationValid = readinessJson.boolean("configuration_valid"),
                serviceReachable = readinessJson.boolean("service_reachable"),
                backgroundExecutionAvailable = readinessJson.boolean("background_execution_available"),
                storageAvailable = readinessJson.boolean("storage_available"),
                canCoordinate = readinessJson.boolean("can_coordinate"),
                capabilities = capabilities,
            ),
            coordinatorTerm = json.long("coordinator_term"),
            coordinatorDeviceId = if (json.isNull("coordinator_device_id")) null
            else json.string("coordinator_device_id"),
        ).also {
            require(it.protocolVersion == BufferCoordinatorElection.PROTOCOL_VERSION)
            require(it.stateRevision >= 0L && it.heartbeatSequence > 0L && it.coordinatorTerm >= 0L)
            require(it.coordinatorDeviceId == null || it.coordinatorDeviceId.isNotBlank())
        }
    }.getOrNull()

    fun encodeLeaseGrant(grant: CoordinatorLeaseGrant): ByteArray {
        require(grant.jobId.isNotBlank() && grant.captureId.isNotBlank() && grant.stage.isNotBlank())
        require(grant.skillVersion.isNotBlank() && grant.ownerDeviceId.isNotBlank())
        require(grant.ownerIncarnationId.isNotBlank() && grant.renewalSequence > 0L)
        require(grant.inputRevision >= 0L && grant.coordinatorTerm >= 0L)
        require(grant.leaseId.isNotBlank() && grant.ttlMillis > 0L)
        val json = JSONObject()
            .put("type", LEASE_GRANT_TYPE)
            .put("job_id", grant.jobId)
            .put("capture_id", grant.captureId)
            .put("stage", grant.stage)
            .put("input_revision", grant.inputRevision)
            .put("skill_version", grant.skillVersion)
            .put("owner_device_id", grant.ownerDeviceId)
            .put("coordinator_term", grant.coordinatorTerm)
            .put("lease_id", grant.leaseId)
            .put("ttl_ms", grant.ttlMillis)
            .put("renewal_sequence", grant.renewalSequence)
            .put("owner_incarnation_id", grant.ownerIncarnationId)
        return json.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Coordinator lease exceeds the message limit" }
        }
    }

    fun decodeLeaseGrant(payload: ByteArray): CoordinatorLeaseGrant? = runCatching {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES)
        val json = JSONObject(payload.toString(StandardCharsets.UTF_8))
        require(json.string("type") == LEASE_GRANT_TYPE)
        CoordinatorLeaseGrant(
            jobId = json.string("job_id"),
            captureId = json.string("capture_id"),
            stage = json.string("stage"),
            inputRevision = json.long("input_revision"),
            skillVersion = json.string("skill_version"),
            ownerDeviceId = json.string("owner_device_id"),
            coordinatorTerm = json.long("coordinator_term"),
            leaseId = json.string("lease_id"),
            ttlMillis = json.long("ttl_ms"),
            renewalSequence = json.long("renewal_sequence"),
            ownerIncarnationId = json.string("owner_incarnation_id"),
        ).also {
            require(it.jobId.isNotBlank() && it.captureId.isNotBlank() && it.stage.isNotBlank())
            require(it.skillVersion.isNotBlank() && it.ownerDeviceId.isNotBlank() && it.leaseId.isNotBlank())
            require(it.ownerIncarnationId.isNotBlank() && it.renewalSequence > 0L)
            require(it.inputRevision >= 0L && it.coordinatorTerm >= 0L && it.ttlMillis > 0L)
        }
    }.getOrNull()

    private fun JSONObject.string(name: String): String =
        (get(name) as? String)?.takeIf { it.isNotBlank() } ?: error("invalid $name")

    private fun JSONObject.boolean(name: String): Boolean =
        get(name) as? Boolean ?: error("invalid $name")

    private fun JSONObject.int(name: String): Int {
        val number = get(name) as? Number ?: error("invalid $name")
        val value = number.toLong()
        require(value in Int.MIN_VALUE..Int.MAX_VALUE && number.toDouble() == value.toDouble())
        return value.toInt()
    }

    private fun JSONObject.long(name: String): Long {
        val number = get(name) as? Number ?: error("invalid $name")
        val value = number.toLong()
        require(number.toDouble() == value.toDouble())
        return value
    }
}
