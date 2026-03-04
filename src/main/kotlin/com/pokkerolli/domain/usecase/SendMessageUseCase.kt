package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class SendMessageUseCase(
    private val repository: ChatRepository
) {
    fun execute(sessionId: String, content: String): Result<Flow<String>> {
        return runCatching {
            repository.sendMessageStreaming(sessionId, content)
        }
    }
}
