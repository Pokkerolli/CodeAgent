package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.model.ChatSession
import com.pokkerolli.codeagent.domain.repository.ChatRepository

class CreateSessionUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(): Result<ChatSession> {
        return runCatching {
            repository.createSession()
        }
    }
}
