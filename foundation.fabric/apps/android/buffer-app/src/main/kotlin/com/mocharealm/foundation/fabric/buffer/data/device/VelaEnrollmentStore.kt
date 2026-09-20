package com.mocharealm.foundation.fabric.buffer.data.device
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/** Credentials deliberately have no generated toString/copy that exposes secrets. */
internal class PendingVelaEnrollment(
    val deviceId: String, val address: String, val requestId: String, val token: String,
    val networkReady: Boolean, val lanVerified: Boolean, val cancelled: Boolean,
) {
    override fun toString() = "PendingVelaEnrollment(deviceId=$deviceId, requestId=$requestId)"
}

/** UI projection intentionally excludes credentials and Wi-Fi configuration. */
internal data class VelaEnrollmentStatus(
    val deviceId: String, val requestId: String, val networkReady: Boolean, val lanVerified: Boolean,
)

/** Shares the repository's SQL transaction with the final PeerPaired event. */
internal class VelaEnrollmentStore(
    private val database: () -> SQLiteDatabase,
    private val onConfirmed: (SQLiteDatabase, String, String) -> Unit,
) {
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database()
        db.beginTransaction()
        try { return block(db).also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }

    fun activeStatuses(): List<VelaEnrollmentStatus> = database().query(TABLE,
        arrayOf("device_id", "request_id", "network_ready", "lan_verified"), "cancelled = 0",
        null, null, null, "created_at DESC, device_id ASC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(VelaEnrollmentStatus(cursor.getString(0), cursor.getString(1),
            cursor.getInt(2) != 0, cursor.getInt(3) != 0)) }
    }

    fun forAddress(address: String): PendingVelaEnrollment? {
        val db = database()
        return db.query(TABLE, arrayOf("device_id"), "ble_address = ?",
            arrayOf(address.uppercase(Locale.ROOT)), null, null, null).use { cursor ->
            if (cursor.count == 1 && cursor.moveToFirst()) read(db, cursor.getString(0)) else null
        }
    }

    fun pending(deviceId: String): PendingVelaEnrollment? = read(database(), deviceId)

    private fun read(db: SQLiteDatabase, id: String): PendingVelaEnrollment? = db.query(
        TABLE, null, "device_id = ?", arrayOf(id), null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) null else PendingVelaEnrollment(
            c.getString(c.getColumnIndexOrThrow("device_id")),
            c.getString(c.getColumnIndexOrThrow("ble_address")),
            c.getString(c.getColumnIndexOrThrow("request_id")),
            c.getString(c.getColumnIndexOrThrow("token")),
            c.getInt(c.getColumnIndexOrThrow("network_ready")) != 0,
            c.getInt(c.getColumnIndexOrThrow("lan_verified")) != 0,
            c.getInt(c.getColumnIndexOrThrow("cancelled")) != 0,
        )
    }

    /** Called only after authenticated GATT identity has been read. No Wi-Fi password is persisted. */
    fun begin(deviceId: String, address: String): PendingVelaEnrollment {
        require(deviceId.matches(Regex("[A-Za-z0-9._-]{1,79}")))
        val normalized = address.uppercase(Locale.ROOT)
        require(normalized.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
        return transaction { db ->
            db.rawQuery("SELECT 1 FROM paired_peers WHERE device_id = ?", arrayOf(deviceId)).use {
                require(!it.moveToFirst()) { "设备已经添加" }
            }
            val old = read(db, deviceId)
            if (old == null) {
                val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
                db.insertOrThrow(TABLE, null, ContentValues().apply {
                    put("device_id", deviceId); put("ble_address", normalized)
                    put("request_id", UUID.randomUUID().toString().replace("-", ""))
                    put("token", random.joinToString("") { "%02x".format(it.toInt() and 255) })
                    put("created_at", System.currentTimeMillis())
                })
                random.fill(0)
            } else {
                db.update(TABLE, ContentValues().apply {
                    put("ble_address", normalized); put("cancelled", 0)
                    if (old.cancelled) put("lan_verified", 0)
                }, "device_id = ?", arrayOf(deviceId))
            }
            requireNotNull(read(db, deviceId))
        }
    }

    /** A corrected configuration gets a new request ID; the peer secret remains recoverable. */
    fun retry(deviceId: String, previousRequestId: String): PendingVelaEnrollment = transaction { db ->
        val old = requireNotNull(read(db, deviceId)) { "待添加设备不存在" }
        require(!old.cancelled && old.requestId == previousRequestId) { "配网请求已改变" }
        require(!old.networkReady) { "设备已联网，请先完成连接确认" }
        db.update(TABLE, ContentValues().apply {
            put("request_id", UUID.randomUUID().toString().replace("-", ""))
            put("network_ready", 0); put("lan_verified", 0)
        }, "device_id = ?", arrayOf(deviceId))
        requireNotNull(read(db, deviceId))
    }

    fun cancel(deviceId: String, requestId: String): Boolean = transaction { db ->
        db.update(TABLE, ContentValues().apply { put("cancelled", 1); put("lan_verified", 0) },
            "device_id = ? AND request_id = ?", arrayOf(deviceId, requestId)) == 1
    }

    fun authenticationToken(deviceId: String): String? = pending(deviceId)?.takeUnless { it.cancelled }?.token

    /** Only a matching GATT network_ready result may call this, never an ATT write ACK. */
    fun networkReady(deviceId: String, requestId: String): Boolean = transaction { db ->
        val old = read(db, deviceId) ?: return@transaction false
        if (old.cancelled || old.requestId != requestId) return@transaction false
        db.update(TABLE, ContentValues().apply { put("network_ready", 1) }, "device_id = ?", arrayOf(deviceId))
        finish(db, deviceId)
    }

    /** Called by the bridge only after it has verified the fresh HMAC challenge. */
    fun lanVerified(deviceId: String, token: String): Boolean = transaction { db ->
        val old = read(db, deviceId) ?: return@transaction false
        if (old.cancelled || !MessageDigest.isEqual(old.token.toByteArray(), token.toByteArray())) return@transaction false
        db.update(TABLE, ContentValues().apply { put("lan_verified", 1) }, "device_id = ?", arrayOf(deviceId))
        finish(db, deviceId)
    }

    /** Only after both the normal fresh challenge and request-bound receipt proof were verified. */
    fun recoverVerifiedReceipt(deviceId: String, requestId: String, token: String): Boolean = transaction { db ->
        val old = read(db, deviceId) ?: return@transaction false
        if (old.cancelled || old.requestId != requestId ||
            !MessageDigest.isEqual(old.token.toByteArray(), token.toByteArray())) return@transaction false
        db.update(TABLE, ContentValues().apply { put("network_ready", 1); put("lan_verified", 1) },
            "device_id = ?", arrayOf(deviceId))
        finish(db, deviceId)
    }

    private fun finish(db: SQLiteDatabase, id: String): Boolean {
        val row = read(db, id) ?: return false
        if (row.cancelled || !row.networkReady || !row.lanVerified) return false
        // Never replace a peer that another pairing path already committed.
        db.insertOrThrow("paired_peers", null, ContentValues().apply {
            put("device_id", id); put("token", row.token); put("paired_at", System.currentTimeMillis())
        })
        onConfirmed(db, id, row.requestId)
        db.delete(TABLE, "device_id = ?", arrayOf(id))
        return true
    }

    companion object {
        private const val TABLE = "vela_enrollments"
        fun createTable(db: SQLiteDatabase) = db.execSQL("""
            CREATE TABLE IF NOT EXISTS vela_enrollments (
                device_id TEXT PRIMARY KEY, ble_address TEXT NOT NULL,
                request_id TEXT NOT NULL UNIQUE, token TEXT NOT NULL,
                network_ready INTEGER NOT NULL DEFAULT 0,
                lan_verified INTEGER NOT NULL DEFAULT 0,
                cancelled INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
