package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobGate
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorElection
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorCapability
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorDeviceClass
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorMember
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorReadiness
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorRole
import com.mocharealm.foundation.fabric.buffer.data.coordinator.MemoryCoordinatorStateStore
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPendingCaptureInput
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPendingCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.data.llm.commitVelaClassificationIfCurrent
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BufferClassificationFenceTest {
    @After
    fun detachGate() {
        BufferCoordinatorJobGate.detach()
    }

    @Test
    fun lostCoordinatorFenceReleasesOnlyItsRequestAndDoesNotPublishProposal() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("buffer.db")
        val repository = BufferRepository(context)
        try {
            val captureId = "vela-lost-fence-1"
            val velaId = "a".repeat(64)
            val rawText = "来自 Vela 的原始待分类文本".toByteArray(Charsets.UTF_8)
            assertEquals("imported", repository.importVelaPendingCapture(
                velaId,
                VelaPendingCaptureInput(
                    captureId = captureId,
                    mode = "normal",
                    createdAt = System.currentTimeMillis(),
                    durationMs = 0L,
                    contentType = "text/plain; charset=utf-8",
                    recordSha256 = VelaPendingCaptureProtocol.fingerprint(rawText),
                    recordBytes = rawText,
                ),
            ))
            val placeholder = requireNotNull(repository.cardForCapture(captureId))
            val requestLease = requireNotNull(repository.claimClassificationRequest(captureId))

            val phone = CoordinatorMember("phone-local", CoordinatorDeviceClass.PHONE)
            val election = BufferCoordinatorElection(
                sessionId = "buffer-session-fence-test",
                membershipVersion = "membership-fence-test",
                localMember = phone,
                members = listOf(phone),
                initialReadiness = CoordinatorReadiness(
                    configurationValid = true,
                    serviceReachable = true,
                    backgroundExecutionAvailable = true,
                    storageAvailable = true,
                    canCoordinate = true,
                    capabilities = setOf(CoordinatorCapability.CLASSIFY),
                ),
                store = MemoryCoordinatorStateStore(),
            )
            election.tick(0L)
            assertEquals(CoordinatorRole.COORDINATOR, election.tick(9_000L).role)
            BufferCoordinatorJobGate.attach(election, velaId)
            val coordinatorLease = requireNotNull(BufferCoordinatorJobGate.acquire(
                captureId = captureId,
                sourceDeviceId = velaId,
                stage = "classify",
                inputRevision = placeholder.createdAt,
                capability = CoordinatorCapability.CLASSIFY,
                nowElapsedRealtimeMillis = 9_000L,
            ))
            BufferCoordinatorJobGate.detach()

            var proposalCallbackRan = false
            val applied = commitVelaClassificationIfCurrent(
                repository = repository,
                captureId = captureId,
                requestLease = requestLease,
                coordinatorLease = coordinatorLease,
            ) {
                proposalCallbackRan = true
                repository.applyRemoteClassification(
                    captureId,
                    listOf(BufferClassificationProposal(
                        text = "旧任期结果",
                        kind = "question_card",
                        title = "旧结果不应发布",
                        summary = "fenced",
                        healthCategory = null,
                        confidence = 0.99,
                    )),
                    requestLease,
                )
            }

            assertFalse(applied)
            assertFalse(proposalCallbackRan)
            val currentCard = requireNotNull(repository.cardForCapture(captureId))
            assertEquals(placeholder.cardId, currentCard.cardId)
            assertEquals(placeholder.title, currentCard.title)
            assertTrue(repository.classificationProcessing(captureId).steps.none {
                it.type == "ClassificationProposed"
            })
            assertEquals("pending", repository.classificationProcessing(captureId).state)
            assertEquals(0, repository.classificationProcessing(captureId).attempts)

            val retryLease = repository.claimClassificationRequest(captureId)
            assertNotNull(retryLease)
            assertFalse(repository.releaseClassificationRequest(captureId, requestLease))
            assertNull(repository.claimClassificationRequest(captureId))
            assertEquals("processing", repository.classificationProcessing(captureId).state)
        } finally {
            repository.close()
        }
    }
}
