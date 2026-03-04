package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.repository.ChatRepository

class SetSessionSystemPromptUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(sessionId: String, systemPrompt: String?): Result<Unit> {
        return runCatching {
            repository.setSessionSystemPrompt(sessionId, systemPrompt)
        }
    }
}
