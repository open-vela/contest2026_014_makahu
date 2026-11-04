package com.mocharealm.foundation.fabric.buffer.data.local

import android.database.sqlite.SQLiteDatabase

/** Bounded private JSON may exceed a CursorWindow. Read its exact UTF-8 bytes
 * in small windows while one transaction pins the row. Never truncate it. */
internal object BufferSqlText {
    fun read(db: SQLiteDatabase, table: String, selection: String, args: Array<String>, maximum: Int): String {
        require(table in setOf("source_projection_effects", "source_projection_retained_rows"))
        require(maximum in 1..4 * 1024 * 1024)
        val own = !db.inTransaction()
        if (own) db.beginTransactionNonExclusive()
        try {
            val size = db.rawQuery("SELECT typeof(body),LENGTH(CAST(body AS BLOB)) FROM $table WHERE $selection", args).use {
                check(it.moveToFirst()); require(it.getString(0) == "text")
                it.getLong(1).also { size -> require(size in 1L..maximum.toLong()) }.toInt()
            }
            val bytes = ByteArray(size)
            try {
                var offset = 0
                while (offset < size) {
                    val count = minOf(64 * 1024, size - offset)
                    val part = db.rawQuery("SELECT substr(CAST(body AS BLOB),?,?) FROM $table WHERE $selection",
                        arrayOf((offset + 1).toString(), count.toString(), *args)).use {
                        check(it.moveToFirst()); it.getBlob(0)
                    }
                    try { check(part.size == count); part.copyInto(bytes, offset); offset += count }
                    finally { part.fill(0) }
                }
                val value = bytes.toString(Charsets.UTF_8)
                require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "invalid stored UTF-8" }
                if (own) db.setTransactionSuccessful()
                return value
            } finally { bytes.fill(0) }
        } finally { if (own) db.endTransaction() }
    }
}
