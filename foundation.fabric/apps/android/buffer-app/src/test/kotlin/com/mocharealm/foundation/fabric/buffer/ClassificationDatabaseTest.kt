package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ClassificationDatabaseTest {
    private lateinit var user: BufferRepository
    private lateinit var worker: BufferRepository
    private val proposal = BufferClassificationProposal("后台文本", "question_card", "后台问题", "后台摘要", null, 0.9)
    @Before fun open() {
        user = BufferRepository(RuntimeEnvironment.getApplication())
        worker = BufferRepository(RuntimeEnvironment.getApplication())
    }
    @After fun close() { worker.close(); user.close() }
    private fun remote(card: BufferCard) = worker.applyRemoteClassification(requireNotNull(card.captureId), listOf(proposal))
    private fun current(card: BufferCard) = requireNotNull(user.cards(100).find { it.cardId == card.cardId })

    @Test fun lateRemoteResultPreservesAcceptedCard() {
        val card = user.createTextCapture("一个灵感")
        val accepted = user.reviewClassification(card, ClassificationDecision.ACCEPT)
        remote(card)
        assertEquals(accepted, current(card))
    }
    @Test fun rejectionEventProtectsUnconfirmedCard() {
        val card = user.createTextCapture("拒绝旧提案")
        val rejected = user.reviewClassification(card, ClassificationDecision.REJECT)
        remote(card)
        assertEquals(rejected, current(card))
        assertFalse(current(card).classificationConfirmed)
    }
    @Test fun lateRemoteResultPreservesUserEditedCategory() {
        val card = user.createTextCapture("重新分类")
        val edited = user.reviewClassification(card, ClassificationDecision.EDIT, "task_card")
        remote(card)
        assertEquals(edited, current(card))
    }
    @Test fun remoteUpdateInvalidatesOldConfirmationSnapshot() {
        val card = user.createTextCapture("原始提案")
        remote(card)
        assertThrows(IllegalArgumentException::class.java) {
            user.reviewClassification(card, ClassificationDecision.ACCEPT)
        }
        assertFalse(current(card).classificationConfirmed)
        assertEquals(proposal.title, current(card).title)
    }
    @Test fun competingRepositoriesCannotOverwriteSuccessfulReview() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(12) {
                val card = user.createTextCapture("并发提案 $it")
                val start = CountDownLatch(1)
                val review = executor.submit<BufferCard?> {
                    check(start.await(5, TimeUnit.SECONDS))
                    try { user.reviewClassification(card, ClassificationDecision.EDIT, "task_card") }
                    catch (_: IllegalArgumentException) { null } // A newer proposal requires reconfirmation.
                }
                val classify = executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    remote(card)
                }
                start.countDown()
                val accepted = review.get(10, TimeUnit.SECONDS)
                classify.get(10, TimeUnit.SECONDS)
                if (accepted != null) assertEquals(accepted, current(card))
                else {
                    assertFalse(current(card).classificationConfirmed)
                    assertEquals(proposal.title, current(card).title)
                }
            }
        } finally { executor.shutdownNow() }
    }
}
