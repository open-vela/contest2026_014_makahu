package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.EnrollmentAuthentication

import org.junit.Test
import org.junit.Assert.*

class EnrollmentAuthenticationTest {
    @Test fun matchesCAndPythonVectorAndBindsEveryField() {
        val nonce = "b".repeat(64); val request = "c".repeat(32)
        val proof = EnrollmentAuthentication.proof("test-token", nonce, "vela-test", "phone-test", request)
        assertEquals("b1e8a41c85c384e34e662aa08e1da449a3bafe3aa65dff4a577a090591d8d381", proof)
        assertTrue(EnrollmentAuthentication.verify("test-token", nonce, "vela-test", "phone-test", request, proof))
        assertFalse(EnrollmentAuthentication.verify("wrong", nonce, "vela-test", "phone-test", request, proof))
        assertFalse(EnrollmentAuthentication.verify("test-token", "a".repeat(64), "vela-test", "phone-test", request, proof))
        assertFalse(EnrollmentAuthentication.verify("test-token", nonce, "other", "phone-test", request, proof))
        assertFalse(EnrollmentAuthentication.verify("test-token", nonce, "vela-test", "other", request, proof))
        assertFalse(EnrollmentAuthentication.verify("test-token", nonce, "vela-test", "phone-test", "d".repeat(32), proof))
        assertFalse(EnrollmentAuthentication.verify("test-token", nonce, "vela-test", "phone-test", "bad", proof))
    }
}
