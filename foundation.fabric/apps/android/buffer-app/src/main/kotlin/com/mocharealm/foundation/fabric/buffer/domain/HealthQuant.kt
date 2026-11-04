package com.mocharealm.foundation.fabric.buffer.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Quantities are proposals until explicitly confirmed. Missing values stay missing. */
internal data class HealthQuant(val date: String, val zone: String, val entries: List<Entry>) {
    data class Entry(val name: String, val amount: Double?, val unit: String, val basis: String,
                     val note: String, val values: Map<String, Double>) {
        fun json() = JSONObject().put("name",name).put("amount",amount ?: JSONObject.NULL).put("unit",unit)
            .put("basis",basis).put("note",note).put("values",JSONObject(values))
    }
    fun json() = JSONObject().put("date",date).put("zone",zone).put("entries",JSONArray(entries.map { it.json() }))
    fun description() = entries.joinToString("\n") { e ->
        e.name + (e.amount?.let { " $it${e.unit}" } ?: " · 份量未记录") +
            (if(e.basis=="estimated") "（估算）" else "（原文或用户提供）") +
            e.values.entries.joinToString("",prefix=if(e.values.isEmpty()) " · 数值未记录" else "") { (key,value)->" · ${labels.getValue(key)} $value${units.getValue(key)}" } +
            e.note.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    }
    companion object {
        val labels=linkedMapOf("energy_kcal" to "热量","protein_g" to "蛋白质","carbs_g" to "碳水","fat_g" to "脂肪",
            "water_ml" to "饮水","sleep_min" to "睡眠","exercise_min" to "运动","weight_kg" to "体重")
        val units=mapOf("energy_kcal" to "kcal","protein_g" to "g","carbs_g" to "g","fat_g" to "g","water_ml" to "ml",
            "sleep_min" to "分钟","exercise_min" to "分钟","weight_kg" to "kg")
        fun parse(raw: String): HealthQuant {
            val v=BufferSourceEvent.parseObject(raw,32768)
            require(v.keys().asSequence().toSet()==setOf("date","zone","entries"))
            val date=v.get("date");val zone=v.get("zone");require(date is String && zone is String)
            require(LocalDate.parse(date).toString()==date);ZoneId.of(zone)
            val a=v.getJSONArray("entries");require(a.length() in 1..16)
            val entries=(0 until a.length()).map { i ->
                val e=a.getJSONObject(i)
                require(e.keys().asSequence().toSet()==setOf("name","amount","unit","basis","note","values"))
                fun text(key:String,max:Int):String { val s=e.get(key);require(s is String && s.length<=max);return s }
                val name=text("name",160);require(name.isNotBlank())
                val amount=if(e.isNull("amount"))null else number(e.get("amount"),100000.0)
                val unit=text("unit",20);require(amount==null || unit.isNotBlank())
                val basis=text("basis",20);require(basis in setOf("estimated","reported"))
                val values=e.getJSONObject("values")
                val metrics=values.keys().asSequence().associateWith { key ->
                    require(key in labels);number(values.get(key),when(key){"sleep_min","exercise_min"->1440.0;"weight_kg"->1000.0;else->100000.0})
                }
                val note=text("note",500)
                if(basis=="estimated") {
                    require(note.isNotBlank()) { "请填写估算依据" }
                    require(amount!=null || metrics.keys.none {it in setOf("energy_kcal","protein_g","carbs_g","fat_g")}) { "份量未知时请先补充份量，再估算营养" }
                }
                Entry(name,amount,unit,basis,note,metrics)
            }
            return HealthQuant(date,zone,entries)
        }
        private fun number(value:Any,max:Double):Double {
            require(value is Number);return value.toDouble().also { require(it.isFinite() && it in 0.0..max) }
        }
    }
}

internal data class HealthQuantRecord(val cardId:String,val revision:Long,val state:String,val data:HealthQuant)
internal data class HealthQuantView(val record:HealthQuantRecord,val eventId:String,val localRevision:Long,val local:Boolean)
internal data class HealthQuantJob(val cardId:String,val captureId:String,val lease:String,val input:String,val hash:String,val createdAt:Long,val zone:String)
internal data class HealthQuantTotal(val metric:String,val reported:Double?,val estimated:Double?,val records:Int,val missingEntries:Int=0)
