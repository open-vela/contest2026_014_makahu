package com.mocharealm.foundation.fabric.buffer.data.local
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Settings participating in the same SQLite transactions as their events. */
internal class DatabaseSettings(private val database: () -> SQLiteDatabase) {
    fun getString(key: String, fallback: String?): String? = database().query(
        "runtime_settings", arrayOf("value"), "key = ?", arrayOf(key), null, null, null,
    ).use { if (it.moveToFirst()) it.getString(0) else fallback }
    fun getLong(key: String, fallback: Long): Long = getString(key, null)?.toLong() ?: fallback
    fun getBoolean(key: String, fallback: Boolean): Boolean = getString(key, null)?.toBooleanStrict() ?: fallback

    fun <T> transaction(block: () -> T): T {
        val db = database()
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    fun edit() = Editor()
    inner class Editor {
        private val changes = linkedMapOf<String, String?>()
        fun putString(key: String, value: String?) = apply { changes[key] = value }
        fun putLong(key: String, value: Long) = putString(key, value.toString())
        fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
        fun remove(key: String) = putString(key, null)
        // Synchronous, and nested in any surrounding operation transaction.
        fun apply() = transaction {
            val db = database()
            changes.forEach { (key, value) ->
                if (value == null) db.delete("runtime_settings", "key = ?", arrayOf(key))
                else db.insertWithOnConflict("runtime_settings", null, ContentValues().apply {
                    put("key", key); put("value", value)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "unable to save runtime setting" } }
            }
        }
    }
}
