package com.pokkerolli.data.local.mapper

import com.pokkerolli.data.local.entity.MessageEntity
import com.pokkerolli.data.local.entity.SessionEntity
import com.pokkerolli.data.local.entity.UserProfilePresetEntity
import com.pokkerolli.domain.model.ChatMessage
import com.pokkerolli.domain.model.ChatSession
import com.pokkerolli.domain.model.ContextWindowMode
import com.pokkerolli.domain.model.MessageRole
import com.pokkerolli.domain.model.UserProfilePreset

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
        isContextSummarizationInProgress = isContextSummarizationInProgress
    )
}

fun MessageEntity.toDomain(): ChatMessage {
    return ChatMessage(
        id = id,
        sessionId = sessionId,
        role = MessageRole.fromStored(role),
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
