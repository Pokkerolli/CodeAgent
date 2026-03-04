package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.model.ChatSession
import com.pokkerolli.codeagent.domain.repository.ChatRepository

class CreateSessionBranchUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(
        sourceSessionId: String,
        upToMessageIdInclusive: Long
    ): Result<ChatSession> {
        return runCatching {
            repository.createSessionBranch(
                sourceSessionId = sourceSessionId,
                upToMessageIdInclusive = upToMessageIdInclusive
            )
        }
    }
}
