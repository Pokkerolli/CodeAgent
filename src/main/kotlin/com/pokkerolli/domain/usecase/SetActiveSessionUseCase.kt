package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.repository.ChatRepository

class SetActiveSessionUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(sessionId: String): Result<Unit> {
        return runCatching {
            repository.setActiveSession(sessionId)
        }
    }
}
