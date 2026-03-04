package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.model.ChatSession
import com.pokkerolli.codeagent.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class ObserveSessionsUseCase(
    private val repository: ChatRepository
) {
    fun execute(): Result<Flow<List<ChatSession>>> {
        return runCatching {
            repository.observeSessions()
        }
    }
}
