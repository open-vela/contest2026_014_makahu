package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan

/** Local scheduling authority is never reconstructed from received history. */
internal object BufferResearchPlans {
    fun create(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS research_weekly_plans(id TEXT PRIMARY KEY, body TEXT NOT NULL)")
    fun rows(db: SQLiteDatabase): List<ResearchWeeklyPlan> = db.rawQuery(
        "SELECT id,body,length(CAST(body AS BLOB)) FROM research_weekly_plans ORDER BY id LIMIT 65",null).use { c ->
        val rows=mutableListOf<ResearchWeeklyPlan>()
        while(c.moveToNext()) {
            check(rows.size<64 && c.getLong(2) in 1..16384)
            rows+=ResearchWeeklyPlan.parse(c.getString(1)).also { check(it.id==c.getString(0)) }
        }; rows
    }
    fun save(db: SQLiteDatabase, plan: ResearchWeeklyPlan) {
        check(db.inTransaction())
        val raw=plan.json().toString();require(raw.toByteArray().size<=16384)
        db.insertWithOnConflict("research_weekly_plans",null,ContentValues().apply { put("id",plan.id);put("body",raw) },SQLiteDatabase.CONFLICT_REPLACE)
            .also { check(it!=-1L) }
    }
}
