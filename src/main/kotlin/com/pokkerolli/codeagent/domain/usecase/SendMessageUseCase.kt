package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.repository.ChatRepository
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
