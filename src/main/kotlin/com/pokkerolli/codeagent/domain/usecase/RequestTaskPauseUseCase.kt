package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.repository.ChatRepository

class RequestTaskPauseUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(sessionId: String): Result<Unit> {
        return runCatching {
            repository.requestTaskPause(sessionId)
        }
    }
}
