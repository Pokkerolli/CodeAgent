package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.model.UserProfilePreset
import com.pokkerolli.domain.repository.ChatRepository
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
