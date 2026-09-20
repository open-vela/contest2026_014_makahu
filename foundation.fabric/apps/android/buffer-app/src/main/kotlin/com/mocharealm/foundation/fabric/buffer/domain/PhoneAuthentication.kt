package com.mocharealm.foundation.fabric.buffer.domain
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object PhoneAuthentication {
    fun proof(token: String, clientNonce: String, serverNonce: String, velaId: String, phoneId: String): String {
        require(clientNonce.matches(Regex("[0-9a-f]{64}")) && serverNonce.matches(Regex("[0-9a-f]{64}")))
        require(listOf(velaId, phoneId).all { it.matches(Regex("[A-Za-z0-9._-]{1,79}")) })
        require(token.isNotEmpty())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val message = "buffer-phone-v1:$clientNonce:$serverNonce:$velaId:$phoneId"
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
