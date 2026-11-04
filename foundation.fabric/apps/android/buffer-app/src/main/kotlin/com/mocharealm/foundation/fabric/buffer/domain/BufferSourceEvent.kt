package com.mocharealm.foundation.fabric.buffer.domain

import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

/** The exact UTF-8 envelope is the immutable record. Receivers retain it rather
 * than reserializing JSON or assigning a new event ID, clock or parent. Source
 * IDs identify application journals; their authenticated Fabric owner is pinned
 * separately. This format does not claim a transferable origin signature. */
internal class BufferSourceEvent private constructor(val raw: String, val envelope: JSONObject) {
    val source: String get() = envelope.getString("source_id")
    val sequence: Long get() = envelope.getLong("source_sequence")
    val event: JSONObject get() = envelope.getJSONObject("event")
    val eventId: String get() = event.getString("event_id")
    val logicalClock: Long get() = event.getLong("logical_clock")
    val parents: List<String> get() = event.getJSONArray("parent_event_ids").let { a ->
        (0 until a.length()).map(a::getString)
    }
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_COUNTER = 9_007_199_254_740_991L
        private val identity = Regex("[A-Za-z0-9_.:-]{1,160}")
        private val sourceIdentity = Regex("[A-Za-z0-9_.-]{1,96}")
        private val eventKeys = setOf("event_id", "app_namespace", "actor_id", "schema_version",
            "parent_event_ids", "logical_clock", "type", "capture_id", "created_at", "payload")

        fun encode(source: String, sequence: Long, event: JSONObject, legacy: Boolean = false): BufferSourceEvent =
            decode(JSONObject().put("wire_version", 1).put("source_id", source).put("source_sequence", sequence)
                .put("provenance", if (legacy) "legacy_local" else "native").put("event", event).toString())

        fun decode(raw: String): BufferSourceEvent {
            val value = parseObject(raw, MAX_BYTES)
            require(value.keys().asSequence().toSet() == setOf("wire_version", "source_id", "source_sequence", "provenance", "event"))
            require(integer(value, "wire_version", 1, 1) == 1L)
            require(value.get("source_id") is String && sourceIdentity.matches(value.getString("source_id")))
            // Reserve one safe integer for a terminal next-sequence receipt.
            integer(value, "source_sequence", 1, MAX_COUNTER - 1)
            require(value.get("provenance") in setOf("native", "legacy_local"))
            val event = value.getJSONObject("event")
            require(event.keys().asSequence().toSet() == eventKeys)
            require(event.get("event_id") is String && identity.matches(event.getString("event_id")))
            require(event.get("app_namespace") == "app.buffer")
            require(event.get("actor_id") is String && sourceIdentity.matches(event.getString("actor_id")))
            integer(event, "schema_version", 1, 65535)
            integer(event, "logical_clock", if (value.getString("provenance") == "native") 1 else 0, MAX_COUNTER)
            integer(event, "created_at", 0, MAX_COUNTER)
            require(event.get("type") is String && event.getString("type").matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,95}")))
            require(event.get("capture_id") == JSONObject.NULL ||
                (event.get("capture_id") is String && identity.matches(event.getString("capture_id"))))
            val parents = event.getJSONArray("parent_event_ids")
            require(parents.length() <= 32)
            val ids = (0 until parents.length()).map {
                require(parents.get(it) is String)
                parents.getString(it).also { id -> require(identity.matches(id) && id != event.getString("event_id")) }
            }
            require(ids.distinct().size == ids.size)
            event.getJSONObject("payload")
            return BufferSourceEvent(raw, value)
        }

        internal fun parseObject(raw: String, maximum: Int): JSONObject {
            require(maximum in 1..MAX_BYTES)
            return parseStoredObject(raw, maximum)
        }

        /** Private recovery snapshots may contain several bounded row images.
         * This limit never changes the maximum size of a source event. */
        internal fun parseStoredObject(raw: String, maximum: Int): JSONObject {
            require(maximum in 1..4 * MAX_BYTES)
            val bytes = raw.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..maximum && bytes.toString(Charsets.UTF_8) == raw) { "invalid event UTF-8 or size" }
            StrictJson(raw).check()
            return JSONObject(raw)
        }

        private fun integer(value: JSONObject, key: String, min: Long, max: Long): Long {
            val n = value.get(key)
            require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in min..max)
            return n.toLong()
        }
    }
}

/** Reject ambiguous duplicate keys, permissive org.json extensions and excessive
 * nesting before JSONObject builds a tree. Payload remains data, never code. */
private class StrictJson(private val text: String) {
    private var at = 0
    fun check() { value(0); whitespace(); require(at == text.length) }
    private fun whitespace() { while (at < text.length && text[at] in " \r\n\t") at++ }
    private fun take(c: Char): Boolean { whitespace(); return (at < text.length && text[at] == c).also { if (it) at++ } }
    private fun string(key: Boolean = false): String {
        whitespace(); val start = at
        require(at < text.length && text[at++] == '"')
        while (at < text.length) {
            val c = text[at++]
            require(c >= ' ')
            if (c == '"') {
                val decoded = JSONTokener(text.substring(start, at)).nextValue() as String
                // NUL may be original user data in a payload value. Preserve
                // it; only object keys and bounded identity fields forbid it.
                require((!key || '\u0000' !in decoded) && decoded.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == decoded)
                return decoded
            }
            if (c == '\\') {
                require(at < text.length)
                when (text[at++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> { require(at + 4 <= text.length && text.substring(at, at + 4).all { it in "0123456789abcdefABCDEF" }); at += 4 }
                    else -> error("invalid JSON escape")
                }
            }
        }
        error("unterminated JSON string")
    }
    private fun value(depth: Int) {
        require(depth <= 32); whitespace(); require(at < text.length)
        when (text[at]) {
            '{' -> {
                at++; val keys = hashSetOf<String>()
                if (take('}')) return
                do { require(keys.add(string(key = true))) { "duplicate JSON key" }; require(take(':')); value(depth + 1) } while (take(','))
                require(take('}'))
            }
            '[' -> {
                at++; if (take(']')) return
                do { value(depth + 1) } while (take(','))
                require(take(']'))
            }
            '"' -> string()
            else -> {
                val start = at
                while (at < text.length && text[at] !in ",]} \r\n\t") at++
                val token = text.substring(start, at)
                require(token in setOf("true", "false", "null") ||
                    (token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) && token.toDouble().isFinite()))
            }
        }
    }
}
