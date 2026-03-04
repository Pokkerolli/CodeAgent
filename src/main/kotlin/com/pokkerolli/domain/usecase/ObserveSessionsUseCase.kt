package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.model.ChatSession
import com.pokkerolli.domain.repository.ChatRepository
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
