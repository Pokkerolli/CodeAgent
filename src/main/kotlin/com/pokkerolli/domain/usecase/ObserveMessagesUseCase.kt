package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.model.ChatMessage
import com.pokkerolli.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class ObserveMessagesUseCase(
    private val repository: ChatRepository
) {
    fun execute(sessionId: String): Result<Flow<List<ChatMessage>>> {
        return runCatching {
            repository.observeMessages(sessionId)
        }
    }
}
