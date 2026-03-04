package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class GetActiveSessionUseCase(
    private val repository: ChatRepository
) {
    fun execute(): Result<Flow<String?>> {
        return runCatching {
            repository.observeActiveSessionId()
        }
    }
}
