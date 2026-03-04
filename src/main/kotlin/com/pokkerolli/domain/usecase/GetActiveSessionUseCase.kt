package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.repository.ChatRepository
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
