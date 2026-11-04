package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CardReminderNavigationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun notificationPendingIntentsKeepTheirOwnCardIds() {
        fun pending(id: String) = PendingIntent.getActivity(app, 1410, CardReminderNavigation.intent(app, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val first = pending("card-one")
        val second = pending("card-two")
        assertNotEquals(first, second)
        assertEquals(first, pending("card-one"))
        assertEquals("card-one", CardReminderNavigation.cardId(shadowOf(first).savedIntent))
        assertEquals("card-two", CardReminderNavigation.cardId(shadowOf(second).savedIntent))
        assertEquals(MainActivity::class.java.name, shadowOf(first).savedIntent.component?.className)
        assertNull(shadowOf(first).savedIntent.extras) // Contents are read locally, never embedded in the notification intent.
    }
    @Test fun invalidRoutesAreIgnoredAndConsumedRoutesDoNotReopen() {
        val good = CardReminderNavigation.intent(app, "card-one")
        for (uri in listOf("https://reminder/card-one", "buffer://other/card-one", "buffer://reminder/a/b",
            "buffer://reminder/card-one?x=1", "buffer://reminder/card-one#x", "buffer://reminder/%2F")) {
            assertNull(CardReminderNavigation.cardId(Intent(good).setData(Uri.parse(uri))))
        }
        assertNull(CardReminderNavigation.cardId(Intent(good).setAction(Intent.ACTION_MAIN)))
        assertThrows(IllegalArgumentException::class.java) { CardReminderNavigation.intent(app, "../other") }
    }
    @Test fun openingReferenceDoesNotMutateCompletedOrMissingCards() {
        BufferRepository(app).use { repository ->
            val original = repository.createTextCapture("通知跳转")
            val card = requireNotNull(repository.reviewClassification(original, ClassificationDecision.EDIT, "task_card", null))
            repository.performCardAction(card.cardId, "done")
            val before = repository.eventCount()
            val id = requireNotNull(CardReminderNavigation.cardId(CardReminderNavigation.intent(app, card.cardId)))
            assertEquals("done", repository.findCard(id)?.state)
            assertNull(repository.findCard("card-missing"))
            assertEquals(before, repository.eventCount())
        }
    }
}
