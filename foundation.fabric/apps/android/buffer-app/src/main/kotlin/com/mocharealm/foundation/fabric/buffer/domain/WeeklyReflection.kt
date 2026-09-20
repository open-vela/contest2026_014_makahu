package com.mocharealm.foundation.fabric.buffer.domain
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyTheme
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyDeferredTask
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklySuggestion
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyLifeCategory
import java.time.Instant
import java.time.ZoneId

internal object WeeklyReflection {
    fun lifeOverview(cards: List<BufferCard>, zone: ZoneId = ZoneId.systemDefault()): List<WeeklyLifeCategory> {
        val labels = LifeLogCategories.labels + ("other" to "其他生活记录")
        val confirmed = cards.filter { it.kind == "care_card" && it.classificationConfirmed }
        return labels.map { (category, label) ->
            val records = confirmed.filter {
                (it.healthCategory?.takeIf { key -> key in labels } ?: "other") == category
            }.sortedWith(compareByDescending<BufferCard> { it.createdAt }.thenBy { it.cardId })
                .distinctBy { it.captureId ?: it.cardId }
            WeeklyLifeCategory(category, label, records.map { it.cardId },
                records.map { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }.distinct().size,
                records.take(2).map { it.summary })
        }
    }

    fun themes(cards: List<BufferCard>): List<WeeklyTheme> = cards
        .filter { it.kind != "weekly_card" }
        .flatMap { card -> card.tags.map(String::trim).filter(String::isNotBlank).distinct().map { it to card } }
        .groupBy({ it.first }, { it.second })
        .map { (tag, matches) ->
            // Derived cards from one capture are not independent repetitions.
            WeeklyTheme(tag, matches.distinctBy { it.captureId ?: it.cardId }.map { it.cardId }.sorted())
        }
        .filter { it.cardIds.size >= 2 }
        .sortedWith(compareByDescending<WeeklyTheme> { it.cardIds.size }.thenBy { it.tag })
        .take(5)

    fun deferred(cards: List<BufferCard>, laterCounts: Map<String, Int>): List<WeeklyDeferredTask> = cards
        .filter { it.kind == "task_card" && it.state !in setOf("done", "archived") && (laterCounts[it.cardId] ?: 0) >= 2 }
        .map { WeeklyDeferredTask(it, laterCounts.getValue(it.cardId)) }
        .sortedWith(compareByDescending<WeeklyDeferredTask> { it.times }.thenBy { it.card.createdAt }.thenBy { it.card.cardId })
        .take(5)

    fun suggestions(deferred: List<WeeklyDeferredTask>, candidates: List<BufferCard>): List<WeeklySuggestion> =
        (deferred.take(2).map {
            WeeklySuggestion(it.card.cardId, "重新看看「${it.card.summary}」：最近七天延后 ${it.times} 次，可以拆成一个小步骤，或决定暂时不做。")
        } + candidates.take(1).map {
            WeeklySuggestion(it.cardId, "若下周还有兴趣，为「${it.summary}」留一小段探索时间。")
        }).take(3)
}
