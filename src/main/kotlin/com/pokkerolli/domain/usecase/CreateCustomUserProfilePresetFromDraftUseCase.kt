package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.model.UserProfilePreset
import com.pokkerolli.domain.repository.ChatRepository

class CreateCustomUserProfilePresetFromDraftUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(rawDraft: String): Result<UserProfilePreset> {
        return runCatching {
            repository.createCustomUserProfilePresetFromDraft(rawDraft)
        }
    }
}
