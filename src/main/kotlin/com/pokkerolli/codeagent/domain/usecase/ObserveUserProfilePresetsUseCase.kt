package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.model.UserProfilePreset
import com.pokkerolli.codeagent.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class ObserveUserProfilePresetsUseCase(
    private val repository: ChatRepository
) {
    fun execute(): Result<Flow<List<UserProfilePreset>>> {
        return runCatching {
            repository.observeUserProfilePresets()
        }
    }
}
