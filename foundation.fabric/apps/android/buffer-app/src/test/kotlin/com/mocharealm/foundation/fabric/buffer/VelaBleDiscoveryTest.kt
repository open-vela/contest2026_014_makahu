package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBleDiscovery

import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaBleDiscoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val scanner get() = shadowOf(adapter.bluetoothLeScanner)
    private lateinit var discovery: VelaBleDiscovery
    @Before fun open() {
        shadowOf(app).grantPermissions(*VelaBleDiscovery.permissions())
        shadowOf(adapter).setEnabled(true)
        discovery = VelaBleDiscovery(app)
    }
    @After fun close() { discovery.close() }

    @Test fun timeoutStopsPlatformScanAndCompletesOnce() {
        val messages = mutableListOf<String>()
        discovery.scan({}, messages::add)
        assertEquals(1, scanner.scanCallbacks.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(scanner.scanCallbacks.isEmpty())
        assertEquals(1, messages.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(1, messages.size)
    }
    @Test fun closeSuppressesLateFailureAndTimeout() {
        val messages = mutableListOf<String>()
        discovery.scan({}, messages::add)
        val callback = scanner.scanCallbacks.single()
        discovery.close()
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(scanner.scanCallbacks.isEmpty())
        assertTrue(messages.isEmpty())
    }
    @Test fun oldScanFailureDoesNotStopReplacementScan() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        discovery.scan({}, first::add)
        val old = scanner.scanCallbacks.single()
        discovery.scan({}, second::add)
        old.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, scanner.scanCallbacks.size)
        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        scanner.scanCallbacks.single().onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(scanner.scanCallbacks.isEmpty())
        assertEquals(1, second.size)
    }
    @Test fun permissionDenialDoesNotStartScan() {
        shadowOf(app).denyPermissions(*VelaBleDiscovery.permissions())
        val messages = mutableListOf<String>()
        discovery.scan({}, messages::add)
        assertEquals(1, messages.size)
        assertTrue(scanner.scanCallbacks.isEmpty())
    }
    @Test fun disabledBluetoothReturnsActionableFailure() {
        shadowOf(adapter).setEnabled(false)
        val messages = mutableListOf<String>()
        discovery.scan({}, messages::add)
        assertEquals(listOf("请先打开手机蓝牙"), messages)
    }
}
