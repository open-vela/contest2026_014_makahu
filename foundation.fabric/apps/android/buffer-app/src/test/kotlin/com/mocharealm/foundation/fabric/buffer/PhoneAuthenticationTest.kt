package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.PhoneAuthentication

import org.junit.Assert.*
import org.junit.Test

class PhoneAuthenticationTest {
    private fun proof(client: String = "a".repeat(64), server: String = "b".repeat(64),
                      vela: String = "vela-test", phone: String = "phone-test", token: String = "test-token") =
        PhoneAuthentication.proof(token, client, server, vela, phone)
    @Test fun matchesIndependentHmacVector() { assertEquals("f5d228a7347a6607f9d29ce8d57b1d8fc0f2eaf8a755de13962ed6bb4312a7f4", proof()) }
    @Test fun bindsBothChallengesAndIdentities() {
        val original = proof()
        assertNotEquals(original, proof(client = "c".repeat(64)))
        assertNotEquals(original, proof(server = "c".repeat(64)))
        assertNotEquals(original, proof(vela = "other"))
        assertNotEquals(original, proof(phone = "other"))
        assertNotEquals(original, proof(token = "wrong"))
    }
    @Test fun rejectsMissingChallengeAndAmbiguousIdentity() {
        assertThrows(IllegalArgumentException::class.java) { proof(client = "") }
        assertThrows(IllegalArgumentException::class.java) { proof(phone = "phone:other") }
        assertThrows(IllegalArgumentException::class.java) { proof(token = "") }
    }
}
