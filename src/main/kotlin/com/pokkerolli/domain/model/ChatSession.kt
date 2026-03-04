package com.pokkerolli.domain.model

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val systemPrompt: String?,
    val userProfileName: String?,
    val contextWindowMode: ContextWindowMode,
    val isStickyFactsExtractionInProgress: Boolean,
    val contextSummary: String?,
    val summarizedMessagesCount: Int,
    val isContextSummarizationInProgress: Boolean
)
