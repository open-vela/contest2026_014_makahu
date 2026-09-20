package com.mocharealm.foundation.fabric.buffer.data.local
import com.mocharealm.foundation.fabric.buffer.domain.CardLookup
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmKey
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.data.llm.WeeklyLlmJob
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStore
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
    internal val velaEnrollments = VelaEnrollmentStore({ writableDatabase }) { db, id, requestId ->
        appendEvent(db, "PeerPaired", null, System.currentTimeMillis(),
            JSONObject().put("device_id", id).put("enrollment_id", requestId))
    }

    @Volatile private var openedDatabase: SQLiteDatabase? = null

    // Serialize schema setup across helpers. Already-open connections bypass
    // this lock so a transaction can finish while another helper is opening.
    override fun getWritableDatabase(): SQLiteDatabase =
        openedDatabase?.takeIf { it.isOpen && !it.isReadOnly } ?: synchronized(databaseOpenLock) {
            super.getWritableDatabase().also { openedDatabase = it }
        }

    override fun getReadableDatabase(): SQLiteDatabase =
        openedDatabase?.takeIf { it.isOpen } ?: synchronized(databaseOpenLock) {
            super.getReadableDatabase().also { openedDatabase = it }
        }

    override fun onCreate(db: SQLiteDatabase) {
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
        db.execSQL("CREATE INDEX events_created_at ON events(created_at)")
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
    }

    private fun createCardReminders(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS card_reminders (
            card_id TEXT PRIMARY KEY, due_at INTEGER NOT NULL, revision TEXT NOT NULL,
            delivered INTEGER NOT NULL DEFAULT 0, cancelled INTEGER NOT NULL DEFAULT 0,
            retry_at INTEGER NOT NULL DEFAULT 0)""")
    }

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

    internal fun reconcileCardReturns(now: Long): Long? {
        val db = writableDatabase
        db.beginTransaction()
        try {
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
        if (value == bedtimeSchedule()) return@transaction
        runtimeSettings.edit().apply {
            if (value.isBlank()) remove(KEY_BEDTIME_SCHEDULE) else putString(KEY_BEDTIME_SCHEDULE, value)
            remove(KEY_BEDTIME_PENDING_DAY)
            remove(KEY_BEDTIME_RETRY_AT)
            remove(KEY_BEDTIME_SCHEDULED_FOR)
        }.apply()
    }

    @Synchronized
    internal fun reconcileBedtime(now: Long): Long? = runtimeSettings.transaction {
        val previous = BedtimeState(
            runtimeSettings.getLong(KEY_BEDTIME_PENDING_DAY, 0L),
            runtimeSettings.getLong(KEY_BEDTIME_RETRY_AT, 0L),
            runtimeSettings.getLong(KEY_BEDTIME_SCHEDULED_FOR, 0L),
        )
        val decision = BedtimePlanner.plan(
            bedtimeSchedule(), now, mode() == "focus",
            runtimeSettings.getLong(KEY_BEDTIME_LAST_TRIGGERED_DAY, 0L), previous,
        )
        decision.triggerDay?.let { day -> setMode("bedtime", "auto-bedtime-$day") }
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
    internal fun startRabbitSession(cardId: String, minutes: Int, now: Long = System.currentTimeMillis()): RabbitHoleSession = runtimeSettings.transaction {
        require(minutes in setOf(60, 90, 120)) { "请选择 60、90 或 120 分钟" }
        require(now > 0 && now <= Long.MAX_VALUE - minutes * 60_000L)
        check(rabbitSession()?.active != true) { "请先结束当前研究" }
        val card = requireNotNull(findCard(cardId)) { "主题已不存在" }
        require(card.kind in setOf("question_card", "spark_card", "rabbit_hole_card") &&
            card.state !in setOf("done", "archived")) { "请选择尚未完成的研究主题" }
        val session = RabbitHoleSession(newId("research"), cardId, card.title, now, now + minutes * 60_000L)
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
            "SELECT kind, COUNT(*) FROM cards WHERE created_at >= ? AND created_at <= ? AND state != 'pending_transcription' " +
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
                "AND state NOT IN ('archived', 'done', 'researched', 'quick_answered') " +
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
            "SELECT COUNT(*) FROM cards WHERE created_at >= ? AND created_at <= ? AND (kind = ? OR health_category IS NOT NULL)",
            arrayOf(since.toString(), now.toString(), "care_card"),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val candidates = mutableListOf<BufferCard>()
        readableDatabase.query(
            "cards",
            null,
            "created_at >= ? AND created_at <= ? AND ((kind = ? AND state NOT IN (?, ?, ?, ?) " +
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
        readableDatabase.query("cards", null, "created_at >= ? AND created_at <= ?",
            arrayOf(since.toString(), now.toString()), null, null, "created_at ASC, card_id ASC").use { cursor ->
            while (cursor.moveToNext()) recentCards += readCard(cursor)
        }
        val openTasks = mutableListOf<BufferCard>()
        readableDatabase.query("cards", null, "kind = ? AND created_at <= ? AND state NOT IN ('done', 'archived')",
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

    fun setWeeklyAutoEnabled(enabled: Boolean) = runtimeSettings.transaction {
        if (weeklyAutoEnabled() == enabled) return@transaction
        runtimeSettings.edit().putBoolean("weekly_auto_enabled", enabled)
            .remove("weekly_auto_next").remove("weekly_auto_zone").apply()
        appendEvent(writableDatabase, "WeeklyScheduleChanged", null, System.currentTimeMillis(),
            JSONObject().put("enabled", enabled).put("local_schedule", "Sunday 20:00"))
    }

    internal fun reconcileWeekly(now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? = runtimeSettings.transaction {
        if (!weeklyAutoEnabled()) return@transaction null
        val next = WeeklySchedule.next(now, zone)
        val saved = runtimeSettings.getLong("weekly_auto_next", 0L)
        val savedZone = runtimeSettings.getString("weekly_auto_zone", "")
        if (saved > 0L && savedZone == zone.id && now >= saved) {
            // After a long absence generate only the latest due report, not a backlog.
            generateWeeklySummary(WeeklySchedule.latest(now, zone))
        }
        val target = if (saved > now && savedZone == zone.id) minOf(saved, next) else next
        runtimeSettings.edit().putLong("weekly_auto_next", target).putString("weekly_auto_zone", zone.id).apply()
        target
    }

    /** Enqueues a durable LLM request. Never creates a rule-generated summary card. */
    fun generateWeeklySummary(now: Long = System.currentTimeMillis()): String = runtimeSettings.transaction {
        val id = "weekly-${weekStartMillis(now)}"
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        val existing = jobs.optJSONObject(id)
        if (existing != null) {
            if (existing.optString("state") == "failed") {
                existing.put("retry_at", 0L).put("state", "pending")
                runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
            }
            return@transaction id
        }
        val facts = weeklyLlmFacts(now)
        jobs.put(id, JSONObject().put("id", id).put("requested_at", now).put("state", "pending")
            .put("facts", facts).put("attempts", 0).put("retry_at", 0L))
        runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
        appendEvent(writableDatabase, "WeeklySummaryRequested", null, now, JSONObject().put("job_id", id)
            .put("period_start", now - 7L * 86_400_000).put("period_end", now))
        id
    }

    internal fun weeklyLlmFacts(now: Long): JSONObject {
        val since = now - 7L * 86_400_000
        val records = JSONArray()
        var count = 0
        readableDatabase.query("cards", null,
            "created_at >= ? AND created_at <= ? AND kind != 'weekly_card' AND state != 'pending_transcription' " +
                "AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.capture_id = cards.capture_id AND c.blob_path IS NOT NULL AND (c.transcript IS NULL OR TRIM(c.transcript) = ''))",
            arrayOf(since.toString(), now.toString()), null, null, "created_at DESC").use { cursor ->
            while (cursor.moveToNext()) {
                count++
                if (records.length() >= 100) continue
                val card = readCard(cursor)
                records.put(JSONObject().put("id", card.cardId).put("kind", card.kind)
                    .put("title", card.title.takeCodePoints(80)).put("text", card.summary.takeCodePoints(500))
                    .put("created_at", card.createdAt).put("state", card.state)
                    .put("classification_confirmed", card.classificationConfirmed)
                    .put("tags", JSONArray(card.tags)).put("health_category", card.healthCategory ?: JSONObject.NULL))
            }
        }
        val facts = weeklySummary(now)
        return JSONObject().put("task", "weekly_review").put("period_start", since).put("period_end", now)
            .put("records", records).put("omitted_record_count", count - records.length())
            .put("statistics", JSONObject().put("sparks", facts.sparks).put("unresolved_questions", facts.questions)
                .put("completed_tasks", facts.tasksCompleted).put("care_records", facts.careRecords))
            .put("deferred_tasks", JSONArray(facts.deferredTasks.map { JSONObject().put("card_id", it.card.cardId).put("times", it.times) }))
    }

    internal fun weeklyLlmStatus(now: Long = System.currentTimeMillis()): JSONObject? =
        JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!).optJSONObject("weekly-${weekStartMillis(now)}")

    internal fun claimWeeklyLlm(now: Long): WeeklyLlmJob? = runtimeSettings.transaction {
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        for (id in jobs.keys()) {
            val job = jobs.getJSONObject(id)
            val state = job.optString("state")
            if (state == "complete" || (state == "running" && job.optLong("lease_until") > now) || job.optLong("retry_at") > now) continue
            val lease = UUID.randomUUID().toString()
            job.put("state", "running").put("lease", lease).put("lease_until", now + 180_000)
                .put("attempts", job.optInt("attempts") + 1)
            runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
            return@transaction WeeklyLlmJob(id, lease, job.getLong("requested_at"), job.getJSONObject("facts"))
        }
        null
    }

    internal fun completeWeeklyLlm(job: WeeklyLlmJob, result: JSONObject, model: String): Boolean = runtimeSettings.transaction {
        val title = result.opt("title")
        val summary = result.opt("summary")
        require(title is String && title.isNotBlank() && title.length <= 160) { "周总结标题无效" }
        require(summary is String && summary.isNotBlank() && summary.length <= 4000) { "周总结正文无效" }
        val jobs = JSONObject(runtimeSettings.getString("weekly_llm_jobs", "{}")!!)
        val stored = jobs.optJSONObject(job.id) ?: return@transaction false
        if (stored.optString("state") != "running" || stored.optString("lease") != job.lease) return@transaction false
        // A distinct card id separates old rule-based weekly cards from LLM output.
        val cardId = "${job.id}-llm"
        val card = BufferCard(cardId, null, "weekly_card", title.trim(), summary.trim(), "logged",
            listOf("archive", "later"), job.requestedAt, classificationConfirmed = true, tags = listOf("weekly", "llm"))
        insertCard(writableDatabase, card)
        appendEvent(writableDatabase, "WeeklySummaryGenerated", null, System.currentTimeMillis(), JSONObject()
            .put("job_id", job.id).put("card_id", cardId).put("source", "llm").put("model", model)
            .put("period_start", job.facts.getLong("period_start")).put("period_end", job.requestedAt))
        stored.put("state", "complete").put("card_id", cardId).remove("error")
        runtimeSettings.edit().putString("weekly_llm_jobs", jobs.toString()).apply()
        true
    }

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

    @Synchronized
    fun createAudioCapture(
        captureId: String,
        sourceDevice: String,
        createdAt: Long,
        mode: String,
        audioFormat: String,
        durationMs: Long,
        audio: ByteArray,
    ): BufferCard {
        require(captureId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid capture id" }
        require(mode in MODES) { "unknown Buffer mode: $mode" }
        require(audio.size <= BufferConstants.maxAudioBytes) { "audio payload is too large" }

        val pendingMetadata = File(captureDir, "$captureId.pending.json")
        var successful = false
        val db = writableDatabase
        db.beginTransaction()
        try {
            cardForCapture(captureId)?.let {
                db.setTransactionSuccessful()
                successful = true
                return it
            }
            val destination = File(captureDir, "$captureId.bin")
            if (destination.exists()) {
                // A prior database failure may leave the committed audio behind.
                check(destination.length() == audio.size.toLong() && destination.readBytes().contentEquals(audio)) {
                    "capture id already has different audio; original file retained"
                }
            }
            val metadata = JSONObject()
                .put("capture_id", captureId).put("source_device", sourceDevice)
                .put("created_at", createdAt).put("mode", mode)
                .put("audio_format", audioFormat).put("duration_ms", durationMs)
            if (pendingMetadata.exists()) {
                check(pendingMetadata.length() <= 16384) { "invalid audio metadata size" }
                val saved = JSONObject(pendingMetadata.readText())
                check(metadata.keys().asSequence().all { saved.opt(it)?.toString() == metadata.opt(it)?.toString() }) {
                    "capture id already has different metadata; original files retained"
                }
            } else {
                val bytes = metadata.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 16384) { "audio metadata is too large" }
                RecoveryFiles.save(pendingMetadata, bytes)
            }
            RecoveryFiles.save(destination, audio)

            val card = BufferCard(
                cardId = newId("card"),
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
                .put("blob_ref", destination.name))
            appendEvent(db, "CaptureTranscriptionRequested", captureId, createdAt,
                JSONObject().put("capture_id", captureId))
            insertCard(db, card)
            db.setTransactionSuccessful()
            successful = true
            return card
        } finally {
            // Delete recovery metadata only after the database commits.
            db.endTransaction()
            if (successful) pendingMetadata.delete()
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
            val capture = captures().firstOrNull { it.captureId == captureId }
                ?: throw IllegalArgumentException("unknown capture: $captureId")
            val existingCard = cardForCapture(captureId)
                ?: throw IllegalArgumentException("capture has no card: $captureId")
            if (!capture.transcript.isNullOrBlank()) {
                // The background transcription worker and the manual fallback can
                // finish at the same time.  The first committed transcript owns
                // the capture; a retry must not create another card set.
                return existingCard
            }
            val segments = splitTextIntoSegments(cleanTranscript)
            val classified = segments.map { segment ->
                classifyText(captureId, segment, capture.createdAt)
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
    ) {
        require(captureId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid capture id" }
        require(mode in MODES) { "unknown Buffer mode: $mode" }
        require(sourceFile.isFile) { "audio source file is missing" }
        require(sourceFile.length() <= BufferConstants.maxAudioBytes) {
            "audio payload is too large"
        }

        val metadata = JSONObject()
            .put("capture_id", captureId).put("source_device", sourceDevice)
            .put("created_at", createdAt).put("mode", mode)
            .put("audio_format", audioFormat).put("duration_ms", durationMs)
            .toString().toByteArray(Charsets.UTF_8)
        require(metadata.size <= 16384) { "audio metadata is too large" }
        val audio = sourceFile.readBytes()
        require(audio.size <= BufferConstants.maxAudioBytes) { "audio payload is too large" }
        // Metadata goes first so a completed backup is always discoverable.
        // Leave any committed component intact when the next write fails.
        RecoveryFiles.save(File(captureDir, "$captureId$RETRY_METADATA_SUFFIX"), metadata)
        RecoveryFiles.save(File(captureDir, "$captureId$RETRY_AUDIO_SUFFIX"), audio)
    }

    @Synchronized
    fun recoverPendingAudio(): Int {
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
                val metadata = JSONObject(metadataFile.readText())
                check(metadata.getString("capture_id") == captureId) { "recovery capture id mismatch" }
                createAudioCapture(
                    captureId = metadata.getString("capture_id"),
                    sourceDevice = metadata.getString("source_device"),
                    createdAt = metadata.getLong("created_at"),
                    mode = metadata.getString("mode"),
                    audioFormat = metadata.getString("audio_format"),
                    durationMs = metadata.getLong("duration_ms"),
                    audio = retryFile.readBytes(),
                )
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

    fun pendingClassificationJobs(
        limit: Int = 4,
        now: Long = System.currentTimeMillis(),
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
            limit.coerceIn(1, 20).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BufferClassificationJob(
                    captureId = cursor.getString(0),
                    transcript = cursor.getString(1),
                )
            }
        }
        return result
    }

    @Synchronized
    internal fun claimClassificationRequest(captureId: String, now: Long = System.currentTimeMillis()): String? =
        runtimeSettings.transaction {
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
                if (type == "ClassificationProposed" && payload.optString("source") != "phone_llm") continue
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

    /** Applies coordinator output as proposals; it never accepts a proposal. */
    @Synchronized
    fun applyRemoteClassification(
        captureId: String,
        proposals: List<BufferClassificationProposal>,
        lease: String? = null,
    ) {
        require(proposals.isNotEmpty()) { "classification proposal is empty" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (lease != null && !ownsClassificationLease(db, captureId, lease)) {
                db.setTransactionSuccessful()
                return
            }

            val captureCreatedAt = db.query(
                "captures",
                arrayOf("created_at"),
                "capture_id = ?",
                arrayOf(captureId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            } ?: throw IllegalArgumentException("unknown capture: $captureId")
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
                    val targetId = existing.getOrNull(index)?.cardId ?: newId("card")
                    val relatedIds = proposal.relatedCardIds.distinct().filter { id ->
                        id != targetId && findCard(id)?.let { it.state != "pending_transcription" && it.captureId != captureId } == true
                    }
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
                            .put("presentation", proposal.presentation ?: JSONObject.NULL),
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
                    },
                    "capture_id = ?",
                    arrayOf(captureId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun cards(limit: Int = 5): List<BufferCard> {
        return queryCards(
            selection = "state NOT IN (?, ?)",
            selectionArgs = arrayOf("archived", "done"),
            orderBy = "created_at DESC",
            limit = limit,
        )
    }

    /** Shared small display deck for Vela and the phone's bedtime home screen. */
    fun cardsForMode(mode: String, limit: Int = 5): List<BufferCard> {
        if (mode == "bedtime") return bedtimeCards(limit)
        val active = "state NOT IN (?, ?)"
        val (selection, args, orderBy) = when (mode) {
            "focus" -> Triple(
                "$active AND kind IN (?, ?)",
                arrayOf("archived", "done", "care_card", "task_card"),
                "CASE WHEN kind = 'care_card' THEN 0 ELSE 1 END, created_at DESC",
            )
            "rabbit_hole" -> Triple(
                "$active AND kind IN (?, ?, ?)",
                arrayOf("archived", "done", "question_card", "spark_card", "rabbit_hole_card"),
                "CASE WHEN state = 'rabbit_hole_candidate' THEN 0 ELSE 1 END, created_at DESC",
            )
            else -> Triple(active, arrayOf("archived", "done"), "created_at DESC")
        }
        return queryCards(selection, args, orderBy, limit)
    }

    private fun bedtimeCards(limit: Int): List<BufferCard> {
        val result = mutableListOf<BufferCard>()
        val maximum = limit.coerceIn(1, 5)
        readableDatabase.query(
            "cards", null, "state NOT IN (?, ?) AND (state != 'later' OR card_id NOT IN (SELECT card_id FROM card_returns WHERE due_at IS NOT NULL))", arrayOf("archived", "done"),
            null, null, "COALESCE((SELECT returned_at FROM card_returns WHERE card_returns.card_id = cards.card_id), 0) DESC, CASE WHEN kind = 'care_card' THEN 0 ELSE 1 END, created_at DESC",
        ).use { cursor ->
            while (result.size < maximum && cursor.moveToNext()) {
                BedtimeDeck.project(readCard(cursor))?.let(result::add)
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
            "($selection) AND (state != 'later' OR card_id NOT IN (SELECT card_id FROM card_returns WHERE due_at IS NOT NULL))",
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

    internal fun searchLocalRecords(query: String): List<BufferCard> {
        require(query.length in 1..120)
        val result = mutableListOf<BufferCard>()
        readableDatabase.query("cards", null,
            "state != 'pending_transcription' AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.capture_id = cards.capture_id AND c.blob_path IS NOT NULL AND (c.transcript IS NULL OR TRIM(c.transcript) = '')) " +
                "AND (instr(lower(title), lower(?)) > 0 OR instr(lower(summary), lower(?)) > 0 OR instr(lower(COALESCE(research_notes, '')), lower(?)) > 0 OR instr(lower(tags), lower(?)) > 0)",
            arrayOf(query, query, query, query), null, null, "created_at DESC", "12").use {
            while (it.moveToNext()) result += readCard(it)
        }
        return result
    }

    internal fun cardPresentation(cardId: String): String? = runtimeSettings.getString("card_presentation:$cardId", null)

    fun shelfCards(limit: Int = 100): List<BufferCard> = cardsByKind("spark_card", limit)

    fun answerBoxCards(limit: Int = 100): List<BufferCard> =
        cardsByKind("question_card", limit)

    fun lifeLogCards(limit: Int = 100): List<BufferCard> {
        val result = mutableListOf<BufferCard>()
        readableDatabase.query(
            "cards",
            null,
            "(health_category IS NOT NULL OR kind = ?) AND state != 'pending_transcription' " +
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
            "kind IN ($placeholders) AND state != 'pending_transcription' " +
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
                if (remaining != card.relatedCardIds) db.update("cards", ContentValues().apply {
                    put("related_card_ids", JSONArray(remaining).toString())
                }, "card_id = ?", arrayOf(card.cardId))
            }
            db.setTransactionSuccessful()
            return cards.size
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun performCardAction(cardId: String, action: String, actionId: String? = null,
                          requestedReturnAt: Long? = null): BufferCard? {
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
            require(action in old.actions) { "action is not available for this card" }
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
    fun saveQuestionAnswer(cardId: String, answer: String, source: String = "user", expected: BufferCard? = null): BufferCard? {
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
    fun updateCardTags(cardId: String, tags: List<String>): BufferCard? {
        val old = findCard(cardId) ?: return null
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
            appendEvent(
                db,
                "CardTagsUpdated",
                old.captureId,
                System.currentTimeMillis(),
                JSONObject()
                    .put("card_id", cardId)
                    .put("tags", JSONArray(normalized)),
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

    fun recordQuestionAnswerRequested(card: BufferCard) {
        appendEvent(
            type = "QuestionAnswerRequested",
            captureId = card.captureId,
            payload = JSONObject().put("card_id", card.cardId),
        )
    }

    fun recordQuestionAnswerDeferred(card: BufferCard, detail: String) {
        appendEvent(
            type = "QuestionAnswerDeferred",
            captureId = card.captureId,
            payload = JSONObject()
                .put("card_id", card.cardId)
                .put("detail", detail),
        )
    }

    fun recordQuestionAnswerFailed(card: BufferCard, detail: String) {
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
            while (cursor.moveToNext()) {
                result += BufferCapture(
                    captureId = cursor.getString(cursor.getColumnIndexOrThrow("capture_id")),
                    sourceDevice = cursor.getString(cursor.getColumnIndexOrThrow("source_device")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")),
                    audioFormat = cursor.getString(cursor.getColumnIndexOrThrow("audio_format")),
                    durationMs = cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                    blobPath = cursor.getString(cursor.getColumnIndexOrThrow("blob_path")),
                    transcript = cursor.getString(cursor.getColumnIndexOrThrow("transcript")),
                    classification = cursor.getString(cursor.getColumnIndexOrThrow("classification")),
                )
            }
        }
        return result
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
            summary = text.take(160),
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

    private fun insertCard(db: SQLiteDatabase, card: BufferCard) {
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
        val eventId = newId("evt")
        val parentEventId = db.query(
            "events",
            arrayOf("event_id"),
            if (captureId == null) null else "capture_id = ?",
            captureId?.let { arrayOf(it) },
            null,
            null,
            "created_at DESC, rowid DESC",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val logicalClock = db.rawQuery(
            "SELECT COALESCE(MAX(logical_clock), 0) + 1 FROM events",
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
                if (parentEventId != null) put(parentEventId)
            }.toString())
            put("logical_clock", logicalClock)
            put("type", type)
            put("capture_id", captureId)
            put("created_at", createdAt)
            put("payload", payload.toString())
        })
    }

    override fun findCard(cardId: String): BufferCard? {
        readableDatabase.query("cards", null, "card_id = ?", arrayOf(cardId), null, null, null, "1").use { cursor ->
            return if (cursor.moveToFirst()) readCard(cursor) else null
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
        private const val DATABASE_VERSION = 19
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
