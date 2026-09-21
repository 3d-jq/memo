package com.psyche.memo.llm.stream

data class SseEvent(
    val id: String?,
    val event: String?,
    val data: String,
    val retryMillis: Long?,
)
