package com.mocharealm.foundation.fabric.ble

import android.bluetooth.BluetoothManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in actual GATT backend test. Needs an advertising Vela and a granted
 * BLUETOOTH_CONNECT permission; never substitutes a mock or pretrusted key. */
class GattCarrierTest {
    @Test fun realPeerIdentityReadAndNotifySubscription() {
        val address = InstrumentationRegistry.getArguments().getString("fabricPeerAddress")
        assumeTrue("supply the actual Vela address to exercise GATT", address != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        assertNotNull("Bluetooth controller unavailable", adapter)
        val ready = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val identity = AtomicReference<ByteArray?>()
        val mtu = AtomicReference<Int?>()
        val carrier = FabricGattClient(context, object : FabricGattClient.Listener {
            override fun onIdentity(publicKey: ByteArray, deviceId: ByteArray) {
                // A public locator is not an authenticated/paired app Session.
                assertEquals(32, publicKey.size); assertEquals(32, deviceId.size)
                identity.set(publicKey + deviceId)
            }
            override fun onReady(packetBytes: Int) { mtu.set(packetBytes); ready.countDown() }
            override fun onPacket(packet: ByteArray) { /* no app payload before pairing */ }
            override fun onClosed(reason: String) { failure.set(reason); ready.countDown() }
        })
        try {
            @Suppress("MissingPermission")
            carrier.connect(adapter!!.getRemoteDevice(address!!))
            assertTrue("actual peer setup timed out", ready.await(25, TimeUnit.SECONDS))
            assertNull("actual GATT setup failed: ${failure.get()}", failure.get())
            assertEquals("Identity Read/ReadBlob did not return64 bytes", 64, identity.get()?.size)
            assertTrue("invalid negotiated packet budget", (mtu.get() ?: 0) in 20..512)
        } finally { carrier.close() }
    }
}
