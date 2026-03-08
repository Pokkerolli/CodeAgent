package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class ResumeTaskStreamingUseCase(
    private val repository: ChatRepository
) {
    fun execute(sessionId: String): Result<Flow<String>> {
        return runCatching {
            repository.resumeTaskStreaming(sessionId)
        }
    }
}
