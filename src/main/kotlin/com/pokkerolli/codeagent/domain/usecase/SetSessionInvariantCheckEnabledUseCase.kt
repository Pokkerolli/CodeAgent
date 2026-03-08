package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.repository.ChatRepository

class SetSessionInvariantCheckEnabledUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(sessionId: String, enabled: Boolean): Result<Unit> {
        return runCatching {
            repository.setSessionInvariantCheckEnabled(
                sessionId = sessionId,
                enabled = enabled
            )
        }
    }
}
