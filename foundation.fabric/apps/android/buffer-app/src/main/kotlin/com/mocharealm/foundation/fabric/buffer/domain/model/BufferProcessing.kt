package com.mocharealm.foundation.fabric.buffer.domain.model

data class BufferProcessingStep(val type: String, val time: Long, val tool: String = "", val success: Boolean = true, val count: Int = 0)
data class BufferProcessing(val state: String, val attempts: Int, val original: String, val steps: List<BufferProcessingStep>)
