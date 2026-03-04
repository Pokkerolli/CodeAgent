package com.pokkerolli.codeagent.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatCompletionMessage>,
    val stream: Boolean,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null
)

@Serializable
data class ChatCompletionMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompletionChunk(
    val choices: List<Choice> = emptyList(),
    val usage: ChatCompletionUsage? = null
)

@Serializable
data class Choice(
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val content: String? = null
)

@Serializable
data class ChatCompletionUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null,
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)
