package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.EnrollmentStep
import com.mocharealm.foundation.fabric.buffer.data.device.EnrollmentView
import com.mocharealm.foundation.fabric.buffer.data.device.VelaFabricEnrollment
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBlePeer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaFabricQrLocator
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EnrollmentWizardTest {
    private lateinit var repository: BufferRepository
    private lateinit var enrollment: VelaFabricEnrollment
    private var view: EnrollmentView? = null

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        repository = BufferRepository(app)
        enrollment = VelaFabricEnrollment(app, repository) { null }
        enrollment.listener = { view = it }
    }
    @After fun tearDown() { enrollment.close(); repository.close() }

    private fun requireConfirmation() {
        val method = enrollment.javaClass.getDeclaredMethod("state", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
        method.isAccessible = true
        method.invoke(enrollment, "ConfirmPairing", 123, 12288, null)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun invoke(name: String) {
        enrollment.javaClass.getDeclaredMethod(name).also { it.isAccessible = true }.invoke(enrollment)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun field(name: String) = enrollment.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun paired(identity: ByteArray) {
        enrollment.javaClass.getDeclaredMethod("state", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java).apply {
            isAccessible = true
        }.invoke(enrollment, "Paired", -1, 12288, identity)
    }

    @Test fun explicitRescanCanPairResetBoardWithoutClearingPhoneData() {
        val address="12:34:56:78:9A:BC"
        val old="a".repeat(64)
        val other="c".repeat(64)
        repository.saveVelaFabricRoute(address,old)
        repository.saveVelaFabricRoute("12:34:56:78:9A:BD",other)
        field("expectedDevice").set(enrollment,old)
        enrollment.connect(VelaBlePeer(address,"Gemini S1",-40,true))
        shadowOf(field("thread").get(enrollment).let { it as android.os.HandlerThread }.looper).idle()
        assertNull(field("expectedDevice").get(enrollment))
        // Until SAS pairing commits, the original saved route remains intact.
        assertTrue(repository.velaFabricRoutes().any { it.second==old })
        field("address").set(enrollment,address)
        paired(ByteArray(32) { 0x0b })
        assertEquals(setOf("0b".repeat(32),other),repository.velaFabricRoutes().map { it.second }.toSet())
    }

    @Test fun savedResumeAndQrPinsStillRejectChangedIdentity() {
        val address="12:34:56:78:9A:BC"
        val old="a".repeat(64)
        repository.saveVelaFabricRoute(address,old)
        field("address").set(enrollment,address)
        field("expectedDevice").set(enrollment,old)
        paired(ByteArray(32) { 0x0b })
        assertEquals(old,repository.velaFabricRoute()!!.second)
        val locator=VelaFabricQrLocator.parse(VelaFabricQrLocator.encode(ByteArray(32) { 1 }))!!
        enrollment.connect(VelaBlePeer(address,"Gemini S1",-40,true),locator)
        shadowOf(field("thread").get(enrollment).let { it as android.os.HandlerThread }.looper).idle()
        assertEquals(locator.deviceId,field("expectedDevice").get(enrollment))
        paired(ByteArray(32) { 0x0b })
        assertEquals(old,repository.velaFabricRoute()!!.second)
    }

    @Test fun numericConfirmationNeverShowsNetworkConfigurationOrCompletion() {
        requireConfirmation()
        assertEquals(EnrollmentStep.CONFIRM, view!!.step)
        assertEquals(123, view!!.pairingCode)
        assertFalse(view!!.canConfigure)
        assertFalse(view!!.complete)
    }

    @Test fun transportProgressCannotDismissThePendingNumericDecision() {
        requireConfirmation()
        val method = enrollment.javaClass.getDeclaredMethod("state", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
        method.isAccessible = true
        for (state in listOf("Connecting", "Negotiating", "Pairing")) {
            method.invoke(enrollment, state, -1, 12288, null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(EnrollmentStep.CONFIRM, view!!.step)
            assertEquals(123, view!!.pairingCode)
            assertFalse(view!!.canConfigure)
        }
    }

    @Test fun pairingSessionReconnectDoesNotSkipExplicitNetworkChoice() {
        requireConfirmation()
        invoke("closeConnection")
        invoke("configureNetworkOrAgent")
        assertEquals(EnrollmentStep.NETWORK, view!!.step)
        assertTrue(view!!.canConfigure)
        assertNull(view!!.pairingCode)
        assertFalse(view!!.complete)
        // A worker refresh must retain the same step, not insert a service
        // progress panel before the user has selected the device network.
        invoke("configureNetworkOrAgent")
        assertEquals(EnrollmentStep.NETWORK, view!!.step)
    }
}
