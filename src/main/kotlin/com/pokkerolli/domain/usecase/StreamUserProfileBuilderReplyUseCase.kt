package com.pokkerolli.domain.usecase

import com.pokkerolli.domain.model.UserProfileBuilderMessage
import com.pokkerolli.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class StreamUserProfileBuilderReplyUseCase(
    private val repository: ChatRepository
) {
    fun execute(
        history: List<UserProfileBuilderMessage>,
        userMessage: String?
    ): Result<Flow<String>> {
        return runCatching {
            repository.streamUserProfileBuilderReply(
                history = history,
                userMessage = userMessage
            )
        }
    }
}
