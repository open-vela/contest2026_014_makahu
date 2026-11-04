package com.mocharealm.foundation.fabric.buffer.domain

import org.json.JSONArray
import org.json.JSONObject

/** State-based per-field Lamport LWW map and a grow-only deletion tombstone.
 * An identical stamp with unequal values is corrupt input, never a tie-break. */
internal object BufferCrdt {
    private const val MAX_CLOCK = 9_007_199_254_740_991L
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> { require(value.toDouble().isFinite()); if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toDouble().toString() }
        else -> error("unsupported CRDT value")
    }
    fun validate(document: JSONObject) {
        require(document.keys().asSequence().toSet() == setOf("version", "id", "kind", "deleted", "fields"))
        require(document.get("version") == 1 && document.get("id") is String && document.getString("id").matches(Regex("[A-Za-z0-9_-]{1,79}")))
        require(document.get("kind") in setOf("card", "calendar") && document.get("deleted") is Boolean)
        val fields = document.getJSONObject("fields"); require(fields.length() <= 32)
        fields.keys().forEach { key ->
            require(key.length in 1..48)
            val field = fields.getJSONObject(key)
            require(field.keys().asSequence().toSet() == setOf("counter", "actor", "value"))
            val n = field.get("counter")
            require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in 1..MAX_CLOCK)
            require(field.get("actor") is String && field.getString("actor").matches(Regex("[0-9a-f]{64}")))
            checkValue(field.get("value"), 0)
        }
        require(canonical(document).toByteArray(Charsets.UTF_8).size <= 60 * 1024)
    }
    private fun checkValue(value: Any?, depth: Int) {
        require(depth <= 8)
        when (value) {
            is JSONObject -> value.keys().forEach { checkValue(value.get(it), depth + 1) }
            is JSONArray -> (0 until value.length()).forEach { checkValue(value.get(it), depth + 1) }
            is String -> require(value.toByteArray(Charsets.UTF_8).size <= 24000 && '\u0000' !in value)
            is Number -> require(value.toDouble().isFinite())
        }
    }
    fun clock(document: JSONObject): Long = document.getJSONObject("fields").let { fields ->
        fields.keys().asSequence().maxOfOrNull { fields.getJSONObject(it).getLong("counter") } ?: 0
    }
    fun values(document: JSONObject): JSONObject {
        validate(document)
        return JSONObject().also { values -> document.getJSONObject("fields").let { fields ->
            fields.keys().forEach { values.put(it, fields.getJSONObject(it).get("value")) }
        } }
    }
    fun merge(left: JSONObject?, right: JSONObject): JSONObject {
        validate(right)
        if (left == null) return JSONObject(right.toString())
        validate(left)
        require(left.getString("id") == right.getString("id") && left.getString("kind") == right.getString("kind"))
        val result = JSONObject(left.toString()); val fields = result.getJSONObject("fields")
        right.getJSONObject("fields").let { incoming -> incoming.keys().forEach { key ->
            val next = incoming.getJSONObject(key); val old = fields.optJSONObject(key)
            val order = if (old == null) 1 else compareValuesBy(next, old,
                { it.getLong("counter") }, { it.getString("actor") })
            if (order == 0) require(canonical(old!!.get("value")) == canonical(next.get("value")))
            if (order > 0) fields.put(key, JSONObject(next.toString()))
        } }
        result.put("deleted", left.getBoolean("deleted") || right.getBoolean("deleted"))
        validate(result); return result
    }
    fun update(previous: JSONObject?, id: String, kind: String, changes: JSONObject, actor: String, deleted: Boolean = false): JSONObject {
        previous?.let { validate(it); require(it.getString("id") == id && it.getString("kind") == kind) }
        val result = previous?.let { JSONObject(it.toString()) } ?: JSONObject().put("version", 1).put("id", id)
            .put("kind", kind).put("deleted", false).put("fields", JSONObject())
        val fields = result.getJSONObject("fields"); val counter = clock(result) + 1; require(counter in 1..MAX_CLOCK)
        changes.keys().forEach { key ->
            val value = changes.get(key)
            if (fields.optJSONObject(key)?.let { canonical(it.get("value")) == canonical(value) } != true)
                fields.put(key, JSONObject().put("counter", counter).put("actor", actor).put("value", value))
        }
        result.put("deleted", deleted || result.getBoolean("deleted")); validate(result); return result
    }
}
