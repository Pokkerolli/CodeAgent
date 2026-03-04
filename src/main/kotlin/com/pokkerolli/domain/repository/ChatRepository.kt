package com.pokkerolli.domain.repository

import com.pokkerolli.domain.datasource.ChatDataSource
import com.pokkerolli.domain.model.ChatMessage
import com.pokkerolli.domain.model.ChatSession
import com.pokkerolli.domain.model.ContextWindowMode
import com.pokkerolli.domain.model.UserProfileBuilderMessage
import com.pokkerolli.domain.model.UserProfilePreset
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val dataSource: ChatDataSource
) {
    fun observeSessions(): Flow<List<ChatSession>> {
        return dataSource.observeSessions()
    }

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> {
        return dataSource.observeMessages(sessionId)
    }

    suspend fun createSession(): ChatSession {
        return dataSource.createSession()
    }

    suspend fun createSessionBranch(
        sourceSessionId: String,
        upToMessageIdInclusive: Long
    ): ChatSession {
        return dataSource.createSessionBranch(
            sourceSessionId = sourceSessionId,
            upToMessageIdInclusive = upToMessageIdInclusive
        )
    }

    suspend fun deleteSession(sessionId: String) {
        dataSource.deleteSession(sessionId)
    }

    suspend fun setActiveSession(sessionId: String) {
        dataSource.setActiveSession(sessionId)
    }

    suspend fun setSessionSystemPrompt(sessionId: String, systemPrompt: String?) {
        dataSource.setSessionSystemPrompt(
            sessionId = sessionId,
            systemPrompt = systemPrompt
        )
    }

    suspend fun setSessionUserProfile(sessionId: String, userProfileName: String?) {
        dataSource.setSessionUserProfile(
            sessionId = sessionId,
            userProfileName = userProfileName
        )
    }

    suspend fun setSessionContextWindowMode(sessionId: String, mode: ContextWindowMode) {
        dataSource.setSessionContextWindowMode(
            sessionId = sessionId,
            mode = mode
        )
    }

    fun observeUserProfilePresets(): Flow<List<UserProfilePreset>> {
        return dataSource.observeUserProfilePresets()
    }

    fun streamUserProfileBuilderReply(
        history: List<UserProfileBuilderMessage>,
        userMessage: String?
    ): Flow<String> {
        return dataSource.streamUserProfileBuilderReply(
            history = history,
            userMessage = userMessage
        )
    }

    suspend fun createCustomUserProfilePresetFromDraft(rawDraft: String): UserProfilePreset {
        return dataSource.createCustomUserProfilePresetFromDraft(rawDraft)
    }

    suspend fun runContextSummarizationIfNeeded(sessionId: String) {
        dataSource.runContextSummarizationIfNeeded(sessionId)
    }

    fun observeActiveSessionId(): Flow<String?> {
        return dataSource.observeActiveSessionId()
    }

    fun sendMessageStreaming(sessionId: String, content: String): Flow<String> {
        return dataSource.sendMessageStreaming(
            sessionId = sessionId,
            content = content
        )
    }
}
