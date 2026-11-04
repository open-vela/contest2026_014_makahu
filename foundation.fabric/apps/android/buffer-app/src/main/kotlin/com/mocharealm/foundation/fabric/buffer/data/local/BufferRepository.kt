package com.mocharealm.foundation.fabric.buffer.data.local
import com.mocharealm.foundation.fabric.buffer.domain.CardLookup
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmKey
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationCodec
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferAnswerCodec
import com.mocharealm.foundation.fabric.buffer.data.llm.QuickAnswerResult
import com.mocharealm.foundation.fabric.buffer.data.llm.WeeklyLlmJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyCodec
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStore
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPendingCaptureInput
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPendingCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCapture
import com.mocharealm.foundation.fabric.buffer.data.device.toJson
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeDeck
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlan
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlanner
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeState
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipt
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipts
import com.mocharealm.foundation.fabric.buffer.domain.CalendarWriteSelection
import com.mocharealm.foundation.fabric.buffer.domain.CardReturnTime
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationReview
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig
import com.mocharealm.foundation.fabric.buffer.domain.RabbitHoleSession
import com.mocharealm.foundation.fabric.buffer.domain.RecoveryFiles
import com.mocharealm.foundation.fabric.buffer.domain.WeeklyReflection
import com.mocharealm.foundation.fabric.buffer.domain.WeeklySchedule
import com.mocharealm.foundation.fabric.buffer.domain.takeCodePoints
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferWeeklySummary
import com.mocharealm.foundation.fabric.buffer.domain.FocusPlan
import com.mocharealm.foundation.fabric.buffer.domain.FocusSession
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdtView
import com.mocharealm.foundation.fabric.buffer.domain.TaskSchedule
import com.mocharealm.foundation.fabric.buffer.domain.CalendarEntry
import com.mocharealm.foundation.fabric.buffer.domain.CaptureContext
import com.mocharealm.foundation.fabric.buffer.domain.CaptureContextJob
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePolicy
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeResolution
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeAssessment
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

class BufferRepository(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
), CardLookup {
    private val appContext = context.applicationContext
    private val captureDir = File(appContext.filesDir, "captures").apply { mkdirs() }
    private val settings = appContext.getSharedPreferences("buffer", Context.MODE_PRIVATE)
    private val runtimeSettings = DatabaseSettings { writableDatabase }
    private val meshSourceTasks = BufferMeshSourceTasks()
    private val meshChannels = mutableMapOf<String, BufferMeshChannel>()
    private val meshVelaLeases = mutableMapOf<String, com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobLease>()
    private var meshVelaScope = ""
    private var meshGatewayCheckedAt = -1L
    private var meshGatewayVerified = emptySet<com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorCapability>()
    private var meshExecutionContext: Pair<String, () -> Boolean>? = null
    private val meshCoordinator by lazy {
        BufferMeshCoordinator(com.mocharealm.foundation.fabric.buffer.data.coordinator.SharedPreferencesCoordinatorStateStore(appContext))
    }
    private val documentObservationDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }
    internal val velaEnrollments = VelaEnrollmentStore({ writableDatabase }) { db, id, requestId ->
        appendEvent(db, "PeerPaired", null, System.currentTimeMillis(),
            JSONObject().put("device_id", id).put("enrollment_id", requestId))
    }

    @Volatile private var openedDatabase: SQLiteDatabase? = null

    // Repository methods can already hold this helper's monitor. Acquire it
    // before the shared schema lock too, matching SQLiteOpenHelper's monitor;
    // the reverse order deadlocks first-open against a synchronized worker.
    // Open connections bypass both locks while other helpers finish opening.
    override fun getWritableDatabase(): SQLiteDatabase =
        openedDatabase?.takeIf { it.isOpen && !it.isReadOnly } ?: synchronized(this) {
            synchronized(databaseOpenLock) {
                super.getWritableDatabase().also { openedDatabase = it }
            }
        }

    override fun getReadableDatabase(): SQLiteDatabase =
        openedDatabase?.takeIf { it.isOpen } ?: synchronized(this) {
            synchronized(databaseOpenLock) {
                super.getReadableDatabase().also { openedDatabase = it }
            }
        }

    override fun onCreate(db: SQLiteDatabase) {
        BufferResearchPlans.create(db)
        createHealthQuant(db)
        createCardReminders(db)
        createCardReturns(db)
        VelaEnrollmentStore.createTable(db)
        createRuntimeSettings(db)
        migrateBedtimeSettings(db)
        db.execSQL(
            """
            CREATE TABLE events (
                event_id TEXT PRIMARY KEY,
                app_namespace TEXT NOT NULL,
                actor_id TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                parent_event_ids TEXT NOT NULL,
                logical_clock INTEGER NOT NULL,
                type TEXT NOT NULL,
                capture_id TEXT,
                created_at INTEGER NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE captures (
                capture_id TEXT PRIMARY KEY,
                source_device TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                mode TEXT NOT NULL,
                audio_format TEXT,
                duration_ms INTEGER NOT NULL,
                blob_path TEXT,
                transcript TEXT,
                classification TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE cards (
                card_id TEXT PRIMARY KEY,
                capture_id TEXT,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                state TEXT NOT NULL,
                actions TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                answer TEXT,
                health_category TEXT,
                classification_confirmed INTEGER NOT NULL DEFAULT 0,
                research_notes TEXT,
                external_links TEXT NOT NULL DEFAULT '[]',
                tags TEXT NOT NULL DEFAULT '[]',
                related_card_ids TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent(),
        )
        createVelaCardStateReceipts(db)
        db.execSQL("CREATE INDEX events_created_at ON events(created_at)")
        createContextStorage(db)
        BufferEventJournal.create(db)
        BufferHealthQuantFacts.create(db)
        BufferCapabilities.create(db)
        BufferMeshMembers.create(db)
        BufferMeshConfiguration.create(db)
        BufferMeshConfirmations.create(db)
        BufferMeshActivations.create(db)
        BufferMeshTaskBroker.create(db)
        BufferMeshSourceTasks.create(db)
        BufferMeshSourceDispatch.create(db)
        BufferMeshScopedDispatch.create(db)
        BufferMeshExecution.create(db)
        BufferMeshClassificationSource.create(db)
        BufferMeshAnswerSource.create(db)
        BufferMeshWeeklySource.create(db)
        BufferMeshFacts.create(db)
        db.execSQL("CREATE INDEX cards_created_at ON cards(created_at)")
        db.execSQL(
            """
            CREATE TABLE paired_peers (
                device_id TEXT PRIMARY KEY,
                token TEXT NOT NULL,
                paired_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE action_receipts (
                action_id TEXT PRIMARY KEY,
                card_id TEXT NOT NULL,
                action TEXT NOT NULL,
                resulting_state TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE classification_jobs (
                capture_id TEXT PRIMARY KEY,
                state TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                lease_id TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE mode_receipts (
                mode_id TEXT PRIMARY KEY,
                mode TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE captures ADD COLUMN classification TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE paired_peers (
                    device_id TEXT PRIMARY KEY,
                    token TEXT NOT NULL,
                    paired_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE events ADD COLUMN app_namespace TEXT NOT NULL DEFAULT 'app.buffer'")
            db.execSQL("ALTER TABLE events ADD COLUMN actor_id TEXT NOT NULL DEFAULT 'local'")
            db.execSQL("ALTER TABLE events ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE events ADD COLUMN parent_event_ids TEXT NOT NULL DEFAULT '[]'")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE events ADD COLUMN logical_clock INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE cards ADD COLUMN answer TEXT")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE cards ADD COLUMN health_category TEXT")
        }
        if (oldVersion < 8) {
            // Cards created before proposal confirmation existed were already
            // shown as accepted and remain backward compatible.
            db.execSQL("ALTER TABLE cards ADD COLUMN classification_confirmed INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 9) {
            db.execSQL(
                """
                CREATE TABLE action_receipts (
                    action_id TEXT PRIMARY KEY,
                    card_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    resulting_state TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 10) {
            db.execSQL(
                """
                CREATE TABLE classification_jobs (
                    capture_id TEXT PRIMARY KEY,
                    state TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 11) {
            db.execSQL(
                """
                CREATE TABLE mode_receipts (
                    mode_id TEXT PRIMARY KEY,
                    mode TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 12) {
            db.execSQL("ALTER TABLE cards ADD COLUMN research_notes TEXT")
            db.execSQL("ALTER TABLE cards ADD COLUMN external_links TEXT NOT NULL DEFAULT '[]'")
        }
        if (oldVersion < 13) {
            db.execSQL("ALTER TABLE cards ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE cards ADD COLUMN related_card_ids TEXT NOT NULL DEFAULT '[]'")
        }
        if (oldVersion < 14) createRuntimeSettings(db)
        if (oldVersion < 15) migrateBedtimeSettings(db)
        if (oldVersion < 16) db.execSQL("ALTER TABLE classification_jobs ADD COLUMN lease_id TEXT")
        if (oldVersion < 17) VelaEnrollmentStore.createTable(db)
        if (oldVersion < 18) createCardReturns(db)
        if (oldVersion < 19) createCardReminders(db)
        if (oldVersion < 20) createVelaCardStateReceipts(db)
        if (oldVersion < 21) createContextStorage(db)
        if (oldVersion < 22) {
            BufferEventJournal.create(db)
            BufferEventJournal.backfill(db)
        }
        if (oldVersion < 23) BufferEventJournal.createDeliveries(db)
        if (oldVersion < 24) BufferEventHistory.create(db)
        if (oldVersion < 25) {
            BufferSourceParents.create(db)
            db.execSQL("UPDATE source_event_history_scan SET after_event_id='',complete=0 WHERE id=1")
        }
        if (oldVersion < 26) {
            BufferSourceBlobs.create(db)
            db.execSQL("UPDATE source_event_history_scan SET after_event_id='',complete=0 WHERE id=1")
        }
        if (oldVersion < 27) {
            if (oldVersion >= 26) db.execSQL("ALTER TABLE source_event_blob_receivers ADD COLUMN content_type TEXT NOT NULL DEFAULT 'audio/wav'")
            BufferLocalAudio.create(db)
            db.execSQL("UPDATE source_event_history_scan SET after_event_id='',complete=0 WHERE id=1")
        }
        if (oldVersion < 28) BufferLocalAudio.create(db)
        if (oldVersion < 29) BufferEventVisibility.create(db)
        if (oldVersion < 30) BufferSourceProjection.create(db)
        if (oldVersion < 31) {
            if (oldVersion >= 30) {
                db.execSQL("ALTER TABLE source_event_projection_details ADD COLUMN capture_id TEXT")
                db.execSQL("ALTER TABLE source_event_projection_details ADD COLUMN document_id TEXT")
            }
            BufferSourceProjection.createCrdt(db)
        }
        if (oldVersion < 32) {
            BufferSourceObservations.create(db)
            db.execSQL("UPDATE source_projection_scan SET after_event_id='',complete=0 WHERE kind='replay'")
        }
        if (oldVersion < 33) {
            BufferDocumentSource.create(db)
            db.execSQL("UPDATE source_projection_scan SET after_event_id='',complete=0 WHERE kind='replay'")
        }
        if (oldVersion < 34) BufferCapabilities.create(db)
        if (oldVersion < 35) BufferMeshMembers.create(db)
        if (oldVersion < 36) BufferMeshConfiguration.create(db)
        if (oldVersion < 37) BufferMeshConfirmations.create(db)
        if (oldVersion < 38) BufferMeshActivations.create(db)
        if (oldVersion < 39) BufferMeshTaskBroker.create(db)
        if (oldVersion < 40) BufferMeshSourceTasks.create(db)
        if (oldVersion < 41) {
            BufferMeshTaskBroker.create(db)
            BufferMeshSourceDispatch.create(db)
        }
        if (oldVersion < 42) BufferMeshExecution.create(db)
        if (oldVersion < 43) {
            BufferMeshExecution.create(db)
            BufferMeshSourceTasks.create(db)
        }
        if (oldVersion < 44) BufferMeshClassificationSource.create(db)
        if (oldVersion < 45) {
            BufferMeshClassificationSource.create(db)
            // Existing approval described ASR disclosure only. Expanded
            // classification/record disclosure requires the new local policy;
            // changing an offer or importing an old grant cannot approve it.
            db.execSQL("UPDATE mesh_members SET state='pending',approved_digest=NULL,approval_event=NULL,approver=NULL WHERE state='approved'")
        }
        if (oldVersion < 46) {
            if (oldVersion >= 39) db.execSQL("ALTER TABLE mesh_task_inbox ADD COLUMN source_revision TEXT")
            BufferMeshTaskBroker.create(db)
            BufferMeshScopedDispatch.create(db)
        }
        if (oldVersion < 47) {
            BufferMeshAnswerSource.create(db)
            db.execSQL("UPDATE mesh_members SET state='pending',approved_digest=NULL,approval_event=NULL,approver=NULL WHERE state='approved'")
        }
        if (oldVersion < 48) BufferMeshWeeklySource.create(db)
        if (oldVersion < 49) BufferMeshFacts.create(db)
        if (oldVersion < 50) BufferEventHistory.requestRebuild(db)
        if (oldVersion < 51) {
            BufferSourceEffects.create(db)
            BufferEventHistory.requestRebuild(db)
        }
        if(oldVersion<52){
            BufferSourceEffects.create(db)
            BufferSourceLegacyMigration.create(db,start=true)
            BufferEventHistory.requestRebuild(db)
        }
        if(oldVersion<53){
            BufferSourceDeviceObservations.create(db)
            db.execSQL("UPDATE source_projection_scan SET after_event_id='',complete=0 WHERE kind='replay'")
        }
        if(oldVersion<54)BufferSourceEffects.createDamageStorage(db)
        if(oldVersion<55)BufferEventHistory.createHistoryIndex(db)
        if(oldVersion<56) {
            // Sharing ordinary history expands the application data scope.
            // A task-only approval cannot silently authorize that disclosure.
            db.execSQL("UPDATE mesh_members SET state='pending',approved_digest=NULL,approval_event=NULL,approver=NULL WHERE state='approved'")
        }
        if(oldVersion<58)BufferSourceMetadata.create(db)
        if(oldVersion<59)BufferSourceBlobs.createDamageStorage(db)
        if(oldVersion<61)BufferSourceMetadata.create(db)
        if(oldVersion<62)BufferResearchPlans.create(db)
        if(oldVersion<63)createHealthQuant(db)
        if(oldVersion<64) {
            BufferHealthQuantFacts.create(db)
            db.execSQL("UPDATE source_projection_scan SET after_event_id='',complete=0 WHERE kind='replay'")
        }
    }

    private fun createContextStorage(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS events_capture_type_clock ON events(capture_id,type,logical_clock)")
        db.execSQL("CREATE INDEX IF NOT EXISTS events_type_clock ON events(type,logical_clock)")
        db.execSQL("CREATE TABLE IF NOT EXISTS capture_context_jobs (capture_id TEXT PRIMARY KEY, request_event_id TEXT NOT NULL UNIQUE)")
    }

    private fun capabilityEmitter(db: SQLiteDatabase): (String, JSONObject) -> String = { type, payload ->
        check(db.inTransaction())
        appendEvent(db, type, null, System.currentTimeMillis(), payload)
        val head = BufferEventJournal.head(db)
        BufferSourceEvent.decode(BufferEventJournal.page(db, head - 1, 1).single()).eventId.also {
            BufferMeshFacts.published(db, it)
        }
    }

    internal fun capabilitySkill() = com.mocharealm.foundation.fabric.buffer.domain.CapabilitySkill.load(appContext)

    internal fun meshHello(local: String, reply: Boolean = false): JSONObject {
        val offered = JSONObject()
        listOf("TRANSCRIBE", "CLASSIFY", "ANSWER", "GENERATE").forEach { offered.put(it, 2) }
        offered.put("CLASSIFY", 3)
        offered.put("HISTORY", 1)
        return BufferMeshMembers.descriptor(JSONObject().put("type", "buffer.mesh.hello").put("protocol", 1)
            .put("device_id", local).put("label", android.os.Build.MODEL.take(80).ifBlank { "Android" })
            .put("device_class", if (appContext.resources.configuration.smallestScreenWidthDp >= 600) "TABLET" else "PHONE")
            .put("capabilities", offered), local).put("type", if (reply) "buffer.mesh.hello.reply" else "buffer.mesh.hello")
    }

    @Synchronized
    internal fun meshMembers() = BufferMeshMembers.list(readableDatabase)

    @Synchronized
    internal fun observeMeshMember(peer: String, local: String, value: JSONObject) = runtimeSettings.transaction {
        if (BufferMeshMembers.observe(writableDatabase, peer, local, value, System.currentTimeMillis(), capabilityEmitter(writableDatabase)))
            BufferMeshConfiguration.invalidate(writableDatabase, "member_offer_changed", capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun decideMeshMember(peer: String, digest: String, enable: Boolean, local: String?) = runtimeSettings.transaction {
        if (BufferMeshMembers.decide(writableDatabase, peer, digest, enable, local, System.currentTimeMillis(), capabilityEmitter(writableDatabase)))
            BufferMeshConfiguration.invalidate(writableDatabase, "member_approval_changed", capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun forgetMeshMember(peer: String, digest: String) = runtimeSettings.transaction {
        BufferMeshMembers.forget(writableDatabase, peer, digest, capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun <T> withApprovedMeshMember(peer: String, local: String, action: () -> T): T = runtimeSettings.transaction {
        if (!BufferMeshMembers.allowed(writableDatabase, peer, local)) throw SecurityException("这台设备尚未获得本机 Buffer 授权")
        BufferMeshConfiguration.snapshot(writableDatabase, local, meshHello(local), capabilityEmitter(writableDatabase))
        action()
    }

    @Synchronized
    internal fun meshBinding(local: String, digest: String? = null, revision: String? = null): JSONObject = runtimeSettings.transaction {
        if (digest == null && revision == null) BufferMeshConfiguration.snapshot(writableDatabase, local, meshHello(local), capabilityEmitter(writableDatabase))
        else BufferMeshConfiguration.requireCurrent(writableDatabase, local, meshHello(local), requireNotNull(digest), requireNotNull(revision), capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun meshGroupPlan(local: String): MeshGroupPlan = runtimeSettings.transaction {
        MeshGroupPlan(BufferMeshConfiguration.snapshot(writableDatabase, local, meshHello(local), capabilityEmitter(writableDatabase)),
            BufferMeshConfiguration.participants(writableDatabase))
    }

    private fun currentMeshPlan(local: String, expected: JSONObject): MeshGroupPlan {
        val binding = BufferMeshConfiguration.requireCurrent(writableDatabase, local, meshHello(local),
            expected.getString("digest"), expected.getString("revision"), capabilityEmitter(writableDatabase))
        return MeshGroupPlan(binding, BufferMeshConfiguration.participants(writableDatabase))
    }

    @Synchronized
    internal fun confirmMeshPeer(local: String, expected: JSONObject, peer: String, reply: JSONObject, boot: String, started: Long, now: Long) = runtimeSettings.transaction {
        BufferMeshConfirmations.accept(writableDatabase, peer, currentMeshPlan(local, expected), reply, boot, started, now, capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun failMeshPeer(peer: String, revision: String, boot: String, started: Long) = runtimeSettings.transaction {
        BufferMeshConfirmations.failed(writableDatabase, peer, revision, boot, started)
    }

    @Synchronized
    internal fun confirmedMeshPeers(local: String, expected: JSONObject, boot: String, now: Long): Int = runtimeSettings.transaction {
        BufferMeshConfirmations.count(writableDatabase, currentMeshPlan(local, expected), boot, now)
    }

    @Synchronized
    internal fun advanceMeshConfiguration(local: String, expected: JSONObject, boot: String, now: Long): Boolean = runtimeSettings.transaction {
        BufferMeshActivations.advance(writableDatabase, currentMeshPlan(local, expected), boot, now, capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun resetMeshCoordinator() { meshCoordinator.reset(); meshSourceTasks.reset() }

    @Synchronized
    internal fun meshChannelTarget(local: String, expected: JSONObject, peer: String): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
        val remote = requireNotNull(BufferMeshConfirmations.reply(writableDatabase, plan, peer))
        JSONObject().put("protocol", 1).put("digest", plan.binding.get("digest"))
            .put("source_revision", plan.binding.get("revision")).put("target_revision", remote.get("revision"))
    }

    @Synchronized
    internal fun installMeshChannel(local: String, expected: JSONObject, peer: String, channel: BufferMeshChannel) {
        require(channel.current() && channel.binding == BufferCrdt.canonical(meshChannelTarget(local, expected, peer)))
        require(UUID.fromString(channel.id).toString() == channel.id)
        meshChannels[peer] = channel
    }

    @Synchronized
    internal fun removeMeshChannel(peer: String, id: String) {
        if (meshChannels[peer]?.id != id) return
        meshChannels.remove(peer)
        meshSourceTasks.retirePeer(peer)
    }

    @Synchronized
    internal fun meshApplicationChannel(local: String, expected: JSONObject, peer: String): BufferMeshChannel? {
        val channel = meshChannels[peer] ?: return null
        return channel.takeIf { it.current() && it.binding == BufferCrdt.canonical(meshChannelTarget(local, expected, peer)) }
    }

    @Synchronized
    internal fun meshApplicationChannelCurrent(local: String, expected: JSONObject, peer: String, id: String): Boolean =
        runCatching { meshApplicationChannel(local, expected, peer)?.id == id }.getOrDefault(false)

    /** The context is queried from the selected-provider Hub IPC call, never
     * read from the peer's payload. Native has already verified the live plan. */
    @Synchronized
    internal fun requireMeshInvocationSession(peer: String, local: String, request: JSONObject, context: String?) = runtimeSettings.transaction {
        val value = BufferSourceEvent.parseObject(requireNotNull(context) { "需要当前授权 Session" }, 32 * 1024)
        require(value.keys().asSequence().toSet() == setOf("session_id", "ability", "binding_b64") && value.get("ability") == BufferConstants.ability)
        require(UUID.fromString(value.getString("session_id")).toString() == value.getString("session_id"))
        val encoded = value.get("binding_b64"); require(encoded is String && encoded.length <= 24 * 1024)
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        try {
            require(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) == encoded)
            val raw = bytes.toString(Charsets.UTF_8); require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes))
            val binding = BufferSourceEvent.parseObject(raw, 16 * 1024)
            require(binding.keys().asSequence().toSet() == setOf("protocol", "digest", "source_revision", "target_revision") && binding.get("protocol") == 1)
            val plan = meshGroupPlan(local)
            require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
            require(binding.get("digest") == plan.binding.get("digest") && binding.get("target_revision") == plan.binding.get("revision") &&
                binding.get("source_revision") == request.getJSONObject("binding").get("revision") &&
                binding.get("target_revision") == request.get("target_revision"))
        } finally { bytes.fill(0) }
    }

    @Synchronized
    internal fun meshCoordinatorRequest(local: String, expected: JSONObject, peer: String, nonce: String): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(peer in plan.peers)
        val remote = BufferMeshConfirmations.reply(writableDatabase, plan, peer)
        if (remote == null || !BufferMeshActivations.current(writableDatabase, plan))
            JSONObject().put("type", "buffer.mesh.binding.get").put("protocol", 1).put("device_id", local)
        else meshCoordinatorEnvelope("buffer.mesh.heartbeat", local, nonce, remote.getString("revision"), plan)
    }

    private fun meshCoordinatorEnvelope(type: String, local: String, nonce: String, target: String, plan: MeshGroupPlan): JSONObject =
        meshEnvelope(type, local, nonce, target, plan)
            .put("heartbeat", meshCoordinator.heartbeat(writableDatabase, local, plan))

    private fun meshEnvelope(type: String, local: String, nonce: String, target: String, plan: MeshGroupPlan): JSONObject =
        JSONObject().put("type", type).put("protocol", 1).put("device_id", local).put("nonce", nonce)
            .put("target_revision", target).put("binding", plan.binding)

    private fun validateMeshCoordinatorEnvelope(value: JSONObject, type: String, peer: String, plan: MeshGroupPlan, content: String = "heartbeat"): JSONObject {
        require(value.keys().asSequence().toSet() == setOf("type", "protocol", "device_id", "nonce", "target_revision", "binding", content))
        require(value.get("type") == type && value.get("protocol") == 1 && value.get("device_id") == peer)
        require(UUID.fromString(value.getString("nonce")).toString() == value.getString("nonce"))
        require(value.getString("target_revision") == plan.binding.getString("revision")) { "成员配置修订已失效" }
        val remote = value.getJSONObject("binding"); BufferMeshConfirmations.validate(peer, remote)
        require(remote.getString("digest") == plan.binding.getString("digest") && remote.getInt("member_count") == plan.peers.size + 1)
        return remote
    }

    @Synchronized
    internal fun exchangeMeshCoordinator(peer: String, local: String, request: JSONObject): JSONObject = runtimeSettings.transaction {
        if (!BufferMeshMembers.allowed(writableDatabase, peer, local)) throw SecurityException("设备未获得 Buffer 授权")
        val plan = meshGroupPlan(local)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
        val remote = validateMeshCoordinatorEnvelope(request, "buffer.mesh.heartbeat", peer, plan)
        val confirmed = requireNotNull(BufferMeshConfirmations.reply(writableDatabase, plan, peer))
        require(BufferCrdt.canonical(remote) == BufferCrdt.canonical(confirmed)) { "设备配置需要重新确认" }
        meshCoordinator.receive(writableDatabase, local, plan, peer, request.getJSONObject("heartbeat"))
        meshCoordinatorEnvelope("buffer.mesh.heartbeat.reply", local, request.getString("nonce"), remote.getString("revision"), plan)
    }

    @Synchronized
    internal fun acceptMeshCoordinatorReply(local: String, expected: JSONObject, peer: String, reply: JSONObject,
                                           nonce: String, boot: String, started: Long, now: Long) = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
        val remote = validateMeshCoordinatorEnvelope(reply, "buffer.mesh.heartbeat.reply", peer, plan)
        require(reply.getString("nonce") == nonce)
        BufferMeshConfirmations.accept(writableDatabase, peer, plan, remote, boot, started, now, capabilityEmitter(writableDatabase))
        meshCoordinator.receive(writableDatabase, local, plan, peer, reply.getJSONObject("heartbeat"))
    }

    @Synchronized
    internal fun exchangeMeshTask(peer: String, local: String, request: JSONObject): JSONObject = runtimeSettings.transaction {
        if (!BufferMeshMembers.allowed(writableDatabase, peer, local)) throw SecurityException("设备未获得 Buffer 授权")
        val plan = meshGroupPlan(local)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
        val remote = validateMeshCoordinatorEnvelope(request, "buffer.mesh.job", peer, plan, "request")
        val confirmed = requireNotNull(BufferMeshConfirmations.reply(writableDatabase, plan, peer))
        require(BufferCrdt.canonical(remote) == BufferCrdt.canonical(confirmed)) { "设备配置需要重新确认" }
        val result = meshCoordinator.task(writableDatabase, local, plan, peer, remote.getString("revision"), request.getJSONObject("request"), capabilityEmitter(writableDatabase))
        meshEnvelope("buffer.mesh.job.reply", local, request.getString("nonce"), remote.getString("revision"), plan).put("result", result)
    }

    @Synchronized
    internal fun meshLocalTask(local: String, expected: JSONObject, request: JSONObject): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(BufferMeshActivations.current(writableDatabase, plan))
        noteMeshOffer(plan, local, local, plan.binding.getString("revision"), request)
        meshCoordinator.task(writableDatabase, local, plan, local, plan.binding.getString("revision"), request, capabilityEmitter(writableDatabase))
    }

    /** A negative control request grants no reads or future task execution. */
    @Synchronized
    internal fun exchangeMeshCancellation(peer: String, local: String, request: JSONObject): JSONObject = runtimeSettings.transaction {
        BufferMeshTaskCancellation.validate(request, peer)
        val admitUnknown = {
            if (peer != local && !BufferMeshMembers.allowed(writableDatabase, peer, local)) false else {
                val plan = meshGroupPlan(local)
                // Cancellation may overtake configuration confirmation and
                // the offer itself. Approval plus the exact recipient revision
                // permits a negative tombstone, never admission of execution.
                request.get("target_revision") == plan.binding.get("revision")
            }
        }
        meshCoordinator.cancel(writableDatabase, local, peer, request, admitUnknown, capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun meshTaskSnapshot(local: String, expected: JSONObject): com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorSnapshot? = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        if (BufferMeshActivations.current(writableDatabase, plan)) meshCoordinator.snapshot(writableDatabase, local, plan) else null
    }

    @Synchronized
    internal fun meshExecutionReadiness(local: String, expected: JSONObject, readiness: com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorReadiness,
                                        connectionCurrent: () -> Boolean) = runtimeSettings.transaction {
        check(connectionCurrent())
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        meshCoordinator.readiness(writableDatabase, local, plan, readiness)
        meshExecutionContext = local to connectionCurrent
    }

    @Synchronized
    internal fun withdrawMeshExecution(local: String, expected: JSONObject) = runtimeSettings.transaction {
        meshCoordinator.readiness(writableDatabase, local, currentMeshPlan(local, expected),
            com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorReadiness(false, false, false, false, false, emptySet()))
    }

    @Synchronized
    internal fun meshManagesLocalTranscription(capture: String): Boolean = runtimeSettings.transaction {
        val context = meshExecutionContext ?: return@transaction false
        if (!context.second()) return@transaction false
        val plan = meshGroupPlan(context.first)
        if (!BufferMeshActivations.current(writableDatabase, plan)) return@transaction false
        if (findCapture(capture)?.sourceDevice?.let(VelaPendingCaptureProtocol::isFabricDeviceId) == true)
            return@transaction meshVelaReceipt(capture) != null && velaMeshAudienceAllowed(requireNotNull(findCapture(capture)).sourceDevice)
        val id = writableDatabase.rawQuery("SELECT event_id FROM events WHERE capture_id=? AND type='CaptureCreated' ORDER BY logical_clock,rowid LIMIT 1", arrayOf(capture))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return@transaction false
        val record = BufferEventJournal.localRecord(writableDatabase, id)
        val payload = record.event.getJSONObject("payload")
        record.envelope.getString("provenance") == "native" && payload.opt("local_original") == true && payload.has("blob")
    }

    @Synchronized
    internal fun meshManagesLocalClassification(capture: String): Boolean = runtimeSettings.transaction {
        val context = meshExecutionContext ?: return@transaction false
        if (!context.second()) return@transaction false
        val plan = meshGroupPlan(context.first)
        if (!BufferMeshActivations.current(writableDatabase, plan) || !meshClassificationSupported()) return@transaction false
        val source = findCapture(capture)?.sourceDevice ?: return@transaction false
        (!VelaPendingCaptureProtocol.isFabricDeviceId(source) || velaMeshAudienceAllowed(source)) && nativeLocalClassificationCapture(capture)
    }

    private fun meshClassificationSupported(): Boolean = runCatching {
        BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(writableDatabase), "classify") in setOf("buffer-classify-v1", "buffer-classify-v2")
    }.getOrDefault(false)

    /** A receipt written by the actual authenticated input receiver, not an
     * imported history row or a claim that the recording originated here. */
    private fun meshVelaReceipt(captureId: String): BufferSourceEvent? {
        val capture = findCapture(captureId) ?: return null
        if (!VelaPendingCaptureProtocol.isFabricDeviceId(capture.sourceDevice)) return null
        val id = writableDatabase.rawQuery("SELECT event_id FROM events WHERE capture_id=? AND type='VelaPendingCaptureImported' ORDER BY logical_clock DESC,rowid DESC LIMIT 1",
            arrayOf(captureId)).use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        val original = BufferEventJournal.localRecord(writableDatabase, id); val p = original.event.getJSONObject("payload")
        if (original.envelope.get("provenance") != "native") return null
        check(original.event.get("type") == "VelaPendingCaptureImported" && original.event.get("capture_id") == captureId &&
            p.get("source_device") == capture.sourceDevice && p.getString("record_sha256").matches(Regex("[0-9a-f]{64}")))
        return original
    }

    private var meshGatewayDevice = ""
    private val liveVelaControllerViews = mutableMapOf<String, JSONObject>()

    @Synchronized
    internal fun knownVelaControllerView(device: String): Boolean =
        runtimeSettings.getString("vela_controller_view:$device", null) != null

    @Synchronized
    internal fun observeVelaControllerView(device: String, view: JSONObject) = runtimeSettings.transaction {
        liveVelaControllerViews.remove(device)
        meshGatewayCheckedAt = -1L
        val key = "vela_controller_view:$device"
        val old = runtimeSettings.getString(key, null)?.let(::JSONObject)
        if (old != null) {
            require(view.getLong("revision") >= old.getLong("revision"))
            if (view.getLong("revision") == old.getLong("revision"))
                require(view.getString("membership_hash") == old.getString("membership_hash"))
        }
        runtimeSettings.edit().putString(key, view.toString()).apply()
    }

    @Synchronized
    internal fun activateVelaControllerView(device: String, view: JSONObject) = runtimeSettings.transaction {
        val saved = JSONObject(requireNotNull(runtimeSettings.getString("vela_controller_view:$device", null)))
        require(saved.getLong("revision") == view.getLong("revision") &&
            saved.getString("membership_hash") == view.getString("membership_hash"))
        liveVelaControllerViews[device] = JSONObject(view.toString())
        meshGatewayCheckedAt = -1L
    }

    @Synchronized
    internal fun clearVelaControllerView(device: String) {
        liveVelaControllerViews.remove(device)
        meshGatewayCheckedAt = -1L
    }

    private fun velaMeshAudienceAllowed(device: String): Boolean {
        if (!knownVelaControllerView(device)) return true
        val view = liveVelaControllerViews[device] ?: return false
        val context = meshExecutionContext ?: return false
        if (!context.second()) return false
        val ids = view.getJSONArray("members").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val members = meshGroupPlan(context.first).peers + context.first
        return members.all { it in ids }
    }

    private fun meshVelaLease(captureId: String, stage: String, acquire: Boolean = false): com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobLease? {
        val capture = findCapture(captureId) ?: return null
        if (meshVelaReceipt(captureId) == null || !velaMeshAudienceAllowed(capture.sourceDevice)) return null
        val key = "$captureId:$stage"
        val gate = com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobGate
        meshVelaLeases[key]?.let {
            if (gate.termIfCurrent(it) { android.os.SystemClock.elapsedRealtime() } != null) return it
            meshVelaLeases.remove(key); meshSourceTasks.retireCapture(captureId); gate.release(it)
        }
        if (!acquire) return null
        if (meshVelaLeases.size >= 8) return null
        return gate.acquire(captureId, capture.sourceDevice, stage, capture.createdAt, BufferMeshTaskContract.capability(stage),
            android.os.SystemClock.elapsedRealtime())?.also { meshVelaLeases[key] = it }
    }

    private fun meshVelaCurrent(contract: JSONObject): Boolean {
        if (contract.get("stage") !in setOf("transcribe", "classify")) return true
        val capture = findCapture(contract.getString("capture_id")) ?: return false
        return !VelaPendingCaptureProtocol.isFabricDeviceId(capture.sourceDevice) || meshVelaLease(capture.captureId, contract.getString("stage")) != null
    }

    private fun <T : Any> withMeshVelaOwner(contract: JSONObject, block: () -> T): T? {
        if (contract.get("stage") !in setOf("transcribe", "classify")) return block()
        val capture = findCapture(contract.getString("capture_id")) ?: return null
        if (!VelaPendingCaptureProtocol.isFabricDeviceId(capture.sourceDevice)) return block()
        val lease = meshVelaLease(capture.captureId, contract.getString("stage")) ?: return null
        return com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobGate.withCurrent(lease,
            { android.os.SystemClock.elapsedRealtime() }, block)
    }

    private fun releaseMeshVela(contract: JSONObject) {
        if (contract.get("stage") !in setOf("transcribe", "classify")) return
        meshVelaLeases.remove("${contract.getString("capture_id")}:${contract.getString("stage")}")?.let {
            com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobGate.release(it)
        }
    }

    @Synchronized
    internal fun abandonMeshVelaPreparation(capture: String, stage: String) {
        meshVelaLeases.remove("$capture:$stage")?.let {
            meshSourceTasks.retireCapture(capture)
            com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobGate.release(it)
        }
    }

    @Synchronized
    internal fun meshGatewayCapabilities(device: String): Set<com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorCapability> = runtimeSettings.transaction {
        fun clear(): Set<com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorCapability> {
            meshVelaLeases.values.toList().forEach {
                meshSourceTasks.retireCapture(it.captureId)
                com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobGate.release(it)
            }
            meshVelaLeases.clear(); meshVelaScope = ""; meshGatewayCheckedAt = -1L; meshGatewayVerified = emptySet(); return emptySet()
        }
        if (!velaMeshAudienceAllowed(device)) return@transaction clear()
        val context = meshExecutionContext ?: return@transaction clear()
        if (!context.second()) return@transaction clear()
        val now = android.os.SystemClock.elapsedRealtime()
        // Enrollment ticks every 100 ms. Reuse a short service observation;
        // actual offers, input reads and commits still check live ownership.
        if (meshGatewayDevice == device && meshGatewayCheckedAt >= 0L && now >= meshGatewayCheckedAt && now - meshGatewayCheckedAt < 1000L)
            return@transaction meshGatewayVerified
        val plan = meshGroupPlan(context.first)
        if (!BufferMeshActivations.current(writableDatabase, plan)) return@transaction clear()
        val scope = plan.binding.getString("revision")
        if (scope != meshVelaScope) { clear(); meshVelaScope = scope }
        meshGatewayVerified = if (BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(writableDatabase), "classify") != "buffer-classify-v2") emptySet()
        else meshCoordinator.executableMembers(writableDatabase, context.first, plan).filterKeys {
            it == context.first || meshApplicationChannel(context.first, plan.binding, it) != null
        }.values.flatten().toSet()
        meshGatewayDevice = device
        meshGatewayCheckedAt = now
        meshGatewayVerified
    }

    private fun nativeLocalClassificationCapture(capture: String): Boolean {
        val value = findCapture(capture) ?: return false
        if (value.transcript.isNullOrBlank() || value.classification == "assistant_selected") return false
        if (VelaPendingCaptureProtocol.isFabricDeviceId(value.sourceDevice)) {
            val receipt = meshVelaReceipt(capture) ?: return false
            return receipt.event.getJSONObject("payload").optString("target", "buffer") != "auto" ||
                BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(writableDatabase), "classify") == "buffer-classify-v2"
        }
        if (value.sourceDevice !in setOf("android", deviceId())) return false
        val id = writableDatabase.rawQuery("SELECT event_id FROM events WHERE capture_id=? AND type='CaptureCreated' ORDER BY logical_clock,rowid LIMIT 1", arrayOf(capture))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        val record = BufferEventJournal.localRecord(writableDatabase, id)
        val payload = record.event.getJSONObject("payload")
        return record.envelope.get("provenance") == "native" && (if (value.audioFormat == null) {
            payload.optString("source") == value.sourceDevice && payload.optString("content_type") == "text/plain"
        } else {
            payload.opt("local_original") == true && payload.optString("device_id") == value.sourceDevice && payload.has("blob")
        })
    }

    private fun meshClassificationCurrent(intent: BufferMeshSourceTasks.Intent): Boolean {
        val input = BufferMeshClassificationSource.load(writableDatabase, intent.contract)
        val lease = BufferMeshClassificationSource.request(writableDatabase, intent) ?: return false
        return classificationRequestCurrent(intent.contract.getString("capture_id"), lease, input.getString("transcript"))
    }

    private fun releaseMeshClassification(intent: BufferMeshSourceTasks.Intent) {
        if (intent.contract.get("stage") != "classify") return
        BufferMeshClassificationSource.request(writableDatabase, intent)?.let {
            releaseClassificationRequest(intent.contract.getString("capture_id"), it)
        }
    }

    @Synchronized
    internal fun meshClassificationCandidates(after: String): List<BufferClassificationJob> = readableDatabase.rawQuery(
        "SELECT c.capture_id,c.transcript FROM captures c JOIN classification_jobs j ON j.capture_id=c.capture_id " +
            "WHERE c.capture_id>? AND c.transcript IS NOT NULL AND TRIM(c.transcript)<>'' AND j.state IN ('pending','processing') " +
            "ORDER BY c.capture_id LIMIT 8", arrayOf(after),
    ).use { rows -> buildList { while (rows.moveToNext()) add(BufferClassificationJob(rows.getString(0), rows.getString(1))) } }

    @Synchronized
    internal fun acceptMeshExecution(local: String, expected: JSONObject, coordinator: String, contract: JSONObject,
                                     grant: JSONObject, started: Long, connectionCurrent: () -> Boolean): MeshExecutionLease = runtimeSettings.transaction {
        check(connectionCurrent())
        val plan = currentMeshPlan(local, expected)
        val handle = meshCoordinator.acceptExecution(writableDatabase, local, plan, coordinator, contract, BufferMeshSourceTasks.grant(grant), started)
        check(connectionCurrent()); handle
    }

    @Synchronized
    internal fun meshExecutionCurrent(local: String, expected: JSONObject, handle: MeshExecutionLease): Boolean = runtimeSettings.transaction {
        meshCoordinator.execution(writableDatabase, local, currentMeshPlan(local, expected), handle) { true } == true
    }

    @Synchronized
    internal fun releaseMeshExecution(handle: MeshExecutionLease) {
        handle.election.releaseLease(handle.lease.grant.leaseId, handle.lease.grant.coordinatorTerm, handle.lease.grant.ownerDeviceId)
    }

    @Synchronized
    internal fun meshExecutionResult(local: String, expected: JSONObject, handle: MeshExecutionLease, contract: JSONObject,
                                     value: JSONObject? = null, connectionCurrent: () -> Boolean): JSONObject? = runtimeSettings.transaction {
        check(connectionCurrent())
        require(handle.lease.grant.jobId == contract.getString("job_id"))
        val outcome = meshCoordinator.execution(writableDatabase, local, currentMeshPlan(local, expected), handle) {
            val result = if (value == null) BufferMeshExecution.result(writableDatabase, contract)
                else BufferMeshExecution.save(writableDatabase, contract, value, handle.lease.grant, handle.scope,
                    requireNotNull(handle.election.snapshot().coordinatorDeviceId), capabilityEmitter(writableDatabase))
            check(connectionCurrent()); Unit to result
        }
        requireNotNull(outcome).second
    }

    @Synchronized
    internal fun recordMeshExecutionFailure(local: String, expected: JSONObject, handle: MeshExecutionLease, contract: JSONObject,
                                            reason: String, connectionCurrent: () -> Boolean) = runtimeSettings.transaction {
        check(connectionCurrent()); require(handle.lease.grant.jobId == contract.getString("job_id"))
        meshCoordinator.execution(writableDatabase, local, currentMeshPlan(local, expected), handle) {
            BufferMeshExecution.failure(writableDatabase, contract, handle.lease.grant, reason, handle.scope,
                requireNotNull(handle.election.snapshot().coordinatorDeviceId), capabilityEmitter(writableDatabase))
            check(connectionCurrent()); true
        }
    }

    @Synchronized
    internal fun meshTranscriptionFailures(captures: List<String>): Map<String, String> = runtimeSettings.transaction {
        buildMap { captures.forEach { id -> meshSourceTasks.failure(writableDatabase, id)?.let { put(id, it) } } }
    }

    /** Record recipient and both revisions in the same transaction that forms
     * the actual outgoing offer, before sending any bytes to that recipient. */
    private fun noteMeshOffer(plan: MeshGroupPlan, local: String, coordinator: String, target: String, request: JSONObject) {
        if (request.opt("action") != "offer") return
        require(meshCoordinator.snapshot(writableDatabase, local, plan).coordinatorDeviceId == coordinator)
        val contract = request.getJSONObject("contract"); BufferMeshTaskContract.validate(contract)
        val intent = requireNotNull(meshSourceTasks.load(writableDatabase, contract.getString("job_id")))
        require(intent.scope == plan.binding.getString("revision") && intent.terminal == null &&
            intent.state == meshTaskSourceState(contract) && BufferCrdt.canonical(intent.contract) == BufferCrdt.canonical(contract))
        require(meshVelaCurrent(contract))
        if (contract.get("stage") == "classify") require(meshClassificationCurrent(intent))
        if (contract.get("stage") == "answer") require(meshAnswerCurrent(contract))
        if (contract.get("stage") == "generate") require(meshWeeklyCurrent(intent))
        BufferMeshScopedDispatch.note(writableDatabase, intent, local, coordinator, target, capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun meshScopedCancellations(local: String, after: String): List<MeshScopedCancellation> = runtimeSettings.transaction {
        val plan = meshGroupPlan(local)
        BufferMeshScopedDispatch.reconcile(writableDatabase, local, plan.binding.getString("revision"), meshSourceTasks, targetRevision = { peer ->
            if (peer == local) plan.binding.getString("revision")
            else if (peer in plan.peers) BufferMeshConfirmations.reply(writableDatabase, plan, peer)?.getString("revision") else null
        }, emit = capabilityEmitter(writableDatabase))
        BufferMeshScopedDispatch.pending(writableDatabase, local, after)
    }

    @Synchronized
    internal fun acknowledgeMeshScopedCancellation(local: String, cancellation: MeshScopedCancellation, reply: JSONObject,
                                                    connectionCurrent: () -> Boolean) = runtimeSettings.transaction {
        check(connectionCurrent() && cancellation.source == local)
        BufferMeshScopedDispatch.acknowledge(writableDatabase, cancellation, reply, capabilityEmitter(writableDatabase))
        check(connectionCurrent())
    }

    @Synchronized
    internal fun meshSourceCancellations(local: String, expected: JSONObject, after: String): List<MeshSourceCancellation> = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        BufferMeshSourceDispatch.pending(writableDatabase, plan, after, meshSourceTasks)
    }

    @Synchronized
    internal fun acknowledgeMeshSourceCancellation(local: String, expected: JSONObject, cancellation: MeshSourceCancellation, result: JSONObject, connectionCurrent: () -> Boolean) = runtimeSettings.transaction {
        check(connectionCurrent())
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        require(cancellation.contract.getString("source_device") == local && (cancellation.coordinator == local || cancellation.coordinator in plan.peers))
        BufferMeshSourceDispatch.acknowledge(writableDatabase, cancellation, result, capabilityEmitter(writableDatabase))
        check(connectionCurrent())
    }

    @Synchronized
    internal fun resolveMeshSourceCancellation(local: String, expected: JSONObject, coordinator: String, job: String, result: JSONObject, connectionCurrent: () -> Boolean) = runtimeSettings.transaction {
        check(connectionCurrent())
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        require(meshCoordinator.snapshot(writableDatabase, local, plan).coordinatorDeviceId == coordinator)
        require(result.keys().asSequence().toSet() == setOf("status", "receipt_event_id") && result.get("status") == "cancelled")
        require(result.getString("receipt_event_id").matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        val intent = requireNotNull(meshSourceTasks.load(writableDatabase, job))
        require(intent.contract.getString("source_device") == local && intent.scope == plan.binding.getString("revision"))
        if (intent.terminal == null) {
            meshSourceTasks.resolve(writableDatabase, intent, "superseded",
                JSONObject().put("coordinator", coordinator).put("cancellation", result), capabilityEmitter(writableDatabase))
            releaseMeshClassification(intent)
            releaseMeshWeekly(intent)
            releaseMeshVela(intent.contract)
            if (intent.contract.get("stage") == "answer") BufferMeshAnswerSource.resolve(writableDatabase,
                BufferMeshAnswerSource.load(writableDatabase, job), "superseded", capabilityEmitter(writableDatabase))
        }
        check(connectionCurrent())
    }

    @Synchronized
    internal fun meshTaskRequest(local: String, expected: JSONObject, peer: String, nonce: String, request: JSONObject): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
        require(UUID.fromString(nonce).toString() == nonce)
        val remote = requireNotNull(BufferMeshConfirmations.reply(writableDatabase, plan, peer))
        noteMeshOffer(plan, local, peer, remote.getString("revision"), request)
        meshEnvelope("buffer.mesh.job", local, nonce, remote.getString("revision"), plan).put("request", request)
    }

    /** The caller must additionally fence its actual Hub client/connection
     * generation before handing an authenticated invoke callback to this method. */
    @Synchronized
    internal fun acceptMeshTaskReply(local: String, expected: JSONObject, peer: String, nonce: String, reply: JSONObject): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
        val remote = validateMeshCoordinatorEnvelope(reply, "buffer.mesh.job.reply", peer, plan, "result")
        require(reply.getString("nonce") == nonce)
        val confirmed = requireNotNull(BufferMeshConfirmations.reply(writableDatabase, plan, peer))
        require(BufferCrdt.canonical(remote) == BufferCrdt.canonical(confirmed)) { "设备配置需要重新确认" }
        reply.getJSONObject("result")
    }

    @Synchronized
    internal fun meshInputRequest(local: String, expected: JSONObject, peer: String, nonce: String, body: JSONObject): JSONObject =
        meshTaskRequest(local, expected, peer, nonce, body).put("type", "buffer.mesh.input")

    @Synchronized
    internal fun meshFactsRequest(local: String, expected: JSONObject, peer: String, nonce: String, body: JSONObject): JSONObject =
        meshTaskRequest(local, expected, peer, nonce, body).put("type", "buffer.mesh.facts")

    @Synchronized
    internal fun exchangeMeshFacts(peer: String, local: String, request: JSONObject): JSONObject = runtimeSettings.transaction {
        if (!BufferMeshMembers.allowed(writableDatabase, peer, local)) throw SecurityException("设备未获得 Buffer 授权")
        val plan = meshGroupPlan(local)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
        val remote = validateMeshCoordinatorEnvelope(request, "buffer.mesh.facts", peer, plan, "request")
        require(BufferCrdt.canonical(remote) == BufferCrdt.canonical(requireNotNull(BufferMeshConfirmations.reply(writableDatabase, plan, peer))))
        meshEnvelope("buffer.mesh.facts.reply", local, request.getString("nonce"), remote.getString("revision"), plan)
            .put("result", request.getJSONObject("request").let { body ->
                if (body.optString("action") == "history") {
                    require(body.keys().asSequence().toSet() == setOf("action", "request"))
                    require(BufferMeshConfiguration.offers(writableDatabase).single { it.getString("device_id") == peer }
                        .getJSONObject("capabilities").optLong("HISTORY", 0L) == 1L)
                    BufferMeshHistory.exchange(this, peer, body.getJSONObject("request")) {
                        BufferMeshMembers.allowed(writableDatabase, peer, local)
                    }
                } else BufferMeshFacts.exchange(writableDatabase, peer, body)
            })
    }

    @Synchronized
    internal fun acceptMeshFactsReply(local: String, expected: JSONObject, peer: String, nonce: String, reply: JSONObject): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
        val remote = validateMeshCoordinatorEnvelope(reply, "buffer.mesh.facts.reply", peer, plan, "result")
        require(reply.get("nonce") == nonce && BufferCrdt.canonical(remote) ==
            BufferCrdt.canonical(requireNotNull(BufferMeshConfirmations.reply(writableDatabase, plan, peer))))
        reply.getJSONObject("result")
    }

    @Synchronized
    internal fun meshHistoryPeers(local: String, expected: JSONObject): List<String> = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(BufferMeshActivations.current(writableDatabase, plan))
        BufferMeshConfiguration.offers(writableDatabase).filter { it.getString("device_id") in plan.peers &&
            it.getJSONObject("capabilities").optLong("HISTORY", 0L) == 1L }.map { it.getString("device_id") }
    }

    /** Observe the real current registers before export, as the BLE document
     * exporter does. Preserve incoming actor stamps and original history; a
     * received private document can only be observed back to its owner. */
    @Synchronized
    internal fun observeMeshDocuments(peer: String, after: String, authorize: () -> Boolean): JSONObject {
        check(writableDatabase.inTransaction() && authorize())
        require(after.isEmpty() || (after.startsWith("crdt:") && after.length <= 165))
        val rows = writableDatabase.rawQuery("SELECT key,value FROM runtime_settings WHERE key LIKE 'crdt:%' AND key>? ORDER BY key LIMIT 8",
            arrayOf(after)).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }
        for ((key, raw) in rows) {
            check(authorize())
            val document = BufferSourceEvent.parseObject(raw, 65536); BufferCrdtView.validate(document)
            val id = document.getString("id"); require(key == "crdt:$id")
            val audience = documentAudience(id, document)
            if ((audience == "local" || audience == peer) && sourceDocumentContextAllowed(document))
                observeDocument(document, audience, "DocumentMerged")
        }
        check(authorize())
        return JSONObject().put("after", rows.lastOrNull()?.first ?: after).put("more", rows.size == 8).put("head", BufferEventJournal.head(writableDatabase))
    }

    /** Hold the configuration and source import transaction across the local
     * reservation/import. The actual Session guard is checked at both ends. */
    @Synchronized
    internal fun <T> withMeshHistory(local: String, expected: JSONObject, peer: String,
        connectionCurrent: () -> Boolean, block: (() -> Boolean) -> T): T = runtimeSettings.transaction {
        check(connectionCurrent())
        require(peer in meshHistoryPeers(local, expected) && BufferMeshMembers.allowed(writableDatabase, peer, local))
        val value = block(connectionCurrent)
        check(connectionCurrent())
        value
    }

    @Synchronized
    internal fun meshFactsResume(local: String, expected: JSONObject, peer: String): BufferMeshFacts.Resume = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan))
        BufferMeshFacts.recover(writableDatabase)
        BufferMeshFacts.resume(writableDatabase, peer, local)
    }

    @Synchronized
    internal fun commitMeshFacts(local: String, expected: JSONObject, peer: String, resume: BufferMeshFacts.Resume,
                                 page: JSONObject, originals: List<String>, connectionCurrent: () -> Boolean) = runtimeSettings.transaction {
        check(connectionCurrent())
        val plan = currentMeshPlan(local, expected)
        require(peer in plan.peers && BufferMeshActivations.current(writableDatabase, plan) &&
            BufferMeshConfirmations.reply(writableDatabase, plan, peer) != null)
        BufferMeshFacts.commit(writableDatabase, peer, local, resume, page, originals)
        check(connectionCurrent())
    }

    @Synchronized
    internal fun meshFactCounts(): Map<String, Int> = BufferMeshFacts.counts(readableDatabase)

    @Synchronized
    internal fun meshTaskHistory(): List<BufferMeshFacts.TaskHistory> = BufferMeshFacts.history(readableDatabase)

    @Synchronized
    internal fun acceptMeshInputReply(local: String, expected: JSONObject, peer: String, nonce: String, reply: JSONObject): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan) && peer in plan.peers)
        val remote = validateMeshCoordinatorEnvelope(reply, "buffer.mesh.input.reply", peer, plan, "result")
        require(reply.getString("nonce") == nonce && BufferCrdt.canonical(remote) ==
            BufferCrdt.canonical(requireNotNull(BufferMeshConfirmations.reply(writableDatabase, plan, peer))))
        reply.getJSONObject("result")
    }

    @Synchronized
    internal fun meshLocalInput(local: String, expected: JSONObject, body: JSONObject): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        val request = meshEnvelope("buffer.mesh.input", local, UUID.randomUUID().toString(), plan.binding.getString("revision"), plan).put("request", body)
        exchangeMeshTaskInput(local, local, request).getJSONObject("result")
    }

    private fun meshSourceState(capture: String): String {
        check(writableDatabase.inTransaction())
        val state = JSONObject().put("projection_recovery", BufferSourceEffects.revision(writableDatabase))
        findCapture(capture)?.sourceDevice?.let { source ->
            runtimeSettings.getString("vela_controller_view:$source", null)?.let { raw ->
                state.put("vela_membership_hash", JSONObject(raw).getString("membership_hash"))
            }
        }
        for (table in listOf("captures", "cards")) {
            val rows = JSONArray()
            val order = if (table == "cards") "card_id" else "capture_id"
            writableDatabase.rawQuery("SELECT * FROM $table WHERE capture_id=? ORDER BY $order", arrayOf(capture)).use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONObject().also { row ->
                    for (column in 0 until cursor.columnCount) row.put(cursor.getColumnName(column),
                        if (cursor.isNull(column)) JSONObject.NULL else cursor.getString(column))
                })
            }
            state.put(table, rows)
        }
        return BufferMeshTaskContract.digest(state)
    }

    private fun meshAnswerCardState(card: String): String {
        check(writableDatabase.inTransaction())
        val value = JSONObject()
        writableDatabase.rawQuery("SELECT * FROM cards WHERE card_id=?", arrayOf(card)).use { row ->
            if (row.moveToFirst()) for (column in 0 until row.columnCount) value.put(row.getColumnName(column),
                if (row.isNull(column)) JSONObject.NULL else row.getString(column))
        }
        return BufferMeshTaskContract.digest(value)
    }

    private fun meshTaskSourceState(contract: JSONObject): String = when (contract.getString("stage")) {
        "answer" -> meshAnswerCardState(BufferMeshAnswerSource.load(writableDatabase, contract.getString("job_id")).input.getString("card_id"))
        "generate" -> BufferMeshTaskContract.digest(JSONObject().put("subject_id", contract.getString("subject_id"))
            .put("request_event_id", contract.getString("source_event_id")).put("input_sha256", contract.getString("input_sha256"))
            .put("card_state", meshAnswerCardState("${contract.getString("subject_id")}-llm")))
        else -> meshSourceState(contract.getString("capture_id"))
    }

    private fun weeklyRequestOriginal(job: JSONObject): BufferSourceEvent? {
        val id = job.opt("request_event_id") as? String ?: return null // Existing local-only request format.
        val original = BufferEventJournal.localRecord(writableDatabase, id); val p = original.event.getJSONObject("payload")
        check(original.envelope.get("provenance") == "native" && original.event.get("type") == "WeeklySummaryRequested" &&
            p.get("job_id") == job.get("id") && p.getLong("period_end") == job.getLong("requested_at") && p.get("trigger") == job.get("trigger") &&
            p.get("facts_sha256") == BufferMeshTaskContract.digest(job.getJSONObject("facts")))
        return original
    }

    private fun meshWeeklyEligible(job: JSONObject): Boolean {
        if (weeklyRequestOriginal(job) == null) return false
        val context = meshExecutionContext ?: return false
        if (!context.second()) return false
        val plan = meshGroupPlan(context.first)
        if (!BufferMeshActivations.current(writableDatabase, plan)) return false
        return runCatching { BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(writableDatabase), "generate") == "buffer-weekly-v1" }.getOrDefault(false)
    }

    private fun meshWeeklyWanted(contract: JSONObject): Boolean {
        val input = BufferMeshWeeklySource.load(writableDatabase, contract.getString("job_id"))
        val subject = contract.getString("subject_id")
        val job = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!).optJSONObject(subject) ?: return false
        return job.optString("state") in setOf("pending", "failed", "running") && job.opt("request_event_id") == contract.get("source_event_id") &&
            weeklyRequestOriginal(job)?.eventId == contract.getString("source_event_id") &&
            job.getLong("requested_at") == input.value.getLong("requested_at") &&
            BufferCrdt.canonical(job.getJSONObject("facts")) == BufferCrdt.canonical(input.value.getJSONObject("facts")) &&
            weeklyFactsStillCurrent(input.value.getJSONObject("facts")) && findCard("$subject-llm") == null
    }

    private fun meshWeeklyCurrent(intent: BufferMeshSourceTasks.Intent): Boolean {
        if (!meshWeeklyWanted(intent.contract)) return false
        val input = BufferMeshWeeklySource.load(writableDatabase, intent.contract.getString("job_id"))
        val stored = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!).getJSONObject(intent.contract.getString("subject_id"))
        return stored.optString("state") == "running" && stored.opt("lease") == input.lease && input.sourceRequest == intent.request
    }

    private fun renewMeshWeekly(intent: BufferMeshSourceTasks.Intent) {
        if (!meshWeeklyCurrent(intent)) return
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        jobs.getJSONObject(intent.contract.getString("subject_id")).put("lease_until", System.currentTimeMillis() + 180_000)
        runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
    }

    private fun releaseMeshWeekly(intent: BufferMeshSourceTasks.Intent) {
        if (intent.contract.get("stage") != "generate") return
        val input = BufferMeshWeeklySource.load(writableDatabase, intent.contract.getString("job_id"))
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        val job = jobs.optJSONObject(intent.contract.getString("subject_id")) ?: return
        if (job.optString("state") != "running" || job.opt("lease") != input.lease) return
        val reason = if (!weeklyFactsStillCurrent(input.value.getJSONObject("facts"))) "facts_changed"
            else if (findCard("${intent.contract.getString("subject_id")}-llm") != null) "existing_card" else "request_superseded"
        val message = when (reason) {
            "facts_changed" -> "记录已变化，请重新生成"
            "existing_card" -> "已有同 ID 卡片，保留现有内容"
            else -> "协作请求已失效，请重新生成"
        }
        job.put("state", "superseded").put("error", message)
        appendEvent("WeeklySummarySkipped", null, JSONObject().put("job_id", intent.contract.getString("subject_id")).put("reason", reason))
        job.remove("lease"); job.remove("lease_until")
        runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
    }

    @Synchronized
    internal fun meshWeeklyCandidates(after: String): List<String> = runtimeSettings.transaction {
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        jobs.keys().asSequence().filter { it > after }.sorted().filter {
            jobs.getJSONObject(it).optString("state") !in setOf("complete", "superseded") && meshWeeklyEligible(jobs.getJSONObject(it))
        }.take(8).toList()
    }

    @Synchronized
    internal fun prepareMeshWeekly(local: String, expected: JSONObject, subject: String): JSONObject? = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        val job = claimWeeklyLlm(System.currentTimeMillis(), forMesh = true, onlyId = subject) ?: return@transaction null
        val stored = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!).getJSONObject(subject)
        val original = requireNotNull(weeklyRequestOriginal(stored))
        val value = BufferWeeklyCodec.input(subject, job.requestedAt, job.facts)
        val contract = JSONObject().put("version", 3).put("source_device", local).put("subject_id", subject)
            .put("source_event_id", original.eventId).put("source_event_sha256", original.fingerprint).put("input_revision", original.logicalClock)
            .put("stage", "generate").put("skill_version", BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(writableDatabase), "generate"))
            .put("input_format", BufferMeshTaskContract.WEEKLY_FORMAT).put("input_sha256", BufferMeshTaskContract.digest(value))
            .put("input_bytes", BufferCrdt.canonical(value).toByteArray(Charsets.UTF_8).size).apply { put("job_id", BufferMeshTaskContract.identity(this)) }
        meshSourceTasks.prepare(writableDatabase, plan, contract, meshTaskSourceState(contract), capabilityEmitter(writableDatabase))
        val intent = requireNotNull(meshSourceTasks.load(writableDatabase, contract.getString("job_id")))
        if (intent.terminal != null) {
            // A terminal immutable task cannot be reopened by issuing another
            // local writer lease. Retire that exact writer so the UI can make
            // a new native request instead of reclaiming this one forever.
            val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
            val current = jobs.getJSONObject(subject)
            check(current.optString("state") == "running" && current.optString("lease") == job.lease &&
                current.getString("request_event_id") == original.eventId)
            current.put("state", "superseded").put("error", "协作请求已结束，请重新生成")
            current.remove("lease"); current.remove("lease_until")
            appendEvent("WeeklySummarySkipped", null, JSONObject().put("job_id", subject).put("reason", "mesh_task_closed"))
            runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
            return@transaction null
        }
        BufferMeshWeeklySource.bind(writableDatabase, intent, value, job.lease, capabilityEmitter(writableDatabase))
        renewMeshWeekly(intent)
        contract
    }

    @Synchronized
    internal fun meshSourcePollRequest(local: String, expected: JSONObject, contract: JSONObject): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        val intent = requireNotNull(meshSourceTasks.load(writableDatabase, contract.getString("job_id")))
        require(contract.get("source_device") == local && intent.scope == plan.binding.getString("revision") && intent.terminal == null)
        if ((contract.get("stage") == "generate" && !meshWeeklyCurrent(intent)) || !meshVelaCurrent(contract))
            JSONObject().put("action", "status").put("job_id", contract.getString("job_id"))
        else JSONObject().put("action", "offer").put("contract", contract)
    }

    private fun meshAnswerCurrent(contract: JSONObject): Boolean {
        val request = BufferMeshAnswerSource.load(writableDatabase, contract.getString("job_id"))
        val card = findCard(request.input.getString("card_id"))
        return request.terminal == null && card != null && card.kind == "question_card" && card.state != "deleted" &&
            card.captureId == contract.getString("capture_id") && request.cardState == meshAnswerCardState(card.cardId) &&
            questionAnswerRequestCurrent(card.cardId, contract.getString("source_event_id"))
    }

    /** Called only by the explicit question action. No imported fact or
     * background classifier creates a user answer request. */
    @Synchronized
    internal fun queueMeshQuestionAnswer(card: BufferCard): Boolean = runtimeSettings.transaction {
        val context = meshExecutionContext ?: return@transaction false
        if (!context.second()) return@transaction false
        val local = context.first; val plan = meshGroupPlan(local)
        if (!BufferMeshActivations.current(writableDatabase, plan)) return@transaction false
        val skill = runCatching { BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(writableDatabase), "answer") }.getOrNull() ?: return@transaction false
        val capture = card.captureId ?: return@transaction false
        if (!capture.matches(Regex("[A-Za-z0-9._-]{1,80}")) || !card.cardId.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) ||
            card.summary.isBlank() || card.summary.length > 32_000 || '\u0000' in card.summary) return@transaction false
        require(findCard(card.cardId) == card && card.kind == "question_card" && card.state != "deleted") { "问题已更新，请刷新后重试" }
        val state = meshAnswerCardState(card.cardId)
        val prior = BufferMeshAnswerSource.pending(writableDatabase, card = card.cardId)
        if (prior.any { it.cardState == state && it.contract.get("source_device") == local }) return@transaction true
        prior.forEach { BufferMeshAnswerSource.resolve(writableDatabase, it, "superseded", capabilityEmitter(writableDatabase)) }
        val count = writableDatabase.rawQuery("SELECT COUNT(*) FROM mesh_answer_requests WHERE terminal_event_id IS NULL", null)
            .use { check(it.moveToFirst()); it.getInt(0) }
        require(count < 64) { "待处理的问题较多，请稍后再试" }
        val input = BufferAnswerCodec.input(card.cardId, capture, card.summary)
        val sha = BufferMeshTaskContract.digest(input)
        appendEvent("QuestionAnswerRequested", capture, JSONObject().put("card_id", card.cardId).put("delivery", "mesh")
            .put("input_sha256", sha).put("card_state", state))
        val eventId = writableDatabase.rawQuery("SELECT event_id FROM events WHERE capture_id=? AND type='QuestionAnswerRequested' ORDER BY logical_clock DESC,rowid DESC LIMIT 1", arrayOf(capture))
            .use { check(it.moveToFirst()); it.getString(0) }
        val original = BufferEventJournal.localRecord(writableDatabase, eventId)
        setQuestionAnswerHead(card.cardId, eventId)
        val contract = JSONObject().put("version", 2).put("source_device", local).put("capture_id", capture)
            .put("source_event_id", original.eventId).put("source_event_sha256", original.fingerprint).put("input_revision", original.logicalClock)
            .put("stage", "answer").put("skill_version", skill).put("input_format", BufferMeshTaskContract.ANSWER_FORMAT)
            .put("input_sha256", sha).put("input_bytes", BufferCrdt.canonical(input).toByteArray(Charsets.UTF_8).size)
            .apply { put("job_id", BufferMeshTaskContract.identity(this)) }
        BufferMeshAnswerSource.save(writableDatabase, contract, input, state, capabilityEmitter(writableDatabase))
        check(context.second())
        true
    }

    @Synchronized
    internal fun meshAnswerCandidates(local: String, after: String): List<JSONObject> = runtimeSettings.transaction {
        BufferMeshAnswerSource.pending(writableDatabase, after).mapNotNull { request ->
            if (request.contract.get("source_device") == local && meshAnswerCurrent(request.contract)) request.contract
            else {
                BufferMeshAnswerSource.resolve(writableDatabase, request, "superseded", capabilityEmitter(writableDatabase))
                null
            }
        }
    }

    @Synchronized
    internal fun prepareMeshAnswer(local: String, expected: JSONObject, contract: JSONObject): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(BufferMeshActivations.current(writableDatabase, plan) && contract.get("source_device") == local && meshAnswerCurrent(contract))
        require(contract.get("skill_version") == BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(writableDatabase), "answer"))
        meshSourceTasks.prepare(writableDatabase, plan, contract, meshTaskSourceState(contract), capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun meshQuestionNotice(card: String): String? = runtimeSettings.transaction {
        val job = writableDatabase.rawQuery("SELECT r.job_id FROM mesh_answer_requests r JOIN question_answer_heads h ON h.event_id=r.request_event_id WHERE h.card_id=?", arrayOf(card))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return@transaction null
        val request = BufferMeshAnswerSource.load(writableDatabase, job)
        if (request.terminal == null) return@transaction if (meshAnswerCurrent(request.contract)) "已提交协作问答，结果将自动显示" else "原问答请求已失效，请按需重新发起"
        val result = BufferEventJournal.localRecord(writableDatabase, request.terminal).event.getJSONObject("payload")
        when (result.getString("status")) {
            "answered" -> "问题已获得短答案"
            "unresolved" -> "暂时未解：${result.getString("detail")}"
            else -> "原问答请求已失效，请按需重新发起"
        }
    }

    private fun meshSourceAudio(contract: JSONObject): ByteArray {
        val record = BufferEventJournal.localRecord(writableDatabase, contract.getString("source_event_id"))
        val event = record.event; val payload = event.getJSONObject("payload")
        val vela = event.get("type") == "VelaPendingCaptureImported"
        check(record.envelope.getString("provenance") == "native" && (vela || event.getString("type") == "CaptureCreated") &&
            record.fingerprint == contract.getString("source_event_sha256") && event.getString("capture_id") == contract.getString("capture_id") &&
            record.logicalClock == contract.getLong("input_revision"))
        if (vela) check(meshVelaReceipt(contract.getString("capture_id"))?.eventId == record.eventId &&
            payload.get("content_type") == contract.get("audio_format") && meshVelaCurrent(contract))
        else check(payload.opt("local_original") == true && payload.getString("audio_format") == contract.getString("audio_format"))
        val bytes = BufferSourceBlobs.snapshotOriginal(File(captureDir, "${contract.getString("capture_id")}.bin"), BufferConstants.maxAudioBytes)
        try {
            if (vela) check(BufferSourceBlobs.originalDigest(bytes) == payload.get("record_sha256"))
            else BufferSourceBlobs.verifyOriginalBytes(record, bytes)
            check(bytes.size.toLong() == contract.getLong("input_bytes") && BufferSourceBlobs.originalDigest(bytes) == contract.getString("input_sha256"))
            return bytes
        } catch (error: Exception) { bytes.fill(0); throw error }
    }

    @Synchronized
    internal fun prepareMeshTranscription(local: String, expected: JSONObject, captureId: String): JSONObject = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        val capture = requireNotNull(findCapture(captureId))
        val vela = VelaPendingCaptureProtocol.isFabricDeviceId(capture.sourceDevice)
        require((capture.sourceDevice in setOf("android", deviceId()) || vela) && capture.transcript.isNullOrBlank() && cardForCapture(captureId)?.state == "pending_transcription")
        if (vela) require(meshVelaLease(captureId, "transcribe", acquire = true) != null) { "Vela 任务所有权尚未就绪" }
        val eventId = if (vela) requireNotNull(meshVelaReceipt(captureId)).eventId else writableDatabase.rawQuery("SELECT event_id FROM events WHERE capture_id=? AND type='CaptureCreated' ORDER BY logical_clock,rowid LIMIT 1", arrayOf(captureId))
            .use { check(it.moveToFirst()); it.getString(0) }
        val record = BufferEventJournal.localRecord(writableDatabase, eventId)
        val blob = if (vela) {
            val bytes = BufferSourceBlobs.snapshotOriginal(File(captureDir, "$captureId.bin"), BufferConstants.maxAudioBytes)
            try {
                val digest = BufferSourceBlobs.originalDigest(bytes)
                check(digest == record.event.getJSONObject("payload").get("record_sha256"))
                JSONObject().put("sha256", digest).put("bytes", bytes.size)
            } finally { bytes.fill(0) }
        } else record.event.getJSONObject("payload").getJSONObject("blob")
        val contract = JSONObject().put("version", 1).put("source_device", local).put("capture_id", captureId)
            .put("source_event_id", eventId).put("source_event_sha256", record.fingerprint).put("stage", "transcribe")
            .put("input_revision", record.logicalClock).put("skill_version", BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(writableDatabase)))
            .put("input_sha256", blob.getString("sha256")).put("input_bytes", blob.getLong("bytes")).put("audio_format", capture.audioFormat)
        contract.put("job_id", BufferMeshTaskContract.identity(contract))
        BufferMeshTaskContract.validate(contract)
        meshSourceAudio(contract).fill(0)
        meshSourceTasks.prepare(writableDatabase, plan, contract, meshSourceState(captureId), capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun prepareMeshClassification(local: String, expected: JSONObject, job: BufferClassificationJob): JSONObject? = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected)
        require(BufferMeshActivations.current(writableDatabase, plan) && meshClassificationSupported() && nativeLocalClassificationCapture(job.captureId))
        require(findCapture(job.captureId)?.transcript == job.transcript)
        val vela = findCapture(job.captureId)?.sourceDevice?.let(VelaPendingCaptureProtocol::isFabricDeviceId) == true
        if (vela) require(meshVelaLease(job.captureId, "classify", acquire = true) != null) { "Vela 任务所有权尚未就绪" }
        // Recover only a request that this source previously delegated and
        // still owns. An unrelated local classifier's lease is never stolen.
        val priorId = writableDatabase.rawQuery("SELECT s.job_id FROM mesh_task_sources s JOIN mesh_classification_requests r ON r.job_id=s.job_id " +
            "JOIN classification_jobs j ON j.lease_id=r.lease_id WHERE j.capture_id=? AND j.state='processing' AND s.terminal_event_id IS NULL LIMIT 1",
            arrayOf(job.captureId)).use { if (it.moveToFirst()) it.getString(0) else null }
        val prior = priorId?.let { meshSourceTasks.load(writableDatabase, it) }
        val priorLease = prior?.let { BufferMeshClassificationSource.request(writableDatabase, it) }
            ?.takeIf { classificationRequestCurrent(job.captureId, it, job.transcript) }
        val lease = priorLease ?: claimClassificationRequest(job.captureId, expectedTranscript = job.transcript) ?: return@transaction null
        if (hasUserCardActivity(writableDatabase, job.captureId)) {
            writableDatabase.update("classification_jobs", ContentValues().apply {
                put("state", "complete"); put("updated_at", System.currentTimeMillis()); putNull("lease_id")
            }, "capture_id=? AND lease_id=?", arrayOf(job.captureId, lease))
            appendEvent("ClassificationSkipped", job.captureId, JSONObject().put("capture_id", job.captureId).put("reason", "user_activity"))
            return@transaction null
        }
        val input = if (prior != null && priorLease != null) BufferMeshClassificationSource.load(writableDatabase, prior.contract)
            else BufferMeshClassificationSource.input(job, focusSession()?.json(), allowAssistantDecision = vela &&
                meshVelaReceipt(job.captureId)?.event?.getJSONObject("payload")?.optString("target", "buffer") == "auto")
        val skill = BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(writableDatabase), "classify")
        val contract = if (prior != null && priorLease != null && prior.contract.get("source_device") == local && prior.contract.get("skill_version") == skill) prior.contract else {
            val id = writableDatabase.rawQuery("SELECT event_id FROM events WHERE capture_id=? AND type='CaptureTranscribed' ORDER BY logical_clock DESC,rowid DESC LIMIT 1", arrayOf(job.captureId))
                .use { check(it.moveToFirst()); it.getString(0) }
            val original = BufferEventJournal.localRecord(writableDatabase, id)
            JSONObject().put("version", 2).put("source_device", local).put("capture_id", job.captureId)
                .put("source_event_id", id).put("source_event_sha256", original.fingerprint).put("input_revision", original.logicalClock)
                .put("stage", "classify").put("skill_version", skill)
                .put("input_format", BufferMeshTaskContract.CLASSIFICATION_FORMAT).put("input_sha256", BufferMeshTaskContract.digest(input))
                .put("input_bytes", BufferCrdt.canonical(input).toByteArray(Charsets.UTF_8).size).apply { put("job_id", BufferMeshTaskContract.identity(this)) }
        }
        BufferMeshClassificationSource.save(writableDatabase, contract, input, capabilityEmitter(writableDatabase))
        meshSourceTasks.prepare(writableDatabase, plan, contract, meshSourceState(job.captureId), capabilityEmitter(writableDatabase))
        val intent = requireNotNull(meshSourceTasks.load(writableDatabase, contract.getString("job_id")))
        if (intent.terminal != null) { releaseClassificationRequest(job.captureId, lease); return@transaction null }
        BufferMeshClassificationSource.bind(writableDatabase, intent, lease, capabilityEmitter(writableDatabase))
        contract
    }

    /** Call only after the matching authenticated task reply and Hub generation
     * have been checked. An ordinary inbound member RPC cannot install permits. */
    @Synchronized
    internal fun pinMeshSourceLease(local: String, expected: JSONObject, coordinator: String, result: JSONObject, started: Long, connectionCurrent: () -> Boolean) = runtimeSettings.transaction {
        check(connectionCurrent())
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        require(result.keys().asSequence().toSet() == setOf("status", "contract", "grant") && result.get("status") == "leased")
        val grant = BufferMeshSourceTasks.grant(result.getJSONObject("grant"))
        val intent = requireNotNull(meshSourceTasks.load(writableDatabase, grant.jobId))
        require(intent.contract.getString("source_device") == local && BufferCrdt.canonical(result.getJSONObject("contract")) == BufferCrdt.canonical(intent.contract))
        require(intent.state == meshTaskSourceState(intent.contract))
        if (intent.contract.get("stage") == "answer") require(meshAnswerCurrent(intent.contract))
        if (intent.contract.get("stage") == "generate") require(meshWeeklyCurrent(intent))
        require(meshVelaCurrent(intent.contract))
        require(meshCoordinator.executorCurrent(writableDatabase, local, plan, grant))
        meshSourceTasks.pin(writableDatabase, plan, coordinator, grant, meshCoordinator.snapshot(writableDatabase, local, plan), started, android.os.SystemClock.elapsedRealtime())
        if (!connectionCurrent()) { meshSourceTasks.reset(); error("协作连接已变化") }
    }

    @Synchronized
    internal fun pendingMeshSourceTasks(local: String, expected: JSONObject): List<JSONObject> = runtimeSettings.transaction {
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        meshSourceTasks.pending(writableDatabase, plan).mapNotNull { intent ->
            require(intent.contract.getString("source_device") == local)
            val capture = BufferMeshTaskContract.target(intent.contract)
            val classification = intent.contract.get("stage") == "classify"
            val current = intent.state == meshTaskSourceState(intent.contract) && when (intent.contract.getString("stage")) {
                "classify" -> meshClassificationCurrent(intent)
                "answer" -> meshAnswerCurrent(intent.contract)
                "generate" -> meshWeeklyWanted(intent.contract)
                else -> cardForCapture(capture)?.state == "pending_transcription"
            }
            if (current) {
                if (intent.contract.get("stage") in setOf("transcribe", "classify") &&
                    findCapture(capture)?.sourceDevice?.let(VelaPendingCaptureProtocol::isFabricDeviceId) == true)
                    meshVelaLease(capture, intent.contract.getString("stage"), acquire = true)
                if (classification) {
                    val lease = requireNotNull(BufferMeshClassificationSource.request(writableDatabase, intent))
                    check(writableDatabase.update("classification_jobs", ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
                        "capture_id=? AND state='processing' AND lease_id=?", arrayOf(capture, lease)) == 1)
                }
                if (intent.contract.get("stage") == "generate") renewMeshWeekly(intent)
                intent.contract
            }
            else {
                meshSourceTasks.resolve(writableDatabase, intent, "superseded", JSONObject().put("reason", "capture_changed"), capabilityEmitter(writableDatabase))
                releaseMeshClassification(intent)
                releaseMeshWeekly(intent)
                releaseMeshVela(intent.contract)
                if (intent.contract.get("stage") == "answer") BufferMeshAnswerSource.resolve(writableDatabase,
                    BufferMeshAnswerSource.load(writableDatabase, intent.contract.getString("job_id")), "superseded", capabilityEmitter(writableDatabase))
                null
            }
        }
    }

    @Synchronized
    internal fun exchangeMeshTaskInput(peer: String, local: String, request: JSONObject): JSONObject = runtimeSettings.transaction {
        if (peer != local && !BufferMeshMembers.allowed(writableDatabase, peer, local)) throw SecurityException("设备未获得 Buffer 授权")
        val plan = meshGroupPlan(local); require(BufferMeshActivations.current(writableDatabase, plan))
        val remote = validateMeshCoordinatorEnvelope(request, "buffer.mesh.input", peer, plan, "request")
        require(BufferCrdt.canonical(remote) == BufferCrdt.canonical(if (peer == local) plan.binding else requireNotNull(BufferMeshConfirmations.reply(writableDatabase, plan, peer))))
        val body = request.getJSONObject("request")
        val toolRead = body.has("action")
        require(body.keys().asSequence().toSet() == (if (toolRead) setOf("job_id", "lease_id", "action", "argument") else setOf("job_id", "lease_id", "offset")))
        val job = body.getString("job_id"); val lease = body.getString("lease_id")
        require(body.get("job_id") is String && job.matches(Regex("[0-9a-f]{64}")) && body.get("lease_id") is String && UUID.fromString(lease).toString() == lease)
        fun authorize(): BufferMeshSourceTasks.Intent {
            val intent = meshSourceTasks.authorize(writableDatabase, plan, peer, job, lease,
                meshCoordinator.snapshot(writableDatabase, local, plan), android.os.SystemClock.elapsedRealtime())
            require(intent.contract.getString("source_device") == local && intent.state == meshTaskSourceState(intent.contract))
            if (intent.contract.get("stage") == "classify") require(meshClassificationCurrent(intent))
            if (intent.contract.get("stage") == "answer") require(meshAnswerCurrent(intent.contract))
            if (intent.contract.get("stage") == "generate") require(meshWeeklyCurrent(intent))
            require(meshVelaCurrent(intent.contract))
            require(meshCoordinator.executorCurrent(writableDatabase, local, plan, meshSourceTasks.permitGrant(job)))
            return intent
        }
        val intent = authorize()
        if (toolRead) {
            require(intent.contract.get("stage") == "classify" && body.get("action") is String && body.get("argument") is String)
            val result = BufferMeshClassificationSource.records(writableDatabase, intent.contract, lease,
                body.getString("action"), body.getString("argument"), authorize = { authorize(); Unit },
                search = { searchLocalRecords(it, excludeCapture = intent.contract.getString("capture_id")) }, read = ::findCard,
                emit = capabilityEmitter(writableDatabase))
            authorize()
            val requestLease = requireNotNull(BufferMeshClassificationSource.request(writableDatabase, intent))
            recordClassificationTool(intent.contract.getString("capture_id"), requestLease, body.getString("action"), true, result.getJSONArray("records").length())
            return@transaction meshEnvelope("buffer.mesh.input.reply", local, request.getString("nonce"), remote.getString("revision"), plan).put("result", result)
        }
        val maximum = when (intent.contract.getString("stage")) {
            "transcribe" -> BufferConstants.maxAudioBytes
            "generate" -> BufferWeeklyCodec.MAX_INPUT_BYTES
            else -> BufferMeshTaskContract.MAX_CLASSIFICATION_INPUT_BYTES
        }
        val offset = BufferMeshTaskContract.integer(body, "offset", 0L..maximum.toLong()).toInt()
        val bytes = when (intent.contract.getString("stage")) {
            "classify" -> BufferCrdt.canonical(BufferMeshClassificationSource.load(writableDatabase, intent.contract)).toByteArray(Charsets.UTF_8)
            "answer" -> BufferCrdt.canonical(BufferMeshAnswerSource.load(writableDatabase, job).input).toByteArray(Charsets.UTF_8)
            "generate" -> BufferCrdt.canonical(BufferMeshWeeklySource.load(writableDatabase, job).value).toByteArray(Charsets.UTF_8)
            else -> meshSourceAudio(intent.contract)
        }
        try {
            require(offset < bytes.size)
            val part = bytes.copyOfRange(offset, minOf(bytes.size, offset + 16 * 1024))
            val encoded = try { android.util.Base64.encodeToString(part, android.util.Base64.NO_WRAP) } finally { part.fill(0) }
            authorize()
            val result = JSONObject().put("job_id", job).put("lease_id", lease).put("offset", offset).put("total", bytes.size)
                .put("input_sha256", intent.contract.getString("input_sha256")).put("data_b64", encoded)
            if (intent.contract.get("stage") != "transcribe") result.put("input_format", intent.contract.getString("input_format"))
            else result.put("audio_format", intent.contract.getString("audio_format"))
            meshEnvelope("buffer.mesh.input.reply", local, request.getString("nonce"), remote.getString("revision"), plan).put("result", result)
        } finally { bytes.fill(0) }
    }

    @Synchronized
    /** The result must come from a verified current-coordinator task reply;
     * receiving history or an ordinary member invocation never calls this. */
    internal fun applyMeshSourceResult(local: String, expected: JSONObject, coordinator: String, result: JSONObject, connectionCurrent: () -> Boolean): String = runtimeSettings.transaction {
        check(connectionCurrent())
        val plan = currentMeshPlan(local, expected); require(BufferMeshActivations.current(writableDatabase, plan))
        require(result.keys().asSequence().toSet() == setOf("status", "job_id", "receipt_event_id", "result_sha256", "result", "grant") && result.get("status") == "completed")
        val job = result.getString("job_id"); val intent = requireNotNull(meshSourceTasks.load(writableDatabase, job))
        require(intent.contract.getString("source_device") == local)
        if (intent.terminal != null) return@transaction "resolved"
        val grant = BufferMeshSourceTasks.grant(result.getJSONObject("grant"))
        meshSourceTasks.validateGrant(intent, plan, coordinator, grant, meshCoordinator.snapshot(writableDatabase, local, plan), terminalReceipt = true)
        val value = result.getJSONObject("result"); BufferMeshExecution.validateResult(intent.contract, value)
        require(BufferMeshTaskContract.digest(value) == result.getString("result_sha256"))
        require(result.getString("receipt_event_id").matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        val applied = withMeshVelaOwner(intent.contract) {
            applyMeshSourceResultOwned(intent, coordinator, result, value, grant, connectionCurrent)
        } ?: return@transaction "waiting"
        if (applied != "waiting") releaseMeshVela(intent.contract)
        applied
    }

    private fun applyMeshSourceResultOwned(intent: BufferMeshSourceTasks.Intent, coordinator: String, result: JSONObject,
        value: JSONObject, grant: com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorLeaseGrant,
        connectionCurrent: () -> Boolean): String {
        val job = intent.contract.getString("job_id")
        val capture = BufferMeshTaskContract.target(intent.contract)
        val classification = intent.contract.get("stage") == "classify"
        val current = intent.state == meshTaskSourceState(intent.contract) && when (intent.contract.getString("stage")) {
            "classify" -> meshClassificationCurrent(intent)
            "answer" -> meshAnswerCurrent(intent.contract)
            "generate" -> meshWeeklyWanted(intent.contract)
            else -> cardForCapture(capture)?.state == "pending_transcription"
        }
        val failed = value.has("failure")
        // An unchanged immutable summary may already have a durable receipt
        // while its local request is temporarily owned by a local worker.
        // Wait for that writer rather than closing/restarting the logical task.
        if (current && intent.contract.get("stage") == "generate" && !meshWeeklyCurrent(intent)) return "waiting"
        if (current && !failed) {
            if (intent.contract.get("stage") == "generate") {
                val input = BufferMeshWeeklySource.load(writableDatabase, job)
                check(connectionCurrent())
                val weekly = WeeklyLlmJob(capture, input.lease, input.value.getLong("requested_at"), input.value.getJSONObject("facts"))
                check(completeWeeklyLlm(weekly, BufferWeeklyCodec.result(value), null, executor = grant.ownerDeviceId))
            } else if (intent.contract.get("stage") == "answer") {
                val request = BufferMeshAnswerSource.load(writableDatabase, job)
                val card = requireNotNull(findCard(request.input.getString("card_id")))
                check(connectionCurrent())
                val output = BufferAnswerCodec.decode(value)
                val status = when (output) {
                    is QuickAnswerResult.Answer -> { requireNotNull(saveQuestionAnswer(card.cardId, output.text, source = "remote", expected = card, request = intent.contract.getString("source_event_id"))); "answered" }
                    is QuickAnswerResult.Unresolved -> { recordQuestionAnswerDeferred(card, output.detail); "unresolved" }
                }
                BufferMeshAnswerSource.resolve(writableDatabase, request, status, capabilityEmitter(writableDatabase), (output as? QuickAnswerResult.Unresolved)?.detail)
            } else if (classification) {
                val input = BufferMeshClassificationSource.load(writableDatabase, intent.contract)
                if (BufferClassificationCodec.assistantDecision(value)) {
                    require(input.opt("allow_device_assistant") == true)
                    val lease = requireNotNull(BufferMeshClassificationSource.request(writableDatabase, intent))
                    check(connectionCurrent() && velaAssistantInput(capture, lease) != null)
                    selectAssistantHandoff(capture, lease)
                    // The existing durable local assistant path sends the
                    // request outside Repository/Gate/DB locks, with a fresh
                    // device-owner lease and an actual device acceptance.
                    releaseClassificationRequest(capture, lease)
                } else {
                val proposed = BufferSourceEvent.parseObject(BufferCrdt.canonical(value), 192 * 1024)
                val segments = proposed.getJSONArray("segments")
                val ids = linkedSetOf<String>()
                for (index in 0 until segments.length()) {
                    val related = segments.getJSONObject(index).getJSONArray("related_card_ids")
                    for (position in 0 until related.length()) ids.add(related.getString(position))
                }
                val trusted = BufferMeshClassificationSource.trustedIds(writableDatabase, intent.contract, ids, ::findCard)
                // Association is a source projection. A record changed or
                // removed since disclosure cannot retain a link merely because
                // it appeared in a historical executor/coordinator receipt.
                for (index in 0 until segments.length()) {
                    val segment = segments.getJSONObject(index); val related = segment.getJSONArray("related_card_ids")
                    segment.put("related_card_ids", JSONArray((0 until related.length()).map { related.getString(it) }.filter { it in trusted }))
                }
                val proposals = BufferClassificationCodec.decode(proposed, trusted)
                val lease = requireNotNull(BufferMeshClassificationSource.request(writableDatabase, intent))
                check(connectionCurrent())
                applyRemoteClassification(capture, proposals, lease, expectedTranscript = input.getString("transcript"))
                }
            } else {
                meshSourceAudio(intent.contract).fill(0)
                check(connectionCurrent())
                completeAudioTranscription(capture, value.getString("transcript"))
            }
        }
        val status = if (!current) "superseded" else if (failed) "failed" else "applied"
        meshSourceTasks.resolve(writableDatabase, intent, status, JSONObject().put("coordinator", coordinator).put("receipt", result), capabilityEmitter(writableDatabase))
        if (!current) releaseMeshClassification(intent)
        if (!current) releaseMeshWeekly(intent)
        if (!current && intent.contract.get("stage") == "answer") BufferMeshAnswerSource.resolve(writableDatabase,
            BufferMeshAnswerSource.load(writableDatabase, job), "superseded", capabilityEmitter(writableDatabase))
        check(connectionCurrent())
        return status
    }

    @Synchronized
    internal fun capabilityProposals() = BufferCapabilities.list(readableDatabase)

    @Synchronized
    internal fun capabilityGenerationState() = BufferCapabilities.generationState(readableDatabase)

    @Synchronized
    internal fun requestCapabilitySuggestions(): String = runtimeSettings.transaction {
        check(llmConfig().ready) { "请先配置 AI 服务" }
        BufferCapabilities.request(writableDatabase, capabilitySkill(), System.currentTimeMillis(), capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun cancelCapabilitySuggestions() = runtimeSettings.transaction {
        BufferCapabilities.cancelGeneration(writableDatabase, capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun claimCapabilitySuggestions(now: Long) = runtimeSettings.transaction { BufferCapabilities.claim(writableDatabase, now) }

    @Synchronized
    internal fun completeCapabilitySuggestions(job: com.mocharealm.foundation.fabric.buffer.domain.CapabilityGeneration, result: JSONObject): Boolean = runtimeSettings.transaction {
        BufferCapabilities.finish(writableDatabase, job, result, capabilitySkill(), System.currentTimeMillis(), capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun failCapabilitySuggestions(job: com.mocharealm.foundation.fabric.buffer.domain.CapabilityGeneration) = runtimeSettings.transaction {
        BufferCapabilities.fail(writableDatabase, job, System.currentTimeMillis())
    }

    @Synchronized
    internal fun decideCapability(id: String, version: Int, digest: String, enabled: Boolean) = runtimeSettings.transaction {
        BufferCapabilities.decide(writableDatabase, id, version, digest, enabled, deviceId(),
            if (enabled) capabilitySkill() else null, System.currentTimeMillis(), capabilityEmitter(writableDatabase))
    }

    @Synchronized
    internal fun reconcileCapabilities(now: Long, elapsed: Long, boot: String,
        deliver: (com.mocharealm.foundation.fabric.buffer.domain.CapabilityNotice) -> Boolean, withdraw: (String) -> Unit): Long? = runtimeSettings.transaction {
        val session = if (mode() == "focus") focusSession()?.takeIf { it.active }?.let { "${it.id}:${it.startedAt}" }
            ?: runtimeSettings.getLong(KEY_FOCUS_STARTED_AT, 0).takeIf { it > 0 }?.let { started ->
                val event = writableDatabase.rawQuery("SELECT event_id FROM events WHERE type='FocusStarted' ORDER BY logical_clock DESC LIMIT 1", null)
                    .use { if(it.moveToFirst()) it.getString(0) else null }
                "mode:${event ?: started.toString()}"
            } else null
        BufferCapabilities.reconcile(writableDatabase, deviceId(), runCatching { capabilitySkill() }.getOrNull(), session,
            now, elapsed, boot, deliver, withdraw, capabilityEmitter(writableDatabase))
    }

    private fun createVelaCardStateReceipts(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS vela_card_state_receipts (
            trusted_device_id TEXT NOT NULL, capture_id TEXT NOT NULL, card_id TEXT NOT NULL,
            revision INTEGER NOT NULL, action_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
            previous_state TEXT NOT NULL, state TEXT NOT NULL, projected_state TEXT NOT NULL,
            deleted INTEGER NOT NULL, review_at INTEGER NOT NULL, outcome TEXT NOT NULL,
            PRIMARY KEY(trusted_device_id, capture_id, card_id, revision, action_id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS vela_card_state_latest ON vela_card_state_receipts(" +
            "trusted_device_id, capture_id, card_id, revision DESC)")
    }

    private fun createCardReminders(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS card_reminders (
            card_id TEXT PRIMARY KEY, due_at INTEGER NOT NULL, revision TEXT NOT NULL,
            delivered INTEGER NOT NULL DEFAULT 0, cancelled INTEGER NOT NULL DEFAULT 0,
            retry_at INTEGER NOT NULL DEFAULT 0)""")
    }

    @Synchronized
    internal fun reconcileCardReminders(now: Long, deliver: (BufferCard) -> Boolean, cancel: (String) -> Unit): Long? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            data class Pending(val id: String, val due: Long, val revision: String, val delivered: Boolean,
                               val cancelled: Boolean, val retry: Long)
            val rows = mutableListOf<Pending>()
            db.rawQuery("SELECT card_id,due_at,revision,delivered,cancelled,retry_at FROM card_reminders", null).use {
                while (it.moveToNext()) rows += Pending(it.getString(0), it.getLong(1), it.getString(2),
                    it.getInt(3) != 0, it.getInt(4) != 0, it.getLong(5))
            }
            val quiet = mode() == "bedtime"
            var next: Long? = null
            for (row in rows) {
                if (BufferSourceEffects.fenced(db, row.id)) {
                    cancel(row.id); val retry = now + 5000; next = next?.coerceAtMost(retry) ?: retry; continue
                }
                val card = findCard(row.id)
                val quietKey = "card_reminder_quiet:${row.id}"
                if (row.cancelled || card == null || card.state != "reminder_requested") {
                    cancel(row.id)
                    db.delete("card_reminders", "card_id = ?", arrayOf(row.id))
                    runtimeSettings.edit().remove(quietKey).apply()
                    continue
                }
                if (quiet) {
                    cancel(row.id)
                    if (!row.delivered && now >= row.due && runtimeSettings.getString(quietKey, null) != row.revision) {
                        appendEvent(db, "CardReminderDeferred", card.captureId, now, JSONObject()
                            .put("card_id", row.id).put("revision", row.revision)
                            .put("scheduled_for", row.due).put("reason", "bedtime"))
                        runtimeSettings.edit().putString(quietKey, row.revision).apply()
                    }
                    // The mode transition / next app start reconciles these plans.
                    // Do not keep waking the device just to retry during quiet mode.
                    continue
                }
                if (row.delivered) continue
                if (now < row.due) cancel(row.id) // Remove a previously posted notification after rescheduling.
                val ready = maxOf(row.due, row.retry)
                if (now >= ready) {
                    if (deliver(card)) {
                        db.execSQL("UPDATE card_reminders SET delivered = 1 WHERE card_id = ?", arrayOf(row.id))
                        runtimeSettings.edit().remove(quietKey).apply()
                        appendEvent(db, "CardReminderPosted", card.captureId, now, JSONObject()
                            .put("card_id", row.id).put("revision", row.revision).put("scheduled_for", row.due))
                        continue
                    }
                    val retry = now + 60_000
                    db.execSQL("UPDATE card_reminders SET retry_at = ? WHERE card_id = ?", arrayOf<Any>(retry, row.id))
                    next = minOf(next ?: retry, retry)
                } else next = minOf(next ?: ready, ready)
            }
            db.setTransactionSuccessful()
            return next
        } finally { db.endTransaction() }
    }

    private fun createCardReturns(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS card_returns (
            card_id TEXT PRIMARY KEY, due_at INTEGER, return_state TEXT NOT NULL,
            returned_at INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS card_returns_due ON card_returns(due_at)")
    }

    @Synchronized
    internal fun reconcileCardReturns(now: Long): Long? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (BufferSourceEffects.undoing(db)||BufferSourceLegacyMigration.active(db)) { db.setTransactionSuccessful(); return now + 5000 }
            val due = mutableListOf<Triple<String, String, Long>>()
            db.rawQuery("SELECT card_id, return_state, due_at FROM card_returns WHERE due_at <= ?",
                arrayOf(now.toString())).use { cursor ->
                while (cursor.moveToNext()) due += Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
            }
            for ((id, state, scheduled) in due) {
                val card = findCard(id)
                if (card == null || card.state != "later") {
                    db.delete("card_returns", "card_id = ?", arrayOf(id))
                    continue
                }
                db.execSQL("UPDATE cards SET state = ? WHERE card_id = ?", arrayOf(state, id))
                db.execSQL("UPDATE card_returns SET due_at = NULL, returned_at = ? WHERE card_id = ?", arrayOf<Any>(now, id))
                appendEvent(db, "CardResurfaced", card.captureId, now, JSONObject()
                    .put("card_id", id).put("scheduled_for", scheduled).put("restored_state", state))
            }
            val next = db.rawQuery("SELECT MIN(due_at) FROM card_returns", null).use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
            db.setTransactionSuccessful()
            return next
        } finally { db.endTransaction() }
    }

    private fun createRuntimeSettings(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE runtime_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        settings.all.filterKeys { it == KEY_MODE || it.startsWith("focus_") }.forEach { (key, value) ->
            if (value != null) db.insertOrThrow("runtime_settings", null, ContentValues().apply {
                put("key", key); put("value", value.toString())
            })
        }
    }

    private fun migrateBedtimeSettings(db: SQLiteDatabase) {
        settings.all.filterKeys { it.startsWith("bedtime_") }.forEach { (key, value) ->
            if (value != null) db.insertOrThrow("runtime_settings", null, ContentValues().apply {
                put("key", key); put("value", value.toString())
            })
        }
    }

    fun deviceId(): String {
        val existing = settings.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val id = "android-${UUID.randomUUID()}"
        settings.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    @Synchronized
    fun pairVela(deviceId: String, token: String) {
        require(deviceId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid Vela device id" }
        require(token.length in 8..128) { "invalid Vela pairing token" }
        val pairedAt = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "paired_peers",
                null,
                ContentValues().apply {
                    put("device_id", deviceId)
                    put("token", token)
                    put("paired_at", pairedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            appendEvent(
                db,
                "PeerPaired",
                null,
                pairedAt,
                JSONObject().put("device_id", deviceId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun isVelaPaired(deviceId: String, token: String): Boolean {
        if (deviceId.isBlank() || token.isBlank()) return false
        readableDatabase.query(
            "paired_peers",
            arrayOf("device_id"),
            "device_id = ? AND token = ?",
            arrayOf(deviceId, token),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    internal fun velaAuthenticationToken(deviceId: String): String? =
        pairedVelaToken(deviceId) ?: velaEnrollments.authenticationToken(deviceId)

    fun pairedVelaToken(deviceId: String): String? {
        if (deviceId.isBlank()) return null
        readableDatabase.query(
            "paired_peers",
            arrayOf("token"),
            "device_id = ?",
            arrayOf(deviceId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun pairedVelaIds(): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.query(
            "paired_peers",
            arrayOf("device_id"),
            null,
            null,
            null,
            null,
            "paired_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0)
        }
        return result
    }

    fun mode(): String = runtimeSettings.getString(KEY_MODE, "normal") ?: "normal"

    fun bedtimeSchedule(): String =
        runtimeSettings.getString(KEY_BEDTIME_SCHEDULE, "")?.trim().orEmpty()

    @Synchronized
    fun setBedtimeSchedule(schedule: String) = runtimeSettings.transaction {
        val value = schedule.trim()
        require(value.isEmpty() || value.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))) {
            "睡前时间应为 HH:mm"
        }
        if (value == bedtimeSchedule() && bedtimePolicy().method != "calendar") return@transaction
        setBedtimePolicy(BedtimePolicy(if (value.isBlank()) "off" else "fixed", value))
    }

    @Synchronized
    internal fun bedtimePolicy(): BedtimePolicy = runtimeSettings.getString("bedtime_policy", null)?.let {
        BedtimePolicy.parse(JSONObject(it))
    } ?: bedtimeSchedule().let { BedtimePolicy(if (it.isBlank()) "off" else "fixed", it) }

    @Synchronized
    internal fun setBedtimePolicy(request: BedtimePolicy) = runtimeSettings.transaction {
        // Preserve the legacy fixed trigger under its original clock before a
        // strategy change replaces that clock. Both strategies own one night.
        val oldClock = bedtimeSchedule()
        val oldDay = runtimeSettings.getLong(KEY_BEDTIME_LAST_TRIGGERED_DAY, 0L)
        if (oldDay > 0L && oldClock.isNotBlank() && runtimeSettings.getString("bedtime_last_triggered_night", null) == null) {
            val night = bedtimeNightDay(oldDay, oldClock)
            runtimeSettings.edit().putBoolean("bedtime_triggered:$night", true)
                .putString("bedtime_last_triggered_night", night).apply()
        }
        val policy = request.copy(revision = UUID.randomUUID().toString())
        runtimeSettings.edit().apply {
            putString("bedtime_policy", policy.json().toString())
            if (policy.method == "off") remove(KEY_BEDTIME_SCHEDULE) else putString(KEY_BEDTIME_SCHEDULE, policy.fallback)
            remove(KEY_BEDTIME_PENDING_DAY)
            remove(KEY_BEDTIME_RETRY_AT)
            remove(KEY_BEDTIME_SCHEDULED_FOR)
            remove("bedtime_calendar_retry_day")
            remove("bedtime_calendar_retry_at")
            remove("bedtime_calendar_plan")
        }.apply()
        appendEvent("BedtimePolicyChanged", null, policy.json())
    }

    /** A morning clock belongs to the previous evening, as in calendar plans.
     * Date keys survive process restarts and timezone offset changes. */
    private fun bedtimeNightDay(day: Long, clock: String): String {
        val date = java.time.Instant.ofEpochMilli(day).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return (if ((clock.substringBefore(':').toIntOrNull() ?: 12) < 12) date.minusDays(1) else date).toString()
    }

    @Synchronized
    internal fun reconcileBedtime(now: Long, calendarPlan: BedtimeResolution? = null): Long? = runtimeSettings.transaction {
        val policy = bedtimePolicy()
        if (policy.method == "calendar") return@transaction reconcileCalendarBedtime(policy, calendarPlan, now)
        val previous = BedtimeState(
            runtimeSettings.getLong(KEY_BEDTIME_PENDING_DAY, 0L),
            runtimeSettings.getLong(KEY_BEDTIME_RETRY_AT, 0L),
            runtimeSettings.getLong(KEY_BEDTIME_SCHEDULED_FOR, 0L),
        )
        val lastTriggeredDay = runtimeSettings.getLong(KEY_BEDTIME_LAST_TRIGGERED_DAY, 0L)
        val decision = BedtimePlanner.plan(
            bedtimeSchedule(), now, mode() in setOf("focus", "rabbit_hole"),
            lastTriggeredDay, previous,
            wasTriggered = { day -> day == lastTriggeredDay || runtimeSettings.getBoolean(
                "bedtime_triggered:${bedtimeNightDay(day, bedtimeSchedule())}", false) },
        )
        decision.triggerDay?.let { day ->
            if (mode() != "bedtime") setMode("bedtime", "auto-bedtime-$day")
            val night = bedtimeNightDay(day, bedtimeSchedule())
            runtimeSettings.edit().putBoolean("bedtime_triggered:$night", true)
                .putString("bedtime_last_triggered_night", night).apply()
        }
        if (decision.state != previous || decision.triggerDay != null) {
            runtimeSettings.edit()
                .putLong(KEY_BEDTIME_PENDING_DAY, decision.state.pendingDay)
                .putLong(KEY_BEDTIME_RETRY_AT, decision.state.retryAt)
                .putLong(KEY_BEDTIME_SCHEDULED_FOR, decision.state.scheduledFor)
                .apply {
                    decision.triggerDay?.let { putLong(KEY_BEDTIME_LAST_TRIGGERED_DAY, it) }
                }.apply()
        }
        return@transaction decision.nextAlarmAt
    }

    private fun reconcileCalendarBedtime(policy: BedtimePolicy, plan: BedtimeResolution?, now: Long): Long {
        if (plan == null || plan.revision != policy.revision || plan.zone != java.time.ZoneId.systemDefault().id)
            return now + 60_000L
        val encoded = JSONObject().put("revision", plan.revision).put("zone", plan.zone).put("status", plan.status)
            .put("nights", JSONArray(plan.nights.map { night -> JSONObject().put("day", night.day).put("start", night.start)
                .put("expires", night.expires).put("source", night.source).put("source_occurrences", JSONArray(night.sources)) }))
        if (runtimeSettings.getString("bedtime_calendar_plan", null) != encoded.toString()) {
            runtimeSettings.edit().putString("bedtime_calendar_plan", encoded.toString()).apply()
            appendEvent("BedtimePlanUpdated", null, encoded)
        }
        val oldFixedDay = runtimeSettings.getString("bedtime_last_triggered_night", null)
            ?: runtimeSettings.getLong(KEY_BEDTIME_LAST_TRIGGERED_DAY, 0L).takeIf { it > 0 }?.let {
                bedtimeNightDay(it, policy.fallback)
            }
        val night = plan.nights.sortedBy { it.start }.firstOrNull {
            it.expires > now && it.day != oldFixedDay && !runtimeSettings.getBoolean("bedtime_triggered:${it.day}", false)
        } ?: return now + 900_000L
        if (night.start > now) return night.start
        // Avoid stealing an active focus or research session. Retry only while
        // this night's wind-down window still exists, never at midday later.
        if (mode() in setOf("focus", "rabbit_hole")) {
            val previousDay = runtimeSettings.getString("bedtime_calendar_retry_day", null)
            val previousAt = runtimeSettings.getLong("bedtime_calendar_retry_at", 0L)
            val retry = if (previousDay == night.day && previousAt > now) previousAt else now + 900_000L
            runtimeSettings.edit().putString("bedtime_calendar_retry_day", night.day).putLong("bedtime_calendar_retry_at", retry).apply()
            return minOf(night.expires, retry)
        }
        runtimeSettings.edit().remove("bedtime_calendar_retry_day").remove("bedtime_calendar_retry_at").apply()
        if (mode() != "bedtime") setMode("bedtime", "auto-bedtime-calendar-${night.day}")
        runtimeSettings.edit().putBoolean("bedtime_triggered:${night.day}", true).apply()
        appendEvent("BedtimePlanTriggered", null, JSONObject().put("day", night.day).put("planned_for", night.start)
            .put("source", night.source).put("source_occurrences", JSONArray(night.sources)).put("policy_revision", policy.revision))
        return plan.nights.filter { it.start > now && !runtimeSettings.getBoolean("bedtime_triggered:${it.day}", false) }
            .minOfOrNull { it.start } ?: (now + 900_000L)
    }

    internal fun bedtimePlanSnapshot(): String? = runtimeSettings.getString("bedtime_calendar_plan", null)

    internal fun llmConfig(): BufferLlmConfig = BufferLlmConfig(
        settings.getString("llm_base_url", BufferMiMo.baseUrl).orEmpty(), settings.getString("llm_model", BufferMiMo.model).orEmpty(),
        BufferLlmKey.decrypt(settings.getString("llm_key_encrypted", "").orEmpty()),
    )

    internal fun saveLlmConfig(baseUrl: String, model: String, apiKey: String?) {
        val key = apiKey ?: llmConfig().apiKey
        BufferLlmConfig(baseUrl.trim(), model.trim(), key).completionUrl()
        val encoded = BufferLlmKey.encrypt(key)
        check(settings.edit().putString("llm_base_url", baseUrl.trim()).putString("llm_model", model.trim())
            .putString("llm_key_encrypted", encoded).commit()) { "无法保存服务设置" }
    }

    internal fun clearLlmConfig() {
        check(settings.edit().putString("llm_base_url", "").putString("llm_model", "").remove("llm_key_encrypted").commit())
        BufferLlmKey.clearCached()
    }

    private fun crdtActor(): String {
        runtimeSettings.getString("crdt_actor", null)?.let { return it }
        val actor = com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol.fingerprint(UUID.randomUUID().toString())
        runtimeSettings.edit().putString("crdt_actor", actor).apply()
        return actor
    }
    private fun deletedCardIds(): Set<String> = buildSet {
        readableDatabase.query("events", arrayOf("payload"), "type=?", arrayOf("CardDeleted"), null, null, null).use { rows ->
            while (rows.moveToNext()) add(JSONObject(rows.getString(0)).getString("card_id"))
        }
    }
    private fun crdtDocument(id: String): JSONObject? = BufferSourceEffects.currentDocument(writableDatabase, id)
        ?: BufferSourceEffects.retainedDocument(readableDatabase, id)

    internal fun taskSchedule(card: BufferCard): TaskSchedule? = taskScheduleValue(card, cache = true)

    private fun taskScheduleValue(card: BufferCard, cache: Boolean): TaskSchedule? {
        if (card.kind != "task_card") return null
        val saved = runtimeSettings.getString("task_schedule:${card.cardId}", null)
        if (saved != null) return if (saved == "null") null else TaskSchedule.parse(JSONObject(saved))
        val source = runtimeSettings.getString("task_source:${card.cardId}", null) ?: buildString {
            append(card.title); append(' '); append(card.summary)
            // Mixed captures never donate one task's date to another task.
            card.captureId?.let { capture ->
                if (cardsForCapture(readableDatabase, capture).size == 1)
                    readableDatabase.rawQuery("SELECT transcript FROM captures WHERE capture_id=?", arrayOf(capture)).use {
                        if (it.moveToFirst()) { append(' '); append(it.getString(0).orEmpty()) }
                    }
            }
        }
        return TaskSchedule.fromText(source, card.createdAt)?.also {
            if (cache) runtimeSettings.edit().putString("task_schedule:${card.cardId}", it.json().toString()).apply()
        }
    }
    @Synchronized
    internal fun setTaskSchedule(cardId: String, schedule: TaskSchedule?) = runtimeSettings.transaction {
        val card = requireNotNull(findCard(cardId)); require(card.kind == "task_card" && card.state != "deleted")
        runtimeSettings.edit().putString("task_schedule:$cardId", schedule?.json()?.toString() ?: "null").apply()
        appendEvent("TaskScheduleChanged", card.captureId, JSONObject().put("card_id", cardId).put("schedule", schedule?.json() ?: JSONObject.NULL))
        exportCardCrdt(cardId)
    }
    private fun cardCrdtValues(id: String, deleted: Boolean = false, cacheSchedule: Boolean = true): JSONObject {
        val card = findCard(id)
        val values = if (card == null) JSONObject() else displayCardJsonValue(card, includeContext = true, cacheSchedule = cacheSchedule).put("answer", card.answer ?: JSONObject.NULL)
            .put("research_notes", card.researchNotes ?: JSONObject.NULL).put("tags", JSONArray(card.tags))
            .put("related_card_ids", JSONArray(card.relatedCardIds))
            .put("presentation", cardPresentation(id)?.let(::JSONObject) ?: JSONObject.NULL)
            .put("conversation", JSONArray(cardConversation(id)))
        values.put("bedtime_review", card?.let { bedtimeReview(it) } ?: JSONObject.NULL)
        values.put("bedtime_user_review", card?.takeIf { bedtimeUserApproved(it) }?.let(BedtimeAssessment::contentHash) ?: JSONObject.NULL)
        if (card != null) values.put("text", runtimeSettings.getString("task_source:$id", null) ?: card.summary)
            .put("actions", JSONArray(card.actions))
        // A tombstone retains old registers unless explicitly overwritten.
        // Never retain collected context in a deletion-only transmission.
        if (card == null || deleted || card.state == "deleted")
            values.put("capture_context", JSONObject.NULL).put("context_summary", "").put("bedtime_readable", false)
        values.put("bedtime_fingerprint", BedtimeDeck.fingerprint(values))
        return values
    }
    private fun exportCardCrdt(id: String, deleted: Boolean = false): JSONObject = runtimeSettings.transaction {
        val card = findCard(id)
        val previous = crdtDocument(id)
        val values = cardCrdtValues(id, deleted)
        val doc = BufferCrdt.update(previous, id, "card", values, crdtActor(), deleted || card?.state == "deleted")
        runtimeSettings.edit().putString("crdt:$id", BufferCrdt.canonical(doc)).apply()
        BufferSourceEffects.editedNativeOverrides(writableDatabase,doc)
        val scope = documentAudience(id, doc)
        BufferDocumentSource.claim(writableDatabase, id, scope)
        observeDocument(doc, scope)
        doc
    }

    private fun documentAudience(id: String, document: JSONObject): String {
        val capture = findCard(id)?.captureId ?: (BufferCrdt.values(document).opt("capture_id") as? String)
        val source = capture?.let { key -> readableDatabase.rawQuery("SELECT source_device FROM captures WHERE capture_id=?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null } }
        val inferred = when {
            source == "android" || (source != null && source == deviceId()) -> "local"
            source?.matches(Regex("[0-9a-f]{64}")) == true -> requireNotNull(source)
            else -> "unknown"
        }
        return BufferDocumentSource.audience(writableDatabase, id, inferred)
    }

    private inline fun <T> withoutDocumentObservation(block: () -> T): T {
        val depth = documentObservationDepth.get() ?: 0
        documentObservationDepth.set(depth + 1)
        try { return block() } finally { documentObservationDepth.set(depth) }
    }

    /** Observe the actual row while the originating local fact transaction is
     * still open. Never derive register stamps from source journal clocks. */
    private fun observeCardFact(db: SQLiteDatabase, type: String, payload: JSONObject) {
        check(db.inTransaction())
        if ((documentObservationDepth.get() ?: 0) != 0 || type in setOf("CardDocumentObserved", "CalendarDocumentObserved", "DocumentMerged")) return
        val ids = listOf("card_id", "source_card_id", "derived_card_id").mapNotNull { payload.opt(it) as? String }.distinct()
        withoutDocumentObservation {
            ids.filter { it.matches(Regex("[A-Za-z0-9_-]{1,79}")) }.forEach { id ->
                val deleted = type == "CardDeleted" && payload.opt("card_id") == id
                if (crdtDocument(id)?.optString("kind") != "calendar" && (deleted || findCard(id) != null)) observeCardSource(db, id, deleted)
            }
        }
    }

    private fun observeCaptureDocuments(db: SQLiteDatabase, captureId: String) {
        check(db.inTransaction())
        if ((documentObservationDepth.get() ?: 0) != 0) return
        withoutDocumentObservation { cardsForCapture(db, captureId).forEach { observeCardSource(db, it.cardId) } }
    }

    private fun observeCardSource(db: SQLiteDatabase, id: String, deleted: Boolean = false) {
        check(db.inTransaction())
        val previous = crdtDocument(id)
        previous?.let(BufferCrdt::validate) // Damaged local metadata remains a storage error.
        val values = cardCrdtValues(id, deleted, cacheSchedule = false)
        val actor = crdtActor()
        try {
            BufferCrdtView.validate(BufferCrdt.update(previous, id, "card", values, actor, deleted || findCard(id)?.state == "deleted"))
        } catch (_: IllegalArgumentException) {
            // A display budget must not roll back a valid original capture.
            // Keep the actual row/fact and record this explicit local outcome.
            val key = "source_document_unavailable:$id"
            val fingerprint = com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol.fingerprint(
                BufferCrdt.canonical(JSONObject().put("values", values).put("deleted", deleted)))
            if (runtimeSettings.getString(key, null) != fingerprint) {
                appendEvent(db, "CardDocumentUnavailable", findCard(id)?.captureId, System.currentTimeMillis(),
                    JSONObject().put("id", id).put("reason", "unrepresentable_view"))
                runtimeSettings.edit().putString(key, fingerprint).apply()
            }
            return
        }
        exportCardCrdt(id, deleted)
        runtimeSettings.edit().remove("source_document_unavailable:$id").apply()
    }

    private fun observeDocument(document: JSONObject, audience: String, preferredType: String? = null) {
        val capture = (BufferCrdt.values(document).opt("capture_id") as? String) ?: findCard(document.getString("id"))?.captureId
        BufferDocumentSource.observe(writableDatabase, document, audience, preferredType) { type, payload ->
            appendEvent(type, capture, payload)
        }
    }

    /** Display metadata only; receiving a card must never create a reminder job. */
    private fun displayReturn(id: String): JSONObject = readableDatabase.rawQuery(
        "SELECT due_at, returned_at FROM card_returns WHERE card_id = ?", arrayOf(id),
    ).use { row ->
        val exists = row.moveToFirst()
        JSONObject().put("hidden_until", if (exists && !row.isNull(0)) row.getLong(0) else 0L)
            .put("returned_at", if (exists) row.getLong(1) else 0L)
    }

    @Synchronized
    internal fun displayCardJson(card: BufferCard, includeContext: Boolean = false): JSONObject = displayCardJsonValue(card, includeContext, cacheSchedule = true)

    private fun displayCardJsonValue(card: BufferCard, includeContext: Boolean, cacheSchedule: Boolean): JSONObject {
        val context = if (includeContext && captureContextPolicy().getBoolean("share")) cardCaptureContext(card.cardId)?.takeIf {
            it.getString("source_device") == deviceId()
        }?.let { snapshot ->
            JSONObject(snapshot.toString()).also { shared ->
                val policy = captureContextPolicy()
                if (!policy.getBoolean("location") || !policy.getBoolean("location_granted"))
                    shared.put("location", CaptureContext.section("disabled"))
                if (!policy.getBoolean("calendar") || !policy.getBoolean("calendar_granted"))
                    shared.put("calendar", CaptureContext.section("disabled"))
            }
        } else null
        return card.toJson().put("schedule", taskScheduleValue(card, cacheSchedule)?.json() ?: JSONObject.NULL)
            .put("display_return", displayReturn(card.cardId))
            .put("capture_context", context ?: JSONObject.NULL)
            .put("context_summary", context?.let(CaptureContext::description) ?: "")
            .put("bedtime_readable", BedtimeDeck.project(card, bedtimeAssessment(card), bedtimeUserApproved(card)) != null)
            .also { it.put("bedtime_fingerprint", BedtimeDeck.fingerprint(it)) }
    }

    private fun bedtimeReview(card: BufferCard): JSONObject? = runtimeSettings.getString("bedtime_review:${card.cardId}", null)?.let {
        JSONObject(it).takeIf { value -> BedtimeAssessment.fromReview(value, card) != null }
    }

    internal fun bedtimeAssessment(card: BufferCard): BedtimeAssessment? =
        BedtimeAssessment.fromReview(bedtimeReview(card), card)

    internal fun bedtimeUserApproved(card: BufferCard): Boolean = BedtimeDeck.SAFE_TAG in card.tags &&
        runtimeSettings.getString("bedtime_user_review:${card.cardId}", null) == BedtimeAssessment.contentHash(card)

    private fun saveBedtimeAssessment(cardId: String, suggestion: String?) {
        val assessment = suggestion?.let { BedtimeAssessment.parse(JSONObject(it)) }
        // Deleted processed segments intentionally have no materialized card.
        // Their proposal stays in ClassificationProposed, never resurrect a
        // card merely to attach display metadata or fail the whole import.
        val card = findCard(cardId)?.takeUnless { it.state == "deleted" }
        val review = card?.let { assessment?.review(it)?.toString() }
        runtimeSettings.edit().putString("bedtime_review:$cardId", review).apply()
    }

    @Synchronized
    internal fun bedtimeDeferredCandidates(): List<Pair<BufferCard, String>> {
        val result = mutableListOf<Pair<BufferCard, String>>()
        readableDatabase.query("cards", null,
            "kind='question_card' AND state NOT IN ('archived','done','deleted','later','pending_transcription') " +
                "AND NOT EXISTS (SELECT 1 FROM card_returns r WHERE r.card_id=cards.card_id AND r.due_at IS NOT NULL)",
            null, null, null, "created_at DESC,card_id ASC").use { rows ->
            while (result.size < 5 && rows.moveToNext()) {
                val card = readCard(rows)
                if (BedtimeDeck.HIGH_TAG in card.tags) result += card to "你已标记为白天再研究"
                else if (!bedtimeUserApproved(card)) bedtimeAssessment(card)?.takeIf { it.level == "high" }?.let {
                    result += card to "AI 建议：${it.reason}"
                }
            }
        }
        return result
    }

    /** Recheck before each application fragment; queued snapshots may predate
     * permission revocation. Already transmitted bytes cannot be recalled. */
    @Synchronized
    internal fun mayShareCardSnapshot(bytes: ByteArray): Boolean {
        val fields = JSONObject(bytes.toString(Charsets.UTF_8)).optJSONObject("crdt")?.optJSONObject("fields") ?: return true
        val context = fields.optJSONObject("capture_context")?.optJSONObject("value") ?: return true
        val policy = captureContextPolicy()
        return policy.getBoolean("share") &&
            (context.getJSONObject("location").getString("status") != "available" || (policy.getBoolean("location") && policy.getBoolean("location_granted"))) &&
            (context.getJSONObject("calendar").getString("status") != "available" || (policy.getBoolean("calendar") && policy.getBoolean("calendar_granted")))
    }

    @Synchronized
    internal fun mayShareCardSnapshotToPeer(bytes: ByteArray, peer: String): Boolean {
        require(peer.matches(Regex("[0-9a-f]{64}")))
        val document = JSONObject(bytes.toString(Charsets.UTF_8)).optJSONObject("crdt") ?: return false
        val audience = documentAudience(document.getString("id"), document)
        return (audience == "local" || audience == peer) && sourceDocumentContextAllowed(document)
    }

    private fun sourceDocumentContextAllowed(document: JSONObject): Boolean {
        BufferCrdtView.validate(document)
        val values = BufferCrdt.values(document)
        if (document.getString("kind") == "calendar" && values.opt("source") == "android_calendar" &&
            androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val context = values.optJSONObject("capture_context")
        var allowedContext: JSONObject? = null
        var allowedSummary: String? = null
        if (context != null) {
            CaptureContext.validate(context)
            val capture = values.opt("capture_id") as? String
            val original = capture?.let { id -> readableDatabase.rawQuery("""SELECT payload FROM events
                WHERE capture_id=? AND type='CaptureContextCaptured' ORDER BY logical_clock DESC LIMIT 1""", arrayOf(id)).use {
                if (it.moveToFirst()) CaptureContext.validate(JSONObject(it.getString(0))) else null
            } }
            val policy = captureContextPolicy()
            if (original != null && original.getString("source_device") == deviceId() && policy.getBoolean("share")) {
                val permitted = JSONObject(original.toString())
                if (!policy.getBoolean("location") || !policy.getBoolean("location_granted")) permitted.put("location", CaptureContext.section("disabled"))
                if (!policy.getBoolean("calendar") || !policy.getBoolean("calendar_granted")) permitted.put("calendar", CaptureContext.section("disabled"))
                if (BufferCrdt.canonical(context) == BufferCrdt.canonical(permitted)) {
                    allowedContext = permitted; allowedSummary = CaptureContext.description(permitted)
                }
            }
        }
        fun allowed(value: Any?): Boolean {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    var nested = value.get(key)
                    if (key in setOf("capture_context", "context_summary", "context")) {
                        if (nested is JSONObject && nested.keys().asSequence().toSet() == setOf("counter", "actor", "value")) {
                            val n = nested.opt("counter"); val actor = nested.opt("actor")
                            if (n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() &&
                                n.toLong() in 1L..BufferSourceEvent.MAX_COUNTER && actor is String && actor.matches(Regex("[0-9a-f]{64}"))) nested = nested.get("value")
                        }
                        if (nested != JSONObject.NULL && nested != "") {
                            if (key == "capture_context" && allowedContext != null && BufferCrdt.canonical(nested) == BufferCrdt.canonical(allowedContext)) Unit
                            else if (key == "context_summary" && nested is String && nested == allowedSummary) Unit
                            else return false
                        }
                    }
                    if (!allowed(nested)) return false
                }
                is JSONArray -> for (i in 0 until value.length()) if (!allowed(value.get(i))) return false
            }
            return true
        }
        return allowed(document)
    }

    @Synchronized
    internal fun captureContextPolicy(): JSONObject = JSONObject()
        .put("revision", runtimeSettings.getLong("context_revision", 0L))
        .put("location", runtimeSettings.getBoolean("context_location", false))
        .put("calendar", runtimeSettings.getBoolean("context_calendar", false))
        .put("share", runtimeSettings.getBoolean("context_share", false))
        .put("location_granted", androidx.core.content.ContextCompat.checkSelfPermission(appContext,
            android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        .put("calendar_granted", androidx.core.content.ContextCompat.checkSelfPermission(appContext,
            android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED)

    @Synchronized
    internal fun setCaptureContextPolicy(location: Boolean, calendar: Boolean, share: Boolean) = runtimeSettings.transaction {
        val revision = runtimeSettings.getLong("context_revision", 0L)
        val collectionChanged = runtimeSettings.getBoolean("context_location", false) != location ||
            runtimeSettings.getBoolean("context_calendar", false) != calendar
        check(!collectionChanged || revision < Long.MAX_VALUE)
        runtimeSettings.edit().putBoolean("context_location", location).putBoolean("context_calendar", calendar)
            .putBoolean("context_share", share).putLong("context_revision", revision + if (collectionChanged) 1 else 0).apply()
        appendEvent("CaptureContextPolicyChanged", null, JSONObject().put("location", location).put("calendar", calendar).put("share", share))
    }

    @Synchronized
    internal fun pendingCaptureContext(): CaptureContextJob? = readableDatabase.rawQuery(
        "SELECT r.capture_id,r.created_at,r.payload,r.event_id FROM capture_context_jobs q JOIN events r ON r.event_id=q.request_event_id " +
            "WHERE EXISTS (SELECT 1 FROM captures c WHERE c.capture_id=r.capture_id) " +
            "ORDER BY r.logical_clock LIMIT 1", null,
    ).use { row -> if (!row.moveToFirst()) null else JSONObject(row.getString(2)).let {
        CaptureContextJob(row.getString(0), row.getLong(1), it.getString("source_device"), it.getJSONObject("policy"), it.optBoolean("local", false), row.getString(3))
    } }

    @Synchronized
    internal fun completeCaptureContext(job: CaptureContextJob, snapshot: JSONObject) = runtimeSettings.transaction {
        CaptureContext.validate(snapshot)
        require(snapshot.getLong("captured_at") == job.capturedAt && snapshot.getString("source_device") == job.source)
        val db = writableDatabase
        if (!db.rawQuery("SELECT 1 FROM capture_context_jobs WHERE capture_id=? AND request_event_id=?", arrayOf(job.captureId, job.requestId))
                .use { it.moveToFirst() }) return@transaction
        if (!db.rawQuery("SELECT 1 FROM captures WHERE capture_id=?", arrayOf(job.captureId)).use { it.moveToFirst() }) {
            db.delete("capture_context_jobs", "capture_id=?", arrayOf(job.captureId)); return@transaction
        }
        if (db.rawQuery("SELECT 1 FROM events WHERE capture_id=? AND type='CaptureContextCaptured' LIMIT 1", arrayOf(job.captureId))
                .use { it.moveToFirst() }) {
            db.delete("capture_context_jobs", "capture_id=?", arrayOf(job.captureId)); return@transaction
        }
        val current = captureContextPolicy()
        val result = JSONObject(snapshot.toString())
        if (job.local && current.getLong("revision") != job.policy.getLong("revision")) {
            result.put("location", CaptureContext.section("cancelled")).put("calendar", CaptureContext.section("cancelled"))
        } else {
            if (!current.getBoolean("location_granted") && result.getJSONObject("location").getString("status") == "available")
                result.put("location", CaptureContext.section("permission_denied"))
            if (!current.getBoolean("calendar_granted") && result.getJSONObject("calendar").getString("status") == "available")
                result.put("calendar", CaptureContext.section("permission_denied"))
        }
        appendEvent("CaptureContextCaptured", job.captureId, CaptureContext.validate(result))
        db.delete("capture_context_jobs", "capture_id=? AND request_event_id=?", arrayOf(job.captureId, job.requestId))
        observeCaptureDocuments(db, job.captureId)
    }

    @Synchronized
    internal fun cardCaptureContext(cardId: String): JSONObject? {
        val card = findCard(cardId) ?: return null
        if (card.state == "deleted") return null
        val shared = runtimeSettings.getString("shared_context:$cardId", null)?.let { CaptureContext.validate(JSONObject(it)) }
        val capture = card.captureId ?: return shared
        val local = readableDatabase.query("events", arrayOf("type", "created_at", "payload"),
            "capture_id=? AND type IN ('CaptureContextCaptured','CaptureContextRequested','CaptureCreated')", arrayOf(capture),
            null, null, "CASE type WHEN 'CaptureContextCaptured' THEN 0 WHEN 'CaptureContextRequested' THEN 1 ELSE 2 END, logical_clock DESC", "1")
            .use { row ->
                if (!row.moveToFirst()) null else {
                    val value = JSONObject(row.getString(2))
                    if (row.getLong(1) !in 0..9_007_199_254_740_991L) return@use null
                    when (row.getString(0)) {
                        "CaptureContextCaptured" -> CaptureContext.validate(value)
                        "CaptureContextRequested" -> CaptureContext.empty(row.getLong(1), value.getString("source_device"), "pending")
                        else -> CaptureContext.empty(row.getLong(1), value.optString("device_id", value.optString("source", "android")), "not_provided")
                    }
                }
            }
        return local?.takeIf { it.getString("source_device") == deviceId() } ?: shared ?: local
    }
    @Synchronized
    internal fun mergeCrdtDocument(incoming: JSONObject, peer: String? = null, authorize: () -> Boolean = { true }): JSONObject = runtimeSettings.transaction {
        check(authorize()); if (peer != null) require(peer.matches(Regex("[0-9a-f]{64}")))
        BufferCrdt.validate(incoming)
        val id = incoming.getString("id")
        val audience = documentAudience(id, incoming)
        require(BufferDocumentSource.compatible(audience, peer)) { "document owner conflict" }
        require(incoming.getString("kind") == "card" || !writableDatabase.rawQuery("SELECT 1 FROM cards WHERE card_id=?", arrayOf(id)).use { it.moveToFirst() }) { "document kind conflicts with card" }
        // Freeze local SQL edits into registers before merging remote edits.
        val deleted = incoming.getString("kind") == "card" &&
            (if (BufferSourceProjection.localHistoryReady(writableDatabase)) BufferSourceProjection.locallyDeleted(writableDatabase, id) else id in deletedCardIds())
        val previous = if (incoming.getString("kind") == "card" && (findCard(id) != null || deleted)) exportCardCrdt(id, deleted) else crdtDocument(id)
        val merged = BufferCrdt.merge(previous, incoming)
        val bound = if (audience == "unknown" && peer != null && BufferCrdt.canonical(merged) == BufferCrdt.canonical(incoming)) peer else audience
        BufferDocumentSource.claim(writableDatabase, id, bound)
        val result = applyMergedCrdt(previous, merged, recordHistory = true)
        if(peer!=null)BufferSourceEffects.clearNativeOverrides(writableDatabase,id)
        check(authorize()); result
    }

    /** Called only inside the source projection transaction. Parsing and
     * merge conflicts precede business writes, so a bad document cannot poison
     * the outer transaction and prevent unrelated pending events progressing. */
    private fun projectSourceCrdt(record: BufferSourceEvent, peer: String, incoming: JSONObject): String {
        val db = writableDatabase; check(db.inTransaction())
        val id = incoming.getString("id")
        val audience = documentAudience(id, incoming)
        if (!BufferDocumentSource.compatible(audience, peer)) return "conflict"
        if (incoming.getString("kind") != "card" && db.rawQuery("SELECT 1 FROM cards WHERE card_id=?", arrayOf(id)).use { it.moveToFirst() }) return "conflict"
        val old = crdtDocument(id)
        val card = if (incoming.getString("kind") == "card") findCard(id) else null
        if (card == null && incoming.getString("kind") == "card" && !BufferSourceProjection.localHistoryReady(db)) return "waiting_local_history"
        val deleted = incoming.getString("kind") == "card" && BufferSourceProjection.locallyDeleted(db, id)
        val current = if (card != null) cardCrdtValues(id, deleted, cacheSchedule = false) else null
        val changes = current?.let { BufferSourceEffects.projectionChanges(db,id,
            if (old == null) BufferSourceProjection.localChanges(db, id, it) else it) }
        // Only actual pre-existing SQL changes get local stamps; a source
        // capture's untouched derived baseline is not a new local user fact.
        val local = if (deleted || (changes != null && changes.length() > 0)) {
            try { BufferCrdt.update(old ?: incoming, id, "card", changes ?: JSONObject(), crdtActor(), deleted) }
            catch (_: IllegalArgumentException) { return "conflict" }
        } else old
        val merged = try { BufferCrdt.merge(local, incoming).also(BufferCrdtView::validate) }
        catch (_: IllegalArgumentException) { return "conflict" }
        catch (_: org.json.JSONException) { return "conflict" }
        if (card != null && !merged.getBoolean("deleted")) {
            val capture = BufferCrdt.values(merged).opt("capture_id").takeUnless { it == JSONObject.NULL }
            if (capture != card.captureId) return "conflict"
        }
        val bound = if (audience == "unknown" && BufferCrdt.canonical(merged) == BufferCrdt.canonical(incoming)) peer else audience
        BufferDocumentSource.claim(db, id, bound)
        applyMergedCrdt(local, merged, recordHistory = false)
        BufferSourceEffects.applyNativeOverrides(db,id)
        BufferSourceProjection.documentApplied(db, peer, record, incoming)
        return "applied"
    }

    private fun applyMergedCrdt(previous: JSONObject?, merged: JSONObject, recordHistory: Boolean): JSONObject = withoutDocumentObservation {
        BufferCrdtView.validate(merged)
        val id = merged.getString("id")
        val values = BufferCrdt.values(merged)
        if (merged.getString("kind") == "card") {
            if (merged.getBoolean("deleted")) {
                if (recordHistory && previous?.optBoolean("deleted") != true) appendEvent("CardDeleted", findCard(id)?.captureId, JSONObject().put("card_id", id))
                writableDatabase.execSQL("UPDATE card_reminders SET cancelled=1 WHERE card_id=?", arrayOf(id))
                writableDatabase.delete("card_returns", "card_id=?", arrayOf(id))
                runtimeSettings.edit().remove("shared_context:$id").apply()
                writableDatabase.update("cards", ContentValues().apply { put("state", "deleted") }, "card_id=?", arrayOf(id))
            } else {
                require(values.optString("card_id") == id)
                val state = values.getString("state"); require(state.length in 1..40)
                val kind = values.getString("kind"); require(kind in setOf("task_card", "spark_card", "question_card", "care_card", "weekly_card", "rabbit_hole_card", "project_card", "article_card", "code_card", ClassificationReview.UNCLASSIFIED))
                val old = findCard(id)
                val capture = if (values.isNull("capture_id")) null else values.optString("capture_id").ifBlank { null }
                val card = BufferCard(id, capture, kind, values.getString("title"), values.getString("summary"), state,
                    values.optJSONArray("actions")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: old?.actions.orEmpty(),
                    values.getLong("created_at"), values.optString("answer").takeUnless { values.isNull("answer") || it.isEmpty() },
                    values.optString("health_category").takeUnless { values.isNull("health_category") || it.isEmpty() },
                    values.optBoolean("classification_confirmed", old?.classificationConfirmed ?: false),
                    values.optString("research_notes").takeUnless { values.isNull("research_notes") || it.isEmpty() },
                    values.optJSONArray("external_links")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: old?.externalLinks.orEmpty(),
                    values.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    values.optJSONArray("related_card_ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
                val exists = old != null || writableDatabase.rawQuery("SELECT 1 FROM cards WHERE card_id=?", arrayOf(id)).use { it.moveToFirst() }
                if (!exists) insertCard(writableDatabase, card, observeSource = false) else {
                    writableDatabase.update("cards", ContentValues().apply {
                        put("title", card.title); put("summary", card.summary); put("state", card.state); put("kind", card.kind)
                        put("answer", card.answer); put("research_notes", card.researchNotes)
                        put("capture_id", card.captureId); put("created_at", card.createdAt)
                        put("health_category", card.healthCategory); put("classification_confirmed", if (card.classificationConfirmed) 1 else 0)
                        put("external_links", JSONArray(card.externalLinks).toString())
                        put("actions", JSONArray(card.actions).toString()); put("tags", JSONArray(card.tags).toString())
                        put("related_card_ids", JSONArray(card.relatedCardIds).toString())
                    }, "card_id=?", arrayOf(id))
                }
                if (values.has("schedule")) {
                    val schedule = values.optJSONObject("schedule")?.let(TaskSchedule::parse)
                    runtimeSettings.edit().putString("task_schedule:$id", schedule?.json()?.toString() ?: "null").apply()
                }
                if (values.has("presentation")) runtimeSettings.edit().putString("card_presentation:$id", values.optJSONObject("presentation")?.toString()).apply()
                if (values.has("conversation")) runtimeSettings.edit().putString("card_conversation:$id", values.optJSONArray("conversation")?.toString() ?: "[]").apply()
                if (values.has("bedtime_review")) {
                    val review = if (values.isNull("bedtime_review")) null else BedtimeAssessment.validateReview(values.getJSONObject("bedtime_review"))
                    runtimeSettings.edit().putString("bedtime_review:$id", review?.toString()).apply()
                }
                if (values.has("bedtime_user_review")) {
                    val review = if (values.isNull("bedtime_user_review")) null else {
                        val hash = values.get("bedtime_user_review")
                        require(hash is String && hash.matches(Regex("[0-9a-f]{64}")))
                        hash
                    }
                    runtimeSettings.edit().putString("bedtime_user_review:$id", review).apply()
                }
                if (values.has("capture_context")) {
                    val context = if (values.isNull("capture_context")) null else CaptureContext.validate(values.getJSONObject("capture_context"))
                    val encoded = context?.toString()
                    if (runtimeSettings.getString("shared_context:$id", null) != encoded) {
                        runtimeSettings.edit().putString("shared_context:$id", encoded).apply()
                        if (recordHistory) appendEvent(if (context == null) "CaptureContextSharingRevoked" else "CaptureContextImported", card.captureId,
                            JSONObject().put("card_id", id).put("context", context ?: JSONObject.NULL))
                    }
                }
                if (values.has("text")) runtimeSettings.edit().putString("task_source:$id",
                    if (values.isNull("text")) null else values.getString("text")).apply()
            }
        } else if (!merged.getBoolean("deleted")) CalendarEntry.fromDocument(merged)
        runtimeSettings.edit().putString("crdt:$id", BufferCrdt.canonical(merged)).apply()
        if (recordHistory) observeDocument(merged, documentAudience(id, merged),
            if (previous == null || BufferCrdt.canonical(previous) != BufferCrdt.canonical(merged)) "DocumentMerged" else null)
        merged
    }
    @Synchronized
    internal fun calendarSourceDocumentId(source: String): String = runtimeSettings.transaction {
        val key = "calendar_source:$source"
        val id = runtimeSettings.getString(key, null) ?: source
        if (crdtDocument(id)?.optBoolean("deleted") != true) id else {
            val replacement = "cal_" + com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol.fingerprint(UUID.randomUUID().toString()).take(32)
            runtimeSettings.edit().putString(key, replacement).apply()
            replacement
        }
    }
    @Synchronized
    internal fun upsertCalendarDocument(id: String, values: JSONObject, deleted: Boolean = false) = runtimeSettings.transaction {
        require(!writableDatabase.rawQuery("SELECT 1 FROM cards WHERE card_id=?", arrayOf(id)).use { it.moveToFirst() }) { "calendar document kind conflicts with card" }
        val previous = crdtDocument(id)
        var audience = BufferDocumentSource.audience(writableDatabase, id, if (previous == null) "local" else "unknown")
        require(audience in setOf("local", "unknown")) { "calendar document owner conflict" }
        // A fresh authorized local input can establish its own calendar view
        // only when it supplies every retained register value. Unknown extra
        // fields are not silently reclassified as locally shareable content.
        if (audience == "unknown" && !deleted && previous != null && previous.getJSONObject("fields").keys().asSequence().all { values.has(it) }) audience = "local"
        val doc = BufferCrdt.update(previous, id, "calendar", values, crdtActor(), deleted)
        if (!doc.getBoolean("deleted")) CalendarEntry.fromDocument(doc)
        runtimeSettings.edit().putString("crdt:$id", BufferCrdt.canonical(doc)).apply()
        BufferSourceEffects.editedNativeOverrides(writableDatabase,doc)
        BufferDocumentSource.claim(writableDatabase, id, audience)
        observeDocument(doc, audience)
    }
    internal fun calendarEntries(start: Long, end: Long): List<CalendarEntry> {
        val entries = mutableListOf<CalendarEntry>()
        readableDatabase.query("cards", null, "kind='task_card' AND state NOT IN ('deleted','pending_transcription')", null, null, null, null).use { rows ->
            while (rows.moveToNext()) {
                val card = readCard(rows)
                taskSchedule(card)?.let { schedule -> if (schedule.start < end && (schedule.end > start || (schedule.end == schedule.start && schedule.start >= start)))
                    entries += CalendarEntry(card.cardId, card.title, schedule.start, schedule.end, schedule.allDay,
                        "", card.cardId, card.state == "done", CalendarEntry.color(card.cardId)) }
            }
        }
        readableDatabase.rawQuery("SELECT value FROM runtime_settings WHERE key LIKE 'crdt:%'", null).use { rows ->
            while (rows.moveToNext()) {
                val doc = JSONObject(rows.getString(0))
                if (BufferSourceEffects.fenced(readableDatabase, doc.getString("id"))) continue
                if (doc.getString("kind") == "calendar" && !doc.getBoolean("deleted")) {
                    val entry = CalendarEntry.fromDocument(doc)
                    val series = BufferCrdt.values(doc).optJSONObject("recurrence")
                    if (series != null) entries += com.mocharealm.foundation.fabric.buffer.domain.CalendarRecurrence.expand(entry, series, start, end)
                    else if (entry.overlaps(start, end)) entries += entry
                }
            }
        }
        val taskIds = entries.filter { it.id == it.cardId }.map { it.id }.toSet()
        val deletedIds = deletedCardIds()
        return entries.filter { it.id == it.cardId || (it.cardId !in taskIds && it.cardId !in deletedIds &&
            (it.cardId.isBlank() || (crdtDocument(it.cardId)?.optBoolean("deleted") != true && findCard(it.cardId)?.state != "deleted"))) }.distinctBy { it.id }.sortedWith(compareBy<CalendarEntry> { it.start }.thenBy { it.id })
    }
    @Synchronized
    internal fun replaceCalendarSourceSnapshot(values: Map<String, JSONObject>) = runtimeSettings.transaction {
        val present = mutableSetOf<String>()
        values.forEach { (source, body) ->
            val id = calendarSourceDocumentId(source)
            upsertCalendarDocument(id, body);present += id
        }
        // Full Events snapshot covers masters, exceptions, deletions and the
        // legacy materialized-instance documents, independent of open month.
        calendarDocumentIds().filter { it !in present }.forEach { id ->
            val doc = crdtDocument(id) ?: return@forEach
            if (!doc.getBoolean("deleted") && BufferCrdt.values(doc).optString("source") == "android_calendar" &&
                BufferDocumentSource.audience(writableDatabase, id, "unknown") == "local")
                upsertCalendarDocument(id, JSONObject(), deleted = true)
        }
    }
    @Synchronized
    internal fun reconcileCalendarDocuments(start: Long, end: Long, present: Set<String>) = runtimeSettings.transaction {
        calendarDocumentIds().filter { it !in present }.forEach { id ->
            val doc = crdtDocument(id) ?: return@forEach
            if (!doc.getBoolean("deleted")) {
                val values = BufferCrdt.values(doc)
                val event = CalendarEntry.fromDocument(doc)
                if (values.optString("source") == "android_calendar" && event.overlaps(start, end))
                    upsertCalendarDocument(id, JSONObject(), deleted = true)
            }
        }
    }
    internal fun calendarDocumentIds(): List<String> = readableDatabase.rawQuery(
        "SELECT value FROM runtime_settings WHERE key LIKE 'crdt:%'", null,
    ).use { rows -> buildList { while (rows.moveToNext()) { val d = JSONObject(rows.getString(0)); if (d.getString("kind") == "calendar") add(d.getString("id")) } } }

    private fun focusTaskForCalendar(eventId: String): BufferCard? {
        val ids = linkedSetOf<String>()
        readableDatabase.query("events", arrayOf("payload"), "type = ?", arrayOf("CalendarEventCreated"),
            null, null, "logical_clock DESC").use { cursor ->
            while (cursor.moveToNext()) {
                val value = JSONObject(cursor.getString(0))
                if (value.optString("event_id") == eventId && !value.isNull("card_id")) ids += value.optString("card_id")
            }
        }
        return ids.mapNotNull(::findCard).filter { it.kind == "task_card" && it.state !in setOf("done", "deleted", "archived") }
            .filter { card -> calendarReceipt(card.cardId, card.captureId)?.let { !it.deleted && it.eventId == eventId } == true }
            .singleOrNull()
    }

    internal fun focusPlans(): List<FocusPlan> = readableDatabase.rawQuery(
        "SELECT value FROM runtime_settings WHERE key LIKE 'focus_plan:%'", null,
    ).use { c -> buildList { while (c.moveToNext()) add(FocusPlan.parse(JSONObject(c.getString(0)))) } }
        .sortedBy { it.begin }

    @Synchronized
    internal fun saveFocusPlan(plan: FocusPlan) = runtimeSettings.transaction {
        require(plan.key.matches(Regex("[0-9]+_[0-9]+")) && plan.eventId > 0 && plan.begin > 0 && plan.end > plan.begin)
        require(plan.title.isNotBlank() && plan.title.toByteArray(Charsets.UTF_8).size <= 160)
        require(plan.state in setOf("scheduled", "offered", "started", "cancelled", "expired"))
        val existing = focusPlans().firstOrNull { it.key == plan.key }
        // Editing notification policy must not start the same occurrence twice.
        require(existing?.state != "started" || plan.state == "started")
        runtimeSettings.edit().putString("focus_plan:${plan.key}", plan.json().toString()).apply()
        appendEvent("FocusPlanUpdated", null, plan.json())
    }

    @Synchronized
    internal fun startPlannedFocus(key: String, now: Long = System.currentTimeMillis()): FocusSession = runtimeSettings.transaction {
        val plan = focusPlans().first { it.key == key }
        require(plan.state in setOf("scheduled", "offered") && now in plan.begin until plan.end) { "这项日程已结束或已处理" }
        check(mode() == "normal" && focusSession()?.active != true) { "请先结束当前模式，再开始日程专注" }
        val linked = focusTaskForCalendar(plan.eventId.toString())
        val session = startFocus(plan.title, cardId = linked?.cardId.orEmpty(), calendarEventId = plan.eventId.toString(),
            plannedMinutes = ((plan.end - now) / 60_000).toInt().coerceIn(1, 1440), now = now)
        saveFocusPlan(plan.copy(state = "started"))
        session
    }

    @Synchronized
    internal fun remainingFocusTask(sessionId: String): BufferCard = runtimeSettings.transaction {
        val session = FocusSession.parse(JSONObject(requireNotNull(runtimeSettings.getString("focus_session:$sessionId", null))))
        require(!session.active && session.remaining.isNotBlank() && session.progress != "done")
        val key = "focus_remaining:$sessionId"
        runtimeSettings.getString(key, null)?.let { id ->
            return@transaction requireNotNull(findCard(id)?.takeIf { it.state != "deleted" }) { "剩余事项已删除" }
        }
        val now = System.currentTimeMillis()
        val capture = newId("cap")
        val card = BufferCard(newId("card"), capture, "task_card", session.remaining.take(50), session.remaining,
            "active", listOf("done", "later", "calendar", "archive"), now, classificationConfirmed = true,
            relatedCardIds = listOf(session.cardId).filter { it.isNotEmpty() })
        insertCapture(writableDatabase, capture, "android", now, "normal", null, 0, null, session.remaining, "task_card")
        insertCard(writableDatabase, card)
        runtimeSettings.edit().putString(key, card.cardId).apply()
        appendEvent("FocusRemainingTaskCreated", capture, JSONObject().put("session_id", sessionId)
            .put("card_id", card.cardId).put("text", session.remaining))
        card
    }

    internal fun focusReviewNotified(id: String): Boolean = runtimeSettings.getBoolean("focus_review_notice:$id", false)
    internal fun markFocusReviewNotified(id: String) = runtimeSettings.edit().putBoolean("focus_review_notice:$id", true).apply()

    internal fun focusTaskCandidates(): List<BufferCard> = queryCards(
        "kind = ? AND state NOT IN ('done', 'archived', 'deleted', 'pending_transcription')",
        arrayOf("task_card"), "created_at DESC", 100,
    )

    internal fun focusSession(): FocusSession? = runtimeSettings.getString("focus_session_current", null)
        ?.let { FocusSession.parse(JSONObject(it)) }

    internal fun focusHistory(): List<FocusSession> = readableDatabase.rawQuery(
        "SELECT value FROM runtime_settings WHERE key LIKE 'focus_session:%'", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(FocusSession.parse(JSONObject(cursor.getString(0)))) } }
        .sortedByDescending { it.startedAt }

    private fun storeFocusSession(session: FocusSession) {
        val raw = session.json().toString()
        runtimeSettings.edit().putString("focus_session_current", raw)
            .putString("focus_session:${session.id}", raw).apply()
        appendEvent("FocusSessionUpdated", session.cardId.takeIf { it.isNotEmpty() }?.let { findCard(it)?.captureId },
            session.json())
    }

    @Synchronized
    internal fun startFocus(title: String, cardId: String = "", calendarEventId: String = "",
        plannedMinutes: Int = 0, now: Long = System.currentTimeMillis()): FocusSession = runtimeSettings.transaction {
        check(focusSession()?.active != true) { "已有专注正在进行，请先结束" }
        if (cardId.isNotEmpty()) require(findCard(cardId)?.let { it.kind == "task_card" && it.state !in setOf("done", "deleted", "archived") } == true)
        val session = FocusSession(newId("focus"), title.trim(), now, cardId = cardId,
            calendarEventId = calendarEventId, plannedMinutes = plannedMinutes)
        storeFocusSession(session)
        setMode("focus")
        session
    }

    @Synchronized
    internal fun finishFocus(id: String, now: Long = System.currentTimeMillis()): FocusSession = runtimeSettings.transaction {
        val session = requireNotNull(focusSession()) { "没有正在进行的专注" }
        require(session.id == id) { "专注已更新，请重新打开" }
        if (!session.active) return@transaction session
        val time = maxOf(now, session.updatedAt + 1)
        val finished = session.copy(endedAt = time, updatedAt = time, status = "review_pending")
        storeFocusSession(finished)
        setMode("normal")
        appendEvent("FocusEnded", null, finished.json())
        finished
    }

    @Synchronized
    internal fun reviewFocus(id: String, progress: String, experience: String, remaining: String,
        now: Long = System.currentTimeMillis()): FocusSession = runtimeSettings.transaction {
        val saved = runtimeSettings.getString("focus_session:$id", null) ?: error("找不到这次专注")
        val session = FocusSession.parse(JSONObject(saved))
        require(!session.active) { "请先结束专注" }
        val updated = session.copy(progress = progress, experience = experience, remaining = remaining.trim(),
            status = "reviewed", updatedAt = maxOf(now, session.updatedAt + 1))
        // Historical feedback must not replace a newer active session.
        runtimeSettings.edit().putString("focus_session:$id", updated.json().toString()).apply()
        if (focusSession()?.id == id) runtimeSettings.edit().putString("focus_session_current", updated.json().toString()).apply()
        appendEvent("FocusFeedbackRecorded", null, updated.json())
        updated
    }

    /** Mode receipt and session metadata share the same SQLite commit. */
    @Synchronized
    internal fun applyVelaFocusMode(mode: String, modeId: String, session: FocusSession?,authorize: () -> Boolean = {true}) = runtimeSettings.transaction {
        check(authorize()) { "device connection expired" }
        if (modeReceipt(modeId) != null) { setMode(mode, modeId);check(authorize()) { "device connection expired" };return@transaction }
        if (session != null) {
            require(session.active == (mode == "focus"))
            val current = focusSession()
            if (current != null && current.id == session.id) {
                require(session.updatedAt >= current.updatedAt && (session.updatedAt != current.updatedAt || session == current)) {
                    "专注反馈存在冲突，原记录保留"
                }
            } else require(current?.active != true) { "另一段专注正在进行" }
            storeFocusSession(session)
        }
        setMode(mode, modeId)
        check(authorize()) { "device connection expired" }
    }

    @Synchronized
    fun setMode(mode: String, modeId: String? = null) {
        require(mode in MODES) { "unknown Buffer mode: $mode" }
        val cleanModeId = modeId?.trim()?.ifBlank { null }
        require(cleanModeId == null || cleanModeId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
            "invalid mode id"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (cleanModeId != null) {
                val previousMode = modeReceipt(cleanModeId)
                if (previousMode != null) {
                    require(previousMode == mode) { "mode id was already used for another mode" }
                    db.setTransactionSuccessful()
                    return
                }
            }
            if (mode != "rabbit_hole") rabbitSession()?.takeIf { it.active }?.let {
                finishRabbitSession(it.id, reason = "mode_changed")
            }
            val work = focusSession()
            val now = System.currentTimeMillis()
            if (mode != "focus" && work?.active == true) {
                val ended = maxOf(now, work.updatedAt + 1)
                storeFocusSession(work.copy(status = "review_pending", endedAt = ended, updatedAt = ended))
                appendEvent("FocusEnded", null, JSONObject().put("session_id", work.id).put("reason", "mode_changed"))
            } else if (mode == "focus" && work?.active != true) {
                storeFocusSession(FocusSession(newId("focus"), "专注", now))
            }
            runtimeSettings.edit().putString(KEY_MODE, mode).apply()
            if (mode == "focus") {
                ensureFocusSession(System.currentTimeMillis())
            } else {
                clearFocusSession()
            }
            appendEvent(
                db,
                "ModeChanged",
                null,
                System.currentTimeMillis(),
                JSONObject()
                    .put("mode", mode)
                    .put("mode_id", cleanModeId ?: JSONObject.NULL),
            )
            if (cleanModeId != null) {
                db.insertOrThrow("mode_receipts", null, ContentValues().apply {
                    put("mode_id", cleanModeId)
                    put("mode", mode)
                    put("created_at", System.currentTimeMillis())
                })
            }
            if (mode == "bedtime") {
                appendEvent(
                    type = "BedtimeDeckUpdated",
                    captureId = null,
                    payload = JSONObject().put(
                        "card_ids",
                        JSONArray(cardsForMode("bedtime", 5).map { it.cardId }),
                )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createHealthQuant(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS health_quant_records(card_id TEXT PRIMARY KEY,revision INTEGER NOT NULL,state TEXT NOT NULL,body TEXT NOT NULL,event_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS health_quant_jobs(card_id TEXT PRIMARY KEY,lease TEXT NOT NULL,input_hash TEXT NOT NULL,lease_until INTEGER NOT NULL,retry_at INTEGER NOT NULL)")
    }

    /** Only an actual local user confirmation may enqueue a new external model request. */
    private fun healthQuantInput(id:String, allowReceivedConfirmation:Boolean=false): Triple<BufferCard,String,String>? {
        val card=findCard(id) ?: return null
        if(card.kind!="care_card" || !card.classificationConfirmed || card.state=="deleted" ||
            card.healthCategory !in setOf("nutrition","hydration","sleep","exercise","symptom"))return null
        val capture=card.captureId?.let(::findCapture) ?: return null
        val text=capture.transcript?.takeIf { it.isNotBlank() && it.toByteArray().size<=32768 } ?: return null
        val consent=readableDatabase.rawQuery("SELECT event_id FROM events WHERE type IN ('ClassificationAccepted','ClassificationEdited','ClassificationRejected') AND json_extract(payload,'$.card_id')=? ORDER BY logical_clock DESC,rowid DESC LIMIT 1",arrayOf(id))
            .use { if(it.moveToFirst())it.getString(0) else null }
        if(consent!=null) {
            val event=BufferEventJournal.localRecord(readableDatabase,consent).event
            val payload=event.getJSONObject("payload")
            if(event.getString("type")=="ClassificationRejected" || payload.optString("kind")!=card.kind || payload.optString("health_category")!=card.healthCategory)return null
        } else {
            // Only the explicit numeric editor may revise an already confirmed
            // received fact. This path is never used to enqueue a model call.
            if(!allowReceivedConfirmation || BufferHealthQuantFacts.latest(readableDatabase).none {
                it.record.cardId==id && it.record.state in setOf("confirmed","retracted") && it.original.source!=BufferEventJournal.source(readableDatabase)
            })return null
        }
        val input=JSONObject().put("transcript",text).put("target_title",card.title).put("target_summary",card.summary)
            .put("category",card.healthCategory).put("capture_id",capture.captureId).put("created_at",capture.createdAt).toString()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString(""){"%02x".format(it)}
        return Triple(card,input,hash)
    }

    @Synchronized internal fun healthQuantRecord(id:String): com.mocharealm.foundation.fabric.buffer.domain.HealthQuantRecord? = runtimeSettings.transaction {
        val fact=BufferHealthQuantFacts.local(writableDatabase,id)
        if(fact==null) {
            check(!writableDatabase.rawQuery("SELECT 1 FROM health_quant_records WHERE card_id=?",arrayOf(id)).use { it.moveToFirst() }) { "健康记录缺少原件" }
            return@transaction null
        }
        val record=fact.record
        // This cache is disposable. Recovery copies the verified original,
        // never appends a new confirmation or recreates an execution lease.
        writableDatabase.insertWithOnConflict("health_quant_records",null,ContentValues().apply {
            put("card_id",id);put("revision",record.revision);put("state",record.state)
            put("body",record.data.json().toString());put("event_id",fact.original.eventId)
        },SQLiteDatabase.CONFLICT_REPLACE).also {check(it!=-1L)}
        record
    }

    @Synchronized internal fun healthQuantView(id:String):com.mocharealm.foundation.fabric.buffer.domain.HealthQuantView? = runtimeSettings.transaction {
        val fact=BufferHealthQuantFacts.latest(readableDatabase).find {it.record.cardId==id} ?: return@transaction null
        com.mocharealm.foundation.fabric.buffer.domain.HealthQuantView(fact.record,fact.original.eventId,
            healthQuantRecord(id)?.revision ?: 0,fact.original.source==BufferEventJournal.source(readableDatabase))
    }

    /** External writes require the current measurement's own local confirmation.
     * A prior local confirmation cannot authorize newer remote edits/retractions. */
    @Synchronized internal fun healthQuantForExport(id:String):com.mocharealm.foundation.fabric.buffer.domain.HealthQuant? = runtimeSettings.transaction {
        val view=healthQuantView(id) ?: return@transaction null
        check(view.local && view.record.state=="confirmed") { "请在本机核对并确认最新健康数值" }
        view.record.data
    }

    private fun checkHealthEdit(id:String,expectedEventId:String?) {
        if(expectedEventId!=null)require(healthQuantView(id)?.eventId.orEmpty()==expectedEventId) { "记录已更新，请重新查看" }
    }

    private fun storeHealthQuant(id:String, revision:Long, state:String, data:com.mocharealm.foundation.fabric.buffer.domain.HealthQuant, capture:String?) {
        val checked=com.mocharealm.foundation.fabric.buffer.domain.HealthQuant.parse(data.json().toString())
        appendEvent("HealthQuantRecorded",capture,JSONObject().put("card_id",id).put("revision",revision).put("state",state).put("data",checked.json()).put("category",requireNotNull(findCard(id)?.healthCategory)))
        val event=readableDatabase.rawQuery("SELECT event_id FROM events WHERE type='HealthQuantRecorded' AND json_extract(payload,'$.card_id')=? ORDER BY logical_clock DESC,rowid DESC LIMIT 1",arrayOf(id)).use { check(it.moveToFirst());it.getString(0) }
        writableDatabase.insertWithOnConflict("health_quant_records",null,ContentValues().apply {
            put("card_id",id);put("revision",revision);put("state",state);put("body",checked.json().toString());put("event_id",event)
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
    }

    @Synchronized internal fun confirmHealthQuant(id:String, expectedRevision:Long, data:com.mocharealm.foundation.fabric.buffer.domain.HealthQuant, expectedEventId:String?=null) = runtimeSettings.transaction {
        checkHealthEdit(id,expectedEventId)
        val input=requireNotNull(healthQuantInput(id,allowReceivedConfirmation=true)){"请先确认这条生活记录的分类"}
        val old=healthQuantRecord(id);require((old?.revision ?: 0L)==expectedRevision){"记录已更新，请重新查看"}
        require(expectedRevision in 0 until BufferSourceEvent.MAX_COUNTER)
        storeHealthQuant(id,expectedRevision+1,"confirmed",data,input.first.captureId)
        writableDatabase.delete("health_quant_jobs","card_id=?",arrayOf(id))
    }

    @Synchronized internal fun retractHealthQuant(id:String, expectedRevision:Long, expectedEventId:String?=null) = runtimeSettings.transaction {
        checkHealthEdit(id,expectedEventId)
        val local=healthQuantRecord(id)
        require((local?.revision ?: 0)==expectedRevision && expectedRevision in 0 until BufferSourceEvent.MAX_COUNTER) { "记录已更新，请重新查看" }
        val current=requireNotNull(healthQuantView(id)).record
        storeHealthQuant(id,expectedRevision+1,"retracted",current.data,findCard(id)?.captureId)
        writableDatabase.delete("health_quant_jobs","card_id=?",arrayOf(id))
    }

    @Synchronized internal fun claimHealthQuant(now:Long=System.currentTimeMillis()):com.mocharealm.foundation.fabric.buffer.domain.HealthQuantJob? = runtimeSettings.transaction {
        val ids=readableDatabase.rawQuery("SELECT c.card_id FROM cards c LEFT JOIN health_quant_records r ON r.card_id=c.card_id LEFT JOIN health_quant_jobs j ON j.card_id=c.card_id WHERE c.kind='care_card' AND c.classification_confirmed=1 AND c.state!='deleted' AND c.health_category IN ('nutrition','hydration','sleep','exercise','symptom') AND r.card_id IS NULL AND (j.card_id IS NULL OR (j.retry_at<=? AND j.lease_until<=?)) ORDER BY c.created_at DESC,c.card_id LIMIT 32",arrayOf(now.toString(),now.toString())).use { c -> buildList { while(c.moveToNext())add(c.getString(0)) } }
        val known=BufferHealthQuantFacts.latest(readableDatabase).map {it.record.cardId}.toSet()
        for(id in ids) {
            if(healthQuantRecord(id)!=null || id in known)continue
            val input=healthQuantInput(id) ?: continue
            val lease=UUID.randomUUID().toString()
            writableDatabase.insertWithOnConflict("health_quant_jobs",null,ContentValues().apply {
                put("card_id",id);put("lease",lease);put("input_hash",input.third);put("lease_until",now+300000);put("retry_at",now)
            },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
            return@transaction com.mocharealm.foundation.fabric.buffer.domain.HealthQuantJob(id,requireNotNull(input.first.captureId),lease,input.second,input.third,input.first.createdAt,java.time.ZoneId.systemDefault().id)
        };null
    }

    private fun currentHealthQuantJob(job:com.mocharealm.foundation.fabric.buffer.domain.HealthQuantJob, now:Long)=readableDatabase.rawQuery(
        "SELECT 1 FROM health_quant_jobs WHERE card_id=? AND lease=? AND input_hash=? AND lease_until>?",arrayOf(job.cardId,job.lease,job.hash,now.toString())).use { it.moveToFirst() }

    @Synchronized internal fun completeHealthQuant(job:com.mocharealm.foundation.fabric.buffer.domain.HealthQuantJob,data:com.mocharealm.foundation.fabric.buffer.domain.HealthQuant,now:Long=System.currentTimeMillis()):Boolean = runtimeSettings.transaction {
        if(!currentHealthQuantJob(job,now) || healthQuantRecord(job.cardId)!=null ||
            BufferHealthQuantFacts.latest(readableDatabase).any {it.record.cardId==job.cardId})return@transaction false
        val input=healthQuantInput(job.cardId) ?: return@transaction false
        if(input.third!=job.hash)return@transaction false
        require(data.zone==job.zone)
        val captured=java.time.Instant.ofEpochMilli(job.createdAt).atZone(java.time.ZoneId.of(job.zone)).toLocalDate()
        require(kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(captured,java.time.LocalDate.parse(data.date)))<=366)
        storeHealthQuant(job.cardId,1,"proposed",data,job.captureId)
        writableDatabase.delete("health_quant_jobs","card_id=? AND lease=?",arrayOf(job.cardId,job.lease));true
    }

    @Synchronized internal fun failHealthQuant(job:com.mocharealm.foundation.fabric.buffer.domain.HealthQuantJob,now:Long=System.currentTimeMillis()) = runtimeSettings.transaction {
        writableDatabase.update("health_quant_jobs",ContentValues().apply { put("lease_until",0);put("retry_at",now+60000) },"card_id=? AND lease=?",arrayOf(job.cardId,job.lease))
    }

    private fun confirmedHealthFacts(from:java.time.LocalDate,to:java.time.LocalDate):List<BufferHealthQuantFacts.Fact> =
        BufferHealthQuantFacts.latest(readableDatabase).filter { fact ->
            val record=fact.record
            val card=findCard(record.cardId)
            record.state=="confirmed" && java.time.LocalDate.parse(record.data.date) in from..to &&
                (card==null || (card.kind=="care_card" && card.state!="deleted" &&
                    (fact.original.source!=BufferEventJournal.source(readableDatabase) || card.classificationConfirmed)))
        }

    @Synchronized internal fun healthQuantFacts(now:Long):JSONObject = runtimeSettings.transaction {
        val date=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val from=date.minusDays(6)
        val facts=confirmedHealthFacts(from,date)
        val totals=healthQuantTotals(from,date)
        val ids=facts.map {it.record.cardId}.distinct()
        JSONObject().put("local_date_from",from.toString()).put("local_date_to",date.toString())
            .put("totals",JSONArray(totals.map { JSONObject().put("metric",it.metric).put("reported",it.reported ?: JSONObject.NULL)
                .put("estimated",it.estimated ?: JSONObject.NULL).put("unit",com.mocharealm.foundation.fabric.buffer.domain.HealthQuant.units.getValue(it.metric))
                .put("aggregation",if(it.metric=="weight_kg")"latest" else "sum").put("records",it.records).put("missing_entries",it.missingEntries) }))
            .put("source_card_ids",JSONArray(ids.take(128))).put("omitted_source_count",(ids.size-128).coerceAtLeast(0))
            .put("source_event_ids",JSONArray(facts.take(128).map {it.original.eventId}))
            .put("notice","已确认本地及已接收来源记录；估算与原文数值分开，缺少记录不代表未发生。同步历史不授予本机执行权限。")
    }

    @Synchronized internal fun healthQuantTotals(from:java.time.LocalDate,to:java.time.LocalDate):List<com.mocharealm.foundation.fabric.buffer.domain.HealthQuantTotal> = runtimeSettings.transaction {
        require(!from.isAfter(to))
        val totals=linkedMapOf<String,MutableList<Pair<Double,String>>>()
        val expectedCounts=mutableMapOf<String,Int>()
        val seen=mutableSetOf<String>()
        confirmedHealthFacts(from,to).forEach { fact ->
            val record=fact.record
            val capture=fact.original.event.optString("capture_id").takeUnless { it.isBlank() || it=="null" } ?: record.cardId
            record.data.entries.forEach { entry ->
                val key=capture+":"+record.data.date+":"+BufferCrdt.canonical(entry.json())
                if(seen.add(key)) {
                    val expected=when(fact.category ?: findCard(record.cardId)?.healthCategory){
                        "nutrition"->setOf("energy_kcal","protein_g","carbs_g","fat_g")
                        "hydration"->setOf("water_ml");"sleep"->setOf("sleep_min");"exercise"->setOf("exercise_min");else->entry.values.keys
                    }
                    (expected+entry.values.keys).forEach { metric->expectedCounts[metric]=(expectedCounts[metric] ?: 0)+1 }
                    entry.values.forEach { (metric,value)->totals.getOrPut(metric){mutableListOf()}.add(value to entry.basis) }
                }
            }
        }
        totals.map { (metric,values)->
            fun amount(basis:String):Double?=values.filter { it.second==basis }.takeIf { it.isNotEmpty() }?.let { if(metric=="weight_kg")it.last().first else it.sumOf { v->v.first } }
            com.mocharealm.foundation.fabric.buffer.domain.HealthQuantTotal(metric,amount("reported"),amount("estimated"),values.size,((expectedCounts[metric] ?: 0)-values.size).coerceAtLeast(0))
        }
    }

    @Synchronized
    internal fun researchPlanApproved(plan: com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan): Boolean = runtimeSettings.transaction {
        val event=writableDatabase.rawQuery("SELECT event_id FROM events WHERE type IN ('ResearchWeeklyPlanSaved','ResearchWeeklyPlanCancelled') AND json_extract(payload,'$.id')=? ORDER BY logical_clock DESC,rowid DESC LIMIT 1",arrayOf(plan.id))
            .use { if(it.moveToFirst())it.getString(0) else null } ?: return@transaction false
        val body=BufferEventJournal.localRecord(writableDatabase,event).event.getJSONObject("payload")
        val expected=plan.json()
        listOf("id","revision","card_id","title","weekday","time","zone","minutes","enabled","calendar_id").all {
            com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt.canonical(body.get(it)) == com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt.canonical(expected.get(it))
        }
    }

    @Synchronized
    internal fun researchWeeklyPlans() = BufferResearchPlans.rows(readableDatabase)

    @Synchronized
    internal fun saveResearchWeeklyPlan(cardId: String, day: Int, time: String, minutes: Int,
        zone: String = java.time.ZoneId.systemDefault().id, calendarId: Long? = null,
        id: String? = null, now: Long = System.currentTimeMillis()): com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan = runtimeSettings.transaction {
        val card=requireNotNull(findCard(cardId)) { "主题已不存在" }
        require(card.kind in setOf("question_card","spark_card","rabbit_hole_card") && card.state !in setOf("done","archived","deleted","pending_transcription"))
        val existing=researchWeeklyPlans();val old=id?.let { key -> requireNotNull(existing.find { it.id==key }) }
        require(old==null || old.enabled) { "请新建预约" }
        require(old==null || old.calendarId==calendarId) { "更换日历前请先撤销原预约" }
        require(old!=null || existing.size<64) { "预约数量已满" }
        val p=com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan(
            old?.id ?: "research_"+java.util.UUID.randomUUID().toString().replace("-",""), (old?.revision ?: 0)+1,
            cardId,card.title,day,time,zone,minutes,
            com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan.nextOccurrence(day,time,zone,now),
            calendarId=calendarId,eventId=old?.eventId,calendarState=if(calendarId==null)"local" else "pending",calendarRevision=old?.calendarRevision ?: 0)
        BufferResearchPlans.save(writableDatabase,p)
        appendEvent(writableDatabase,"ResearchWeeklyPlanSaved",card.captureId,now,p.json());p
    }

    @Synchronized
    internal fun cancelResearchWeeklyPlan(id: String, now: Long = System.currentTimeMillis()) = runtimeSettings.transaction {
        val old=requireNotNull(researchWeeklyPlans().find { it.id==id })
        if(!old.enabled)return@transaction old
        val p=old.copy(revision=old.revision+1,enabled=false,offeredAt=0,delivered=false,
            calendarState=if(old.calendarId==null)"local" else "delete_pending",problem="")
        BufferResearchPlans.save(writableDatabase,p)
        appendEvent(writableDatabase,"ResearchWeeklyPlanCancelled",findCard(old.cardId)?.captureId,now,p.json());p
    }

    @Synchronized
    internal fun researchCalendarResult(id: String, revision: Long, eventId: Long?, state: String, problem: String = "") = runtimeSettings.transaction {
        val p=researchWeeklyPlans().find { it.id==id && it.revision==revision } ?: return@transaction
        if(p.calendarId==null)return@transaction
        require(state in setOf("synced","deleted","failed"))
        if(p.eventId==eventId && p.calendarState==state && p.problem==problem)return@transaction
        val next=p.copy(eventId=eventId,calendarState=state,problem=problem,calendarRevision=if(state=="synced")revision else p.calendarRevision)
        BufferResearchPlans.save(writableDatabase,next)
        appendEvent(writableDatabase,"ResearchCalendarResult",findCard(p.cardId)?.captureId,System.currentTimeMillis(),next.json())
    }

    @Synchronized
    internal fun reconcileResearchWeekly(now: Long, post: (com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan)->Boolean,
        clear: (String)->Unit): Long? = runtimeSettings.transaction {
        val wake=mutableListOf<Long>()
        for(initial in researchWeeklyPlans()) {
            var p=initial
            val card=findCard(p.cardId)
            if(p.enabled && (card==null || card.state in setOf("done","archived","deleted"))) p=cancelResearchWeeklyPlan(p.id,now)
            if(!researchPlanApproved(p)){clear(p.id);continue}
            if(!p.enabled){
                clear(p.id)
                if(p.calendarState in setOf("local","deleted")) writableDatabase.delete("research_weekly_plans","id=?",arrayOf(p.id))
                continue
            }
            if(now>=p.endAt) {
                clear(p.id)
                val next=p.copy(nextAt=p.next(now),offeredAt=0,delivered=false,startedAt=0)
                BufferResearchPlans.save(writableDatabase,next)
                appendEvent(writableDatabase,"ResearchWeeklyOccurrenceClosed",card?.captureId,now,p.json().put("missed",p.startedAt==0L))
                p=next
            }
            if(now<p.nextAt){clear(p.id);wake+=p.nextAt;continue}
            if(p.startedAt!=0L){clear(p.id);wake+=p.endAt;continue}
            if(mode()!="normal" || rabbitSession()?.active==true){clear(p.id);wake+=minOf(p.endAt,now+60_000);continue}
            if(!p.delivered) {
                val delivered=post(p)
                if(delivered) {
                    p=p.copy(offeredAt=p.nextAt,delivered=true);BufferResearchPlans.save(writableDatabase,p)
                    appendEvent(writableDatabase,"ResearchWeeklyOffered",card?.captureId,now,p.json())
                }else wake+=minOf(p.endAt,now+60_000)
            }
            wake+=p.endAt
        }
        wake.minOrNull()
    }

    @Synchronized
    internal fun startResearchWeekly(id: String, revision: Long, occurrence: Long, now: Long = System.currentTimeMillis()): RabbitHoleSession = runtimeSettings.transaction {
        val p=requireNotNull(researchWeeklyPlans().find { it.id==id && it.revision==revision }) { "预约已更新" }
        require(p.enabled && researchPlanApproved(p) && p.nextAt==occurrence && now>=p.nextAt && now<p.endAt) { "预约已过期或当前模式不适合研究" }
        if(p.startedAt!=0L) {
            return@transaction requireNotNull(rabbitSession()).also { require(it.cardId==p.cardId && it.startedAt==p.startedAt && it.endsAt==p.endAt) }
        }
        require(mode()=="normal") { "请先结束当前专注或睡前模式" }
        val created=startRabbitSession(p.cardId,p.minutes,now,p.endAt)
        BufferResearchPlans.save(writableDatabase,p.copy(startedAt=now))
        appendEvent(writableDatabase,"ResearchWeeklyStarted",findCard(p.cardId)?.captureId,now,p.json().put("session_id",created.id))
        created
    }

    @Synchronized
    internal fun rabbitSession(): RabbitHoleSession? = runtimeSettings.getString("rabbit_session", null)
        ?.let(RabbitHoleSession::parse)

    @Synchronized
    internal fun rabbitSnapshot(now: Long): JSONObject = runtimeSettings.transaction {
        reconcileRabbitSession(now)
        val session = rabbitSession() ?: return@transaction JSONObject().put("present", false)
        val title = session.title.takeCodePoints(40)
        JSONObject().put("present", true).put("id", session.id).put("title", title)
            .put("active", session.active).put("reason", session.reason)
            .put("remaining_ms", if (session.active) (session.endsAt - now).coerceIn(0, 7_200_000) else 0)
    }

    /** Latest event snapshot per session, ordered by last recorded change. */
    @Synchronized
    internal fun rabbitHistory(limit: Int = 50, offset: Int = 0): List<RabbitHoleSession> {
        require(limit in 1..100 && offset in 0..100_000)
        val seen = HashSet<String>()
        val result = ArrayList<RabbitHoleSession>()
        readableDatabase.query("events", arrayOf("payload"),
            "type IN (?, ?, ?)", arrayOf("RabbitHoleStarted", "RabbitHoleEnded", "RabbitHoleOutcomeRecorded"),
            null, null, "logical_clock DESC, rowid DESC").use { cursor ->
            while (cursor.moveToNext()) {
                val session = RabbitHoleSession.parse(cursor.getString(0))
                if (!seen.add(session.id)) continue
                if (seen.size <= offset) continue
                result.add(session)
                if (result.size == limit) break
            }
        }
        return result
    }

    @Synchronized
    internal fun startRabbitSession(cardId: String, minutes: Int, now: Long = System.currentTimeMillis(), scheduledEnd: Long? = null): RabbitHoleSession = runtimeSettings.transaction {
        require(minutes in setOf(60, 90, 120)) { "请选择 60、90 或 120 分钟" }
        require(now > 0 && now <= Long.MAX_VALUE - minutes * 60_000L)
        check(rabbitSession()?.active != true) { "请先结束当前研究" }
        val card = requireNotNull(findCard(cardId)) { "主题已不存在" }
        require(card.kind in setOf("question_card", "spark_card", "rabbit_hole_card") &&
            card.state !in setOf("done", "archived")) { "请选择尚未完成的研究主题" }
        val end = scheduledEnd ?: (now + minutes * 60_000L)
        require(end > now && end <= now + minutes * 60_000L)
        val session = RabbitHoleSession(newId("research"), cardId, card.title, now, end)
        runtimeSettings.edit().putString("rabbit_session", session.json().toString())
            .remove("rabbit_notice").remove("rabbit_notice_posted").remove("rabbit_notice_retry").apply()
        setMode("rabbit_hole")
        appendEvent(writableDatabase, "RabbitHoleStarted", card.captureId, now, session.json())
        session
    }

    @Synchronized
    internal fun finishRabbitSession(id: String, outcome: String = "", now: Long = System.currentTimeMillis(),
        reason: String = "finished"): RabbitHoleSession = runtimeSettings.transaction {
        require(reason in setOf("finished", "time_up", "mode_changed"))
        require(outcome.length <= 4000) { "研究记录最多 4000 字" }
        val current = requireNotNull(rabbitSession()) { "没有研究会话" }
        require(current.id == id) { "研究会话已更新，请刷新" }
        if (!current.active) return@transaction current
        val ended = if (reason == "time_up") current.endsAt else now.coerceIn(current.startedAt, current.endsAt)
        val finished = current.copy(finishedAt = ended, reason = reason, outcome = outcome.trim())
        runtimeSettings.edit().putString("rabbit_session", finished.json().toString()).apply()
        if (reason == "time_up") runtimeSettings.edit().putString("rabbit_notice", current.id)
            .putBoolean("rabbit_notice_posted", false).remove("rabbit_notice_retry").apply()
        appendEvent(writableDatabase, "RabbitHoleEnded", findCard(current.cardId)?.captureId,
            ended, finished.json())
        if (reason != "mode_changed" && mode() == "rabbit_hole") setMode("normal")
        finished
    }

    @Synchronized
    internal fun recordRabbitOutcome(id: String, outcome: String): RabbitHoleSession = runtimeSettings.transaction {
        require(outcome.length <= 4000) { "研究记录最多 4000 字" }
        val current = requireNotNull(rabbitSession())
        require(current.id == id && !current.active) { "研究会话已更新，请刷新" }
        val updated = current.copy(outcome = outcome.trim())
        if (updated != current) {
            runtimeSettings.edit().putString("rabbit_session", updated.json().toString()).apply()
            appendEvent("RabbitHoleOutcomeRecorded", findCard(current.cardId)?.captureId, updated.json())
        }
        updated
    }

    @Synchronized
    internal fun reconcileRabbitSession(now: Long): Long? = runtimeSettings.transaction {
        val current = rabbitSession()?.takeIf { it.active } ?: return@transaction null
        when {
            mode() != "rabbit_hole" -> finishRabbitSession(current.id, now = now, reason = "mode_changed")
            now >= current.endsAt -> finishRabbitSession(current.id, now = now, reason = "time_up")
            else -> return@transaction current.endsAt
        }
        null
    }

    @Synchronized
    internal fun deliverRabbitNotice(now: Long, post: (RabbitHoleSession) -> Boolean,
        clear: () -> Unit): Long? = runtimeSettings.transaction {
        val session = rabbitSession()
        val pending = runtimeSettings.getString("rabbit_notice", null)
        if (session == null || session.active || pending != session.id) {
            clear()
            return@transaction null
        }
        if (mode() == "bedtime") {
            clear()
            return@transaction null
        }
        if (runtimeSettings.getBoolean("rabbit_notice_posted", false)) return@transaction null
        val retry = runtimeSettings.getLong("rabbit_notice_retry", 0)
        if (now < retry) return@transaction retry
        if (!post(session)) {
            val next = now + 60_000
            runtimeSettings.edit().putLong("rabbit_notice_retry", next).apply()
            return@transaction next
        }
        runtimeSettings.edit().putBoolean("rabbit_notice_posted", true).remove("rabbit_notice_retry").apply()
        appendEvent(writableDatabase, "RabbitHoleNotificationPosted", findCard(session.cardId)?.captureId,
            now, JSONObject().put("session_id", session.id))
        null
    }

    internal fun focusReminderConfig(): FocusReminderConfig = FocusReminderConfig(
        runtimeSettings.getLong(KEY_FOCUS_INTERVAL_MS, BufferConstants.focusReminderMs),
        runtimeSettings.getLong(KEY_FOCUS_SNOOZE_MS, BufferConstants.focusSnoozeMs),
    )

    @Synchronized
    internal fun setFocusReminderConfig(config: FocusReminderConfig, now: Long = System.currentTimeMillis()) = runtimeSettings.transaction {
        if (config == focusReminderConfig()) return@transaction
        val active = mode() == "focus"
        runtimeSettings.edit().putLong(KEY_FOCUS_INTERVAL_MS, config.intervalMs)
            .putLong(KEY_FOCUS_SNOOZE_MS, config.snoozeMs)
            .putLong(KEY_FOCUS_NEXT_AT, if (active) now + config.intervalMs else 0L)
            .putString(KEY_FOCUS_REVISION, newId("focus"))
            .remove(KEY_FOCUS_PENDING_NOTIFICATION)
            .putBoolean(KEY_FOCUS_CLEAR_NOTICE, true).apply()
        appendEvent(type = "FocusReminderConfigUpdated", captureId = null,
            payload = JSONObject().put("interval_ms", config.intervalMs).put("snooze_ms", config.snoozeMs))
    }

    @Synchronized
    internal fun focusReminderSnapshot(now: Long): JSONObject = runtimeSettings.transaction {
        val active = mode() == "focus"
        val next = if (active) ensureFocusSession(now) else 0L
        val config = focusReminderConfig()
        return@transaction JSONObject().put("type", "FocusReminderConfigUpdated")
            .put("active", active).put("interval_ms", config.intervalMs)
            .put("remaining_ms", if (active) config.remainingMs(next, now) else 0L)
            .put("revision", if (active) runtimeSettings.getString(KEY_FOCUS_REVISION, "") else "inactive")
            .put("clear_notice", runtimeSettings.getBoolean(KEY_FOCUS_CLEAR_NOTICE, false))
            .put("notice_remaining_ms", if (active) config.noticeRemainingMs(
                next, now, runtimeSettings.getBoolean(KEY_FOCUS_CLEAR_NOTICE, false),
            ) else 0L)
    }

    fun focusNextReminderAt(): Long = runtimeSettings.getLong(KEY_FOCUS_NEXT_AT, 0L)

    @Synchronized
    fun ensureFocusSession(now: Long): Long = runtimeSettings.transaction {
        if (mode() != "focus") return@transaction 0L
        val existing = focusNextReminderAt()
        if (existing > 0L) {
            if (runtimeSettings.getString(KEY_FOCUS_REVISION, "").isNullOrBlank()) {
                runtimeSettings.edit().putString(KEY_FOCUS_REVISION, newId("focus")).apply()
            }
            return@transaction existing
        }
        val next = now + focusReminderConfig().intervalMs
        runtimeSettings.edit()
            .putLong(KEY_FOCUS_STARTED_AT, now)
            .putLong(KEY_FOCUS_NEXT_AT, next)
            .putString(KEY_FOCUS_REVISION, newId("focus"))
            .remove(KEY_FOCUS_PENDING_NOTIFICATION)
            .putBoolean(KEY_FOCUS_CLEAR_NOTICE, true)
            .apply()
        appendEvent(
            type = "FocusStarted",
            captureId = null,
            payload = JSONObject().put("started_at", now),
        )
        return@transaction next
    }

    @Synchronized
    fun fireFocusReminder(now: Long): Long = runtimeSettings.transaction {
        val next = now + focusReminderConfig().intervalMs
        val revision = newId("focus")
        runtimeSettings.edit().putLong(KEY_FOCUS_NEXT_AT, next)
            .putString(KEY_FOCUS_REVISION, revision)
            .putString(KEY_FOCUS_PENDING_NOTIFICATION, revision)
            .putBoolean(KEY_FOCUS_CLEAR_NOTICE, false).apply()
        appendEvent(
            type = "FocusReminderShown",
            captureId = null,
            payload = JSONObject().put("next_at", next),
        )
        return@transaction next
    }

    @Synchronized
    fun deferFocusReminder(now: Long, delayMs: Long, action: String): Long = runtimeSettings.transaction {
        require(delayMs in 1..10_800_000L) { "invalid focus delay" }
        val next = now + delayMs
        runtimeSettings.edit().putLong(KEY_FOCUS_NEXT_AT, next)
            .putString(KEY_FOCUS_REVISION, newId("focus"))
            .remove(KEY_FOCUS_PENDING_NOTIFICATION)
            .putBoolean(KEY_FOCUS_CLEAR_NOTICE, true).apply()
        appendEvent(
            type = "FocusReminderDeferred",
            captureId = null,
            payload = JSONObject()
                .put("action", action)
                .put("next_at", next),
        )
        return@transaction next
    }

    @Synchronized
    fun clearFocusSession() = runtimeSettings.transaction {
        runtimeSettings.edit()
            .remove(KEY_FOCUS_STARTED_AT)
            .remove(KEY_FOCUS_NEXT_AT)
            .remove(KEY_FOCUS_REVISION)
            .remove(KEY_FOCUS_CLEAR_NOTICE)
            .remove(KEY_FOCUS_PENDING_NOTIFICATION)
            .apply()
    }

    @Synchronized
    internal fun deliverPendingFocusReminder(deliver: () -> Boolean) = runtimeSettings.transaction {
        val revision = runtimeSettings.getString(KEY_FOCUS_PENDING_NOTIFICATION, null)
        if (mode() != "focus" || revision.isNullOrBlank()) return@transaction
        if (deliver()) {
            runtimeSettings.edit().remove(KEY_FOCUS_PENDING_NOTIFICATION).apply()
            appendEvent("FocusNotificationPosted", null, JSONObject().put("revision", revision))
        }
    }

    fun pendingCaptureCount(): Int {
        // The committed .bin blob is retained after transcription so that a
        // capture can be audited or retried.  Count the database state rather
        // than every audio file, otherwise completed captures stay visible as
        // "待处理" forever.
        val databasePending = pendingAudioCaptures().size
        val retryPending = captureDir.listFiles { file ->
            file.name.endsWith(RETRY_AUDIO_SUFFIX)
        }?.size ?: 0
        return databasePending + retryPending
    }

    fun eventCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun weeklySummary(now: Long = System.currentTimeMillis()): BufferWeeklySummary {
        val since = now - 7L * 24L * 60L * 60L * 1000L
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT kind, COUNT(*) FROM cards WHERE created_at >= ? AND created_at <= ? AND state NOT IN ('pending_transcription', 'deleted') " +
                "AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.capture_id = cards.capture_id AND c.blob_path IS NOT NULL AND (c.transcript IS NULL OR TRIM(c.transcript) = '')) GROUP BY kind",
            arrayOf(since.toString(), now.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        // Completion belongs to the action's date, not the task creation date.
        // Legacy events lack card_kind, so resolve those against existing task cards.
        val taskIds = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT card_id FROM cards WHERE kind = ?", arrayOf("task_card")).use { cursor ->
            while (cursor.moveToNext()) taskIds += cursor.getString(0)
        }
        val completedIds = mutableSetOf<String>()
        val laterActions = mutableMapOf<String, MutableSet<String>>()
        readableDatabase.rawQuery(
            "SELECT payload, event_id FROM events WHERE type = ? AND created_at >= ? AND created_at <= ?",
            arrayOf("CardActionPerformed", since.toString(), now.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = runCatching { JSONObject(cursor.getString(0)) }.getOrNull() ?: continue
                val id = payload.optString("card_id")
                if (id.isNotBlank() && payload.optString("action") == "later") {
                    val receipt = (payload.opt("action_id") as? String)?.takeIf { it.isNotBlank() }
                    laterActions.getOrPut(id) { mutableSetOf() }.add(receipt ?: cursor.getString(1))
                }
                val isTask = if (payload.has("card_kind")) payload.optString("card_kind") == "task_card" else id in taskIds
                if (id.isNotBlank() && isTask && payload.optString("action") == "done" &&
                    payload.optString("old_state") != "done") completedIds += id
            }
        }
        val questions = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cards WHERE kind = ? AND created_at >= ? AND created_at <= ? " +
                "AND state NOT IN ('archived', 'done', 'researched', 'quick_answered', 'deleted') " +
                "AND (answer IS NULL OR TRIM(answer) = '')",
            arrayOf("question_card", since.toString(), now.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val focusReminders = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE type = ? AND created_at >= ? AND created_at <= ?",
            arrayOf("FocusReminderShown", since.toString(), now.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val careRecords = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cards WHERE created_at >= ? AND created_at <= ? AND state != 'deleted' AND (kind = ? OR health_category IS NOT NULL)",
            arrayOf(since.toString(), now.toString(), "care_card"),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val candidates = mutableListOf<BufferCard>()
        readableDatabase.query(
            "cards",
            null,
            "created_at >= ? AND created_at <= ? AND state != 'deleted' AND ((kind = ? AND state NOT IN (?, ?, ?, ?) " +
                "AND (answer IS NULL OR TRIM(answer) = '')) OR (kind = ? AND state = ?))",
            arrayOf(
                since.toString(),
                now.toString(),
                "question_card",
                "archived",
                "done",
                "researched",
                "quick_answered",
                "rabbit_hole_card",
                "rabbit_hole_candidate",
            ),
            null,
            null,
            "created_at DESC",
            "2",
        ).use { cursor ->
            while (cursor.moveToNext()) candidates += readCard(cursor)
        }
        val recentCards = mutableListOf<BufferCard>()
        readableDatabase.query("cards", null, "created_at >= ? AND created_at <= ? AND state != 'deleted'",
            arrayOf(since.toString(), now.toString()), null, null, "created_at ASC, card_id ASC").use { cursor ->
            while (cursor.moveToNext()) recentCards += readCard(cursor)
        }
        val openTasks = mutableListOf<BufferCard>()
        readableDatabase.query("cards", null, "kind = ? AND created_at <= ? AND state NOT IN ('done', 'archived', 'deleted')",
            arrayOf("task_card", now.toString()), null, null, "created_at ASC, card_id ASC").use { cursor ->
            while (cursor.moveToNext()) openTasks += readCard(cursor)
        }
        val themes = WeeklyReflection.themes(recentCards)
        val deferred = WeeklyReflection.deferred(openTasks, laterActions.mapValues { it.value.size })
        return BufferWeeklySummary(
            sparks = counts["spark_card"] ?: 0,
            questions = questions,
            tasksCompleted = completedIds.size,
            careRecords = careRecords,
            focusReminders = focusReminders,
            rabbitHoleCandidates = candidates,
            recurringThemes = themes,
            deferredTasks = deferred,
            nextWeekSuggestions = WeeklyReflection.suggestions(deferred, candidates),
            lifeOverview = WeeklyReflection.lifeOverview(recentCards),
            unconfirmedLifeRecords = recentCards.filter { it.kind == "care_card" && !it.classificationConfirmed }
                .distinctBy { it.captureId ?: it.cardId }.size,
        )
    }

    fun weeklyAutoEnabled(): Boolean = runtimeSettings.getBoolean("weekly_auto_enabled", true)

    @Synchronized
    fun setWeeklyAutoEnabled(enabled: Boolean) = runtimeSettings.transaction {
        if (weeklyAutoEnabled() == enabled) return@transaction
        runtimeSettings.edit().putBoolean("weekly_auto_enabled", enabled)
            .remove("weekly_auto_next").remove("weekly_auto_zone").apply()
        appendEvent(writableDatabase, "WeeklyScheduleChanged", null, System.currentTimeMillis(),
            JSONObject().put("enabled", enabled).put("local_schedule", "Sunday 20:00"))
        if (!enabled) {
            val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
            for (id in jobs.keys()) {
                val job = jobs.getJSONObject(id)
                if (job.optString("trigger") != "schedule" || job.optString("state") in setOf("complete", "superseded")) continue
                job.put("state", "superseded").put("error", "自动总结已关闭").remove("lease"); job.remove("lease_until")
                appendEvent("WeeklySummarySkipped", null, JSONObject().put("job_id", id).put("reason", "schedule_disabled"))
            }
            runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
        }
    }

    @Synchronized
    internal fun reconcileWeekly(now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? = runtimeSettings.transaction {
        if (!weeklyAutoEnabled()) return@transaction null
        val next = WeeklySchedule.next(now, zone)
        val saved = runtimeSettings.getLong("weekly_auto_next", 0L)
        val savedZone = runtimeSettings.getString("weekly_auto_zone", "")
        if (saved > 0L && savedZone == zone.id && now >= saved) {
            // After a long absence generate only the latest due report, not a backlog.
            generateWeeklySummary(WeeklySchedule.latest(now, zone), trigger = "schedule")
        }
        val target = if (saved > now && savedZone == zone.id) minOf(saved, next) else next
        runtimeSettings.edit().putLong("weekly_auto_next", target).putString("weekly_auto_zone", zone.id).apply()
        target
    }

    /** Enqueues a durable LLM request. Never creates a rule-generated summary card. */
    @Synchronized
    fun generateWeeklySummary(now: Long = System.currentTimeMillis(), trigger: String = "user"): String = runtimeSettings.transaction {
        require(trigger in setOf("user", "schedule"))
        if (trigger == "schedule") check(weeklyAutoEnabled())
        val id = "weekly-${weekStartMillis(now)}"
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        val existing = jobs.optJSONObject(id)
        if (existing != null && existing.optString("state") != "superseded" &&
            !(existing.optString("state") == "failed" && existing.optString("trigger") == "schedule" && trigger == "user")) {
            if (existing.optString("state") == "failed") {
                existing.put("retry_at", 0L).put("state", "pending")
                runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
            }
            return@transaction id
        }
        val facts = weeklyLlmFacts(now)
        jobs.put(id, JSONObject().put("id", id).put("requested_at", now).put("state", "pending")
            .put("facts", facts).put("attempts", 0).put("retry_at", 0L).put("trigger", trigger))
        appendEvent(writableDatabase, "WeeklySummaryRequested", null, now, JSONObject().put("job_id", id)
            .put("period_start", now - 7L * 86_400_000).put("period_end", now).put("facts_sha256", BufferMeshTaskContract.digest(facts)).put("trigger", trigger))
        val requestId = writableDatabase.rawQuery("SELECT event_id FROM events WHERE type='WeeklySummaryRequested' ORDER BY logical_clock DESC,rowid DESC LIMIT 1", null)
            .use { check(it.moveToFirst()); it.getString(0) }
        jobs.getJSONObject(id).put("request_event_id", requestId)
        runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
        id
    }

    internal fun weeklyLlmFacts(now: Long): JSONObject {
        val since = now - 7L * 86_400_000
        val records = JSONArray()
        var count = 0
        readableDatabase.query("cards", null,
            "created_at >= ? AND created_at <= ? AND kind != 'weekly_card' AND state NOT IN ('pending_transcription', 'deleted') " +
                "AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.capture_id = cards.capture_id AND c.blob_path IS NOT NULL AND (c.transcript IS NULL OR TRIM(c.transcript) = ''))",
            arrayOf(since.toString(), now.toString()), null, null, "created_at DESC").use { cursor ->
            while (cursor.moveToNext()) {
                count++
                if (records.length() >= 100) continue
                val card = readCard(cursor)
                records.put(weeklyRecordFact(card))
            }
        }
        val facts = weeklySummary(now)
        val result = JSONObject().put("task", "weekly_review").put("period_start", since).put("period_end", now)
            .put("records", records).put("omitted_record_count", count - records.length())
            .put("health_quantified", healthQuantFacts(now))
            .put("statistics", JSONObject().put("sparks", facts.sparks).put("unresolved_questions", facts.questions)
                .put("completed_tasks", facts.tasksCompleted).put("care_records", facts.careRecords))
            .put("deferred_tasks", JSONArray(facts.deferredTasks.map { JSONObject().put("card_id", it.card.cardId).put("times", it.times) }))
        var omitted = result.getInt("omitted_record_count")
        var omittedDeferred = 0
        val deferred = result.getJSONArray("deferred_tasks")
        while (BufferCrdt.canonical(result).toByteArray(Charsets.UTF_8).size > BufferWeeklyCodec.MAX_FACT_BYTES) {
            if (records.length() > 0) { records.remove(records.length() - 1); result.put("omitted_record_count", ++omitted) }
            else if (deferred.length() > 0) { deferred.remove(deferred.length() - 1); result.put("omitted_deferred_task_count", ++omittedDeferred) }
            else error("周总结事实元数据超出上限")
        }
        return result
    }

    private fun weeklyRecordFact(card: BufferCard): JSONObject = JSONObject().put("id", card.cardId).put("kind", card.kind)
        .put("title", card.title.takeCodePoints(80)).put("text", card.summary.takeCodePoints(500)).put("created_at", card.createdAt)
        .put("state", card.state).put("classification_confirmed", card.classificationConfirmed)
        .put("tags", JSONArray(card.tags)).put("health_category", card.healthCategory ?: JSONObject.NULL)

    private fun weeklyFactsStillCurrent(facts: JSONObject): Boolean {
        val records = facts.getJSONArray("records"); val deferred = facts.getJSONArray("deferred_tasks")
        val ids = linkedSetOf<String>()
        for (i in 0 until records.length()) ids.add(records.getJSONObject(i).getString("id"))
        for (i in 0 until deferred.length()) ids.add(deferred.getJSONObject(i).getString("card_id"))
        val cards = mutableMapOf<String, BufferCard>()
        for (batch in ids.toList().chunked(200)) writableDatabase.query("cards", null,
            "card_id IN (${batch.joinToString(",") { "?" }})", batch.toTypedArray(), null, null, null).use { rows ->
            while (rows.moveToNext()) readCard(rows).let { cards[it.cardId] = it }
        }
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i); val card = cards[record.getString("id")] ?: return false
            if (card.state in setOf("deleted", "pending_transcription") || BufferCrdt.canonical(weeklyRecordFact(card)) != BufferCrdt.canonical(record)) return false
        }
        return (0 until deferred.length()).all {
            val card = cards[deferred.getJSONObject(it).getString("card_id")]
            card != null && card.kind == "task_card" && card.state !in setOf("deleted", "pending_transcription", "done", "archived")
        }
    }

    internal fun weeklyLlmStatus(now: Long = System.currentTimeMillis()): JSONObject? =
        JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!).optJSONObject("weekly-${weekStartMillis(now)}")

    @Synchronized
    internal fun claimWeeklyLlm(now: Long, forMesh: Boolean = false, onlyId: String? = null): WeeklyLlmJob? = runtimeSettings.transaction {
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        for (id in jobs.keys()) {
            if (onlyId != null && id != onlyId) continue
            val job = jobs.getJSONObject(id)
            val state = job.optString("state")
            if (state in setOf("complete", "superseded")) continue
            val delegated = meshWeeklyEligible(job)
            if (forMesh != delegated) continue
            if (!weeklyFactsStillCurrent(job.getJSONObject("facts"))) {
                job.put("state", "superseded").put("error", "记录已变化，请重新生成").remove("lease")
                job.remove("lease_until")
                runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
                appendEvent("WeeklySummarySkipped", null, JSONObject().put("job_id", id).put("reason", "facts_changed"))
                continue
            }
            if (findCard("$id-llm") != null) {
                job.put("state", "superseded").put("error", "已有同 ID 卡片，保留现有内容").remove("lease")
                job.remove("lease_until")
                runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
                appendEvent("WeeklySummarySkipped", null, JSONObject().put("job_id", id).put("reason", "existing_card"))
                continue
            }
            if (forMesh && state == "running" && BufferMeshWeeklySource.owned(writableDatabase, id, job.optString("lease")) != null)
                return@transaction WeeklyLlmJob(id, job.getString("lease"), job.getLong("requested_at"), job.getJSONObject("facts"))
            if ((state == "running" && job.optLong("lease_until") > now) || job.optLong("retry_at") > now) continue
            val lease = UUID.randomUUID().toString()
            job.put("state", "running").put("lease", lease).put("lease_until", now + 180_000)
                .put("attempts", job.optInt("attempts") + 1)
            runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
            return@transaction WeeklyLlmJob(id, lease, job.getLong("requested_at"), job.getJSONObject("facts"))
        }
        null
    }

    @Synchronized
    internal fun completeWeeklyLlm(job: WeeklyLlmJob, result: JSONObject, model: String?, executor: String? = null): Boolean = runtimeSettings.transaction {
        val title = result.opt("title")
        val summary = result.opt("summary")
        require(title is String && title.isNotBlank() && title.length <= 160) { "周总结标题无效" }
        require(summary is String && summary.isNotBlank() && summary.length <= 4000) { "周总结正文无效" }
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        val stored = jobs.optJSONObject(job.id) ?: return@transaction false
        if (stored.optString("state") != "running" || stored.optString("lease") != job.lease || stored.getLong("requested_at") != job.requestedAt ||
            BufferCrdt.canonical(stored.getJSONObject("facts")) != BufferCrdt.canonical(job.facts)) return@transaction false
        if (!weeklyFactsStillCurrent(job.facts)) {
            stored.put("state", "superseded").put("error", "记录已变化，请重新生成").remove("lease")
            stored.remove("lease_until")
            runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
            appendEvent("WeeklySummarySkipped", null, JSONObject().put("job_id", job.id).put("reason", "facts_changed"))
            return@transaction false
        }
        // A distinct card id separates old rule-based weekly cards from LLM output.
        val cardId = "${job.id}-llm"
        if (findCard(cardId) != null) {
            stored.put("state", "superseded").put("error", "已有同 ID 卡片，保留现有内容").remove("lease")
            stored.remove("lease_until")
            runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
            appendEvent("WeeklySummarySkipped", null, JSONObject().put("job_id", job.id).put("reason", "existing_card"))
            return@transaction false
        }
        val card = BufferCard(cardId, null, "weekly_card", title.trim(), summary.trim(), "logged",
            listOf("archive", "later"), job.requestedAt, classificationConfirmed = true, tags = listOf("weekly", "llm"))
        insertCard(writableDatabase, card)
        appendEvent(writableDatabase, "WeeklySummaryGenerated", null, System.currentTimeMillis(), JSONObject()
            .put("job_id", job.id).put("card_id", cardId).put("source", if (executor == null) "llm" else "member_llm")
            .put("model", model ?: JSONObject.NULL)
            .put("period_start", job.facts.getLong("period_start")).put("period_end", job.requestedAt))
        stored.put("state", "complete").put("card_id", cardId).remove("error"); stored.remove("lease"); stored.remove("lease_until")
        runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
        true
    }

    @Synchronized
    internal fun failWeeklyLlm(job: WeeklyLlmJob, now: Long) = runtimeSettings.transaction {
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        val stored = jobs.optJSONObject(job.id) ?: return@transaction
        if (stored.optString("lease") != job.lease || stored.optString("state") != "running") return@transaction
        stored.put("state", "failed").put("error", "生成失败，将自动重试")
            .put("retry_at", now + minOf(3_600_000L, 60_000L * stored.optInt("attempts").coerceAtLeast(1)))
        runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
    }

    private fun weekStartMillis(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        val daysSinceMonday =
            (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        add(Calendar.DATE, -daysSinceMonday)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Synchronized
    fun createTextCapture(text: String, mode: String = this.mode()): BufferCard {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "capture text is empty" }
        require(mode in MODES) { "unknown Buffer mode: $mode" }

        val captureId = newId("cap")
        val createdAt = System.currentTimeMillis()
        val segments = splitTextIntoSegments(cleanText)
        val cards = segments.map { segment ->
            classifyText(captureId, segment, createdAt)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertCapture(
                db = db,
                captureId = captureId,
                sourceDevice = "android",
                createdAt = createdAt,
                mode = mode,
                audioFormat = null,
                durationMs = 0,
                blobPath = null,
                transcript = cleanText,
                classification = cards.joinToString("|") { it.kind },
            )
            appendEvent(db, "CaptureCreated", captureId, createdAt, JSONObject()
                .put("capture_id", captureId)
                .put("source", "android")
                .put("context_local", true)
                .put("mode", mode)
                .put("content_type", "text/plain")
                .put("text", cleanText))
            appendEvent(db, "CaptureTranscribed", captureId, createdAt, JSONObject()
                .put("capture_id", captureId)
                .put("transcript", cleanText))
            if (segments.size > 1) {
                appendEvent(db, "CaptureSegmented", captureId, createdAt, JSONObject()
                    .put("capture_id", captureId)
                    .put("segments", JSONArray(segments)))
            }
            cards.forEachIndexed { index, card ->
                runtimeSettings.edit().putString("task_source:${card.cardId}", segments[index]).apply()
                appendEvent(db, "ClassificationProposed", captureId, createdAt, JSONObject()
                    .put("capture_id", captureId)
                    .put("segment_index", index)
                    .put("segment", segments[index])
                    .put("kind", card.kind)
                    .put("confidence", 0.55))
                insertCard(db, card)
            }
            enqueueClassificationJob(db, captureId, createdAt)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return cards.first()
    }

    private fun readAudioIntent(file: File): JSONObject {
        check(file.length() in 1..16384)
        val value = BufferSourceEvent.parseObject(file.readText(), 16384)
        if (value.has("input_target") || value.has("intent_sha256")) {
            val expected = value.getString("intent_sha256")
            val body = JSONObject(value.toString()).also { it.remove("intent_sha256") }
            check(expected == VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(body))) { "audio intent needs review" }
        }
        return value
    }
    private fun saveAudioIntent(file: File, value: JSONObject) {
        val body = JSONObject(value.toString()).also { it.remove("intent_sha256") }
        value.put("intent_sha256", VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(body)))
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16384); RecoveryFiles.save(file, bytes)
    }

    @Synchronized
    fun createAudioCapture(
        captureId: String,
        sourceDevice: String,
        createdAt: Long,
        mode: String,
        audioFormat: String,
        durationMs: Long,
        audio: ByteArray,
        contextPolicy: JSONObject? = null,
        collectLocalContext: Boolean = false,
        localOriginal: Boolean = false,
        pendingTarget: String? = null,
    ): BufferCard {
        require(captureId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid capture id" }
        require(mode in MODES) { "unknown Buffer mode: $mode" }
        require(pendingTarget == null || (VelaPendingCaptureProtocol.isFabricDeviceId(sourceDevice) && pendingTarget in setOf("buffer", "auto")))
        require(audio.size in 1..BufferConstants.maxAudioBytes) { "invalid audio payload size" }
        require(!localOriginal || sourceDevice == "android" || sourceDevice == deviceId()) { "local audio origin mismatch" }

        val pendingMetadata = File(captureDir, "$captureId.pending.json")
        var successful = false
        var originalAudio: ByteArray? = null
        val db = writableDatabase
        val ownsAudioTransaction = !db.inTransaction()
        db.beginTransaction()
        try {
            val snapshot = audio.copyOf(); originalAudio = snapshot
            val inputHash = BufferSourceBlobs.originalDigest(snapshot)
            if (pendingMetadata.exists()) {
                check(pendingMetadata.length() <= 16384)
                val previous = readAudioIntent(pendingMetadata)
                check(previous.has("record_sha256") == previous.has("record_bytes"))
                if (previous.has("record_sha256")) check(previous.getString("record_sha256") == inputHash && previous.getLong("record_bytes") == snapshot.size.toLong())
            }
            cardForCapture(captureId)?.let {
                db.rawQuery("SELECT source_device,created_at,mode,audio_format,duration_ms FROM captures WHERE capture_id=?", arrayOf(captureId)).use { row ->
                    check(row.moveToFirst() && row.getString(0) == sourceDevice && row.getLong(1) == createdAt && row.getString(2) == mode &&
                        row.getString(3) == audioFormat && row.getLong(4) == durationMs) { "capture id already has different metadata" }
                }
                val witnessId = db.rawQuery("SELECT event_id FROM events WHERE capture_id=? AND type='CaptureCreated' ORDER BY logical_clock,rowid LIMIT 1",
                    arrayOf(captureId)).use { row -> check(row.moveToFirst()); row.getString(0) }
                val witness = BufferEventJournal.localRecord(db, witnessId)
                val pinned = witness.event.getJSONObject("payload").has("blob")
                if (pinned) BufferSourceBlobs.verifyOriginalBytes(witness, snapshot)
                val existing = File(captureDir, "$captureId.bin")
                if (!existing.exists() && pinned) RecoveryFiles.save(existing, snapshot)
                val previous = BufferSourceBlobs.snapshotOriginal(existing, BufferConstants.maxAudioBytes)
                try { check(previous.contentEquals(snapshot)) { "capture id already has different audio" } } finally { previous.fill(0) }
                BufferSourceBlobs.syncCaptureDirectory(captureDir)
                db.setTransactionSuccessful()
                successful = true
                return it
            }
            val destination = File(captureDir, "$captureId.bin")
            if (destination.exists()) {
                // A prior database failure may leave the committed audio behind.
                check(destination.length() == snapshot.size.toLong() && destination.readBytes().contentEquals(snapshot)) {
                    "capture id already has different audio; original file retained"
                }
            }
            val metadata = JSONObject()
                .put("capture_id", captureId).put("source_device", sourceDevice)
                .put("created_at", createdAt).put("mode", mode)
                .put("audio_format", audioFormat).put("duration_ms", durationMs)
                .put("record_sha256", inputHash).put("record_bytes", snapshot.size)
            if (pendingTarget != null) metadata.put("input_target", pendingTarget)
            var savedPolicy = contextPolicy ?: captureContextPolicy()
            var savedLocal = collectLocalContext
            var savedOriginal = localOriginal
            if (pendingMetadata.exists()) {
                check(pendingMetadata.length() <= 16384) { "invalid audio metadata size" }
                val saved = readAudioIntent(pendingMetadata)
                check(metadata.keys().asSequence().all {
                    (it in setOf("record_sha256", "record_bytes", "input_target") && !saved.has(it)) || saved.opt(it)?.toString() == metadata.opt(it)?.toString()
                }) {
                    "capture id already has different metadata; original files retained"
                }
                // Recovery preserves capture-time consent, including an old
                // disabled policy. A later permission grant cannot rewrite it.
                savedPolicy = saved.optJSONObject("context_policy") ?: savedPolicy
                savedLocal = saved.optBoolean("context_local", false)
                savedOriginal = if (saved.has("local_original")) saved.opt("local_original") == true
                    else savedLocal && sourceDevice == "android"
                if (!saved.has("record_sha256")) {
                    savedOriginal = savedOriginal && localOriginal
                    savedLocal = savedLocal && (localOriginal || collectLocalContext)
                }
                if (pendingTarget != null && !saved.has("input_target")) {
                    // A currently accepted input may pin its routing target
                    // into an older retry intent after exact-byte matching.
                    saved.put("input_target", pendingTarget).put("record_sha256", inputHash).put("record_bytes", snapshot.size)
                    saveAudioIntent(pendingMetadata, saved)
                }
            } else {
                metadata.put("context_policy", savedPolicy)
                metadata.put("context_local", savedLocal)
                metadata.put("local_original", savedOriginal)
                saveAudioIntent(pendingMetadata, metadata)
            }
            RecoveryFiles.save(destination, snapshot)
            BufferSourceBlobs.syncCaptureDirectory(captureDir)
            val portableBlob = if (savedOriginal) BufferSourceBlobs.describeOriginal(snapshot, audioFormat, createdAt, durationMs, mode) else null

            val card = BufferCard(
                cardId = if (VelaPendingCaptureProtocol.isFabricDeviceId(sourceDevice))
                    VelaPendingCaptureProtocol.deterministicCardId(captureId, 0) else newId("card"),
                captureId = captureId,
                kind = "spark_card",
                title = "语音捕获",
                summary = "来自 $sourceDevice 的语音，等待手机处理（${durationMs} ms）",
                state = "pending_transcription",
                actions = listOf("later", "archive"),
                createdAt = createdAt,
                classificationConfirmed = false,
            )
            insertCapture(
                db = db,
                captureId = captureId,
                sourceDevice = sourceDevice,
                createdAt = createdAt,
                mode = mode,
                audioFormat = audioFormat,
                durationMs = durationMs,
                blobPath = destination.absolutePath,
                transcript = null,
                classification = null,
            )
            appendEvent(db, "CaptureCreated", captureId, createdAt, JSONObject()
                .put("capture_id", captureId)
                .put("device_id", sourceDevice)
                .put("created_at", createdAt)
                .put("mode", mode)
                .put("audio_format", audioFormat)
                .put("duration_ms", durationMs)
                .put("context_policy", savedPolicy)
                .put("context_local", savedLocal)
                .put("local_original", savedOriginal)
                .apply { if (portableBlob != null) put("blob", portableBlob) }
                .put("blob_ref", destination.name))
            appendEvent(db, "CaptureTranscriptionRequested", captureId, createdAt,
                JSONObject().put("capture_id", captureId))
            insertCard(db, card)
            db.setTransactionSuccessful()
            successful = true
            return card
        } finally {
            // A successful nested transaction is not a durable commit.
            // Retain its intent for the normal idempotent recovery pass.
            try { db.endTransaction() } finally { originalAudio?.fill(0) }
            if (successful && ownsAudioTransaction) pendingMetadata.delete()
        }
    }

    /**
     * Imports an unprocessed Vela capture after the caller authorizes its Fabric
     * peer. The source capture ID is the local key; the input receipt does not
     * acknowledge classification or replica completion, so Vela retains audio.
     */
    @Synchronized
    internal fun importVelaPendingCapture(
        trustedDeviceId: String,
        input: VelaPendingCaptureInput,
    ): String {
        require(VelaPendingCaptureProtocol.isFabricDeviceId(trustedDeviceId))
        VelaPendingCaptureProtocol.validateRecord(input)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val absent = !db.rawQuery("SELECT 1 FROM captures WHERE capture_id=?", arrayOf(input.captureId)).use { it.moveToFirst() }
            val cardId = VelaPendingCaptureProtocol.deterministicCardId(input.captureId, 0)
            if (absent && (BufferSourceProjection.locallyDeleted(db, cardId) || crdtDocument(cardId)?.optBoolean("deleted") == true)) {
                db.setTransactionSuccessful(); return "conflict"
            }
            val adoption = if (absent) null else BufferSourceProjection.releasePendingCapture(db, trustedDeviceId, input)
            val result = importVelaPendingCaptureRows(trustedDeviceId, input)
            if (adoption?.transcript != null && result in setOf("imported", "duplicate")) {
                appendEvent(db, "SourceTranscriptionAccepted", input.captureId, System.currentTimeMillis(), JSONObject()
                    .put("source_device", trustedDeviceId).put("source_event_id", adoption.sourceEvent)
                    .put("source_fingerprint", adoption.fingerprint).put("record_sha256", input.recordSha256))
                completeAudioTranscription(input.captureId, adoption.transcript)
            }
            // A rejected handover restores the old derived row with the rest
            // of this transaction, rather than leaving an identity-shaped gap.
            if (adoption == null || result in setOf("imported", "duplicate")) db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun importVelaPendingCaptureRows(
        trustedDeviceId: String,
        input: VelaPendingCaptureInput,
    ): String {
        require(VelaPendingCaptureProtocol.isFabricDeviceId(trustedDeviceId))
        VelaPendingCaptureProtocol.validateRecord(input)
        require(input.captureId.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(input.mode in MODES && input.createdAt > 0L)
        require(input.durationMs in 0..86_400_000L)

        fun existingCapture(): BufferCapture? =
            findCapture(input.captureId)

        fun markerHash(db: SQLiteDatabase): String? = db.query(
            "events",
            arrayOf("payload"),
            "capture_id = ? AND type = ?",
            arrayOf(input.captureId, "VelaPendingCaptureImported"),
            null,
            null,
            "rowid DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else JSONObject(cursor.getString(0)).optString("record_sha256").takeIf(String::isNotBlank)
        }

        fun canBindRecoveredTarget(): Boolean {
            val db = readableDatabase
            if (input.contentType != "audio/wav" || markerHash(db) != null || hasUserCardActivity(db,input.captureId)) return false
            if (db.rawQuery("""SELECT 1 FROM classification_jobs WHERE capture_id=?
                UNION ALL SELECT 1 FROM cards WHERE capture_id=? AND (state<>'pending_transcription' OR classification_confirmed<>0)
                UNION ALL SELECT 1 FROM events WHERE capture_id=? AND type IN
                    ('CaptureTranscribed','ClassificationProposed','VelaProcessedCaptureImported') LIMIT 1""",
                    arrayOf(input.captureId,input.captureId,input.captureId)).use { it.moveToFirst() }) return false
            val id = db.rawQuery("SELECT event_id FROM events WHERE capture_id=? AND type='CaptureCreated' ORDER BY logical_clock,rowid LIMIT 1",
                arrayOf(input.captureId)).use { if(it.moveToFirst())it.getString(0)else null } ?: return false
            val original = BufferEventJournal.localRecord(db,id)
            val payload = original.event.getJSONObject("payload")
            return original.event.optString("capture_id") == input.captureId && payload.opt("local_original") == false &&
                payload.opt("device_id") == trustedDeviceId && payload.opt("created_at") is Number && payload.getLong("created_at") == input.createdAt &&
                payload.opt("mode") == input.mode && payload.opt("audio_format") == input.contentType &&
                payload.opt("duration_ms") is Number && payload.getLong("duration_ms") == input.durationMs
        }

        fun recordAudioReceipt(): String {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val previousHash = markerHash(db)
                if (previousHash != null && previousHash != input.recordSha256) {
                    db.setTransactionSuccessful()
                    return "conflict"
                }
                val firstCardId = db.query(
                    "cards", arrayOf("card_id"), "capture_id = ?", arrayOf(input.captureId),
                    null, null, "rowid ASC", "1",
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                val stableId = VelaPendingCaptureProtocol.deterministicCardId(input.captureId, 0)
                if (firstCardId != null && firstCardId != stableId) {
                    val collision = db.query(
                        "cards", arrayOf("capture_id"), "card_id = ?", arrayOf(stableId),
                        null, null, null, "1",
                    ).use { cursor -> cursor.moveToFirst() }
                    if (collision || BufferSourceProjection.locallyDeleted(db, stableId) || crdtDocument(stableId)?.optBoolean("deleted") == true) {
                        db.setTransactionSuccessful()
                        return "conflict"
                    }
                    db.update(
                        "cards", ContentValues().apply { put("card_id", stableId) },
                        "card_id = ? AND capture_id = ?", arrayOf(firstCardId, input.captureId),
                    )
                    for (table in listOf("action_receipts", "card_reminders", "card_returns")) db.update(
                        table, ContentValues().apply { put("card_id", stableId) }, "card_id = ?", arrayOf(firstCardId))
                    for (prefix in listOf("task_source:", "task_schedule:", "card_presentation:", "card_conversation:",
                        "bedtime_review:", "bedtime_user_review:", "shared_context:")) {
                        val previous = runtimeSettings.getString(prefix + firstCardId, null) ?: continue
                        check(runtimeSettings.getString(prefix + stableId, null) == null) { "canonical card settings already exist" }
                        val moved = if (prefix == "card_conversation:") JSONArray(previous).also { turns ->
                            for (index in 0 until turns.length()) {
                                val turn = turns.getJSONObject(index)
                                if (turn.optString("card_id") == firstCardId) turn.put("card_id", stableId)
                            }
                        }.toString() else previous
                        runtimeSettings.edit().putString(prefix + stableId, moved).remove(prefix + firstCardId).apply()
                    }
                    // This is an accepted live-input identity migration. Keep
                    // its actual old observation as a tombstone so replay
                    // cannot recreate a second card under the old random ID.
                    appendEvent(db, "CardIdentityMigrated", input.captureId, System.currentTimeMillis(),
                        JSONObject().put("old_card_id", firstCardId).put("new_card_id", stableId))
                    appendEvent(db, "CardDeleted", input.captureId, System.currentTimeMillis(), JSONObject().put("card_id", firstCardId))
                    observeCardSource(db, stableId)
                }
                if (previousHash == null) {
                    appendEvent(db, "VelaPendingCaptureImported", input.captureId,
                        System.currentTimeMillis(), JSONObject()
                            .put("source_device", trustedDeviceId)
                            .put("record_sha256", input.recordSha256)
                            .put("content_type", input.contentType).put("target", input.target))
                }
                db.setTransactionSuccessful()
                return if (previousHash == input.recordSha256) "duplicate" else "imported"
            } finally {
                db.endTransaction()
            }
        }

        val existing = existingCapture()
        if (existing != null) {
            val metadataMatches = existing.sourceDevice == trustedDeviceId &&
                existing.createdAt == input.createdAt && existing.mode == input.mode &&
                existing.durationMs == input.durationMs
            if (!metadataMatches) return "conflict"
            val targetMatches = automaticVelaCapture(readableDatabase, input.captureId) == (input.target == "auto")
            if (!targetMatches && !(input.target == "auto" && canBindRecoveredTarget())) return "conflict"
            if (input.contentType == "audio/wav") {
                val file = existing.blobPath?.let(::File)
                val bytesMatch = existing.audioFormat == input.contentType && file?.isFile == true &&
                    file.length() == input.recordBytes.size.toLong() &&
                    file.readBytes().contentEquals(input.recordBytes)
                if (!bytesMatch) return "conflict"
                return recordAudioReceipt()
            }
            val text = input.recordBytes.toString(Charsets.UTF_8).trim()
            val bytesMatch = existing.audioFormat == null && existing.blobPath == null &&
                existing.transcript == text
            if (!bytesMatch) return "conflict"
            val db = readableDatabase
            val previousHash = markerHash(db)
            return if (previousHash == input.recordSha256) "duplicate" else "conflict"
        }

        if (input.contentType == "audio/wav") {
            val stableCardId = VelaPendingCaptureProtocol.deterministicCardId(input.captureId, 0)
            if (cardForCapture(input.captureId) != null || findCard(stableCardId) != null) return "conflict"
            createAudioCapture(
                captureId = input.captureId,
                sourceDevice = trustedDeviceId,
                createdAt = input.createdAt,
                mode = input.mode,
                audioFormat = input.contentType,
                durationMs = input.durationMs,
                audio = input.recordBytes,
                pendingTarget = input.target,
            )
            return recordAudioReceipt()
        }

        val rawText = input.recordBytes.toString(Charsets.UTF_8)
        val cleanText = rawText.trim()
        require(cleanText.isNotEmpty())
        // A Vela capture stays one pending card until the phone's LLM has
        // produced its final (bounded) segment proposals. Splitting the raw
        // input here would briefly publish hundreds of placeholder cards.
        val segments = listOf(cleanText)
        val cards = segments.mapIndexed { index, segment ->
            val proposal = classifyText(input.captureId, segment, input.createdAt)
            proposal.copy(
                cardId = VelaPendingCaptureProtocol.deterministicCardId(input.captureId, index),
                state = if (input.target == "auto") "pending_transcription" else proposal.state,
                actions = if (input.target == "auto") emptyList() else proposal.actions,
            )
        }
        if (cards.any { findCard(it.cardId) != null }) return "conflict"

        val db = writableDatabase
        db.beginTransaction()
        try {
            if (db.rawQuery(
                    "SELECT 1 FROM captures WHERE capture_id = ?",
                    arrayOf(input.captureId),
                ).use { it.moveToFirst() }
            ) {
                db.setTransactionSuccessful()
                return "conflict"
            }
            insertCapture(
                db, input.captureId, trustedDeviceId, input.createdAt, input.mode,
                null, 0L, null, cleanText, cards.joinToString("|") { it.kind },
            )
            appendEvent(db, "CaptureCreated", input.captureId, input.createdAt, JSONObject()
                .put("capture_id", input.captureId).put("source", trustedDeviceId)
                .put("mode", input.mode).put("content_type", input.contentType)
                .put("record_sha256", input.recordSha256))
            appendEvent(db, "CaptureTranscribed", input.captureId, input.createdAt, JSONObject()
                .put("capture_id", input.captureId).put("transcript", cleanText)
                .put("source_device", trustedDeviceId))
            if (segments.size > 1) {
                appendEvent(db, "CaptureSegmented", input.captureId, input.createdAt, JSONObject()
                    .put("capture_id", input.captureId).put("segments", JSONArray(segments)))
            }
            cards.forEachIndexed { index, card ->
                appendEvent(db, "ClassificationProposed", input.captureId, input.createdAt, JSONObject()
                    .put("capture_id", input.captureId).put("card_id", card.cardId)
                    .put("segment_index", index).put("segment", segments[index])
                    .put("kind", card.kind).put("confidence", 0.55))
                insertCard(db, card)
            }
            enqueueClassificationJob(db, input.captureId, input.createdAt)
            appendEvent(db, "VelaPendingCaptureImported", input.captureId, System.currentTimeMillis(),
                JSONObject().put("source_device", trustedDeviceId)
                    .put("record_sha256", input.recordSha256)
                    .put("content_type", input.contentType).put("target", input.target))
            db.setTransactionSuccessful()
            return "imported"
        } finally {
            db.endTransaction()
        }
    }

    fun pendingAudioCaptures(): List<BufferCapture> = captures().filter { capture ->
        capture.audioFormat != null && capture.transcript == null &&
            !capture.blobPath.isNullOrBlank() && File(capture.blobPath).isFile
    }

    @Synchronized
    fun completeAudioTranscription(captureId: String, transcript: String): BufferCard {
        val cleanTranscript = transcript.trim()
        require(cleanTranscript.isNotEmpty()) { "transcript is empty" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val capture = findCapture(captureId)
                ?: throw IllegalArgumentException("unknown capture: $captureId")
            val existingCard = cardForCapture(captureId)
                ?: throw IllegalArgumentException("capture has no card: $captureId")
            if (!capture.transcript.isNullOrBlank()) {
                // The background transcription worker and the manual fallback can
                // finish at the same time.  The first committed transcript owns
                // the capture; a retry must not create another card set.
                return existingCard
            }
            val velaFabricCapture = VelaPendingCaptureProtocol.isFabricDeviceId(capture.sourceDevice)
            if (velaFabricCapture && automaticVelaCapture(db, captureId) && existingCard.state == "pending_transcription") {
                db.update("captures", ContentValues().apply { put("transcript", cleanTranscript) },
                    "capture_id=?", arrayOf(captureId))
                appendEvent(db, "CaptureTranscribed", captureId, System.currentTimeMillis(),
                    JSONObject().put("capture_id", captureId).put("transcript", cleanTranscript))
                enqueueClassificationJob(db, captureId, System.currentTimeMillis())
                db.setTransactionSuccessful()
                return existingCard
            }
            // Keep remote Vela audio as one pending card until LLM
            // classification decides whether it contains multiple segments.
            // Phone-local recording behavior retains its existing segmentation.
            val segments = if (velaFabricCapture) {
                listOf(cleanTranscript)
            } else {
                splitTextIntoSegments(cleanTranscript)
            }
            val classified = segments.mapIndexed { index, segment ->
                val proposal = classifyText(captureId, segment, capture.createdAt)
                if (velaFabricCapture) proposal.copy(
                    cardId = VelaPendingCaptureProtocol.deterministicCardId(captureId, index),
                ) else proposal
            }
            val preserveUserState = existingCard.state != "pending_transcription"
            val keepClassificationConfirmation = existingCard.classificationConfirmed &&
                existingCard.kind == classified.first().kind
            val resultingState = if (preserveUserState) {
                existingCard.state
            } else {
                classified.first().state
            }
            val resultingActions = if (preserveUserState) {
                if (keepClassificationConfirmation) {
                    existingCard.actions
                } else {
                    (existingCard.actions + "accept_classification").distinct()
                }
            } else {
                classified.first().actions
            }
            db.update(
                "captures",
                ContentValues().apply {
                    put("transcript", cleanTranscript)
                    put("classification", classified.joinToString("|") { it.kind })
                },
                "capture_id = ?",
                arrayOf(captureId),
            )
            appendEvent(
                db,
                "CaptureTranscribed",
                captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("capture_id", captureId)
                    .put("transcript", cleanTranscript),
            )
            if (segments.size > 1) {
                appendEvent(db, "CaptureSegmented", captureId, System.currentTimeMillis(), JSONObject()
                    .put("capture_id", captureId)
                    .put("segments", JSONArray(segments)))
            }
            classified.forEachIndexed { index, card ->
                appendEvent(
                    db,
                    "ClassificationProposed",
                    captureId,
                    System.currentTimeMillis(),
                    JSONObject()
                        .put("capture_id", captureId)
                        .put("segment_index", index)
                        .put("segment", segments[index])
                        .put("kind", card.kind)
                        .put("confidence", 0.55),
                )
            }
            db.update(
                "cards",
                ContentValues().apply {
                    put("kind", classified.first().kind)
                    put("title", classified.first().title)
                    put("summary", classified.first().summary)
                    put("state", resultingState)
                    put("actions", JSONArray(resultingActions).toString())
                    put("health_category", classified.first().healthCategory)
                    put("classification_confirmed", if (keepClassificationConfirmation) 1 else 0)
                },
                "card_id = ?",
                arrayOf(existingCard.cardId),
            )
            classified.drop(1).forEach { card -> insertCard(db, card) }
            enqueueClassificationJob(db, captureId, System.currentTimeMillis())
            observeCaptureDocuments(db, captureId)
            db.setTransactionSuccessful()
            return findCard(existingCard.cardId)
                ?: throw IllegalStateException("transcribed card disappeared")
        } finally {
            db.endTransaction()
        }
    }

    private fun splitTextIntoSegments(text: String): List<String> {
        val sentenceParts = text
            .split(Regex("(?<=[。！？!?；;\\n])\\s*"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toMutableList()
        if (sentenceParts.isEmpty()) return listOf(text)

        val result = mutableListOf<String>()
        sentenceParts.forEach { sentence ->
            val marker = MIXED_INPUT_MARKERS
                .asSequence()
                .mapNotNull { marker -> sentence.indexOf(marker).takeIf { it > 3 } }
                .minOrNull()
            if (marker == null) {
                result += sentence
            } else {
                result += sentence.substring(0, marker).trim().trimEnd(',', '，')
                result += sentence.substring(marker).trim()
            }
        }
        return result.filter(String::isNotEmpty).ifEmpty { listOf(text) }
    }

    @Synchronized
    fun preserveAudioForRetry(
        captureId: String,
        sourceDevice: String,
        createdAt: Long,
        mode: String,
        audioFormat: String,
        durationMs: Long,
        sourceFile: File,
        contextPolicy: JSONObject? = null,
        collectLocalContext: Boolean = false,
        localOriginal: Boolean = false,
    ) {
        require(captureId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid capture id" }
        require(mode in MODES) { "unknown Buffer mode: $mode" }
        require(!localOriginal || sourceDevice == "android" || sourceDevice == deviceId()) { "local audio origin mismatch" }
        require(sourceFile.isFile) { "audio source file is missing" }
        require(sourceFile.length() <= BufferConstants.maxAudioBytes) {
            "audio payload is too large"
        }

        val audio = BufferSourceBlobs.snapshotOriginal(sourceFile, BufferConstants.maxAudioBytes)
        try {
        val details = JSONObject()
            .put("capture_id", captureId).put("source_device", sourceDevice)
            .put("created_at", createdAt).put("mode", mode)
            .put("audio_format", audioFormat).put("duration_ms", durationMs)
            .put("context_policy", contextPolicy ?: captureContextPolicy())
            .put("context_local", collectLocalContext)
            .put("local_original", localOriginal)
            .put("record_sha256", BufferSourceBlobs.originalDigest(audio)).put("record_bytes", audio.size)
        val metadataFile = File(captureDir, "$captureId$RETRY_METADATA_SUFFIX")
        val metadata = if (metadataFile.exists()) {
            check(metadataFile.length() <= 16384)
            val bytes = metadataFile.readBytes(); val saved = JSONObject(bytes.toString(Charsets.UTF_8))
            check(listOf("capture_id", "source_device", "created_at", "mode", "audio_format", "duration_ms").all {
                saved.opt(it)?.toString() == details.opt(it)?.toString()
            })
            check(saved.has("record_sha256") == saved.has("record_bytes"))
            if (saved.has("record_sha256")) check(saved.getString("record_sha256") == details.getString("record_sha256") &&
                saved.getLong("record_bytes") == audio.size.toLong())
            bytes
        } else details.toString().toByteArray(Charsets.UTF_8)
        require(metadata.size <= 16384) { "audio metadata is too large" }
        // Metadata goes first so a completed backup is always discoverable.
        // Leave any committed component intact when the next write fails.
        RecoveryFiles.save(metadataFile, metadata)
        RecoveryFiles.save(File(captureDir, "$captureId$RETRY_AUDIO_SUFFIX"), audio)
        BufferSourceBlobs.syncCaptureDirectory(captureDir)
        } finally { audio.fill(0) }
    }

    @Synchronized
    fun recoverPendingAudio(): Int {
        check(!writableDatabase.inTransaction())
        var recovered = 0
        val retryFiles = captureDir.listFiles { file ->
            file.name.endsWith(RETRY_AUDIO_SUFFIX) || file.name.endsWith(".bin")
        }
            ?: return 0
        for (retryFile in retryFiles) {
            val legacyRetry = retryFile.name.endsWith(RETRY_AUDIO_SUFFIX)
            val captureId = retryFile.name.removeSuffix(if (legacyRetry) RETRY_AUDIO_SUFFIX else ".bin")
            val metadataFile = File(captureDir, captureId + if (legacyRetry) RETRY_METADATA_SUFFIX else ".pending.json")
            try {
                if (!metadataFile.isFile) continue
                check(metadataFile.length() <= 16384) { "invalid recovery metadata size" }
                check(retryFile.length() <= BufferConstants.maxAudioBytes) { "audio payload is too large" }
                val metadata = readAudioIntent(metadataFile)
                check(metadata.getString("capture_id") == captureId) { "recovery capture id mismatch" }
                val original = BufferSourceBlobs.snapshotOriginal(retryFile, BufferConstants.maxAudioBytes)
                try {
                check(metadata.has("record_sha256") == metadata.has("record_bytes"))
                if (metadata.has("record_sha256")) check(metadata.getString("record_sha256") == BufferSourceBlobs.originalDigest(original) &&
                    metadata.getLong("record_bytes") == original.size.toLong())
                if (metadata.has("input_target")) {
                    // Finish the accepted input storage/receipt together.
                    // Worker ownership is acquired afresh through the normal
                    // coordinator; this intent restores no active grant.
                    check(metadata.has("record_sha256"))
                    val input = VelaPendingCaptureInput(captureId, metadata.getString("mode"), metadata.getLong("created_at"),
                        metadata.getLong("duration_ms"), metadata.getString("audio_format"), metadata.getString("record_sha256"),
                        original, metadata.getString("input_target"))
                    check(importVelaPendingCapture(metadata.getString("source_device"), input) in setOf("imported", "duplicate"))
                } else createAudioCapture(
                    captureId = metadata.getString("capture_id"),
                    sourceDevice = metadata.getString("source_device"),
                    createdAt = metadata.getLong("created_at"),
                    mode = metadata.getString("mode"),
                    audioFormat = metadata.getString("audio_format"),
                    durationMs = metadata.getLong("duration_ms"),
                    audio = original,
                    contextPolicy = metadata.optJSONObject("context_policy"),
                    collectLocalContext = metadata.optBoolean("context_local", false),
                    localOriginal = if (metadata.has("local_original")) metadata.opt("local_original") == true
                        else metadata.opt("context_local") == true && metadata.optString("source_device") == "android",
                )
                } finally { original.fill(0) }
                if (legacyRetry) retryFile.delete()
                metadataFile.delete()
                recovered++
            } catch (_: Exception) {
                // Keep both files for the next process start.  The original
                // capture remains available until the database accepts it.
            }
        }
        return recovered
    }

    @Synchronized
    fun pendingClassificationJobs(
        limit: Int = 4,
        now: Long = System.currentTimeMillis(),
        eligible: ((BufferClassificationJob) -> Boolean)? = null,
    ): List<BufferClassificationJob> {
        val result = mutableListOf<BufferClassificationJob>()
        val staleBefore = now - CLASSIFICATION_LEASE_MS
        val retryBefore = now - CLASSIFICATION_RETRY_DELAY_MS
        readableDatabase.query(
            "captures INNER JOIN classification_jobs ON captures.capture_id = classification_jobs.capture_id",
            arrayOf("captures.capture_id", "captures.transcript"),
            "captures.transcript IS NOT NULL AND captures.transcript <> '' AND " +
                "((classification_jobs.state = ? AND " +
                "(classification_jobs.attempts = 0 OR classification_jobs.updated_at < ?)) OR " +
                "(classification_jobs.state = ? AND classification_jobs.updated_at < ?))",
            arrayOf("pending", retryBefore.toString(), "processing", staleBefore.toString()),
            null,
            null,
            "captures.created_at ASC",
            if (eligible == null) limit.coerceIn(1, 20).toString() else null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val job = BufferClassificationJob(
                    captureId = cursor.getString(0),
                    transcript = cursor.getString(1),
                )
                if (eligible == null || eligible(job)) result += job
                if (result.size >= limit.coerceIn(1, 20)) break
            }
        }
        return result
    }

    @Synchronized
    internal fun claimClassificationRequest(captureId: String, now: Long = System.currentTimeMillis(), expectedTranscript: String? = null): String? =
        runtimeSettings.transaction {
            if (expectedTranscript != null && findCapture(captureId)?.transcript != expectedTranscript) return@transaction null
            val lease = newId("classification")
            val changed = writableDatabase.update("classification_jobs", ContentValues().apply {
                put("state", "processing"); put("updated_at", now); put("lease_id", lease)
            }, "capture_id = ? AND ((state = 'pending' AND (attempts = 0 OR updated_at < ?)) " +
                "OR (state = 'processing' AND updated_at < ?))",
                arrayOf(captureId, (now - CLASSIFICATION_RETRY_DELAY_MS).toString(),
                    (now - CLASSIFICATION_LEASE_MS).toString()))
            if (changed == 0) return@transaction null
            appendEvent("ClassificationRequested", captureId,
                JSONObject().put("capture_id", captureId).put("lease_id", lease))
            lease
        }

    /** Observable operations only: never persist model reasoning or tool record contents. */
    @Synchronized
    internal fun recordClassificationTool(captureId: String, lease: String, tool: String, success: Boolean, count: Int) {
        if (!ownsClassificationLease(writableDatabase, captureId, lease)) return
        appendEvent("ClassificationToolExecuted", captureId, JSONObject()
            .put("lease_id", lease).put("tool", tool).put("success", success).put("count", count))
    }

    @Synchronized
    fun classificationProcessing(captureId: String): com.mocharealm.foundation.fabric.buffer.domain.model.BufferProcessing {
        val db = readableDatabase
        var state = "none"
        var attempts = 0
        db.rawQuery("SELECT state, attempts FROM classification_jobs WHERE capture_id = ?", arrayOf(captureId)).use {
            if (it.moveToFirst()) { state = it.getString(0); attempts = it.getInt(1) }
        }
        val original = db.rawQuery("SELECT transcript FROM captures WHERE capture_id = ?", arrayOf(captureId)).use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }
        val steps = mutableListOf<com.mocharealm.foundation.fabric.buffer.domain.model.BufferProcessingStep>()
        db.rawQuery("SELECT type, created_at, payload FROM events WHERE capture_id = ? AND type IN " +
            "('ClassificationRequested','ClassificationToolExecuted','ClassificationProposed','ClassificationFailed','ClassificationSkipped') " +
            "ORDER BY rowid DESC LIMIT 64", arrayOf(captureId)).use {
            while (it.moveToNext()) {
                val type = it.getString(0)
                val payload = JSONObject(it.getString(2))
                if (type == "ClassificationProposed" && payload.optString("source") !in setOf("phone_llm", "vela_llm")) continue
                steps += com.mocharealm.foundation.fabric.buffer.domain.model.BufferProcessingStep(type, it.getLong(1),
                    payload.optString("tool"), payload.optBoolean("success", true), payload.optInt("count"))
                if (type == "ClassificationRequested") break
            }
        }
        return com.mocharealm.foundation.fabric.buffer.domain.model.BufferProcessing(state, attempts, original, steps.reversed())
    }

    private fun ownsClassificationLease(db: SQLiteDatabase, captureId: String, lease: String): Boolean =
        db.rawQuery("SELECT 1 FROM classification_jobs WHERE capture_id = ? AND state = 'processing' AND lease_id = ?",
            arrayOf(captureId, lease)).use { it.moveToFirst() }

    @Synchronized
    internal fun classificationRequestCurrent(captureId: String, lease: String, transcript: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM classification_jobs j JOIN captures c ON c.capture_id=j.capture_id " +
            "WHERE j.capture_id=? AND j.state='processing' AND j.lease_id=? AND c.transcript=?",
            arrayOf(captureId, lease, transcript)).use { it.moveToFirst() }

    @Synchronized
    internal fun markClassificationRequestFailed(captureId: String, lease: String, detail: String) =
        runtimeSettings.transaction {
            val db = writableDatabase
            if (!ownsClassificationLease(db, captureId, lease)) return@transaction
            db.execSQL(
                "UPDATE classification_jobs SET state = ?, attempts = attempts + 1, updated_at = ?, lease_id = NULL WHERE capture_id = ?",
                arrayOf<Any>("pending", System.currentTimeMillis(), captureId),
            )
            appendEvent("ClassificationFailed", captureId,
                JSONObject().put("capture_id", captureId).put("lease_id", lease).put("detail", detail))
        }

    /** Return only this request's processing lease to pending without counting a model failure. */
    @Synchronized
    internal fun releaseClassificationRequest(
        captureId: String,
        lease: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = runtimeSettings.transaction {
        require(now >= 0L)
        val retryEligibleAt = now - CLASSIFICATION_RETRY_DELAY_MS - 1L
        writableDatabase.update(
            "classification_jobs",
            ContentValues().apply {
                put("state", "pending")
                put("updated_at", retryEligibleAt)
                putNull("lease_id")
            },
            "capture_id = ? AND state = 'processing' AND lease_id = ?",
            arrayOf(captureId, lease),
        ) > 0
    }

    /** Retry identity for the same device configuration, without storing keys. */
    internal fun velaConfigurationRequest(deviceId: String, kind: String, fingerprint: String): Pair<String, Long> =
        runtimeSettings.transaction {
            require(deviceId.matches(Regex("[0-9a-f]{64}")) && kind in setOf("network", "agent", "skip"))
            require(fingerprint.matches(Regex("[0-9a-f]{64}")))
            val category = if (kind == "network") "network" else "agent"
            val key = "vela_request:$deviceId:$category:"
            val previous = runtimeSettings.getString(key + "fingerprint", null)
            val revision = runtimeSettings.getLong(key + "revision", 0)
            val request = runtimeSettings.getString(key + "id", null)
            if (previous == fingerprint && request != null && revision > 0) request to revision
            else {
                val next = System.currentTimeMillis().coerceAtLeast(revision + 1)
                val id = UUID.randomUUID().toString().replace("-", "")
                runtimeSettings.edit().putString(key + "fingerprint", fingerprint).putString(key + "id", id)
                    .putLong(key + "revision", next).apply()
                id to next
            }
        }

    /** Discovery routing hint only. Hub identity/trust is always revalidated. */
    @Synchronized
    internal fun saveVelaFabricRoute(address: String, deviceId: String): Unit = runtimeSettings.transaction {
        require(address.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) && deviceId.matches(Regex("[0-9a-f]{64}")))
        val canonical=address.uppercase(java.util.Locale.ROOT)
        val routes=JSONObject()
        // Called only after pairing commits or the authenticated session is
        // active. Retire the old routing hint for this address at that point,
        // retaining the old device's user records under its original identity.
        velaFabricRoutes().filterNot { it.first.equals(canonical,true) && it.second!=deviceId }
            .forEach { routes.put(it.second,it.first) }
        check(routes.has(deviceId)||routes.length()<64) { "device route capacity exceeded" }
        routes.put(deviceId,canonical)
        runtimeSettings.edit().putString("vela_fabric_routes",routes.toString())
            .putString("vela_fabric_address", canonical).putString("vela_fabric_device", deviceId).apply()
        Unit
    }
    @Synchronized
    internal fun velaFabricRoutes(): List<Pair<String,String>> {
        val saved=runtimeSettings.getString("vela_fabric_routes",null)
        val routes=if(saved==null)JSONObject() else JSONObject(saved)
        check(routes.length()<=64)
        val legacy=velaFabricRoute()
        if(legacy!=null&&!routes.has(legacy.second)){check(routes.length()<64);routes.put(legacy.second,legacy.first)}
        return routes.keys().asSequence().sorted().map { device ->
            val address=routes.get(device)
            check(device.matches(Regex("[0-9a-f]{64}")) && address is String && address.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")))
            address.uppercase(java.util.Locale.ROOT) to device
        }.toList()
    }
    @Synchronized
    internal fun velaFabricRoute(): Pair<String, String>? {
        val address = runtimeSettings.getString("vela_fabric_address", null) ?: return null
        val device = runtimeSettings.getString("vela_fabric_device", null) ?: return null
        check(address.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) && device.matches(Regex("[0-9a-f]{64}")))
        return address.uppercase(java.util.Locale.ROOT) to device
    }

    /** Import an immutable device result after Fabric peer/channel authorization.
     * Receipt, capture, proposals and presentation commit together. Replays never
     * recreate user-deleted cards or reset locally edited content, even after restart.
     */
    @Synchronized
    internal fun importVelaProcessedCapture(trustedDeviceId: String, value: VelaProcessedCapture): String {
        require(trustedDeviceId.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        val receiptKey = "vela_processed:$trustedDeviceId:${value.captureId}"
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = runtimeSettings.getString(receiptKey, null)
            if (previous != null) {
                db.setTransactionSuccessful()
                return if (previous == value.fingerprint) "duplicate" else "conflict"
            }
            // A source snapshot may already have rebuilt this view without
            // creating replacement local events. Exact retained source bytes,
            // not capture/card existence alone, prove compatibility delivery.
            if (BufferSourceProjection.represented(db, trustedDeviceId, value.captureId, value.fingerprint)) {
                runtimeSettings.edit().putString(receiptKey, value.fingerprint).apply()
                db.setTransactionSuccessful(); return "duplicate"
            }
            value.focusSession?.let { session ->
                val previousSession = runtimeSettings.getString("focus_session:${session.id}", null)?.let { FocusSession.parse(JSONObject(it)) }
                if (previousSession == null || session.updatedAt > previousSession.updatedAt) {
                    runtimeSettings.edit().putString("focus_session:${session.id}", session.json().toString()).apply()
                    val current = focusSession()
                    // Historical processed captures do not change the current mode.
                    if (current?.id == session.id && session.updatedAt >= current.updatedAt)
                        runtimeSettings.edit().putString("focus_session_current", session.json().toString()).apply()
                } else if (session.updatedAt == previousSession.updatedAt && session != previousSession) {
                    db.setTransactionSuccessful(); return "conflict"
                }
                runtimeSettings.edit().putString(receiptKey, value.fingerprint).apply()
                appendEvent(db, "FocusSessionImported", null, System.currentTimeMillis(), session.json().put("source", trustedDeviceId))
                db.setTransactionSuccessful()
                return "imported"
            }
            value.followup?.let { encoded ->
                val turn = JSONObject(encoded)
                val cardId = turn.getString("card_id")
                val card = findCard(cardId)
                if (card == null || card.state == "deleted") {
                    db.setTransactionSuccessful()
                    return "conflict"
                }
                val previousTurns = JSONArray(cardConversation(cardId))
                val turns = (0 until previousTurns.length()).map { previousTurns.getJSONObject(it) }
                    .filterNot { it.getString("capture_id") == value.captureId }.plus(turn)
                    .sortedWith(compareBy<JSONObject> { it.getLong("created_at") }.thenBy { it.getString("capture_id") })
                    .takeLast(8)
                runtimeSettings.edit().putString("card_conversation:$cardId", JSONArray(turns).toString())
                    .putString(receiptKey, value.fingerprint).apply()
                appendEvent(db, "CardFollowupAdded", card.captureId, System.currentTimeMillis(), JSONObject(turn.toString())
                    .put("source", trustedDeviceId))
                db.setTransactionSuccessful()
                return "imported"
            }
            val exists = db.rawQuery("SELECT 1 FROM captures WHERE capture_id = ?", arrayOf(value.captureId))
                .use { it.moveToFirst() }
            if (value.segments.any { segment -> db.rawQuery("SELECT 1 FROM cards WHERE card_id=?", arrayOf(segment.cardId)).use { it.moveToFirst() } } ||
                (exists && !BufferSourceProjection.completeLegacyCapture(db, trustedDeviceId, value))) {
                db.setTransactionSuccessful()
                return "conflict"
            }
            if (!exists) insertCapture(db, value.captureId, trustedDeviceId, value.createdAt, value.mode,
                null, 0, null, value.transcript, value.segments.joinToString("|") { it.proposal.kind })
            appendEvent(db, "CaptureCreated", value.captureId, value.createdAt, JSONObject()
                .put("capture_id", value.captureId).put("source", trustedDeviceId).put("mode", value.mode)
                .put("content_type", "text/plain").put("processed_on_device", true))
            appendEvent(db, "CaptureTranscribed", value.captureId, value.createdAt, JSONObject()
                .put("capture_id", value.captureId).put("transcript", value.transcript).put("source", trustedDeviceId))
            value.toolReceipts?.let { encoded ->
                val receipts = JSONArray(encoded)
                for (index in 0 until receipts.length()) {
                    val receipt = receipts.getJSONObject(index)
                    appendEvent(db, "ClassificationToolExecuted", value.captureId, value.createdAt, JSONObject()
                        .put("tool", receipt.getString("tool")).put("success", receipt.getBoolean("success"))
                        .put("count", receipt.getInt("count")).put("source", trustedDeviceId))
                }
            }
            for (segment in value.segments) {
                val proposal = segment.proposal
                runtimeSettings.edit().putString("task_source:${segment.cardId}", proposal.text).apply()
                val state = when (segment.state) {
                    "active" -> ClassificationReview.initialState(proposal.kind)
                    "reminder_pending" -> "reminder_requested"
                    "research_requested" -> "rabbit_hole_candidate"
                    else -> segment.state
                }
                val permanentlyDeleted = BufferSourceProjection.locallyDeleted(db, segment.cardId) || crdtDocument(segment.cardId)?.optBoolean("deleted") == true
                if (segment.state != "deleted" && !permanentlyDeleted) insertCard(db, BufferCard(segment.cardId, value.captureId, proposal.kind, proposal.title,
                    proposal.summary, state, ClassificationReview.proposalActions(proposal.kind, proposal.healthCategory),
                    value.createdAt, healthCategory = proposal.healthCategory, relatedCardIds = proposal.relatedCardIds))
                appendEvent(db, "ClassificationProposed", value.captureId, value.createdAt, JSONObject()
                    .put("card_id", segment.cardId).put("segment", proposal.text).put("kind", proposal.kind)
                    .put("confidence", proposal.confidence).put("source", "vela_llm").put("source_device", trustedDeviceId)
                    .put("related_card_ids", JSONArray(proposal.relatedCardIds))
                    .put("presentation", proposal.presentation ?: JSONObject.NULL)
                    .put("bedtime", proposal.bedtime?.let(::JSONObject) ?: JSONObject.NULL))
                val projectedState = velaCardProjection(segment.state, proposal.kind)
                db.insertOrThrow("vela_card_state_receipts", null, ContentValues().apply {
                    put("trusted_device_id", trustedDeviceId)
                    put("capture_id", value.captureId)
                    put("card_id", segment.cardId)
                    put("revision", 0L)
                    put("action_id", "baseline")
                    put("fingerprint", value.fingerprint)
                    put("previous_state", "")
                    put("state", segment.state)
                    put("projected_state", projectedState)
                    put("deleted", if (segment.state == "deleted") 1 else 0)
                    put("review_at", 0L)
                    put("outcome", "baseline")
                })
                proposal.presentation?.let {
                    runtimeSettings.edit().putString("card_presentation:${segment.cardId}", it).apply()
                }
                saveBedtimeAssessment(segment.cardId, proposal.bedtime)
            }
            db.insertOrThrow("classification_jobs", null, ContentValues().apply {
                put("capture_id", value.captureId); put("state", "complete"); put("attempts", 0)
                put("updated_at", System.currentTimeMillis())
            })
            appendEvent(db, "VelaProcessedCaptureImported", value.captureId, System.currentTimeMillis(), JSONObject()
                .put("source_device", trustedDeviceId).put("record_sha256", value.fingerprint)
                .put("request_id", value.requestId))
            runtimeSettings.edit().putString(receiptKey, value.fingerprint).apply()
            observeCaptureDocuments(db, value.captureId)
            db.setTransactionSuccessful()
            return "imported"
        } finally { db.endTransaction() }
    }

    /** Apply a trusted Vela card state event and its durable idempotency receipt
     * in one SQLite transaction. Conflicts preserve the phone's current row. */
    @Synchronized
    internal fun applyVelaCardState(
        trustedDeviceId: String,
        change: com.mocharealm.foundation.fabric.buffer.data.device.VelaCardStateChange,
    ): String {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val captureSource = db.rawQuery("SELECT source_device FROM captures WHERE capture_id = ?",
                arrayOf(change.captureId)).use { if (it.moveToFirst()) it.getString(0) else null }
            val existing = readVelaCardStateReceipt(db, trustedDeviceId, change)
            if (existing != null) {
                db.setTransactionSuccessful()
                return if (existing.first == change.fingerprint) {
                    if (existing.second == "applied") "duplicate" else existing.second
                } else "conflict"
            }
            if (hasVelaCardStateRevision(db, trustedDeviceId, change)) {
                insertVelaCardStateReceipt(db, trustedDeviceId, change, "conflict", "revision_conflict")
                db.setTransactionSuccessful()
                return "conflict"
            }

            val outcome: String
            val card = findCard(change.cardId)
            val captureMatches = captureSource == trustedDeviceId && card?.captureId == change.captureId
            val latest = readLatestVelaCardState(db, trustedDeviceId, change.captureId, change.cardId)
            val oldProjection: String?
            val oldRemoteState: String?
            val oldRevision: Long
            if (latest == null) {
                oldProjection = card?.let { velaCardProjection(change.previousState, it.kind) }
                oldRemoteState = change.previousState
                oldRevision = 0L
            } else {
                oldProjection = latest.projectedState
                oldRemoteState = latest.state
                oldRevision = latest.revision
            }
            val canApply = captureMatches && oldRemoteState == change.previousState &&
                card.state == oldProjection &&
                (oldRemoteState != "deleted" || change.state == "deleted") &&
                change.revision > oldRevision
            if (canApply) {
                val projected = velaCardProjection(change.state, card.kind)
                applyVelaCardProjection(db, card, change, projected)
                appendEvent(db, "VelaCardStateChanged", change.captureId, System.currentTimeMillis(),
                    JSONObject().put("source_device", trustedDeviceId).put("capture_id", change.captureId)
                        .put("card_id", change.cardId).put("revision", change.revision)
                        .put("action_id", change.actionId).put("previous_state", change.previousState)
                        .put("state", change.state).put("deleted", change.deleted)
                        .put("review_at", change.reviewAt))
                outcome = "applied"
                insertVelaCardStateReceipt(db, trustedDeviceId, change, outcome, projected)
            } else {
                outcome = when {
                    !captureMatches -> "conflict"
                    latest != null && change.revision < latest.revision -> "stale"
                    else -> "conflict"
                }
                insertVelaCardStateReceipt(db, trustedDeviceId, change, outcome,
                    card?.state ?: "missing")
            }
            db.setTransactionSuccessful()
            return outcome
        } finally { db.endTransaction() }
    }

    private data class VelaCardStateReceipt(
        val revision: Long,
        val state: String,
        val projectedState: String,
    )

    private fun readVelaCardStateReceipt(
        db: SQLiteDatabase,
        trustedDeviceId: String,
        change: com.mocharealm.foundation.fabric.buffer.data.device.VelaCardStateChange,
    ): Pair<String, String>? = db.query("vela_card_state_receipts", arrayOf("fingerprint", "outcome"),
        "trusted_device_id = ? AND capture_id = ? AND card_id = ? AND revision = ? AND action_id = ?",
        arrayOf(trustedDeviceId, change.captureId, change.cardId, change.revision.toString(), change.actionId),
        null, null, null).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }

    private fun readLatestVelaCardState(
        db: SQLiteDatabase,
        trustedDeviceId: String,
        captureId: String,
        cardId: String,
    ): VelaCardStateReceipt? = db.query("vela_card_state_receipts",
        arrayOf("revision", "state", "projected_state"),
        "trusted_device_id = ? AND capture_id = ? AND card_id = ? AND outcome IN ('baseline', 'applied')",
        arrayOf(trustedDeviceId, captureId, cardId), null, null, "revision DESC", "1").use {
        if (it.moveToFirst()) VelaCardStateReceipt(it.getLong(0), it.getString(1), it.getString(2)) else null
    }

    private fun hasVelaCardStateRevision(
        db: SQLiteDatabase,
        trustedDeviceId: String,
        change: com.mocharealm.foundation.fabric.buffer.data.device.VelaCardStateChange,
    ): Boolean = db.rawQuery("SELECT 1 FROM vela_card_state_receipts WHERE trusted_device_id = ? AND " +
        "capture_id = ? AND card_id = ? AND revision = ? LIMIT 1",
        arrayOf(trustedDeviceId, change.captureId, change.cardId, change.revision.toString())).use { it.moveToFirst() }

    private fun insertVelaCardStateReceipt(
        db: SQLiteDatabase,
        trustedDeviceId: String,
        change: com.mocharealm.foundation.fabric.buffer.data.device.VelaCardStateChange,
        outcome: String,
        projectedState: String,
    ) {
        db.insertOrThrow("vela_card_state_receipts", null, ContentValues().apply {
            put("trusted_device_id", trustedDeviceId); put("capture_id", change.captureId)
            put("card_id", change.cardId); put("revision", change.revision)
            put("action_id", change.actionId); put("fingerprint", change.fingerprint)
            put("previous_state", change.previousState); put("state", change.state)
            put("projected_state", projectedState); put("deleted", if (change.deleted) 1 else 0)
            put("review_at", change.reviewAt); put("outcome", outcome)
        })
    }

    private fun velaCardProjection(state: String, kind: String): String = when (state) {
        "active" -> ClassificationReview.initialState(kind)
        "reminder_pending" -> "reminder_requested"
        "research_requested" -> "rabbit_hole_candidate"
        else -> state
    }

    private fun applyVelaCardProjection(
        db: SQLiteDatabase,
        old: BufferCard,
        change: com.mocharealm.foundation.fabric.buffer.data.device.VelaCardStateChange,
        projected: String,
    ) {
        val oldReturnState = db.rawQuery("SELECT return_state FROM card_returns WHERE card_id = ?",
            arrayOf(change.cardId)).use { if (it.moveToFirst()) it.getString(0) else null }
        val returnState = if (old.state == "later") oldReturnState ?: "inbox" else old.state
        db.update("cards", ContentValues().apply {
            put("state", projected)
            if (change.deleted) put("actions", "[]")
        }, "card_id = ?", arrayOf(change.cardId)).also { check(it == 1) }
        if (projected == "later") {
            db.insertWithOnConflict("card_returns", null, ContentValues().apply {
                put("card_id", change.cardId); put("due_at", change.reviewAt)
                put("return_state", returnState.takeUnless { it == "later" || it == "deleted" } ?: "inbox")
                put("returned_at", 0L)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            db.execSQL("UPDATE card_reminders SET cancelled = 1 WHERE card_id = ?", arrayOf(change.cardId))
        } else {
            db.delete("card_returns", "card_id = ?", arrayOf(change.cardId))
            if (change.state == "reminder_pending") {
                val dueAt = System.currentTimeMillis() + 3_600_000L
                db.insertWithOnConflict("card_reminders", null, ContentValues().apply {
                    put("card_id", change.cardId); put("due_at", dueAt); put("revision", newId("reminder"))
                    put("delivered", 0); put("cancelled", 0); put("retry_at", 0L)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            } else {
                db.execSQL("UPDATE card_reminders SET cancelled = 1 WHERE card_id = ?", arrayOf(change.cardId))
            }
        }
    }

    private fun automaticVelaCapture(db: SQLiteDatabase, captureId: String): Boolean =
        db.rawQuery("SELECT payload FROM events WHERE capture_id=? AND type='VelaPendingCaptureImported' ORDER BY rowid DESC LIMIT 1",
            arrayOf(captureId)).use { it.moveToFirst() && JSONObject(it.getString(0)).optString("target", "buffer") == "auto" }

    /** Only imported Vela input can be delegated; no phone-local task acquires a device endpoint. */
    @Synchronized
    internal fun velaAssistantInput(captureId: String, lease: String): com.mocharealm.foundation.fabric.buffer.data.device.VelaAssistantInput? {
        val db = readableDatabase
        if (!ownsClassificationLease(db, captureId, lease) ||
            (hasUserCardActivity(db, captureId) && !assistantHandoffSelected(captureId, lease))) return null
        val capture = findCapture(captureId) ?: return null
        if (!VelaPendingCaptureProtocol.isFabricDeviceId(capture.sourceDevice) || capture.transcript.isNullOrBlank()) return null
        val hash = db.rawQuery("SELECT payload FROM events WHERE capture_id=? AND type='VelaPendingCaptureImported' ORDER BY rowid DESC LIMIT 1",
            arrayOf(captureId)).use { rows ->
            if (!rows.moveToFirst()) null else JSONObject(rows.getString(0)).let {
                if (it.optString("source_device") == capture.sourceDevice && it.optString("target", "buffer") == "auto")
                    it.optString("record_sha256") else null
            }
        } ?: return null
        if (!hash.matches(Regex("[0-9a-f]{64}"))) return null
        return com.mocharealm.foundation.fabric.buffer.data.device.VelaAssistantInput(
            capture.sourceDevice, captureId, hash, capture.transcript)
    }

    @Synchronized
    internal fun assistantHandoffSelected(captureId: String, lease: String): Boolean =
        ownsClassificationLease(readableDatabase, captureId, lease) && readableDatabase.rawQuery(
            "SELECT 1 FROM captures WHERE capture_id=? AND classification='assistant_selected'", arrayOf(captureId),
        ).use { it.moveToFirst() }

    @Synchronized
    internal fun assistantDispositionPending(captureId: String): Boolean =
        findCapture(captureId)?.classification == "assistant_selected"

    /** Freeze the model's disposition before sending, so a lost receipt cannot reclassify as a note. */
    @Synchronized
    internal fun selectAssistantHandoff(captureId: String, lease: String) = runtimeSettings.transaction {
        check(velaAssistantInput(captureId, lease) != null) { "助手任务已改变，请稍后重试" }
        if (!assistantHandoffSelected(captureId, lease)) {
            writableDatabase.update("captures", ContentValues().apply { put("classification", "assistant_selected") },
                "capture_id=?", arrayOf(captureId))
            appendEvent("AssistantIntentSelected", captureId, JSONObject().put("lease_id", lease))
        }
    }

    /** Accepted is a durable device handoff, never a claim that an alarm/timer has already run. */
    @Synchronized
    internal fun applyAssistantHandoff(captureId: String, lease: String) = runtimeSettings.transaction {
        val db = writableDatabase
        if (!ownsClassificationLease(db, captureId, lease)) return@transaction
        check(assistantHandoffSelected(captureId, lease))
        if (!hasUserCardActivity(db, captureId)) {
            cardsForCapture(db, captureId).forEach { card ->
                appendEvent(db, "CardDeleted", captureId, System.currentTimeMillis(), JSONObject().put("card_id", card.cardId))
            }
            db.delete("cards", "capture_id=?", arrayOf(captureId))
        }
        db.update("captures", ContentValues().apply { put("classification", "assistant") }, "capture_id=?", arrayOf(captureId))
        db.update("classification_jobs", ContentValues().apply {
            put("state", "complete"); put("updated_at", System.currentTimeMillis()); putNull("lease_id")
        }, "capture_id=?", arrayOf(captureId))
        appendEvent("AssistantHandoffAccepted", captureId, JSONObject().put("lease_id", lease).put("state", "accepted"))
    }

    /** Applies coordinator output as proposals; it never accepts a proposal. */
    @Synchronized
    fun applyRemoteClassification(
        captureId: String,
        proposals: List<BufferClassificationProposal>,
        lease: String? = null,
        expectedTranscript: String? = null,
    ) {
        require(proposals.isNotEmpty()) { "classification proposal is empty" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (lease != null && !ownsClassificationLease(db, captureId, lease)) {
                db.setTransactionSuccessful()
                return
            }
            // The worker's immutable input must still match at the actual write,
            // not only after the model response or before acquiring this monitor.
            if (expectedTranscript != null && findCapture(captureId)?.transcript != expectedTranscript) {
                if (lease != null) {
                    db.update("classification_jobs", ContentValues().apply {
                        put("state", "pending"); put("updated_at", System.currentTimeMillis()); putNull("lease_id")
                    }, "capture_id=? AND state='processing' AND lease_id=?", arrayOf(captureId, lease))
                }
                db.setTransactionSuccessful()
                return
            }

            val captureMetadata = db.query(
                "captures",
                arrayOf("created_at", "source_device"),
                "capture_id = ?",
                arrayOf(captureId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
            } ?: throw IllegalArgumentException("unknown capture: $captureId")
            val captureCreatedAt = captureMetadata.first
            val velaFabricCapture = VelaPendingCaptureProtocol.isFabricDeviceId(captureMetadata.second)
            val existing = cardsForCapture(db, captureId)
            val userTouched = existing.any { card ->
                card.classificationConfirmed ||
                    !card.answer.isNullOrBlank() ||
                    card.researchNotes.orEmpty().isNotBlank() ||
                    card.externalLinks.isNotEmpty() ||
                    card.tags.isNotEmpty() ||
                    card.relatedCardIds.isNotEmpty() ||
                    card.state in setOf(
                        "quick_answered",
                        "calendar_requested",
                        "health_synced",
                        "done",
                        "archived",
                        "deleted",
                        "later",
                        "rabbit_hole_candidate",
                        "fermenting",
                        "converted",
                        "reminder_requested",
                        "researched",
                    )
            } || hasUserCardActivity(db, captureId)
            if (userTouched) {
                db.update(
                    "classification_jobs",
                    ContentValues().apply {
                        put("state", "complete")
                        put("updated_at", System.currentTimeMillis())
                        putNull("lease_id")
                    },
                    "capture_id = ?",
                    arrayOf(captureId),
                )
                appendEvent(
                    db,
                    "ClassificationSkipped",
                    captureId,
                    System.currentTimeMillis(),
                    JSONObject()
                        .put("capture_id", captureId)
                        .put("reason", "user_activity"),
                )
            } else {
                proposals.forEachIndexed { index, proposal ->
                    val targetId = existing.getOrNull(index)?.cardId ?: if (velaFabricCapture) {
                        VelaPendingCaptureProtocol.deterministicCardId(captureId, index)
                    } else {
                        newId("card")
                    }
                    val relatedIds = proposal.relatedCardIds.distinct().filter { id ->
                        id != targetId && findCard(id)?.let { it.state !in setOf("pending_transcription", "deleted") && it.captureId != captureId } == true
                    }
                    runtimeSettings.edit().putString("task_source:$targetId", proposal.text).remove("task_schedule:$targetId").apply()
                    val actions = ClassificationReview.proposalActions(proposal.kind, proposal.healthCategory)
                    val state = ClassificationReview.initialState(proposal.kind)
                    appendEvent(
                        db,
                        "ClassificationProposed",
                        captureId,
                        System.currentTimeMillis(),
                        JSONObject()
                            .put("capture_id", captureId)
                            .put("segment_index", index)
                            .put("segment", proposal.text)
                            .put("kind", proposal.kind)
                            .put("health_category", proposal.healthCategory ?: JSONObject.NULL)
                            .put("confidence", proposal.confidence)
                            .put("source", "phone_llm")
                            .put("related_card_ids", JSONArray(relatedIds))
                            .put("presentation", proposal.presentation ?: JSONObject.NULL)
                            .put("bedtime", proposal.bedtime?.let(::JSONObject) ?: JSONObject.NULL),
                    )
                    if (index < existing.size) {
                        db.update(
                            "cards",
                            ContentValues().apply {
                                put("kind", proposal.kind)
                                put("title", proposal.title)
                                put("summary", proposal.summary)
                                put("state", state)
                                put("actions", JSONArray(actions).toString())
                                put("health_category", proposal.healthCategory)
                                put("classification_confirmed", 0)
                                put("related_card_ids", JSONArray(relatedIds).toString())
                            },
                            "card_id = ?",
                            arrayOf(existing[index].cardId),
                        )
                    } else {
                        insertCard(
                            db,
                            BufferCard(
                                cardId = targetId,
                                captureId = captureId,
                                kind = proposal.kind,
                                title = proposal.title,
                                summary = proposal.summary,
                                state = state,
                                actions = actions,
                                createdAt = captureCreatedAt,
                                healthCategory = proposal.healthCategory,
                                relatedCardIds = relatedIds,
                            ),
                        )
                    }
                    if (proposal.presentation != null) runtimeSettings.edit()
                        .putString("card_presentation:$targetId", proposal.presentation).apply()
                    saveBedtimeAssessment(targetId, proposal.bedtime)
                }
                existing.drop(proposals.size).forEach { old ->
                    db.update(
                        "cards",
                        ContentValues().apply {
                            put("state", "archived")
                            put("actions", JSONArray(listOf("archive")).toString())
                        },
                        "card_id = ?",
                        arrayOf(old.cardId),
                    )
                    appendEvent(
                        db,
                        "ClassificationSuperseded",
                        captureId,
                        System.currentTimeMillis(),
                        JSONObject()
                            .put("capture_id", captureId)
                            .put("card_id", old.cardId),
                    )
                }
                db.update(
                    "captures",
                    ContentValues().apply {
                        put("classification", proposals.joinToString("|") { it.kind })
                    },
                    "capture_id = ?",
                    arrayOf(captureId),
                )
                db.update(
                    "classification_jobs",
                    ContentValues().apply {
                        put("state", "complete")
                        put("updated_at", System.currentTimeMillis())
                        putNull("lease_id")
                    },
                    "capture_id = ?",
                    arrayOf(captureId),
                )
            }
            observeCaptureDocuments(db, captureId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun cards(limit: Int = 5): List<BufferCard> {
        return queryCards(
            selection = "state NOT IN (?, ?, ?)",
            selectionArgs = arrayOf("archived", "done", "deleted"),
            orderBy = "created_at DESC",
            limit = limit,
        )
    }

    /** Shared small display deck for Vela and the phone's bedtime home screen. */
    fun cardsForMode(mode: String, limit: Int = 5): List<BufferCard> {
        if (limit <= 0) return emptyList()
        if (mode == "normal") return normalCards(limit)
        if (mode == "bedtime") return bedtimeCards(limit)
        val active = "state NOT IN (?, ?, ?)"
        val (selection, args, orderBy) = when (mode) {
            "focus" -> Triple(
                "$active AND kind IN (?, ?)",
                arrayOf("archived", "done", "deleted", "care_card", "task_card"),
                "CASE WHEN kind = 'care_card' THEN 0 ELSE 1 END, created_at DESC",
            )
            "rabbit_hole" -> Triple(
                "$active AND kind IN (?, ?, ?)",
                arrayOf("archived", "done", "deleted", "question_card", "spark_card", "rabbit_hole_card"),
                "CASE WHEN state = 'rabbit_hole_candidate' THEN 0 ELSE 1 END, created_at DESC",
            )
            else -> Triple(active, arrayOf("archived", "done", "deleted"), "created_at DESC")
        }
        return queryCards(selection, args, orderBy, limit)
    }

    /** Reserve one place per available category before filling with recent cards.
     * Scan all eligible rows: limiting to the newest 100 first can starve a rare
     * category. Memory stays bounded by the requested deck plus three choices. */
    private fun normalCards(limit: Int): List<BufferCard> {
        val maximum = limit.coerceAtMost(100)
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.now(zone)
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val recent = mutableListOf<BufferCard>()
        val categories = arrayOfNulls<BufferCard>(3)
        readableDatabase.query(
            "cards", null,
            "state NOT IN ('archived','done','deleted','pending_transcription') " +
                "AND (state != 'later' OR card_id NOT IN (SELECT card_id FROM card_returns WHERE due_at IS NOT NULL)) " +
                "AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.capture_id=cards.capture_id " +
                "AND c.blob_path IS NOT NULL AND (c.transcript IS NULL OR TRIM(c.transcript)=''))",
            null, null, null,
            "COALESCE((SELECT returned_at FROM card_returns WHERE card_returns.card_id=cards.card_id),0) DESC, created_at DESC, card_id ASC",
        ).use { rows ->
            while (rows.moveToNext()) {
                val card = readCard(rows)
                if (recent.size < maximum) recent += card
                val category = when (card.kind) {
                    "spark_card" -> 0
                    "task_card" -> if (taskSchedule(card)?.let { it.start < dayEnd && it.end > dayStart } == true) 1 else -1
                    "question_card", "answer_card" -> 2
                    else -> -1
                }
                if (category >= 0 && categories[category] == null) categories[category] = card
            }
        }
        val result = categories.filterNotNull().take(maximum).toMutableList()
        recent.forEach { card ->
            if (result.size < maximum && result.none { it.cardId == card.cardId }) result += card
        }
        return result
    }

    private fun bedtimeCards(limit: Int): List<BufferCard> {
        val result = mutableListOf<BufferCard>()
        val maximum = limit.coerceIn(1, 5)
        readableDatabase.query(
            "cards", null, "state NOT IN (?, ?, ?) AND (state != 'later' OR card_id NOT IN (SELECT card_id FROM card_returns WHERE due_at IS NOT NULL))", arrayOf("archived", "done", "deleted"),
            null, null, "COALESCE((SELECT returned_at FROM card_returns WHERE card_returns.card_id = cards.card_id), 0) DESC, CASE WHEN kind = 'care_card' THEN 0 ELSE 1 END, created_at DESC",
        ).use { cursor ->
            while (result.size < maximum && cursor.moveToNext()) {
                val card = readCard(cursor)
                BedtimeDeck.project(card, bedtimeAssessment(card), bedtimeUserApproved(card))?.let(result::add)
            }
        }
        return result
    }

    private fun queryCards(
        selection: String,
        selectionArgs: Array<String>,
        orderBy: String,
        limit: Int,
    ): List<BufferCard> {
        val result = mutableListOf<BufferCard>()
        readableDatabase.query(
            "cards",
            null,
            "($selection) AND state != 'deleted' AND (state != 'later' OR card_id NOT IN (SELECT card_id FROM card_returns WHERE due_at IS NOT NULL))",
            selectionArgs,
            null,
            null,
            "COALESCE((SELECT returned_at FROM card_returns WHERE card_returns.card_id = cards.card_id), 0) DESC, $orderBy",
            limit.coerceIn(1, 100).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += readCard(cursor)
        }
        return result
    }

    internal fun searchLocalRecords(query: String, excludeCapture: String? = null): List<BufferCard> {
        require(query.length in 1..120)
        val result = mutableListOf<BufferCard>()
        val exclusion = if (excludeCapture == null) "" else "AND (capture_id IS NULL OR capture_id <> ?) "
        val arguments = (excludeCapture?.let { arrayOf(it) } ?: emptyArray()) + arrayOf(query, query, query, query)
        readableDatabase.query("cards", null,
            "state NOT IN ('pending_transcription', 'deleted') AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.capture_id = cards.capture_id AND c.blob_path IS NOT NULL AND (c.transcript IS NULL OR TRIM(c.transcript) = '')) " +
                exclusion +
                "AND (instr(lower(title), lower(?)) > 0 OR instr(lower(summary), lower(?)) > 0 OR instr(lower(COALESCE(research_notes, '')), lower(?)) > 0 OR instr(lower(tags), lower(?)) > 0)",
            arguments, null, null, "created_at DESC, card_id ASC", "12").use {
            while (it.moveToNext()) result += readCard(it)
        }
        return result
    }

    internal fun cardConversation(cardId: String): String = runtimeSettings.getString("card_conversation:$cardId", "[]") ?: "[]"

    internal fun cardPresentation(cardId: String): String? = runtimeSettings.getString("card_presentation:$cardId", null)

    fun shelfCards(limit: Int = 100): List<BufferCard> = cardsByKind("spark_card", limit)

    fun answerBoxCards(limit: Int = 100): List<BufferCard> =
        cardsByKind("question_card", limit)

    fun lifeLogCards(limit: Int = 100): List<BufferCard> {
        val result = mutableListOf<BufferCard>()
        readableDatabase.query(
            "cards",
            null,
            "(health_category IS NOT NULL OR kind = ?) AND state NOT IN ('pending_transcription', 'deleted') " +
                "AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.capture_id = cards.capture_id AND c.blob_path IS NOT NULL AND (c.transcript IS NULL OR TRIM(c.transcript) = ''))",
            arrayOf("weekly_card"),
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 100).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += readCard(cursor)
        }
        return result
    }

    private fun cardsByKind(kind: String, limit: Int): List<BufferCard> {
        return cardsByKinds(listOf(kind), limit)
    }

    private fun cardsByKinds(kinds: List<String>, limit: Int): List<BufferCard> {
        val result = mutableListOf<BufferCard>()
        val placeholders = kinds.joinToString(",") { "?" }
        readableDatabase.query(
            "cards",
            null,
            "kind IN ($placeholders) AND state NOT IN ('pending_transcription', 'deleted') " +
                "AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.capture_id = cards.capture_id AND c.blob_path IS NOT NULL AND (c.transcript IS NULL OR TRIM(c.transcript) = ''))",
            kinds.toTypedArray(),
            null,
            null,
            "created_at DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += readCard(cursor)
        }
        return result
    }

    @Synchronized
    internal fun velaReplicaResume(peer: String): String {
        require(peer.matches(Regex("[0-9a-f]{64}")))
        return runtimeSettings.getString("vela_replica_resume:$peer", "").orEmpty()
            .takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }.orEmpty()
    }

    @Synchronized
    internal fun saveVelaReplicaResume(peer: String, cardId: String) {
        require(peer.matches(Regex("[0-9a-f]{64}")))
        require(cardId.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        runtimeSettings.edit().putString("vela_replica_resume:$peer", cardId).apply()
    }

    @Synchronized
    internal fun velaCalendarReplicaResume(peer: String): String {
        require(peer.matches(Regex("[0-9a-f]{64}")))
        return runtimeSettings.getString("vela_calendar_replica_resume:$peer", "").orEmpty()
            .takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }.orEmpty()
    }

    @Synchronized
    internal fun saveVelaCalendarReplicaResume(peer: String, id: String) {
        require(peer.matches(Regex("[0-9a-f]{64}")) && id.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        runtimeSettings.edit().putString("vela_calendar_replica_resume:$peer", id).apply()
    }

    private fun calendarReplicaSnapshot(id: String, doc: JSONObject): String {
        val values = BufferCrdt.values(doc)
        val body = JSONObject().put("card_id", id).put("deleted", doc.getBoolean("deleted"))
            .put("revision", BufferCrdt.clock(doc).coerceAtLeast(1)).put("crdt", doc)
        if (!doc.getBoolean("deleted")) body.put("card", values.put("card_id", id)
            .put("kind", "calendar_event").put("state", "active").put("summary", values.optString("location"))
            .put("created_at", values.getJSONObject("schedule").getLong("start")))
        return BufferCrdt.canonical(body)
    }

    /** Current calendar series precede retained legacy tombstones. The normal
     * scan still visits every document and owns the complete-sync receipt. */
    @Synchronized
    internal fun velaLiveCalendarReplicaPage(afterId: String, limit: Int = 4): List<String> {
        require(limit in 1..16)
        return calendarDocumentIds().asSequence().filter { it > afterId }.sorted()
            .mapNotNull { id -> crdtDocument(id)?.takeUnless { it.getBoolean("deleted") }?.let { id to it } }
            .take(limit).map { (id, doc) -> calendarReplicaSnapshot(id, doc) }.toList()
    }

    /** All authorized cards, paged independently of the five-card screen deck.
     * Stable snapshots survive retries/restarts; a deletion is an explicit
     * tombstone, never inferred from absence in a page or the home screen. */
    @Synchronized
    internal fun velaReplicaPage(afterId: String, limit: Int = 4): List<String> {
        require(limit in 1..16)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val deleted = mutableSetOf<String>()
            db.query("events", arrayOf("payload"), "type = ?", arrayOf("CardDeleted"), null, null, null).use { rows ->
                while (rows.moveToNext()) deleted += JSONObject(rows.getString(0)).getString("card_id")
            }
            db.query("cards", arrayOf("card_id"), "state = 'deleted' AND card_id > ?",
                arrayOf(afterId), null, null, "card_id", limit.toString()).use { rows ->
                while (rows.moveToNext()) deleted += rows.getString(0)
            }
            val ids = java.util.TreeSet<String>()
            ids.addAll(calendarDocumentIds().filter { it > afterId })
            ids.addAll(deleted.filter { it > afterId })
            db.query("cards", arrayOf("card_id"), "card_id > ? AND state NOT IN ('pending_transcription', 'deleted') " +
                "AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.capture_id = cards.capture_id AND c.blob_path IS NOT NULL AND (c.transcript IS NULL OR TRIM(c.transcript) = ''))",
                arrayOf(afterId), null, null, "card_id", limit.toString()).use { rows ->
                while (rows.moveToNext()) ids += rows.getString(0)
            }
            val result = ids.take(limit).mapNotNull { id ->
                val card = findCard(id)
                if (card == null && id !in deleted) {
                    val doc = crdtDocument(id) ?: return@mapNotNull null
                    return@mapNotNull calendarReplicaSnapshot(id, doc)
                }
                val body = JSONObject().put("card_id", id).put("deleted", id in deleted)
                if (card != null && id !in deleted) {
                    body.put("card", card.toJson().put("answer", card.answer ?: JSONObject.NULL)
                        .put("tags", JSONArray(card.tags)).put("related_card_ids", JSONArray(card.relatedCardIds))
                        .put("research_notes", card.researchNotes ?: JSONObject.NULL))
                    cardPresentation(id)?.let { body.put("presentation", JSONObject(it)) }
                    val conversation = JSONArray(cardConversation(id))
                    if (conversation.length() > 0) body.put("conversation", conversation)
                    val processed = card.captureId?.let { capture ->
                        db.rawQuery("SELECT 1 FROM classification_jobs WHERE capture_id=? AND state='complete'", arrayOf(capture))
                            .use { it.moveToFirst() }
                    } ?: false
                    body.put("processed", processed)
                }
                val doc = exportCardCrdt(id, id in deleted)
                body.put("crdt", doc)
                body.optJSONObject("card")?.put("schedule", BufferCrdt.values(doc).opt("schedule") ?: JSONObject.NULL)
                val fingerprint = com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(body))
                val key = "vela_replica_snapshot:$id"
                val previous = runtimeSettings.getString(key, null)?.let(::JSONObject)
                val snapshot = if (previous?.optString("fingerprint") == fingerprint) previous.getString("record") else {
                    val clock = runtimeSettings.getLong("vela_replica_clock", 0) + 1
                    check(clock in 1..9_007_199_254_740_991L)
                    body.put("revision", clock)
                    val raw = BufferCrdt.canonical(body)
                    check(raw.toByteArray(Charsets.UTF_8).size <= 65_280) { "卡片超出设备副本预算" }
                    runtimeSettings.edit().putLong("vela_replica_clock", clock)
                        .putString(key, JSONObject().put("fingerprint", fingerprint).put("record", raw).toString()).apply()
                    raw
                }
                snapshot
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    /** Edits the derived card, retaining the immutable capture and previous event history. */
    @Synchronized
    fun editCardContent(expected: BufferCard, title: String, summary: String): BufferCard {
        val cleanTitle = title.trim()
        val cleanSummary = summary.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.codePointCount(0, cleanTitle.length) <= 80) { "标题需为 1–80 个字" }
        require(cleanSummary.isNotEmpty() && cleanSummary.length <= 4000) { "内容需为 1–4000 个字符" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = requireNotNull(findCard(expected.cardId)) { "这条收藏已被删除" }
            require(current.state != "pending_transcription") { "请先完成语音转写" }
            require(current.title == expected.title && current.summary == expected.summary) { "内容已经更新，请重新打开后编辑" }
            if (current.title != cleanTitle || current.summary != cleanSummary) {
                if (current.kind == "task_card" && current.summary != cleanSummary) {
                    runtimeSettings.edit().putString("task_source:${current.cardId}", cleanSummary).apply()
                    TaskSchedule.fromText(cleanSummary, System.currentTimeMillis())?.let {
                        runtimeSettings.edit().putString("task_schedule:${current.cardId}", it.json().toString()).apply()
                    }
                }
                db.update("cards", ContentValues().apply { put("title", cleanTitle); put("summary", cleanSummary) },
                    "card_id = ?", arrayOf(current.cardId))
                appendEvent(db, "CardContentEdited", current.captureId, System.currentTimeMillis(), JSONObject()
                    .put("card_id", current.cardId).put("previous_title", current.title).put("previous_summary", current.summary)
                    .put("title", cleanTitle).put("summary", cleanSummary))
            }
            db.setTransactionSuccessful()
            return current.copy(title = cleanTitle, summary = cleanSummary)
        } finally { db.endTransaction() }
    }

    /** Removes card projections atomically; deletion events prevent late classification from restoring them. */
    @Synchronized
    fun deleteCards(cardIds: List<String>): Int {
        require(cardIds.size <= 100) { "一次最多删除 100 条" }
        val ids = cardIds.distinct()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cards = ids.mapNotNull(::findCard)
            require(cards.none { it.state == "pending_transcription" }) { "待转写语音请在语音整理中处理" }
            for (card in cards) {
                appendEvent(db, "CardDeleted", card.captureId, System.currentTimeMillis(), JSONObject().put("card_id", card.cardId))
                db.delete("cards", "card_id = ?", arrayOf(card.cardId))
                runtimeSettings.edit().remove("card_presentation:${card.cardId}").apply()
                db.delete("card_returns", "card_id = ?", arrayOf(card.cardId))
                // Keep cancellation rows until the notification reconciler has withdrawn posted notifications.
                db.execSQL("UPDATE card_reminders SET cancelled = 1 WHERE card_id = ?", arrayOf(card.cardId))
            }
            val removed = cards.map { it.cardId }.toSet()
            val related = mutableListOf<BufferCard>()
            db.query("cards", null, "related_card_ids != '[]'", null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) related += readCard(cursor)
            }
            for (card in related) {
                val remaining = card.relatedCardIds.filterNot { it in removed }
                if (remaining != card.relatedCardIds) {
                    db.update("cards", ContentValues().apply {
                        put("related_card_ids", JSONArray(remaining).toString())
                    }, "card_id = ?", arrayOf(card.cardId))
                    observeCardSource(db, card.cardId)
                }
            }
            db.setTransactionSuccessful()
            return cards.size
        } finally { db.endTransaction() }
    }

    @Synchronized
    internal fun performVelaCardAction(peer: String,cardId: String,action: String,actionId: String,authorize: ()->Boolean): String = runtimeSettings.transaction {
        require(peer.matches(Regex("[0-9a-f]{64}")));check(authorize())
        val card=findCard(cardId)
        val result=if(card==null)"absent" else {
            val source=card.captureId?.let { capture -> readableDatabase.rawQuery("SELECT source_device FROM captures WHERE capture_id=?",arrayOf(capture))
                .use { if(it.moveToFirst())it.getString(0)else null } }
            val inferred=when {source=="android"||source==deviceId()->"local";source?.matches(Regex("[0-9a-f]{64}"))==true->requireNotNull(source);else->"unknown"}
            val audience=BufferDocumentSource.audience(writableDatabase,cardId,inferred)
            if(audience!="local" && audience!=peer)"rejected"
            else try {performCardAction(cardId,action,actionId,expectedCard=card);"applied"} catch (_: IllegalArgumentException){"rejected"}
        }
        check(authorize());result
    }

    @Synchronized
    internal fun applyAuthenticatedVelaCardState(peer: String,change: com.mocharealm.foundation.fabric.buffer.data.device.VelaCardStateChange,authorize: ()->Boolean): String = runtimeSettings.transaction {
        check(authorize());val result=applyVelaCardState(peer,change);check(authorize());result
    }

    @Synchronized
    fun performCardAction(cardId: String, action: String, actionId: String? = null,
                          requestedReturnAt: Long? = null, expectedCard: BufferCard? = null): BufferCard? {
        val cleanActionId = actionId?.trim()?.ifBlank { null }
        require(cleanActionId == null || cleanActionId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
            "invalid action id"
        }
        require(requestedReturnAt == null || (action in setOf("later", "remind") && cleanActionId == null && requestedReturnAt > System.currentTimeMillis())) {
            "自选时间必须在未来，且仅用于本机稍后或提醒操作"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (cleanActionId != null) {
                val receipt = actionReceipt(cleanActionId)
                if (receipt != null) {
                    require(receipt.cardId == cardId && receipt.action == action) {
                        "action id was already used for another operation"
                    }
                    db.setTransactionSuccessful()
                    return findCard(cardId)
                }
            }
            val old = findCard(cardId) ?: run {
                db.setTransactionSuccessful()
                return null
            }
            require(expectedCard == null || old == expectedCard) { "卡片已更新，请重新确认回流时间" }
            require(action in old.actions) { "action is not available for this card" }
            require(action != "reopen" || (old.kind == "task_card" && old.state == "done")) { "只有已完成的待办可以恢复" }
            if (action == "calendar") {
                require(old.classificationConfirmed) {
                    "请先确认分类，再写入日历"
                }
            }
            if (old.kind == "spark_card" && action in SPARK_DERIVED_ACTIONS) {
                val derived = createSparkDerivedCard(old, action, cleanActionId)
                db.setTransactionSuccessful()
                return derived
            }
            val newState = when (action) {
                "archive" -> "archived"
                "done" -> "done"
                "reopen" -> "active"
                "later" -> "later"
                "rabbit_hole" -> "rabbit_hole_candidate"
                "ferment" -> "fermenting"
                "mark_answered" -> "quick_answered"
                "mark_researched" -> "researched"
                "remind" -> "reminder_requested"
                "calendar" -> "calendar_requested"
                "remove_calendar" -> "inbox"
                "next" -> old.state
                else -> throw IllegalArgumentException("unknown card action: $action")
            }
            db.update(
                "cards",
                ContentValues().apply {
                    put("state", newState)
                    when (action) {
                        "calendar" -> put(
                            "actions",
                            JSONArray(
                                ClassificationReview.preserveActions(
                                    old,
                                    listOf("done", "later", "remove_calendar"),
                                ),
                            ).toString(),
                        )
                        "remove_calendar" -> put(
                            "actions",
                            JSONArray(
                                ClassificationReview.preserveActions(
                                    old,
                                    listOf("done", "later", "remind", "calendar"),
                                ),
                            ).toString(),
                        )
                        "mark_researched" -> put(
                            "actions",
                            JSONArray(old.actions.filterNot { it == "mark_researched" }).toString(),
                        )
                    }
                },
                "card_id = ?",
                arrayOf(cardId),
            )
            var returnAt: Long? = null
            if (action == "later") {
                val previous = db.rawQuery("SELECT return_state FROM card_returns WHERE card_id = ?", arrayOf(cardId)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                val returnState = if (old.state == "later") previous ?: "inbox" else old.state
                returnAt = requestedReturnAt ?: CardReturnTime.tomorrow(System.currentTimeMillis())
                db.insertWithOnConflict("card_returns", null, ContentValues().apply {
                    put("card_id", cardId); put("due_at", returnAt); put("return_state", returnState); put("returned_at", 0L)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            } else if (newState != old.state || action in setOf("archive", "done")) {
                db.delete("card_returns", "card_id = ?", arrayOf(cardId))
            }
            if (action == "remind") {
                returnAt = requestedReturnAt ?: (System.currentTimeMillis() + 3_600_000)
                db.insertWithOnConflict("card_reminders", null, ContentValues().apply {
                    put("card_id", cardId); put("due_at", returnAt); put("revision", newId("reminder"))
                    put("delivered", 0); put("cancelled", 0); put("retry_at", 0L)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            } else if (newState != old.state || action in setOf("archive", "done", "later")) {
                db.execSQL("UPDATE card_reminders SET cancelled = 1 WHERE card_id = ?", arrayOf(cardId))
            }
            appendEvent(db, "CardActionPerformed", old.captureId, System.currentTimeMillis(), JSONObject()
                .put("card_id", cardId)
                .put("card_kind", old.kind)
                .put("action", action)
                .put("action_id", cleanActionId ?: JSONObject.NULL)
                .put("old_state", old.state)
                .put("return_at", returnAt ?: JSONObject.NULL)
                .put("new_state", newState))
            if (cleanActionId != null) {
                db.insertOrThrow("action_receipts", null, ContentValues().apply {
                    put("action_id", cleanActionId)
                    put("card_id", cardId)
                    put("action", action)
                    put("resulting_state", newState)
                    put("created_at", System.currentTimeMillis())
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    fun isClassificationConfirmed(cardId: String): Boolean =
        findCard(cardId)?.classificationConfirmed == true

    @Synchronized
    fun hasHealthDataWritten(cardId: String): Boolean {
        if (cardId.isBlank()) return false
        readableDatabase.query(
            "events",
            arrayOf("payload"),
            "type = ?",
            arrayOf("HealthDataWritten"),
            null,
            null,
            "created_at DESC, rowid DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = runCatching { JSONObject(cursor.getString(0)) }.getOrNull()
                if (payload?.optString("card_id") == cardId) return true
            }
        }
        return false
    }

    fun actionResultState(actionId: String): String? =
        actionReceipt(actionId)?.resultingState

    @Synchronized
    internal fun reviewClassification(
        expected: BufferCard, decision: ClassificationDecision,
        kind: String? = null, healthCategory: String? = null,
    ): BufferCard? {
        val cardId = expected.cardId
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = findCard(cardId) ?: return null
            val change = ClassificationReview.decide(old, decision, kind, healthCategory, expected)
            if (change.eventType == null) return old
            old.captureId?.let { captureId ->
                val hasTranscript = db.query(
                    "captures", arrayOf("transcript"), "capture_id = ?", arrayOf(captureId),
                    null, null, null, "1",
                ).use { it.moveToFirst() && !it.getString(0).isNullOrBlank() }
                require(hasTranscript) { "请先完成语音转写" }
            }
            val reviewed = change.card
            db.update(
                "cards",
                ContentValues().apply {
                    put("kind", reviewed.kind)
                    put("title", reviewed.title)
                    put("state", reviewed.state)
                    put("health_category", reviewed.healthCategory)
                    put("classification_confirmed", if (reviewed.classificationConfirmed) 1 else 0)
                    put("actions", JSONArray(reviewed.actions).toString())
                }, "card_id = ?", arrayOf(cardId),
            )
            appendEvent(
                db, change.eventType, old.captureId, System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("previous_kind", old.kind)
                    .put("kind", reviewed.kind)
                    .put("previous_health_category", old.healthCategory ?: JSONObject.NULL)
                    .put("health_category", reviewed.healthCategory ?: JSONObject.NULL)
                    .put("policy", "user_confirmation"),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    @Synchronized
    fun saveQuestionAnswer(cardId: String, answer: String, source: String = "user", expected: BufferCard? = null, request: String? = null): BufferCard? {
        val cleanAnswer = answer.trim()
        require(cleanAnswer.isNotEmpty() && cleanAnswer.length <= 4000) { "answer length is invalid" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = findCard(cardId) ?: return null
            require(old.kind == "question_card") { "only question cards accept answers" }
            require(source != "remote" || (expected != null && old == expected)) {
                "问题已更新，未覆盖现有内容；请刷新后重新请求"
            }
            require(request == null || questionAnswerRequestCurrent(cardId, request)) { "问题请求已更新，未覆盖当前答案" }
            if (source == "user") {
                supersedeMeshAnswers(cardId)
                setQuestionAnswerHead(cardId, null)
            }
            db.update(
                "cards",
                ContentValues().apply {
                    put("answer", cleanAnswer)
                    put("state", "quick_answered")
                    put(
                        "actions",
                        JSONArray(
                            ClassificationReview.preserveActions(
                                old,
                                listOf("later", "archive", "rabbit_hole", "mark_researched"),
                            ),
                        ).toString(),
                    )
                },
                "card_id = ?",
                arrayOf(cardId),
            )
            appendEvent(
                db,
                "QuestionAnswered",
                old.captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("answer", cleanAnswer)
                    .put("source", source),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    @Synchronized
    fun saveResearchNote(cardId: String, note: String): BufferCard? {
        val old = findCard(cardId) ?: return null
        require(old.kind == "question_card") { "only question cards accept research notes" }
        val cleanNote = note.trim()
        require(cleanNote.isNotEmpty()) { "research note is empty" }
        require(cleanNote.length <= MAX_RESEARCH_NOTE_LENGTH) {
            "research note is too long"
        }
        val combined = if (old.researchNotes.isNullOrBlank()) {
            cleanNote
        } else {
            "${old.researchNotes.trim()}\n\n$cleanNote"
        }
        require(combined.length <= MAX_RESEARCH_TOTAL_LENGTH) {
            "research notes are too long"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "cards",
                ContentValues().apply { put("research_notes", combined) },
                "card_id = ?",
                arrayOf(cardId),
            )
            appendEvent(
                db,
                "QuestionResearchNoteAdded",
                old.captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("note", cleanNote),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    @Synchronized
    fun addExternalLink(cardId: String, link: String): BufferCard? {
        val old = findCard(cardId) ?: return null
        require(old.kind == "question_card") { "only question cards accept external links" }
        val cleanLink = link.trim()
        require(cleanLink.length <= MAX_RESEARCH_LINK_LENGTH) {
            "external link is too long"
        }
        val uri = Uri.parse(cleanLink)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "external link must use http or https"
        }
        if (old.externalLinks.contains(cleanLink)) return old
        require(old.externalLinks.size < MAX_RESEARCH_LINK_COUNT) {
            "too many external links"
        }
        val links = old.externalLinks + cleanLink
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "cards",
                ContentValues().apply {
                    put("external_links", JSONArray(links).toString())
                },
                "card_id = ?",
                arrayOf(cardId),
            )
            appendEvent(
                db,
                "QuestionExternalLinkAdded",
                old.captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("url", cleanLink),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    @Synchronized
    fun updateCardTags(cardId: String, tags: List<String>, expectedCard: BufferCard? = null): BufferCard? {
        val old = findCard(cardId) ?: return null
        require(expectedCard == null || expectedCard == old) { "卡片内容已更新，请重新确认标签" }
        val normalized = tags
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        require(normalized.size <= MAX_TAG_COUNT) { "too many tags" }
        require(normalized.all { it.length <= MAX_TAG_LENGTH }) { "tag is too long" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "cards",
                ContentValues().apply { put("tags", JSONArray(normalized).toString()) },
                "card_id = ?",
                arrayOf(cardId),
            )
            val approval = if (BedtimeDeck.SAFE_TAG in normalized && BedtimeDeck.HIGH_TAG !in normalized)
                BedtimeAssessment.contentHash(old) else null
            runtimeSettings.edit().putString("bedtime_user_review:$cardId", approval).apply()
            appendEvent(
                db,
                "CardTagsUpdated",
                old.captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("tags", JSONArray(normalized)).put("bedtime_content_sha256", approval ?: JSONObject.NULL),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    @Synchronized
    fun linkQuestionCard(cardId: String, targetCardId: String): BufferCard? {
        val old = findCard(cardId) ?: return null
        require(old.kind == "question_card") { "only question cards can link related cards" }
        require(cardId != targetCardId) { "a card cannot link to itself" }
        val target = findCard(targetCardId)
            ?: throw IllegalArgumentException("related card not found")
        require(target.kind in setOf("spark_card", "care_card", "weekly_card")) {
            "question cards can link to Sparks or life events"
        }
        if (targetCardId in old.relatedCardIds) return old
        val related = old.relatedCardIds + targetCardId
        require(related.size <= MAX_RELATED_CARD_COUNT) { "too many related cards" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "cards",
                ContentValues().apply {
                    put("related_card_ids", JSONArray(related).toString())
                },
                "card_id = ?",
                arrayOf(cardId),
            )
            appendEvent(
                db,
                "QuestionRelatedCardAdded",
                old.captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("related_card_id", targetCardId)
                    .put("related_kind", target.kind),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    @Synchronized
    fun unlinkQuestionCard(cardId: String, targetCardId: String): BufferCard? {
        val old = findCard(cardId) ?: return null
        require(old.kind == "question_card") { "only question cards can unlink related cards" }
        if (targetCardId !in old.relatedCardIds) return old
        val related = old.relatedCardIds.filterNot { it == targetCardId }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "cards",
                ContentValues().apply {
                    put("related_card_ids", JSONArray(related).toString())
                },
                "card_id = ?",
                arrayOf(cardId),
            )
            appendEvent(
                db,
                "QuestionRelatedCardRemoved",
                old.captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("related_card_id", targetCardId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    @Synchronized
    fun linkSparkCard(cardId: String, targetCardId: String): BufferCard? {
        val old = findCard(cardId) ?: return null
        require(old.kind == "spark_card") { "only Spark cards can link related ideas" }
        require(cardId != targetCardId) { "a card cannot link to itself" }
        val target = findCard(targetCardId)
            ?: throw IllegalArgumentException("related card not found")
        require(target.kind in setOf("spark_card", "question_card", "care_card", "weekly_card")) {
            "Spark cards can link to Sparks, questions, or life events"
        }
        if (targetCardId in old.relatedCardIds) return old
        val related = old.relatedCardIds + targetCardId
        require(related.size <= MAX_RELATED_CARD_COUNT) { "too many related cards" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "cards",
                ContentValues().apply {
                    put("related_card_ids", JSONArray(related).toString())
                },
                "card_id = ?",
                arrayOf(cardId),
            )
            appendEvent(
                db,
                "SparkRelatedCardAdded",
                old.captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("related_card_id", targetCardId)
                    .put("related_kind", target.kind),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    @Synchronized
    fun unlinkSparkCard(cardId: String, targetCardId: String): BufferCard? {
        val old = findCard(cardId) ?: return null
        require(old.kind == "spark_card") { "only Spark cards can unlink related ideas" }
        if (targetCardId !in old.relatedCardIds) return old
        val related = old.relatedCardIds.filterNot { it == targetCardId }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "cards",
                ContentValues().apply {
                    put("related_card_ids", JSONArray(related).toString())
                },
                "card_id = ?",
                arrayOf(cardId),
            )
            appendEvent(
                db,
                "SparkRelatedCardRemoved",
                old.captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("related_card_id", targetCardId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    private fun setQuestionAnswerHead(card: String, event: String?) {
        check(writableDatabase.inTransaction())
        writableDatabase.insertWithOnConflict("question_answer_heads", null, ContentValues().apply {
            put("card_id", card); if (event == null) putNull("event_id") else put("event_id", event)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    private fun supersedeMeshAnswers(card: String) {
        BufferMeshAnswerSource.pending(writableDatabase, card = card).forEach {
            BufferMeshAnswerSource.resolve(writableDatabase, it, "superseded", capabilityEmitter(writableDatabase))
        }
    }

    @Synchronized
    internal fun questionAnswerRequestCurrent(card: String, request: String): Boolean {
        val current = readableDatabase.rawQuery("SELECT event_id FROM question_answer_heads WHERE card_id=?", arrayOf(card))
            .use { it.moveToFirst() && !it.isNull(0) && it.getString(0) == request }
        if (!current) return false
        val original = BufferEventJournal.localRecord(readableDatabase, request)
        val event = original.event
        check(original.envelope.get("provenance") == "native" && event.get("type") == "QuestionAnswerRequested" && event.getJSONObject("payload").get("card_id") == card)
        return true
    }

    @Synchronized
    fun recordQuestionAnswerRequested(card: BufferCard): String = runtimeSettings.transaction {
        require(findCard(card.cardId) == card && card.kind == "question_card" && card.state != "deleted") { "问题已更新，请刷新后重试" }
        supersedeMeshAnswers(card.cardId)
        appendEvent(
            type = "QuestionAnswerRequested",
            captureId = card.captureId,
            payload = JSONObject().put("card_id", card.cardId),
        )
        val id = writableDatabase.rawQuery("SELECT event_id FROM events WHERE type='QuestionAnswerRequested' ORDER BY logical_clock DESC,rowid DESC LIMIT 1", null)
            .use { check(it.moveToFirst()); it.getString(0) }
        setQuestionAnswerHead(card.cardId, id)
        id
    }

    @Synchronized
    fun recordQuestionAnswerDeferred(card: BufferCard, detail: String, request: String? = null) = runtimeSettings.transaction {
        if (request != null && (!questionAnswerRequestCurrent(card.cardId, request) || findCard(card.cardId) != card)) return@transaction
        appendEvent(
            type = "QuestionAnswerDeferred",
            captureId = card.captureId,
            payload = JSONObject()
                .put("card_id", card.cardId)
                .put("detail", detail),
        )
    }

    @Synchronized
    fun recordQuestionAnswerFailed(card: BufferCard, detail: String, request: String? = null) = runtimeSettings.transaction {
        if (request != null && (!questionAnswerRequestCurrent(card.cardId, request) || findCard(card.cardId) != card)) return@transaction
        appendEvent(
            type = "QuestionAnswerFailed",
            captureId = card.captureId,
            payload = JSONObject()
                .put("card_id", card.cardId)
                .put("detail", detail),
        )
    }

    fun recordHealthSyncRequested(card: BufferCard) {
        appendEvent(
            type = "HealthSyncRequested",
            captureId = card.captureId,
            payload = JSONObject()
                .put("card_id", card.cardId)
                .put("category", card.healthCategory ?: JSONObject.NULL),
        )
    }

    fun recordHealthSyncFailed(card: BufferCard, detail: String) {
        appendEvent(
            type = "HealthSyncFailed",
            captureId = card.captureId,
            payload = JSONObject()
                .put("card_id", card.cardId)
                .put("category", card.healthCategory ?: JSONObject.NULL)
                .put("detail", detail),
        )
    }

    @Synchronized
    fun recordVelaButtonPressed(deviceId: String, captureId: String) {
        if (deviceId.isBlank() || captureId.isBlank()) return
        val alreadyRecorded = readableDatabase.query(
            "events",
            arrayOf("event_id"),
            "type = ? AND capture_id = ?",
            arrayOf("ButtonPressed", captureId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> cursor.moveToFirst() }
        if (alreadyRecorded) return
        appendEvent(
            type = "ButtonPressed",
            captureId = captureId,
            payload = JSONObject()
                .put("device_id", deviceId)
                .put("capture_id", captureId)
                .put("input", "button"),
        )
    }

    @Synchronized
    fun recordVelaStatus(
        deviceId: String,
        mode: String,
        pendingCaptures: Int,
        cardsStale: Boolean,
        recording: Boolean,
    ) {
        if (deviceId.isBlank()) return
        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("mode", mode)
            .put("pending_captures", pendingCaptures.coerceAtLeast(0))
            .put("cards_stale", cardsStale)
            .put("recording", recording)
        var unchanged = false
        readableDatabase.query(
            "events",
            arrayOf("payload"),
            "type = ?",
            arrayOf("DeviceStatusUpdated"),
            null,
            null,
            "created_at DESC, rowid DESC",
            "50",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = runCatching { JSONObject(cursor.getString(0)) }
                    .getOrNull()
                if (candidate?.optString("device_id") == deviceId) {
                    unchanged = candidate.optString("mode") == mode &&
                        candidate.optInt("pending_captures", -1) == pendingCaptures.coerceAtLeast(0) &&
                        candidate.optBoolean("cards_stale", false) == cardsStale &&
                        candidate.optBoolean("recording", false) == recording
                    break
                }
            }
        }
        if (unchanged) return
        appendEvent(
            type = "DeviceStatusUpdated",
            captureId = null,
            payload = payload,
        )
    }

    @Synchronized
    fun recordVelaUploadRetry(deviceId: String, captureId: String, detail: String) {
        if (deviceId.isBlank() || captureId.isBlank()) return
        val normalizedDetail = detail.trim().ifBlank { "upload retry requested" }
        val alreadyRecorded = readableDatabase.query(
            "events",
            arrayOf("payload"),
            "type = ? AND capture_id = ?",
            arrayOf("UploadRetryRequested", captureId),
            null,
            null,
            "created_at DESC, rowid DESC",
        ).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                val candidate = runCatching { JSONObject(cursor.getString(0)) }
                    .getOrNull()
                if (candidate?.optString("device_id") == deviceId &&
                    candidate.optString("detail") == normalizedDetail
                ) {
                    found = true
                    break
                }
            }
            found
        }
        if (alreadyRecorded) return
        appendEvent(
            type = "UploadRetryRequested",
            captureId = captureId,
            payload = JSONObject()
                .put("device_id", deviceId)
                .put("capture_id", captureId)
                .put("detail", normalizedDetail),
        )
    }

    @Synchronized
    fun markHealthSynced(cardId: String, recordType: String, recordId: String? = null, clientRecordId: String? = null): BufferCard? {
        val old = findCard(cardId) ?: return null
        require(old.classificationConfirmed) {
            "请先确认分类，再写入健康数据"
        }
        val alreadyWritten = hasHealthDataWritten(cardId)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "cards",
                ContentValues().apply {
                    put("state", "health_synced")
                    put("actions", JSONArray(old.actions.filterNot { it == "health_sync" }).toString())
                },
                "card_id = ?",
                arrayOf(cardId),
            )
            if (!alreadyWritten) {
                appendEvent(
                    db,
                    "HealthDataWritten",
                    old.captureId,
                    System.currentTimeMillis(),
                    JSONObject()
                        .put("card_id", cardId)
                        .put("category", old.healthCategory ?: JSONObject.NULL)
                        .put("record_type", recordType)
                        .put("record_id", recordId ?: JSONObject.NULL)
                        .put("client_record_id", clientRecordId ?: JSONObject.NULL),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(cardId)
    }

    @Synchronized
    internal fun commitCalendarChange(cardId: String, captureId: String?, eventId: String, deleted: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val card = requireNotNull(findCard(cardId)) { "任务已不存在" }
            val action = if (deleted) "remove_calendar" else "calendar"
            val alreadyApplied = if (deleted) "calendar" in card.actions else "remove_calendar" in card.actions
            if (!alreadyApplied) requireNotNull(performCardAction(cardId, action))
            if (deleted) recordCalendarDelete(cardId, captureId, eventId, true)
            else recordCalendarWrite(cardId, captureId, eventId, true)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordCalendarWrite(
        cardId: String,
        captureId: String?,
        eventId: String?,
        success: Boolean,
        detail: String? = null,
    ) {
        appendEvent(
            type = if (success) "CalendarEventCreated" else "CalendarWriteFailed",
            captureId = captureId,
            payload = JSONObject()
                .put("card_id", cardId)
                .put("capture_id", captureId ?: JSONObject.NULL)
                .put("event_id", eventId ?: JSONObject.NULL)
                .put("detail", detail ?: JSONObject.NULL),
        )
    }

    internal fun recordCalendarWriteRequested(
        cardId: String, captureId: String?, selection: CalendarWriteSelection,
    ) {
        appendEvent(
            type = "CalendarWriteRequested",
            captureId = captureId,
            payload = JSONObject()
                .put("card_id", cardId)
                .put("capture_id", captureId ?: JSONObject.NULL)
                .put("calendar_id", selection.calendarId)
                .put("start_at", selection.startMillis)
                .put("end_at", selection.endMillis)
                .put("time_zone", selection.timeZone)
                .put("priority", selection.priority.wireName),
        )
    }

    internal fun calendarReceipt(cardId: String, captureId: String?): CalendarReceipt? {
        if (cardId.isBlank()) return null
        val allowLegacy = !captureId.isNullOrBlank() && readableDatabase.query(
            "cards", arrayOf("card_id"), "capture_id = ?", arrayOf(captureId),
            null, null, null,
        ).use { cursor ->
            cursor.count == 1 && cursor.moveToFirst() && cursor.getString(0) == cardId
        }
        val captureFilter = if (captureId.isNullOrBlank()) "capture_id IS NULL" else "capture_id = ?"
        val args = mutableListOf("CalendarEventCreated", "CalendarEventDeleted")
        if (!captureId.isNullOrBlank()) args.add(captureId)
        return readableDatabase.query(
            "events", arrayOf("type", "payload"),
            "type IN (?, ?) AND $captureFilter", args.toTypedArray(),
            null, null, "logical_clock DESC, rowid DESC",
        ).use { cursor ->
            CalendarReceipts.resolve(cardId, sequence {
                while (cursor.moveToNext()) {
                    val payload = JSONObject(cursor.getString(1))
                    yield(CalendarReceipt(
                        cardId = if (payload.isNull("card_id")) null else payload.optString("card_id"),
                        eventId = if (payload.isNull("event_id")) null else payload.optString("event_id"),
                        deleted = cursor.getString(0) == "CalendarEventDeleted",
                    ))
                }
            }, allowLegacy)
        }
    }

    fun recordCalendarDelete(
        cardId: String,
        captureId: String?,
        eventId: String?,
        success: Boolean,
        detail: String? = null,
    ) {
        appendEvent(
            type = if (success) "CalendarEventDeleted" else "CalendarDeleteFailed",
            captureId = captureId,
            payload = JSONObject()
                .put("card_id", cardId)
                .put("capture_id", captureId ?: JSONObject.NULL)
                .put("event_id", eventId ?: JSONObject.NULL)
                .put("detail", detail ?: JSONObject.NULL),
        )
    }

    fun cardForCapture(captureId: String): BufferCard? {
        readableDatabase.query(
            "cards",
            null,
            "capture_id = ?",
            arrayOf(captureId),
            null,
            null,
            "created_at DESC",
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) readCard(cursor) else null
        }
    }

    fun captures(): List<BufferCapture> {
        val result = mutableListOf<BufferCapture>()
        readableDatabase.query("captures", null, null, null, null, null, "created_at DESC").use { cursor ->
            while (cursor.moveToNext()) result += readCapture(cursor)
        }
        return result
    }

    /** Single-task lookups must not materialize the complete capture history. */
    internal fun findCapture(captureId: String): BufferCapture? = readableDatabase.query(
        "captures", null, "capture_id=?", arrayOf(captureId), null, null, null, "1",
    ).use { if (it.moveToFirst()) readCapture(it) else null }

    private fun readCapture(cursor: android.database.Cursor): BufferCapture {
        val id=cursor.getString(cursor.getColumnIndexOrThrow("capture_id"))
        val fenced=BufferSourceLegacyMigration.captureFenced(readableDatabase,id)
        return BufferCapture(
        captureId = id,
        sourceDevice = cursor.getString(cursor.getColumnIndexOrThrow("source_device")),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
        mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")),
        audioFormat = cursor.getString(cursor.getColumnIndexOrThrow("audio_format")),
        durationMs = cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
        blobPath = cursor.getString(cursor.getColumnIndexOrThrow("blob_path")),
        transcript = if(fenced)null else cursor.getString(cursor.getColumnIndexOrThrow("transcript")),
        classification = if(fenced)null else cursor.getString(cursor.getColumnIndexOrThrow("classification")),
    )
    }

    private fun cardsForCapture(db: SQLiteDatabase, captureId: String): List<BufferCard> {
        val result = mutableListOf<BufferCard>()
        db.query(
            "cards",
            null,
            "capture_id = ?",
            arrayOf(captureId),
            null,
            null,
            "created_at ASC, rowid ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result += readCard(cursor)
        }
        return result
    }

    private fun hasUserCardActivity(db: SQLiteDatabase, captureId: String): Boolean {
        val userEventTypes = listOf(
            "CardContentEdited",
            "CardDeleted",
            "ClassificationAccepted",
            "ClassificationEdited",
            "ClassificationRejected",
            "CardActionPerformed",
            "QuestionAnswered",
            "QuestionAnswerRequested",
            "QuestionAnswerDeferred",
            "QuestionAnswerFailed",
            "QuestionResearchNoteAdded",
            "QuestionExternalLinkAdded",
            "QuestionRelatedCardAdded",
            "QuestionRelatedCardRemoved",
            "SparkRelatedCardAdded",
            "SparkRelatedCardRemoved",
            "CardTagsUpdated",
            "SparkDerivedCardCreated",
            "CalendarWriteRequested",
            "CalendarEventCreated",
            "CalendarWriteFailed",
            "CalendarEventDeleted",
            "CalendarDeleteFailed",
            "HealthSyncRequested",
            "HealthDataWritten",
            "HealthSyncFailed",
        )
        val placeholders = userEventTypes.joinToString(",") { "?" }
        val selectionArgs = arrayOf(captureId) + userEventTypes.toTypedArray()
        db.query(
            "events",
            arrayOf("event_id"),
            "capture_id = ? AND type IN ($placeholders)",
            selectionArgs,
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun enqueueClassificationJob(db: SQLiteDatabase, captureId: String, now: Long) {
        db.insertWithOnConflict(
            "classification_jobs",
            null,
            ContentValues().apply {
                put("capture_id", captureId)
                put("state", "pending")
                put("attempts", 0)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun classifyText(captureId: String, text: String, createdAt: Long): BufferCard {
        val lowered = text.lowercase()
        val healthCategory = classifyHealthCategory(text)
        val kind = when {
            text.contains("?") || text.contains("？") ||
                listOf("为什么", "怎么", "如何", "何时", "what", "why", "how").any(lowered::contains) ->
                "question_card"
            listOf("提醒", "记得", "待办", "需要", "明天", "今天", "todo", "remind").any(lowered::contains) ->
                "task_card"
            healthCategory != null -> "care_card"
            listOf(
                "休息", "睡觉", "喝水", "散步", "焦虑", "累", "睡眠", "吃饭", "饮食",
                "运动", "跑步", "走路", "情绪", "症状", "self-care",
            ).any(lowered::contains) ->
                "care_card"
            listOf("会议", "家人", "朋友", "工作", "回家", "生活").any(lowered::contains) ->
                "weekly_card"
            else -> "spark_card"
        }
        val prefix = when (kind) {
            "question_card" -> "问题"
            "task_card" -> "待办"
            "care_card" -> "照顾自己"
            "weekly_card" -> "生活记录"
            else -> "灵感"
        }
        return BufferCard(
            cardId = newId("card"),
            captureId = captureId,
            kind = kind,
            title = "$prefix：${text.takeCodePoints(32)}",
            summary = text.takeCodePoints(160),
            state = ClassificationReview.initialState(kind),
            actions = ClassificationReview.proposalActions(kind, healthCategory.takeUnless { kind == "question_card" }),
            createdAt = createdAt,
            healthCategory = healthCategory.takeUnless { kind == "question_card" },
        )
    }

    private fun classifyHealthCategory(text: String): String? {
        val lowered = text.lowercase()
        return when {
            listOf("吃饭", "吃了", "饮食", "食物", "早餐", "午餐", "晚餐", "零食", "food", "meal")
                .any { text.contains(it) || lowered.contains(it) } -> "nutrition"
            listOf("喝水", "饮水", "ml", "毫升", "升水", "hydration", "water")
                .any { text.contains(it) || lowered.contains(it) } -> "hydration"
            listOf("运动", "跑步", "走路", "散步", "锻炼", "健身", "exercise", "walk", "run")
                .any { text.contains(it) || lowered.contains(it) } -> "exercise"
            listOf("睡觉", "睡眠", "午睡", "失眠", "sleep")
                .any { text.contains(it) || lowered.contains(it) } -> "sleep"
            listOf("情绪", "心情", "焦虑", "emotion", "mood")
                .any { text.contains(it) || lowered.contains(it) } -> "mood"
            listOf("冥想", "呼吸练习", "正念", "mindfulness", "meditation")
                .any { text.contains(it) || lowered.contains(it) } -> "mindfulness"
            listOf("专注", "学习", "复习", "读书", "study", "learning", "focus")
                .any { text.contains(it) || lowered.contains(it) } -> "learning"
            listOf("症状", "疼", "痛", "不舒服", "发烧", "symptom")
                .any { text.contains(it) || lowered.contains(it) } -> "symptom"
            listOf("旅行", "出差", "地点", "到过", "travel", "location")
                .any { text.contains(it) || lowered.contains(it) } -> "travel"
            else -> null
        }
    }

    private fun createSparkDerivedCard(
        source: BufferCard,
        action: String,
        actionId: String?,
    ): BufferCard {
        val spec = when (action) {
            "create_project" -> DerivedCardSpec(
                kind = "project_card",
                titlePrefix = "项目草稿",
                state = "inbox",
                actions = listOf("later", "archive"),
            )
            "create_article" -> DerivedCardSpec(
                kind = "article_card",
                titlePrefix = "文章草稿",
                state = "draft",
                actions = listOf("later", "archive"),
            )
            "create_code_task" -> DerivedCardSpec(
                kind = "task_card",
                titlePrefix = "代码任务",
                state = "inbox",
                actions = listOf("done", "later", "remind", "calendar"),
            )
            "rabbit_hole" -> DerivedCardSpec(
                kind = "rabbit_hole_card",
                titlePrefix = "兔子洞主题",
                state = "rabbit_hole_candidate",
                actions = listOf("later", "archive"),
            )
            else -> throw IllegalArgumentException("unknown Spark derivation: $action")
        }
        val now = System.currentTimeMillis()
        val derived = BufferCard(
            cardId = newId("card"),
            captureId = source.captureId,
            kind = spec.kind,
            title = "${spec.titlePrefix}：${source.title.removePrefix("灵感：")}".take(80),
            summary = source.summary,
            state = spec.state,
            actions = spec.actions,
            createdAt = now,
            classificationConfirmed = true,
            tags = source.tags,
            relatedCardIds = listOf(source.cardId),
        )
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "cards",
                ContentValues().apply {
                    put("state", "converted")
                    put("actions", JSONArray(listOf("archive", "later")).toString())
                },
                "card_id = ?",
                arrayOf(source.cardId),
            )
            insertCard(db, derived)
            appendEvent(
                db,
                "SparkDerivedCardCreated",
                source.captureId,
                now,
                JSONObject()
                    .put("source_card_id", source.cardId)
                    .put("derived_card_id", derived.cardId)
                    .put("target_kind", derived.kind)
                    .put("action", action),
            )
            appendEvent(
                db,
                "CardActionPerformed",
                source.captureId,
                now,
                JSONObject()
                    .put("card_id", source.cardId)
                    .put("action", action)
                    .put("action_id", actionId ?: JSONObject.NULL)
                    .put("old_state", source.state)
                    .put("new_state", "converted"),
            )
            if (actionId != null) {
                db.insertOrThrow("action_receipts", null, ContentValues().apply {
                    put("action_id", actionId)
                    put("card_id", source.cardId)
                    put("action", action)
                    put("resulting_state", "converted")
                    put("created_at", now)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return findCard(source.cardId)
            ?: throw IllegalStateException("source Spark disappeared")
    }

    private fun insertCapture(
        db: SQLiteDatabase,
        captureId: String,
        sourceDevice: String,
        createdAt: Long,
        mode: String,
        audioFormat: String?,
        durationMs: Long,
        blobPath: String?,
        transcript: String?,
        classification: String?,
    ) {
        db.insertOrThrow("captures", null, ContentValues().apply {
            put("capture_id", captureId)
            put("source_device", sourceDevice)
            put("created_at", createdAt)
            put("mode", mode)
            put("audio_format", audioFormat)
            put("duration_ms", durationMs)
            put("blob_path", blobPath)
            put("transcript", transcript)
            put("classification", classification)
        })
    }

    private fun insertCard(db: SQLiteDatabase, card: BufferCard, observeSource: Boolean = true) {
        db.beginTransaction()
        try {
            db.insertOrThrow("cards", null, ContentValues().apply {
                put("card_id", card.cardId)
                put("capture_id", card.captureId)
                put("kind", card.kind)
                put("title", card.title)
                put("summary", card.summary)
                put("state", card.state)
                put("actions", JSONArray(card.actions).toString())
                put("created_at", card.createdAt)
                put("answer", card.answer)
                put("health_category", card.healthCategory)
                put("classification_confirmed", if (card.classificationConfirmed) 1 else 0)
                put("research_notes", card.researchNotes)
                put("external_links", JSONArray(card.externalLinks).toString())
                put("tags", JSONArray(card.tags).toString())
                put("related_card_ids", JSONArray(card.relatedCardIds).toString())
            })
            if (observeSource && (documentObservationDepth.get() ?: 0) == 0) withoutDocumentObservation {
                // A fresh local weekly card has no Capture row to establish
                // provenance. Never reclaim a previously scoped identity.
                if (card.captureId == null && crdtDocument(card.cardId) == null) {
                    check(BufferDocumentSource.audience(db, card.cardId, "local") == "local") { "card identity already scoped" }
                    BufferDocumentSource.claim(db, card.cardId, "local")
                }
                observeCardSource(db, card.cardId)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun appendEvent(type: String, captureId: String?, payload: JSONObject) {
        appendEvent(writableDatabase, type, captureId, System.currentTimeMillis(), payload)
    }

    @Synchronized
    private fun appendEvent(
        db: SQLiteDatabase,
        type: String,
        captureId: String?,
        createdAt: Long,
        payload: JSONObject,
    ) {
        // A nested failure must poison an outer operation too, even if that
        // caller catches the exception. Never commit a fact without its journal.
        db.beginTransaction()
        try {
            appendEventRecord(db, type, captureId, createdAt, payload)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun appendEventRecord(
        db: SQLiteDatabase,
        type: String,
        captureId: String?,
        createdAt: Long,
        payload: JSONObject,
    ) {
        val eventId = newId("evt")
        // Observed remote facts participate in causality even before a card
        // projection is ready. Wall timestamps must not reorder logical history.
        val parentEventId = db.query(
            "source_events",
            arrayOf("event_id"),
            if (captureId == null) null else "capture_id = ?",
            captureId?.let { arrayOf(it) },
            null,
            null,
            "logical_clock DESC, source_id DESC, source_sequence DESC",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val logicalClock = db.rawQuery(
            "SELECT COALESCE(MAX(clock),0)+1 FROM (SELECT MAX(logical_clock) AS clock FROM events " +
                "UNION ALL SELECT MAX(logical_clock) AS clock FROM source_events " +
                "UNION ALL SELECT MAX(logical_clock) AS clock FROM mesh_fact_originals)",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 1L
        }
        db.insertOrThrow("events", null, ContentValues().apply {
            put("event_id", eventId)
            put("app_namespace", BufferConstants.ability)
            put("actor_id", deviceId())
            put("schema_version", EVENT_SCHEMA_VERSION)
            put("parent_event_ids", JSONArray().apply {
                val witness = when (type) {
                    "CaptureBlobDescribed" -> payload.optString("capture_event_id").takeIf { it.isNotBlank() }
                    "SourceTranscriptionAccepted" -> payload.getString("source_event_id")
                    else -> null
                }
                listOfNotNull(parentEventId, witness).distinct().forEach { put(it) }
            }.toString())
            put("logical_clock", logicalClock)
            put("type", type)
            put("capture_id", captureId)
            put("created_at", createdAt)
            put("payload", payload.toString())
        })
        db.query("events", null, "event_id=?", arrayOf(eventId), null, null, null, "1").use { row ->
            check(row.moveToFirst())
            BufferEventJournal.append(db, BufferEventJournal.event(row))
        }
        BufferSourceProjection.localFact(db, type, payload)
        if (captureId != null) BufferLocalAudio.appended(db, eventId, captureId, type, payload)
        if (type == "CaptureCreated" && captureId != null && createdAt in 0..9_007_199_254_740_991L) {
            val source = payload.optString("device_id", payload.optString("source", "android"))
                .replace("\u0000", "").take(96).ifBlank { "unknown" }
            val policy = payload.optJSONObject("context_policy") ?: captureContextPolicy()
            val local = payload.optBoolean("context_local", false) && (source == "android" || payload.opt("local_original") == true)
            appendEvent(db, "CaptureContextRequested", captureId, createdAt, JSONObject()
                .put("source_device", if (local) deviceId() else source)
                .put("local", local).put("policy", JSONObject(policy.toString())))
        }
        if (type == "CaptureContextRequested" && captureId != null) db.insertOrThrow("capture_context_jobs", null, ContentValues().apply {
            put("capture_id", captureId); put("request_event_id", eventId)
        })
        observeCardFact(db, type, payload)
    }

    /** A requested parent is only a scheduling hint. It must be our actual
     * immutable original and pass today's policy before it can be offered. */
    @Synchronized
    internal fun sourceParentToSend(peer: String, id: String, authorize: () -> Boolean): Long? = runtimeSettings.transaction {
        require(peer.matches(Regex("[0-9a-f]{64}")) && id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        check(authorize())
        val local = BufferEventJournal.source(writableDatabase)
        val own = writableDatabase.rawQuery("SELECT 1 FROM source_events WHERE event_id=? AND source_id=?", arrayOf(id, local))
            .use { it.moveToFirst() }
        if (!own) return@transaction null
        val record = BufferEventJournal.localRecord(writableDatabase, id)
        val sequence = record.sequence.takeIf { mayShareSourceEvent(record, peer) }
        check(authorize()); sequence
    }

    /** Storage pages only. The transport must apply visibility policy and live
     * authorization before every fragment; a source cursor is not consent. */
    @Synchronized
    internal fun sourceEventPage(afterSequence: Long, limit: Int = 8, authorize: () -> Boolean): List<String> {
        check(authorize()) { "event send authorization required" }
        return BufferEventJournal.page(readableDatabase, afterSequence, limit).also {
            check(authorize()) { "event send authorization revoked" }
        }
    }

    @Synchronized
    internal fun sourceEventManifest(authorize: () -> Boolean): JSONObject {
        check(authorize()) { "event send authorization required" }
        return JSONObject().put("source_id", BufferEventJournal.source(readableDatabase))
            .put("last_sequence", BufferEventJournal.head(readableDatabase)).also {
                check(authorize()) { "event send authorization revoked" }
            }
    }

    /** Recheck for every fragment; never redact an immutable source envelope.
     * A withheld sequence needs an explicit visibility marker in the transport,
     * not a pretend gap-free receipt. Incoming private history is not forwarded. */
    @Synchronized
    internal fun mayShareSourceEvent(raw: String, peer: String? = null): Boolean {
        return mayShareSourceEvent(BufferSourceEvent.decode(raw), peer)
    }

    @Synchronized
    internal fun mayShareSourceEvent(record: BufferSourceEvent, peer: String? = null): Boolean {
        if (record.source != BufferEventJournal.source(readableDatabase)) return false
        if (record.event.getLong("schema_version") != 1L) return false
        val type = record.event.getString("type")
        val payload = record.event.getJSONObject("payload")
        val captureId = record.event.optString("capture_id")
        if (captureId.isNotBlank()) {
            val source = findCapture(captureId)?.sourceDevice
            if (source != null && VelaPendingCaptureProtocol.isFabricDeviceId(source) && knownVelaControllerView(source)) {
                val view = liveVelaControllerViews[source] ?: return false
                val members = view.getJSONArray("members")
                if (peer == null || (0 until members.length()).none { members.getString(it) == peer }) return false
            }
        }
        if (type.startsWith("MeshMember") || type.startsWith("MeshConfiguration") || type.startsWith("MeshTask")) return false
        if (type == "CardDocumentUnavailable") return false
        if (type in setOf("CardDocumentObserved", "CalendarDocumentObserved", "DocumentMerged") && payload.has("document")) {
            if (peer == null || !peer.matches(Regex("[0-9a-f]{64}"))) return false
            require(payload.keys().asSequence().toSet() == setOf("id", "clock", "document", "audience"))
            val document = payload.getJSONObject("document"); val id = document.getString("id")
            val audience = payload.getString("audience"); require(BufferDocumentSource.validAudience(audience) && payload.get("id") == id)
            val clock = payload.get("clock"); require(clock is Number && clock.toDouble().isFinite() && clock.toDouble() == clock.toLong().toDouble() && clock.toLong() == BufferCrdt.clock(document))
            val current = BufferDocumentSource.audience(readableDatabase, id, "unknown")
            return (audience == "local" || audience == peer) && (current == "local" || current == peer) && sourceDocumentContextAllowed(document)
        }
        if (!type.startsWith("CaptureContext")) return true
        if (type != "CaptureContextCaptured") return false
        val context = CaptureContext.validate(record.event.getJSONObject("payload"))
        val policy = captureContextPolicy()
        return context.getString("source_device") == deviceId() && policy.getBoolean("share") &&
            (context.getJSONObject("location").getString("status") != "available" ||
                (policy.getBoolean("location") && policy.getBoolean("location_granted"))) &&
            (context.getJSONObject("calendar").getString("status") != "available" ||
                (policy.getBoolean("calendar") && policy.getBoolean("calendar_granted")))
    }

    @Synchronized
    internal fun importSourceEvent(peer: String, raw: String, authorize: () -> Boolean): SourceEventImport =
        BufferEventJournal.receive(writableDatabase, peer, raw, authorize)

    @Synchronized
    internal fun sourceEventResume(peer: String, source: String, authorize: () -> Boolean): Long {
        check(authorize()) { "event receive authorization required" }
        return BufferEventJournal.progress(writableDatabase, peer, source, authorize).also {
            check(authorize()) { "event receive authorization revoked" }
        }
    }

    /** Storage entry points for the explicitly negotiated visibility-v1
     * profile. Legacy receive cursors continue to count original bytes only. */
    @Synchronized
    internal fun sourceVisibilityProgress(peer: String, source: String, version: Int, authorize: () -> Boolean): SourceVisibilityProgress {
        require(version == 1)
        return BufferEventVisibility.progress(writableDatabase, peer, source, authorize)
    }

    @Synchronized
    internal fun sourceVisibilityEnabled(peer: String, source: String, authorize: () -> Boolean): Boolean =
        BufferEventVisibility.enabled(writableDatabase, peer, source, authorize)

    @Synchronized
    internal fun sourceVisibilityToken(peer: String, source: String, sequence: Long, authorize: () -> Boolean): String? =
        BufferEventVisibility.token(writableDatabase, peer, source, sequence, authorize)

    @Synchronized
    internal fun reserveSourceVisibility(peer: String, source: String, sequence: Long, token: String, version: Int,
                                         authorize: () -> Boolean): SourceVisibilityImport {
        require(version == 1)
        return BufferEventVisibility.reserve(writableDatabase, peer, source, sequence, token, authorize)
    }

    @Synchronized
    internal fun importVisibleSourceEvent(peer: String, raw: String, token: String, version: Int, authorize: () -> Boolean): SourceVisibilityImport {
        require(version == 1)
        return BufferEventVisibility.receive(writableDatabase, peer, raw, token, authorize)
    }

    @Synchronized
    internal fun nextHeldSourceEvent(peer: String, source: String, afterSequence: Long, version: Int,
                                     authorize: () -> Boolean): SourceVisibilitySlot? {
        require(version == 1)
        return BufferEventVisibility.nextHeld(writableDatabase, peer, source, afterSequence, authorize)
    }

    @Synchronized
    internal fun prepareSourceVisibility(peer: String, raw: String, version: Int, authorize: () -> Boolean): SourceVisibilitySlot {
        require(version == 1)
        return BufferSourceVisibilityOutgoing.prepare(writableDatabase, peer, raw, authorize)
    }

    @Synchronized
    internal fun sourceVisibilitySendEnabled(peer: String, authorize: () -> Boolean): Boolean =
        BufferSourceVisibilityOutgoing.enabled(readableDatabase, peer, authorize)

    @Synchronized
    internal fun sourceVisibilityReserved(peer: String, source: String, sequence: Long, token: String, version: Int, authorize: () -> Boolean) {
        require(version == 1)
        BufferSourceVisibilityOutgoing.reserved(writableDatabase, peer, source, sequence, token, authorize)
    }

    @Synchronized
    internal fun nextSourceVisibilityFill(peer: String, afterSequence: Long, version: Int, authorize: () -> Boolean): SourceVisibilitySlot? {
        require(version == 1)
        return BufferSourceVisibilityOutgoing.nextHeld(writableDatabase, peer, afterSequence, authorize)
    }

    @Synchronized
    internal fun nextSourceBlob(peer: String, now: Long, auditAfter: String?, authorize: () -> Boolean): SourceBlobCandidate? =
        BufferSourceBlobs.next(writableDatabase, peer, now, auditAfter, authorize)

    @Synchronized
    internal fun resumeSourceBlob(peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean): Boolean =
        BufferSourceBlobs.resume(writableDatabase, appContext.filesDir, captureDir, peer, ref, authorize)

    @Synchronized
    internal fun storeSourceBlob(peer: String, ref: SourceBlobTransfer, bytes: ByteArray, authorize: () -> Boolean) =
        BufferSourceBlobs.store(writableDatabase, appContext.filesDir, peer, ref, bytes, authorize)

    @Synchronized
    internal fun confirmSourceBlob(peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean) =
        BufferSourceBlobs.confirm(writableDatabase, appContext.filesDir, peer, ref, authorize)

    @Synchronized
    internal fun deferSourceBlob(peer: String, ref: SourceBlobTransfer, reason: String, now: Long, authorize: () -> Boolean) =
        BufferSourceBlobs.defer(writableDatabase, peer, ref, reason, now, authorize)

    @Synchronized
    internal fun requestSourceBlobCheck(id: String) = BufferSourceBlobs.requestCheck(writableDatabase, id)

    @Synchronized
    internal fun nextSourceParentQuery(peer: String, now: Long, authorize: () -> Boolean): String? =
        BufferSourceParents.next(readableDatabase, peer, now, authorize)

    @Synchronized
    internal fun nextSourceParentFill(peer: String, source: String, before: Long, now: Long,
                                      authorize: () -> Boolean): SourceVisibilitySlot? = runtimeSettings.transaction {
        BufferSourceParents.nextFill(writableDatabase, peer, source, before, now, authorize)
    }

    @Synchronized
    internal fun sourceParentQueryResult(peer: String, id: String, state: String, location: SourceParentLocation?, now: Long,
                                        authorize: () -> Boolean): String =
        BufferSourceParents.result(writableDatabase, peer, id, state, location, now, authorize)

    @Synchronized
    internal fun requestLocalAudioCheck(capture: String) = BufferLocalAudio.requestCheck(writableDatabase, capture)

    @Synchronized
    internal fun nextOutgoingSourceBlob(peer: String, now: Long, auditAfter: String?, authorize: () -> Boolean): SourceBlobCandidate? =
        BufferSourceBlobOutgoing.next(writableDatabase, peer, now, auditAfter, authorize)

    @Synchronized
    internal fun hasOutgoingSourceBlobAudit(peer: String, after: String, authorize: () -> Boolean): Boolean =
        BufferSourceBlobOutgoing.auditPending(readableDatabase, peer, after, authorize)

    @Synchronized
    internal fun prepareOutgoingSourceBlob(peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean) =
        BufferSourceBlobOutgoing.prepare(writableDatabase, peer, ref, authorize)

    @Synchronized
    internal fun mayShareOutgoingSourceBlob(peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean): Boolean =
        mayShareSourceEvent(BufferSourceBlobOutgoing.validate(writableDatabase, peer, ref, authorize), peer).also { check(authorize()) }

    @Synchronized
    internal fun readOutgoingSourceBlob(peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean): ByteArray {
        val record = BufferSourceBlobOutgoing.validate(writableDatabase, peer, ref, authorize)
        check(mayShareSourceEvent(record, peer))
        val bytes = BufferSourceBlobs.readOriginalArchive(appContext.filesDir, record)
        try { check(authorize() && mayShareSourceEvent(record, peer)); return bytes }
        catch (error: Throwable) { bytes.fill(0); throw error }
    }

    @Synchronized
    internal fun outgoingSourceBlobDelivered(peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean) =
        BufferSourceBlobOutgoing.confirm(writableDatabase, peer, ref, authorize)

    @Synchronized
    internal fun deferOutgoingSourceBlob(peer: String, ref: SourceBlobTransfer, reason: String, now: Long, authorize: () -> Boolean) =
        BufferSourceBlobOutgoing.defer(writableDatabase, peer, ref, reason, now, authorize)

    @Synchronized
    internal fun reconcileLocalAudio(): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val audio = BufferLocalAudio.reconcile(db, appContext.filesDir, captureDir, now) { capture, payload ->
            appendEvent(db, "CaptureBlobDescribed", capture, now, payload)
        }
        val exports = BufferSourceBlobOutgoing.migrate(db)
        return audio || exports
    }

    @Synchronized
    internal fun reconcileSourceHistory(limit: Int = 8): Boolean = BufferEventHistory.reconcile(writableDatabase, limit)

    @Synchronized
    internal fun reconcileSourceProjections(limit: Int = 8): Boolean {
        // Metadata errors may also arise inside migration/undo. End the failed
        // SQLite transaction before recording their recovery, so nested-failure
        // semantics cannot roll the archive back along with the bad projection.
        check(!writableDatabase.inTransaction())
        try {
            return runtimeSettings.transaction {
                if (!BufferSourceEffects.undoing(writableDatabase) && !BufferSourceLegacyMigration.active(writableDatabase))
                    BufferSourceEffects.inspectMetadata(writableDatabase, sourceMetadataAfter)
                sourceBlobDescriptorAfter = BufferSourceBlobs.reconcileDescriptors(writableDatabase, sourceBlobDescriptorAfter)
                val more = BufferSourceProjection.reconcile(writableDatabase, limit, ::projectSourceCrdt, crdtActor()) { native ->
                    applyMergedCrdt(null, native, recordHistory = false)
                }
                val native = if (BufferSourceEffects.undoing(writableDatabase) || BufferSourceLegacyMigration.active(writableDatabase)) false else repairNativeSourceCards()
                more || native
            }
        } catch (error: BufferSourceMetadata.Damaged) {
            return runtimeSettings.transaction { BufferSourceMetadata.recover(writableDatabase, error); true }
        }
    }

    private var sourceBlobDescriptorAfter = ""
    private val sourceMetadataAfter = LongArray(7)
    private var nativeSourceRepairAfter = ""
    /** A source rollback may leave an independently edited row awaiting its
     * full document. Re-materialize only an exact, verified native checkpoint;
     * a retained preview or a foreign register cannot establish this anchor. */
    private fun repairNativeSourceCards(): Boolean {
        val db = writableDatabase; check(db.inTransaction())
        val ids = db.rawQuery("SELECT card_id FROM cards WHERE state='source_unavailable' AND card_id>? ORDER BY card_id LIMIT 8",
            arrayOf(nativeSourceRepairAfter)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        if (ids.isEmpty()) { nativeSourceRepairAfter = ""; return false }
        for (id in ids) {
            nativeSourceRepairAfter = id
            val document = BufferSourceEffects.currentDocument(db, id) ?: continue
            if (runCatching { BufferCrdtView.validate(document) }.isFailure || document.getString("kind") != "card") continue
            val actor = crdtActor(); val fields = document.getJSONObject("fields")
            if (fields.keys().asSequence().any { fields.getJSONObject(it).getString("actor") != actor }) continue
            val event = db.rawQuery("SELECT event_id FROM source_document_observations WHERE document_id=?", arrayOf(id))
                .use { if (it.moveToFirst()) it.getString(0) else null } ?: continue
            val original = BufferEventJournal.localRecord(db, event)
            if (original.envelope.getString("provenance") != "native" || original.event.getString("type") !in
                setOf("CardDocumentObserved", "DocumentMerged")) continue
            val payload = original.event.getJSONObject("payload")
            if (payload.optString("id") != id || BufferCrdt.canonical(payload.getJSONObject("document")) != BufferCrdt.canonical(document)) continue
            val linked = db.rawQuery("SELECT 1 FROM source_event_history WHERE event_id=? AND fingerprint=? AND state='linked'",
                arrayOf(event, original.fingerprint)).use { it.moveToFirst() }
            if (!linked) continue
            if (BufferSourceProjection.locallyDeleted(db, id)) document.put("deleted", true)
            applyMergedCrdt(document, document, recordHistory = false)
        }
        return ids.size == 8
    }

    @Synchronized
    internal fun sourceObservations(cardId: String? = null, before: String? = null): SourceObservationPage =
        BufferSourceObservations.page(readableDatabase, cardId, before)

    @Synchronized
    internal fun requestSourceHistoryRebuild() = BufferEventHistory.requestRebuild(writableDatabase)

    @Synchronized
    internal fun sourceHistory(captureId: String, before: com.mocharealm.foundation.fabric.buffer.domain.BufferSourceHistoryCursor? = null):
        com.mocharealm.foundation.fabric.buffer.domain.BufferSourceHistoryPage = BufferEventHistory.page(readableDatabase, captureId, before)

    @Synchronized
    internal fun sourceJournal(before: com.mocharealm.foundation.fabric.buffer.domain.BufferSourceHistoryCursor? = null):
        com.mocharealm.foundation.fabric.buffer.domain.BufferSourceHistoryPage = BufferEventHistory.page(readableDatabase,null,before)

    @Synchronized
    internal fun sourceOriginal(eventId: String,offset: Int = 0): com.mocharealm.foundation.fabric.buffer.domain.BufferSourceOriginalPage =
        BufferEventHistory.originalPage(readableDatabase,eventId,offset)

    @Synchronized
    internal fun receivedSourceEvent(peer: String, source: String, sequence: Long, authorize: () -> Boolean): BufferSourceEvent =
        BufferEventJournal.received(writableDatabase, peer, source, sequence, authorize)

    @Synchronized
    internal fun sourceEventDelivered(peer: String, raw: String, authorize: () -> Boolean) =
        BufferEventJournal.delivered(writableDatabase, peer, raw, authorize)

    @Synchronized
    internal fun sourceEventWithoutReceipt(peer: String, after: Long, authorize: () -> Boolean): Long? {
        check(authorize()) { "event send authorization required" }
        return BufferEventJournal.nextUndelivered(readableDatabase, peer, after).also {
            check(authorize()) { "event send authorization revoked" }
        }
    }

    override fun findCard(cardId: String): BufferCard? {
        if (BufferSourceEffects.fenced(readableDatabase, cardId)) return null
        readableDatabase.query("cards", null, "card_id = ?", arrayOf(cardId), null, null, null, "1").use { cursor ->
            return if (cursor.moveToFirst() && cursor.getString(cursor.getColumnIndexOrThrow("state")) != "source_unavailable") readCard(cursor) else null
        }
    }

    private fun actionReceipt(actionId: String): ActionReceipt? {
        readableDatabase.query(
            "action_receipts",
            arrayOf("card_id", "action", "resulting_state"),
            "action_id = ?",
            arrayOf(actionId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ActionReceipt(
                cardId = cursor.getString(0),
                action = cursor.getString(1),
                resultingState = cursor.getString(2),
            )
        }
    }

    private fun modeReceipt(modeId: String): String? {
        readableDatabase.query(
            "mode_receipts",
            arrayOf("mode"),
            "mode_id = ?",
            arrayOf(modeId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun readCard(cursor: android.database.Cursor): BufferCard {
        val identity = cursor.getString(cursor.getColumnIndexOrThrow("card_id"))
        if (cursor.getString(cursor.getColumnIndexOrThrow("state")) == "source_unavailable" || BufferSourceEffects.fenced(readableDatabase, identity)) return BufferCard(
            identity, cursor.getString(cursor.getColumnIndexOrThrow("capture_id")), ClassificationReview.UNCLASSIFIED,
            "来源记录正在核对", "本机修改已保留。", "source_unavailable", emptyList(), cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
        val storedActions = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("actions")))
        val kind = cursor.getString(cursor.getColumnIndexOrThrow("kind"))
        val state = cursor.getString(cursor.getColumnIndexOrThrow("state"))
        val storedActionNames = buildList {
            for (index in 0 until storedActions.length()) {
                val action = storedActions.getString(index)
                if (kind != "question_card" || action != "answer") add(action)
            }
        }
        val actions = if (kind == "spark_card" && state == "cooling") {
            (storedActionNames + SPARK_EXTRA_ACTIONS).distinct()
        } else if (kind == "question_card" &&
            state !in setOf("archived", "done", "researched")
        ) {
            (storedActionNames + "mark_researched").distinct()
        } else {
            storedActionNames
        }
        val externalLinks = runCatching {
            val raw = cursor.getString(cursor.getColumnIndexOrThrow("external_links"))
            val array = JSONArray(raw ?: "[]")
            buildList { for (index in 0 until array.length()) add(array.getString(index)) }
        }.getOrDefault(emptyList())
        val tags = readStringArray(cursor, "tags")
        val relatedCardIds = readStringArray(cursor, "related_card_ids")
        return ClassificationReview.normalizeActions(BufferCard(
            cardId = cursor.getString(cursor.getColumnIndexOrThrow("card_id")),
            captureId = cursor.getString(cursor.getColumnIndexOrThrow("capture_id")),
            kind = kind,
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
            state = state,
            actions = actions,
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            answer = cursor.getString(cursor.getColumnIndexOrThrow("answer")),
            healthCategory = cursor.getString(cursor.getColumnIndexOrThrow("health_category")),
            classificationConfirmed = cursor.getInt(
                cursor.getColumnIndexOrThrow("classification_confirmed"),
            ) != 0,
            researchNotes = cursor.getString(cursor.getColumnIndexOrThrow("research_notes")),
            externalLinks = externalLinks,
            tags = tags,
            relatedCardIds = relatedCardIds,
        ))
    }

    private fun readStringArray(cursor: android.database.Cursor, column: String): List<String> = runCatching {
        val array = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow(column)) ?: "[]")
        buildList { for (index in 0 until array.length()) add(array.getString(index)) }
    }.getOrDefault(emptyList())

    private fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    companion object {
        private val databaseOpenLock = Any()
        private const val DATABASE_NAME = "buffer.db"
        private const val DATABASE_VERSION = 64
        private const val EVENT_SCHEMA_VERSION = 1
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MODE = "mode"
        private const val KEY_BEDTIME_SCHEDULE = "bedtime_schedule"
        private const val KEY_BEDTIME_PENDING_DAY = "bedtime_pending_day"
        private const val KEY_BEDTIME_RETRY_AT = "bedtime_retry_at"
        private const val KEY_BEDTIME_SCHEDULED_FOR = "bedtime_scheduled_for"
        private const val KEY_BEDTIME_LAST_TRIGGERED_DAY = "bedtime_last_triggered_day"
        private const val KEY_FOCUS_INTERVAL_MS = "focus_interval_ms"
        private const val KEY_FOCUS_SNOOZE_MS = "focus_snooze_ms"
        private const val KEY_FOCUS_PENDING_NOTIFICATION = "focus_pending_notification"
        private const val KEY_FOCUS_REVISION = "focus_revision"
        private const val KEY_FOCUS_CLEAR_NOTICE = "focus_clear_notice"
        private const val KEY_FOCUS_STARTED_AT = "focus_started_at"
        private const val KEY_FOCUS_NEXT_AT = "focus_next_at"
        private const val CLASSIFICATION_RETRY_DELAY_MS = 30 * 1000L
        private const val CLASSIFICATION_LEASE_MS = 10 * 60 * 1000L
        private const val MAX_RESEARCH_NOTE_LENGTH = 4_000
        private const val MAX_RESEARCH_TOTAL_LENGTH = 16_000
        private const val MAX_RESEARCH_LINK_LENGTH = 2_048
        private const val MAX_RESEARCH_LINK_COUNT = 20
        private const val MAX_TAG_COUNT = 20
        private const val MAX_TAG_LENGTH = 48
        private const val MAX_RELATED_CARD_COUNT = 20
        private val SPARK_DERIVED_ACTIONS = setOf(
            "create_project",
            "create_article",
            "create_code_task",
            "rabbit_hole",
        )
        private val SPARK_EXTRA_ACTIONS = SPARK_DERIVED_ACTIONS + "ferment"
        private const val RETRY_AUDIO_SUFFIX = ".retry.m4a"
        private const val RETRY_METADATA_SUFFIX = ".retry.json"
        private val MODES = setOf("normal", "focus", "bedtime", "rabbit_hole")
        private val MIXED_INPUT_MARKERS = listOf(
            "明天",
            "今天",
            "后天",
            "记得",
            "提醒我",
            "待办",
            "需要",
            "todo",
            "remind",
        )
    }

    private data class ActionReceipt(
        val cardId: String,
        val action: String,
        val resultingState: String,
    )

    private data class DerivedCardSpec(
        val kind: String,
        val titlePrefix: String,
        val state: String,
        val actions: List<String>,
    )
}
