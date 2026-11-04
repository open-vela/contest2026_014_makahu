package com.mocharealm.foundation.fabric.buffer.domain

import org.json.JSONObject

/** Immutable observations, not instructions or authorization to use a provider. */
internal object CaptureContext {
    const val MAX_BYTES = 8192
    const val MAX_AGE_MS = 60_000L
    private val states = setOf("available", "disabled", "permission_denied", "service_unavailable",
        "no_recent_fix", "provider_error", "expired", "not_provided", "pending", "cancelled")

    fun section(status: String): JSONObject {
        require(status in states && status != "available")
        return JSONObject().put("status", status)
    }

    fun empty(at: Long, source: String, status: String, collectedAt: Long = at): JSONObject = JSONObject()
        .put("version", 1).put("captured_at", at).put("collected_at", collectedAt)
        .put("source_device", source).put("location", section(status)).put("calendar", section(status))

    private fun millis(value: JSONObject, key: String): Long {
        val n = value.get(key)
        require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() &&
            n.toLong() in 0..9_007_199_254_740_991L)
        return n.toLong()
    }

    fun validate(value: JSONObject): JSONObject {
        require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        require(value.keys().asSequence().toSet() == setOf("version", "captured_at", "collected_at", "source_device", "location", "calendar"))
        require(value.get("version") == 1)
        val at = millis(value, "captured_at"); millis(value, "collected_at")
        require(value.get("source_device") is String && value.getString("source_device").length in 1..96 && '\u0000' !in value.getString("source_device"))
        for (key in listOf("location", "calendar")) {
            val part = value.getJSONObject(key)
            require(part.get("status") in states)
            if (part.getString("status") != "available") {
                require(part.keys().asSequence().toSet() == setOf("status")); continue
            }
            if (key == "location") {
                require(part.keys().asSequence().toSet() == setOf("status", "observed_at", "latitude_min", "latitude_max", "longitude_min", "longitude_max"))
                val observed = millis(part, "observed_at")
                require(observed <= at && at - observed <= 900_000)
                fun coordinate(name: String, min: Double, max: Double): Double {
                    val n = part.get(name); require(n is Number && n.toDouble().isFinite() && n.toDouble() in min..max)
                    return n.toDouble()
                }
                val south = coordinate("latitude_min", -90.0, 90.0)
                val north = coordinate("latitude_max", -90.0, 90.0)
                val west = coordinate("longitude_min", -180.0, 180.0)
                val east = coordinate("longitude_max", -180.0, 180.0)
                require(north - south >= 0.099999 && east > west)
            } else {
                require(part.keys().asSequence().toSet() == setOf("status", "events", "more"))
                require(part.get("more") is Boolean)
                val events = part.getJSONArray("events"); require(events.length() <= 3)
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    require(event.keys().asSequence().toSet() == setOf("title", "begin", "end"))
                    require(event.get("title") is String && event.getString("title").length <= 96 && '\u0000' !in event.getString("title"))
                    require(millis(event, "begin") <= at && millis(event, "end") > at)
                }
            }
        }
        return value
    }

    fun description(value: JSONObject): String {
        validate(value)
        fun reason(status: String) = when (status) {
            "disabled" -> "未启用"
            "permission_denied" -> "未授权"
            "service_unavailable" -> "系统服务不可用"
            "no_recent_fix" -> "没有捕获前十五分钟内的位置"
            "provider_error" -> "读取失败"
            "expired" -> "采集窗口已过，未使用事后状态补写"
            "not_provided" -> "来源设备未提供"
            "pending" -> "等待采集"
            "cancelled" -> "设置已变更，已取消采集"
            else -> status
        }
        val location = value.getJSONObject("location")
        val calendar = value.getJSONObject("calendar")
        return buildString {
            append("来源："); append(value.getString("source_device")); append('\n')
            append("采集时间："); append(java.time.Instant.ofEpochMilli(value.getLong("collected_at")).atZone(java.time.ZoneId.systemDefault())); append('\n')
            append("位置：")
            if (location.getString("status") == "available") {
                append("粗略区域 · 纬度 "); append(location.getDouble("latitude_min")); append("—"); append(location.getDouble("latitude_max"))
                append("，经度 "); append(location.getDouble("longitude_min")); append("—"); append(location.getDouble("longitude_max"))
                append("\n位置观测："); append(java.time.Instant.ofEpochMilli(location.getLong("observed_at")).atZone(java.time.ZoneId.systemDefault()))
            } else append(reason(location.getString("status")))
            append("\n当时日程：")
            if (calendar.getString("status") == "available") {
                val events = calendar.getJSONArray("events")
                if (events.length() == 0) append("无正在进行的非全天事项")
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    fun time(key: String) = java.time.Instant.ofEpochMilli(event.getLong(key)).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString()
                    append("\n· ${event.getString("title").ifBlank { "未命名事项" }}（${time("begin")}—${time("end")}）")
                }
                if (calendar.getBoolean("more")) append("\n另有重叠事项未展开")
            } else append(reason(calendar.getString("status")))
        }
    }
}

internal data class CaptureContextJob(val captureId: String, val capturedAt: Long, val source: String,
    val policy: JSONObject, val local: Boolean, val requestId: String)
