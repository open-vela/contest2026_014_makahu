package com.mocharealm.foundation.fabric.buffer.domain
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

/** Small domain boundary used by UI and use cases to resolve a saved card. */
internal interface CardLookup {
    fun findCard(cardId: String): BufferCard?
}
