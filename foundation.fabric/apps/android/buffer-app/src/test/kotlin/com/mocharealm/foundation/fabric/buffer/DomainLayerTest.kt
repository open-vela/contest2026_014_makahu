package com.mocharealm.foundation.fabric.buffer

import com.mocharealm.foundation.fabric.buffer.domain.CardLookup
import com.mocharealm.foundation.fabric.buffer.domain.FindCardUseCase
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainLayerTest {
    @Test
    fun findCardUseCaseResolvesCardsOutsideCurrentUiWindow() {
        val archived = BufferCard(
            cardId = "archived-1", captureId = null, kind = "spark_card",
            title = "旧记录", summary = "仍可从详情入口打开", state = "archived",
            actions = emptyList(), createdAt = 1L,
        )
        val useCase = FindCardUseCase(object : CardLookup {
            override fun findCard(cardId: String): BufferCard? =
                archived.takeIf { it.cardId == cardId }
        })

        assertEquals(archived, useCase("archived-1"))
        assertNull(useCase(""))
        assertNull(useCase("missing"))
    }
}
