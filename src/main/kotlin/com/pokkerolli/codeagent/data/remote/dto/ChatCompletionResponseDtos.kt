package com.pokkerolli.codeagent.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatCompletionResponseChoice> = emptyList(),
    val usage: ChatCompletionUsage? = null
)

@Serializable
data class ChatCompletionResponseChoice(
    val message: ChatCompletionResponseMessage? = null
)

@Serializable
data class ChatCompletionResponseMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatCompletionToolCall>? = null
)
