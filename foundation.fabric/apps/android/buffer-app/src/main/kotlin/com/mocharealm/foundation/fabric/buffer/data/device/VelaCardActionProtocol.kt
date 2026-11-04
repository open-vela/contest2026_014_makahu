package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONObject

/** Caller is the authenticated app.buffer channel worker. SQL action receipts
 * deduplicate retransmissions, including a lost reply or a process restart. */
internal object VelaCardActionProtocol {
    fun apply(bytes: ByteArray, repository: BufferRepository, trustedDeviceId: String? = null,
              authorize: ()->Boolean = {trustedDeviceId==null}): ByteArray {
        require(bytes.size in 1..1024)
        val raw=bytes.toString(Charsets.UTF_8);require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        val value = com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent.parseObject(raw,1024)
        require(value.keys().asSequence().toSet() == setOf("type", "protocol", "card_id", "action_id", "action"))
        require(value.get("type") == "buffer.card.action" && value.get("protocol") == 1)
        val card = value.getString("card_id")
        val id = value.getString("action_id")
        val action = value.getString("action")
        require(card.matches(Regex("[A-Za-z0-9_.-]{1,80}")) && id.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        require(action in setOf("later", "done", "reopen", "archive", "remind", "rabbit_hole"))
        val result = if(trustedDeviceId!=null)repository.performVelaCardAction(trustedDeviceId,card,action,id,authorize)
        else if (repository.findCard(card) == null) "absent" else {
            try { repository.performCardAction(card, action, id); "applied" }
            catch (_: IllegalArgumentException) { "rejected" }
        }
        return JSONObject().put("type", "buffer.card.action.reply").put("protocol", 1)
            .put("card_id", card).put("action_id", id).put("action", action).put("state", result)
            .toString().toByteArray(Charsets.UTF_8)
    }
}
