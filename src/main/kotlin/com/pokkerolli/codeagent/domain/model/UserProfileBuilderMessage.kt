package com.pokkerolli.codeagent.domain.model

data class UserProfileBuilderMessage(
    val role: MessageRole,
    val content: String
)
