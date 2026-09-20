package com.mocharealm.foundation.fabric.buffer.domain
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

/** Resolves cards outside the bounded list shown on the current screen. */
internal class FindCardUseCase(private val lookup: CardLookup) {
    operator fun invoke(cardId: String): BufferCard? =
        cardId.takeIf(String::isNotBlank)?.let(lookup::findCard)
}
