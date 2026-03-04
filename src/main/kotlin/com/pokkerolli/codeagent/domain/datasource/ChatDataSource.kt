package com.pokkerolli.codeagent.domain.datasource

import com.pokkerolli.codeagent.domain.model.ChatMessage
import com.pokkerolli.codeagent.domain.model.ChatSession
import com.pokkerolli.codeagent.domain.model.ContextWindowMode
import com.pokkerolli.codeagent.domain.model.UserProfileBuilderMessage
import com.pokkerolli.codeagent.domain.model.UserProfilePreset
import kotlinx.coroutines.flow.Flow

interface ChatDataSource {
    fun observeSessions(): Flow<List<ChatSession>>
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>
    suspend fun createSession(): ChatSession
    suspend fun createSessionBranch(
        sourceSessionId: String,
        upToMessageIdInclusive: Long
    ): ChatSession
    suspend fun deleteSession(sessionId: String)
    suspend fun setActiveSession(sessionId: String)
    suspend fun setSessionSystemPrompt(sessionId: String, systemPrompt: String?)
    suspend fun setSessionUserProfile(sessionId: String, userProfileName: String?)
    suspend fun setSessionContextWindowMode(sessionId: String, mode: ContextWindowMode)
    fun observeUserProfilePresets(): Flow<List<UserProfilePreset>>
    fun streamUserProfileBuilderReply(
        history: List<UserProfileBuilderMessage>,
        userMessage: String?
    ): Flow<String>
    suspend fun createCustomUserProfilePresetFromDraft(rawDraft: String): UserProfilePreset
    suspend fun runContextSummarizationIfNeeded(sessionId: String)
    fun observeActiveSessionId(): Flow<String?>
    fun sendMessageStreaming(sessionId: String, content: String): Flow<String>
}
