package com.mocharealm.foundation.fabric.buffer.domain
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object EnrollmentAuthentication {
    fun proof(token: String, nonce: String, deviceId: String, phoneId: String, requestId: String): String {
        require(nonce.matches(Regex("[0-9a-f]{64}")) && requestId.matches(Regex("[0-9a-f]{32}")))
        require(listOf(deviceId, phoneId).all { it.matches(Regex("[A-Za-z0-9._-]{1,79}")) })
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("buffer-enrollment-v1:$nonce:$deviceId:$phoneId:$requestId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun verify(token: String, nonce: String, deviceId: String, phoneId: String, requestId: String, supplied: String): Boolean {
        if (!supplied.matches(Regex("[0-9a-f]{64}"))) return false
        return runCatching { MessageDigest.isEqual(proof(token, nonce, deviceId, phoneId, requestId).toByteArray(), supplied.toByteArray()) }.getOrDefault(false)
    }
}
