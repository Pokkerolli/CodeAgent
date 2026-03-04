package com.pokkerolli.domain.model

data class ChatMessage(
    val id: Long,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val promptTokens: Int? = null,
    val promptCacheHitTokens: Int? = null,
    val promptCacheMissTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)
