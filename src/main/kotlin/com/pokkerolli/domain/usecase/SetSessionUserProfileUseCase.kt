package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.repository.ChatRepository

class SetSessionUserProfileUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(sessionId: String, userProfileName: String?): Result<Unit> {
        return runCatching {
            repository.setSessionUserProfile(sessionId, userProfileName)
        }
    }
}
