package com.mocharealm.foundation.fabric.buffer.data.llm

sealed interface QuickAnswerResult {
    data class Answer(val text: String) : QuickAnswerResult
    data class Unresolved(val detail: String) : QuickAnswerResult
}
