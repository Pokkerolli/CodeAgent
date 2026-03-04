package com.pokkerolli.domain.model

data class UserProfileBuilderMessage(
    val role: MessageRole,
    val content: String
)
