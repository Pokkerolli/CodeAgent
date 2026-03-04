package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.model.UserProfileBuilderMessage
import com.pokkerolli.codeagent.domain.repository.ChatRepository
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
