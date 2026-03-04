package com.pokkerolli.presentation.chat

import com.pokkerolli.domain.model.MessageRole
import com.pokkerolli.domain.model.ContextWindowMode

data class ChatSessionUi(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val systemPrompt: String?,
    val userProfileName: String?,
    val contextWindowMode: ContextWindowMode,
    val isStickyFactsExtractionInProgress: Boolean,
    val isContextSummarizationInProgress: Boolean
)

data class ChatMessageUi(
    val stableId: String,
    val sourceMessageId: Long? = null,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    val userTokens: Int? = null,
    val userCacheHitTokens: Int? = null,
    val userCacheMissTokens: Int? = null,
    val inputCostCacheHitUsd: Double? = null,
    val inputCostCacheMissUsd: Double? = null,
    val assistantTokens: Int? = null,
    val requestTotalTokens: Int? = null,
    val outputCostUsd: Double? = null,
    val requestTotalCostUsd: Double? = null
)

data class UserProfilePresetUi(
    val profileName: String,
    val label: String,
    val isBuiltIn: Boolean
)

data class ProfileBuilderMessageUi(
    val stableId: String,
    val role: MessageRole,
    val content: String
)

data class ConversationUsageUi(
    val contextLength: Int = 0,
    val cumulativeTotalCostUsd: Double = 0.0
)

data class ChatUiState(
    val sessions: List<ChatSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    val activeSessionTitle: String = "New chat",
    val activeSessionSystemPrompt: String? = null,
    val activeSessionUserProfileName: String? = null,
    val availableUserProfiles: List<UserProfilePresetUi> = emptyList(),
    val activeSessionContextWindowMode: ContextWindowMode = ContextWindowMode.FULL_HISTORY,
    val isActiveSessionStickyFactsExtractionInProgress: Boolean = false,
    val isActiveSessionContextSummarizationInProgress: Boolean = false,
    val messages: List<ChatMessageUi> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val streamingText: String = "",
    val isCustomProfileBuilderVisible: Boolean = false,
    val customProfileBuilderSourceSessionId: String? = null,
    val customProfileBuilderInput: String = "",
    val customProfileBuilderMessages: List<ProfileBuilderMessageUi> = emptyList(),
    val customProfileBuilderStreamingText: String = "",
    val isCustomProfileBuilderSending: Boolean = false,
    val canApplyCustomProfile: Boolean = false,
    val customProfileBuilderErrorMessage: String? = null,
    val errorMessage: String? = null,
    val usage: ConversationUsageUi = ConversationUsageUi()
)
