package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.model.ContextWindowMode
import com.pokkerolli.codeagent.domain.repository.ChatRepository

class SetSessionContextWindowModeUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(sessionId: String, mode: ContextWindowMode): Result<Unit> {
        return runCatching {
            repository.setSessionContextWindowMode(sessionId, mode)
        }
    }
}
