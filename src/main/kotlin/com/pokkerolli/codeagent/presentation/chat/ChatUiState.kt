package com.pokkerolli.codeagent.presentation.chat

import com.pokkerolli.codeagent.domain.model.MessageRole
import com.pokkerolli.codeagent.domain.model.ContextWindowMode
import com.pokkerolli.codeagent.domain.model.TaskStage

data class ChatSessionUi(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val systemPrompt: String?,
    val userProfileName: String?,
    val contextWindowMode: ContextWindowMode,
    val isStickyFactsExtractionInProgress: Boolean,
    val isContextSummarizationInProgress: Boolean,
    val taskStage: TaskStage = TaskStage.CONVERSATION,
    val isInvariantCheckEnabled: Boolean = false,
    val isTaskPaused: Boolean = false
)

data class ChatMessageUi(
    val stableId: String,
    val sourceMessageId: Long? = null,
    val role: MessageRole,
    val taskStage: TaskStage = TaskStage.CONVERSATION,
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

data class InvariantRuleUi(
    val id: Long,
    val title: String,
    val description: String
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
    val activeSessionTaskStage: TaskStage = TaskStage.CONVERSATION,
    val activeSessionInvariantCheckEnabled: Boolean = false,
    val activeSessionTaskPaused: Boolean = false,
    val invariantRules: List<InvariantRuleUi> = emptyList(),
    val isInvariantEnableDialogVisible: Boolean = false,
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
