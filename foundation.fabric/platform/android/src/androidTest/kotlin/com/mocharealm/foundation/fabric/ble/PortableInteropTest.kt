package com.mocharealm.foundation.fabric.ble

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom

/** Native protocol/ABI fixture on Android, not a Bluetooth radio test. */
class PortableInteropTest {
    private fun entropy() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    @Test fun firstPairThenTwelveKiBSessionAndReopen() {
        val root = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val aDir = File(root, "fabric-a-${System.nanoTime()}").absolutePath
        val bDir = File(root, "fabric-b-${System.nanoTime()}").absolutePath
        val aInfo = requireNotNull(FabricBleNative.identityInfo(aDir))
        val bInfo = requireNotNull(FabricBleNative.identityInfo(bDir))
        val aPublic = aInfo.copyOfRange(0,32); val bPublic = bInfo.copyOfRange(0,32)
        assertFalse(FabricBleNative.isTrusted(aDir,bPublic))
        var a = FabricBleNative.requestPairingHost(aDir,entropy(),bPublic,20,0)
        var b = FabricBleNative.requestPairingHost(bDir,entropy(),null,20,0)
        assertTrue(a>0 && b>0)
        var now=0L
        fun pump() {
            now+=10
            for (h in listOf(a,b)) { val r=FabricBleNative.poll(h,now); assertTrue("poll $r",r==0||r == -9) }
            FabricBleNative.takePacket(a)?.let { val r=FabricBleNative.feed(b,it,now); assertTrue("feedB $r",r==0||r == -9) }
            FabricBleNative.takePacket(b)?.let { val r=FabricBleNative.feed(a,it,now); assertTrue("feedA $r",r==0||r == -9) }
        }
        repeat(1000) { if (FabricBleNative.pairingHostCode(a)<0 || FabricBleNative.pairingHostCode(b)<0) pump() }
        val code=FabricBleNative.pairingHostCode(a)
        assertTrue(code in 0..999999); assertEquals(code,FabricBleNative.pairingHostCode(b))
        assertFalse(FabricBleNative.isTrusted(aDir,bPublic))
        assertEquals(0,FabricBleNative.pairingHostConfirm(a,code,true,now))
        repeat(50) {pump()}
        assertFalse("one confirmation cannot persist",FabricBleNative.isTrusted(aDir,bPublic))
        assertEquals(0,FabricBleNative.pairingHostConfirm(b,code,true,now))
        repeat(1000) { if (FabricBleNative.phase(a)!=4 || FabricBleNative.phase(b)!=4 || !FabricBleNative.pairingHostFlushed(a) || !FabricBleNative.pairingHostFlushed(b)) pump() }
        assertEquals(4,FabricBleNative.phase(a));assertEquals(4,FabricBleNative.phase(b))
        assertTrue(FabricBleNative.isTrusted(aDir,bPublic));assertTrue(FabricBleNative.isTrusted(bDir,aPublic))
        FabricBleNative.close(a);FabricBleNative.close(b)
        assertArrayEquals(aInfo,FabricBleNative.identityInfo(aDir))
        assertArrayEquals(bPublic,FabricBleNative.trustedPeer(aDir,bInfo.copyOfRange(32,64)))
        val raw=ByteArray(140)
        raw.fill(1,0,16);ByteBuffer.wrap(raw,16,8).putLong(1)
        raw.fill(2,24,40);raw.fill(3,40,56);raw.fill(4,56,72);raw.fill(5,72,88);raw.fill(6,88,104)
        ByteBuffer.wrap(raw,136,4).putInt(12288)
        val context=requireNotNull(FabricBleNative.finalizeContext(raw))
        var allowed=true
        val policy=FabricBlePolicy { c,action -> allowed && action in 0..2 && c.contentEquals(context) }
        a=FabricBleNative.createFromIdentity(aDir,bPublic,entropy(),policy,20)
        b=FabricBleNative.createFromIdentity(bDir,aPublic,entropy(),policy,20)
        assertTrue(a>0 && b>0);assertEquals(0,FabricBleNative.propose(a,context))
        repeat(1000) { if(FabricBleNative.phase(a)!=6||FabricBleNative.phase(b)!=6) pump() }
        assertEquals(6,FabricBleNative.phase(a));assertEquals(6,FabricBleNative.phase(b));assertEquals(12288,FabricBleNative.maxPayload(a))
        val payload=ByteArray(12288){(it*17).toByte()}
        assertEquals(0,FabricBleNative.send(a,payload,now))
        var received:ByteArray?=null
        repeat(3000){if(received==null){pump();received=FabricBleNative.takeMessage(b)}}
        assertArrayEquals(payload,received)
        assertEquals(-4,FabricBleNative.send(a,ByteArray(12289),now))
        repeat(20){pump()}
        allowed=false
        assertEquals(-2,FabricBleNative.send(a,byteArrayOf(1),now))
        FabricBleNative.close(a);FabricBleNative.close(b)
        assertEquals(-1,FabricBleNative.phase(a)) // stale generation handle rejected
    }
}
