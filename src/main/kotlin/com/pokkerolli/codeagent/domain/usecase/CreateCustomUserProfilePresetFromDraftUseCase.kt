package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.model.UserProfilePreset
import com.pokkerolli.codeagent.domain.repository.ChatRepository

class CreateCustomUserProfilePresetFromDraftUseCase(
    private val repository: ChatRepository
) {
    suspend fun execute(rawDraft: String): Result<UserProfilePreset> {
        return runCatching {
            repository.createCustomUserProfilePresetFromDraft(rawDraft)
        }
    }
}
