package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.model.ChatMessage
import com.pokkerolli.codeagent.domain.repository.ChatRepository
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
