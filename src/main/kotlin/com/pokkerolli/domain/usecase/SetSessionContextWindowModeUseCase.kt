package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.model.ContextWindowMode
import com.pokkerolli.domain.repository.ChatRepository

class SetSessionContextWindowModeUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(sessionId: String, mode: ContextWindowMode): Result<Unit> {
        return runCatching {
            repository.setSessionContextWindowMode(sessionId, mode)
        }
    }
}
