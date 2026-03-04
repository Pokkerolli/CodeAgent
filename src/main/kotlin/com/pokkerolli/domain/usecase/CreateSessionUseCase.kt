package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.model.ChatSession
import com.pokkerolli.domain.repository.ChatRepository

class CreateSessionUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(): Result<ChatSession> {
        return runCatching {
            repository.createSession()
        }
    }
}
