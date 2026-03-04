package com.pokkerolli.codeagent.data.remote.api

import com.pokkerolli.codeagent.data.remote.dto.ChatCompletionRequest
import com.pokkerolli.codeagent.data.remote.dto.ChatCompletionResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType

class DeepSeekApi(
    private val client: HttpClient,
    private val baseUrl: String
) {
    suspend fun prepareStreamChatCompletions(request: ChatCompletionRequest): HttpStatement {
        return client.preparePost("$baseUrl${CHAT_COMPLETIONS_PATH}") {
            accept(ContentType.Text.EventStream)
            setBody(request)
        }
    }

    suspend fun chatCompletions(
        request: ChatCompletionRequest
    ): ApiResponse<ChatCompletionResponse> {
        val response = client.post("$baseUrl${CHAT_COMPLETIONS_PATH}") {
            accept(ContentType.Application.Json)
            setBody(request)
        }
        val code = response.status.value
        val isSuccessful = code in 200..299
        return if (isSuccessful) {
            ApiResponse(
                isSuccessful = true,
                code = code,
                body = response.body<ChatCompletionResponse>(),
                rawError = null
            )
        } else {
            ApiResponse(
                isSuccessful = false,
                code = code,
                body = null,
                rawError = runCatching { response.bodyAsText() }.getOrNull()
            )
        }
    }

    private companion object {
        const val CHAT_COMPLETIONS_PATH = "chat/completions"
    }
}

data class ApiResponse<T>(
    val isSuccessful: Boolean,
    val code: Int,
    val body: T?,
    val rawError: String?
)
