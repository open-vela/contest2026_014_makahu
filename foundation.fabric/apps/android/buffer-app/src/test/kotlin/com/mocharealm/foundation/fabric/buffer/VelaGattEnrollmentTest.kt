package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.DatabaseSettings
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferHttp
import com.mocharealm.foundation.fabric.buffer.data.llm.QuickAnswerResult
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmKey
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferCaptureAgent
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferTranscriptionWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMoAsrClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferAsrAudio
import com.mocharealm.foundation.fabric.buffer.data.llm.WeeklyLlmJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyLlmWorker
import com.mocharealm.foundation.fabric.buffer.data.device.BufferWire
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBlePeer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBleDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPeer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPairing
import com.mocharealm.foundation.fabric.buffer.data.device.VelaDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResult
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResultReader
import com.mocharealm.foundation.fabric.buffer.data.device.PendingVelaEnrollment
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStatus
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStore
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentViewModel
import com.mocharealm.foundation.fabric.buffer.data.device.EnrollmentView
import com.mocharealm.foundation.fabric.buffer.data.device.VelaGattEnrollment
import com.mocharealm.foundation.fabric.buffer.data.device.toJson
import com.mocharealm.foundation.fabric.buffer.data.system.HealthConnectExporter
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeDeck
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlan
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlanner
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeState
import com.mocharealm.foundation.fabric.buffer.domain.CalendarPriority
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipt
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipts
import com.mocharealm.foundation.fabric.buffer.domain.CalendarRecovery
import com.mocharealm.foundation.fabric.buffer.domain.CalendarWriteSelection
import com.mocharealm.foundation.fabric.buffer.domain.CardReturnTime
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationChange
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationReview
import com.mocharealm.foundation.fabric.buffer.domain.EnrollmentAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig
import com.mocharealm.foundation.fabric.buffer.domain.HealthExportResult
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncOutcome
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncRecovery
import com.mocharealm.foundation.fabric.buffer.domain.LifeLogCategories
import com.mocharealm.foundation.fabric.buffer.domain.PhoneAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.RabbitHoleSession
import com.mocharealm.foundation.fabric.buffer.domain.RecordingDestination
import com.mocharealm.foundation.fabric.buffer.domain.RecordingFinalizer
import com.mocharealm.foundation.fabric.buffer.domain.RecordingResult
import com.mocharealm.foundation.fabric.buffer.domain.RecoveryFiles
import com.mocharealm.foundation.fabric.buffer.domain.WeeklyReflection
import com.mocharealm.foundation.fabric.buffer.domain.WeeklySchedule
import com.mocharealm.foundation.fabric.buffer.domain.LineTooLongException
import com.mocharealm.foundation.fabric.buffer.domain.readBoundedLine
import com.mocharealm.foundation.fabric.buffer.domain.takeCodePoints
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferWeeklySummary
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyTheme
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyDeferredTask
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklySuggestion
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyLifeCategory
import com.mocharealm.foundation.fabric.buffer.domain.model.displayAnswer

import android.app.Application
import android.bluetooth.*
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import android.os.Looper
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowBluetoothGatt
import java.io.ByteArrayOutputStream
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [EnrollmentGatt::class])
class VelaGattEnrollmentTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val peer = VelaBlePeer("AA:BB:CC:DD:EE:FF", "S1", -40)
    private lateinit var device: BluetoothDevice
    private lateinit var repository: BufferRepository
    private lateinit var client: VelaGattEnrollment
    private val views = mutableListOf<EnrollmentView>()
    private val loop get() = shadowOf(Looper.getMainLooper())
    @Before fun open() {
        shadowOf(app).grantPermissions(*VelaBleDiscovery.permissions())
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        device = adapter.getRemoteDevice(peer.address)
        shadowOf(device).setCreatedBond(true)
        repository = BufferRepository(app)
        EnrollmentGatt.reset(repository)
        client = VelaGattEnrollment(app, repository, views::add)
    }
    @After fun close() { client.close(); repository.close() }
    private fun connect(): BluetoothGatt {
        client.connect(peer)
        return discover(shadowOf(device).bluetoothGatts.last())
    }
    private fun discover(gatt: BluetoothGatt): BluetoothGatt {
        val service = BluetoothGattService(VelaBleDiscovery.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(VelaGattEnrollment.WRITE_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, 0))
        service.addCharacteristic(BluetoothGattCharacteristic(VelaGattEnrollment.RESULT_UUID, BluetoothGattCharacteristic.PROPERTY_READ, 0))
        shadowOf(gatt).addDiscoverableService(service)
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_CONNECTED)
        loop.idle()
        return gatt
    }
    private fun bond(variant: Int = 1) {
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_PAIRING_REQUEST)
            .putExtra(BluetoothDevice.EXTRA_DEVICE,device).putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,variant))
        loop.idle()
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE,device))
        loop.idle()
    }
    @Test fun retainedOwnerKeepsGattAndWifiFormUntilLanConfirmation() {
        val store = ViewModelStore()
        val factory = VelaEnrollmentViewModel.Factory(app, repository)
        try {
            val original = ViewModelProvider(store, factory)[VelaEnrollmentViewModel::class.java]
            original.connect(peer)
            val gatt = discover(shadowOf(device).bluetoothGatts.last())
            bond()
            assertTrue(original.view.canConfigure)
            original.ssid = "wifi"; original.password = "password"
            // Disposing the list item / stopping the old Activity stops discovery only.
            original.stopScan()
            val recreated = ViewModelProvider(store, factory)[VelaEnrollmentViewModel::class.java]
            assertSame(original, recreated)
            assertFalse(shadowOf(gatt).isClosed)
            assertEquals("password", recreated.password)
            recreated.submit(); loop.idle()
            val row = requireNotNull(repository.velaEnrollments.pending("vela-test"))
            assertFalse(recreated.view.complete)
            recreated.stopScan()
            repository.velaEnrollments.lanVerified(row.deviceId, row.token)
            loop.idleFor(Duration.ofMillis(800))
            assertTrue(recreated.view.complete)
            assertEquals("", recreated.password)
            assertEquals(listOf(row.deviceId), recreated.paired)
        } finally { store.clear() }
    }
    @Test fun finishingOwnerClosesGattErasesFormAndPreservesRecoveryRecord() {
        val store = ViewModelStore()
        val factory = VelaEnrollmentViewModel.Factory(app, repository)
        val model = ViewModelProvider(store, factory)[VelaEnrollmentViewModel::class.java]
        model.connect(peer)
        val gatt = discover(shadowOf(device).bluetoothGatts.last())
        bond()
        model.ssid = "wifi"; model.password = "password"
        model.submit(); loop.idle()
        val row = requireNotNull(repository.velaEnrollments.pending("vela-test"))
        store.clear()
        assertTrue(shadowOf(gatt).isClosed)
        assertEquals("", model.password)
        assertEquals("", model.ssid)
        assertFalse(requireNotNull(repository.velaEnrollments.pending(row.deviceId)).cancelled)
        val last = model.view
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_CONNECTED)
        loop.idleFor(Duration.ofSeconds(181))
        assertEquals(last, model.view)
        val fresh = ViewModelProvider(store, factory)[VelaEnrollmentViewModel::class.java]
        assertNotSame(model, fresh)
        assertEquals("", fresh.password)
        assertFalse(fresh.view.active)
        store.clear()
    }
    @Test fun completeFlowWaitsForLanProofAndPersistsBeforeSending() {
        connect(); bond()
        assertTrue(views.last().canConfigure)
        client.submit("wifi", "password"); loop.idle()
        assertEquals(1, EnrollmentGatt.requests.size)
        val row = requireNotNull(repository.velaEnrollments.pending("vela-test"))
        assertTrue(row.networkReady)
        assertNull(repository.pairedVelaToken(row.deviceId))
        assertFalse(views.last().complete)
        repository.velaEnrollments.lanVerified(row.deviceId,row.token)
        loop.idleFor(Duration.ofMillis(800))
        assertTrue(views.last().complete)
        assertEquals(row.token, repository.pairedVelaToken(row.deviceId))
    }
    @Test fun rejectsConsentOnlyAndUnknownExistingBonds() {
        val first=connect();bond(3)
        assertTrue(shadowOf(first).isClosed)
        assertTrue(EnrollmentGatt.requests.isEmpty())
        val second=connect();loop.idle()
        assertTrue(shadowOf(second).isClosed)
        assertTrue(repository.pairedVelaIds().isEmpty())
    }
    @Test fun invalidPasswordKeepsFormAndRetryUsesNewRequestId() {
        connect();bond()
        client.submit("wifi","short")
        assertTrue(views.last().canConfigure)
        assertTrue(EnrollmentGatt.requests.isEmpty())
        EnrollmentGatt.failWifi=true
        client.submit("wifi","password");loop.idle()
        assertTrue(views.last().canConfigure)
        val old=EnrollmentGatt.requests.single().getString("request_id")
        EnrollmentGatt.failWifi=false
        client.submit("wifi","corrected");loop.idle()
        assertNotEquals(old,EnrollmentGatt.requests.last().getString("request_id"))
        assertEquals(2,EnrollmentGatt.requests.size)
        assertFalse(views.last().complete)
    }
    @Test fun cancellationRevokesPendingTokenAndStopsLateCallbacks() {
        val gatt=connect();bond()
        val row=requireNotNull(repository.velaEnrollments.pending("vela-test"))
        client.cancel()
        assertNull(repository.velaAuthenticationToken(row.deviceId))
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_CONNECTED)
        loop.idleFor(Duration.ofSeconds(190))
        assertFalse(views.last().active)
        assertFalse(views.last().complete)
        assertTrue(shadowOf(gatt).isClosed)
    }
    @Test fun networkReadyCanFinishAfterBleDisconnect() {
        val gatt=connect();bond();client.submit("wifi","password");loop.idle()
        val row=requireNotNull(repository.velaEnrollments.pending("vela-test"))
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED)
        loop.idle()
        repository.velaEnrollments.lanVerified(row.deviceId,row.token)
        loop.idleFor(Duration.ofMillis(800))
        assertTrue(views.last().complete)
    }
    @Test fun verifiedBondCanResumeAndDifferentIdentityIsRejected() {
        val row=repository.velaEnrollments.begin("vela-test",peer.address)
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        val first=connect()
        assertTrue(views.last().canConfigure)
        assertEquals(row.token,repository.velaEnrollments.pending("vela-test")?.token)
        client.close()
        EnrollmentGatt.resultDeviceId="different-device"
        val second=connect()
        assertTrue(shadowOf(first).isClosed)
        assertTrue(shadowOf(second).isClosed)
        assertTrue(EnrollmentGatt.requests.isEmpty())
        assertNull(repository.velaEnrollments.pending("different-device"))
    }
    @Test fun lostFinalBleResultWaitsForAuthenticatedReceiptRecovery() {
        val gatt = connect(); bond()
        EnrollmentGatt.loseResult = true
        client.submit("wifi", "password"); loop.idle()
        val row = requireNotNull(repository.velaEnrollments.pending("vela-test"))
        assertFalse(row.networkReady)
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTED)
        loop.idle()
        assertTrue(views.last().active); assertFalse(views.last().complete)
        assertTrue(repository.velaEnrollments.recoverVerifiedReceipt(row.deviceId, row.requestId, row.token))
        loop.idleFor(Duration.ofMillis(800))
        assertTrue(views.last().complete)
    }
    @Test fun existingWifiOmitsCredentialsAndStillRequiresLanVerification() {
        connect(); bond()
        client.submit("", "", useExistingWifi = true); loop.idle()
        val sent = EnrollmentGatt.requests.single()
        assertEquals("buffer.bind", sent.getString("type"))
        assertFalse(sent.has("ssid")); assertFalse(sent.has("password"))
        val row = requireNotNull(repository.velaEnrollments.pending("vela-test"))
        assertTrue(row.networkReady); assertFalse(views.last().complete)
        repository.velaEnrollments.lanVerified(row.deviceId, row.token)
        loop.idleFor(Duration.ofMillis(800))
        assertTrue(views.last().complete)
    }
    @Test fun unavailableSavedNetworkAllowsRetryWithNewWifi() {
        connect(); bond(); EnrollmentGatt.failWifi = true
        client.submit("", "", useExistingWifi = true); loop.idle()
        assertTrue(views.last().canConfigure)
        val first = EnrollmentGatt.requests.single().getString("request_id")
        EnrollmentGatt.failWifi = false
        client.submit("wifi", "password"); loop.idle()
        val second = EnrollmentGatt.requests.last()
        assertNotEquals(first, second.getString("request_id"))
        assertEquals("buffer.configure", second.getString("type"))
        assertEquals("wifi", second.getString("ssid"))
    }
    @Test fun missingLanProofNeverReportsCompletionAndHasDeadline() {
        connect();bond();client.submit("wifi","password");loop.idle()
        loop.idleFor(Duration.ofSeconds(181))
        assertFalse(views.last().active)
        assertFalse(views.last().complete)
        assertTrue(repository.pairedVelaIds().isEmpty())
        assertNotNull(repository.velaEnrollments.pending("vela-test"))
    }
    @Test fun missingCallbackTimesOutWithoutPairing() {
        client.connect(peer)
        loop.idleFor(Duration.ofSeconds(31))
        assertFalse(views.last().active)
        assertTrue(repository.pairedVelaIds().isEmpty())
    }
}

/** Only platform transport is replaced; client callbacks, codecs and SQL are production. */
@Implements(BluetoothGatt::class)
class EnrollmentGatt : ShadowBluetoothGatt() {
    @RealObject private lateinit var real: BluetoothGatt
    private var incoming=ByteArrayOutputStream()
    private var result=JSONObject().put("protocol",1).put("request_id","").put("device_id",resultDeviceId).put("state","idle").put("error",0)
    private var snapshot=byteArrayOf()
    private var offset=0
    @Implementation
    override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: Int): Int {
        if(value.size==2) {
            offset=((value[0].toInt() and 255) shl 8) or (value[1].toInt() and 255)
            if(offset==0) snapshot=result.toString().toByteArray()
        } else {
            fun u16(i:Int)=((value[i].toInt() and 255) shl 8) or (value[i+1].toInt() and 255)
            if(u16(0)==0) incoming=ByteArrayOutputStream()
            assertEquals(incoming.size(),u16(0))
            incoming.write(value,4,value.size-4)
            if(incoming.size()==u16(2)) {
                val request=JSONObject(incoming.toString("UTF-8"))
                val saved=requireNotNull(repository.velaEnrollments.pending("vela-test"))
                assertEquals(saved.requestId,request.getString("request_id"))
                assertEquals(saved.token,request.getString("token"))
                requests.add(request)
                result=result.put("request_id",saved.requestId).put("state",if(failWifi)"failed" else "network_ready").put("error",if(failWifi)-5 else 0)
            }
        }
        gattCallback.onCharacteristicWrite(real,characteristic,0)
        return BluetoothStatusCodes.SUCCESS
    }
    @Implementation
    override fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        if (loseResult && requests.isNotEmpty()) return true
        val n=minOf(16,snapshot.size-offset)
        val page=byteArrayOf((offset ushr 8).toByte(),offset.toByte(),(snapshot.size ushr 8).toByte(),snapshot.size.toByte())+snapshot.copyOfRange(offset,offset+n)
        gattCallback.onCharacteristicRead(real,characteristic,page,0)
        return true
    }
    companion object {
        lateinit var repository: BufferRepository
        val requests=mutableListOf<JSONObject>()
        var loseResult=false
        var failWifi=false
        var resultDeviceId="vela-test"
        fun reset(repo:BufferRepository){repository=repo;requests.clear();loseResult=false;failWifi=false;resultDeviceId="vela-test"}
    }
}
