package com.mocharealm.foundation.fabric.buffer

import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmSecretCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BufferLlmSecretCacheTest {
    @Test fun heartbeatReadsDecryptOnceAndKeyChangesInvalidateTheCache() {
        var calls=0
        val cache=BufferLlmSecretCache { calls++;"fixture-$it" }
        repeat(100) { assertEquals("fixture-a",cache.decrypt("a")) }
        assertEquals(1,calls)
        assertEquals("fixture-b",cache.decrypt("b"));assertEquals(2,calls)
        assertEquals("",cache.decrypt(""))
        assertEquals("fixture-b",cache.decrypt("b"));assertEquals(3,calls)
    }
    @Test fun aSlowOldReadCannotOverwriteAReplacementCredential() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var calls=0
        val cache=BufferLlmSecretCache { calls++;entered.countDown();check(release.await(5,TimeUnit.SECONDS));"fixture-a" }
        val worker=Thread { assertEquals("fixture-a",cache.decrypt("a")) }.apply { start() }
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        cache.remember("b","fixture-b");release.countDown();worker.join(5000)
        assertFalse(worker.isAlive);assertEquals("fixture-b",cache.decrypt("b"));assertEquals(1,calls)
    }
    @Test fun failedHardwareDecryptionIsNeverCached() {
        var calls=0
        val cache=BufferLlmSecretCache { if(++calls==1)error("fixture failure");"fixture" }
        assertThrows(IllegalStateException::class.java) { cache.decrypt("a") }
        assertEquals("fixture",cache.decrypt("a"));assertEquals(2,calls)
    }
}
