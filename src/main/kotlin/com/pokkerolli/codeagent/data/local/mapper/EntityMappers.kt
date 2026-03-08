package com.pokkerolli.codeagent.data.local.mapper

import com.pokkerolli.codeagent.data.local.entity.MessageEntity
import com.pokkerolli.codeagent.data.local.entity.InvariantRuleEntity
import com.pokkerolli.codeagent.data.local.entity.SessionEntity
import com.pokkerolli.codeagent.data.local.entity.UserProfilePresetEntity
import com.pokkerolli.codeagent.domain.model.ChatMessage
import com.pokkerolli.codeagent.domain.model.ChatSession
import com.pokkerolli.codeagent.domain.model.ContextWindowMode
import com.pokkerolli.codeagent.domain.model.InvariantRule
import com.pokkerolli.codeagent.domain.model.MessageRole
import com.pokkerolli.codeagent.domain.model.TaskStage
import com.pokkerolli.codeagent.domain.model.UserProfilePreset

fun SessionEntity.toDomain(): ChatSession {
    return ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        systemPrompt = systemPrompt,
        userProfileName = userProfileName,
        contextWindowMode = ContextWindowMode.fromStored(contextWindowMode),
        isStickyFactsExtractionInProgress = isStickyFactsExtractionInProgress,
        contextSummary = contextSummary,
        summarizedMessagesCount = summarizedMessagesCount,
        isContextSummarizationInProgress = isContextSummarizationInProgress,
        taskStage = TaskStage.fromStored(taskStage),
        taskDescription = taskDescription,
        taskFinalResult = taskFinalResult,
        isInvariantCheckEnabled = isInvariantCheckEnabled,
        isTaskPaused = isTaskPaused
    )
}

fun MessageEntity.toDomain(): ChatMessage {
    return ChatMessage(
        id = id,
        sessionId = sessionId,
        role = MessageRole.fromStored(role),
        taskStage = TaskStage.fromStored(taskStage),
        content = content,
        timestamp = createdAt,
        promptTokens = promptTokens,
        promptCacheHitTokens = promptCacheHitTokens,
        promptCacheMissTokens = promptCacheMissTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens
    )
}

fun UserProfilePresetEntity.toDomain(): UserProfilePreset {
    return UserProfilePreset(
        profileName = profileName,
        label = label,
        payloadJson = payloadJson,
        isBuiltIn = false
    )
}

fun InvariantRuleEntity.toDomain(): InvariantRule {
    return InvariantRule(
        id = id,
        title = title,
        description = description
    )
}
