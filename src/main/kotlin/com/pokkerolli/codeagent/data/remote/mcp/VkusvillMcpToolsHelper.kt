package com.pokkerolli.codeagent.data.remote.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class VkusvillMcpToolsHelper(
    private val json: Json
) {
    suspend fun requestToolsMessage(): String {
        var lastFailure: Throwable? = null
        for (attempt in 0 until TOOLS_FETCH_ATTEMPTS) {
            try {
                return requestToolsMessageOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                lastFailure = throwable
                val isRetriable = throwable is SocketTimeoutException ||
                    throwable.message?.contains("timeout", ignoreCase = true) == true
                val canRetry = isRetriable && attempt < TOOLS_FETCH_ATTEMPTS - 1
                if (!canRetry) {
                    throw wrapMcpFailure(throwable)
                }
            }
            delay(TOOLS_RETRY_DELAY_MS)
        }

        if (lastFailure != null) {
            throw wrapMcpFailure(lastFailure)
        }
        throw McpToolsException("Не удалось получить список tools MCP VKUSVILL.")
    }

    private suspend fun requestToolsMessageOnce(): String {
        val httpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = HTTP_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = HTTP_SOCKET_TIMEOUT_MS
                requestTimeoutMillis = HTTP_REQUEST_TIMEOUT_MS
            }
            defaultRequest {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json")
            }
            engine {
                config {
                    retryOnConnectionFailure(true)
                    connectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    readTimeout(HTTP_SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    writeTimeout(HTTP_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    callTimeout(HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }
        }

        var sessionId: String? = null
        return try {
            val initializeResponse = httpClient.post(VKUSVILL_MCP_URL) {
                setBody(buildInitializePayload())
            }
            val initializeBody = initializeResponse.bodyAsText()
            ensureSuccessfulHttpResponse(
                stage = "initialize",
                statusCode = initializeResponse.status.value,
                rawBody = initializeBody
            )
            sessionId = initializeResponse.headers[SESSION_HEADER]
                ?.trim()
                ?.ifBlank { null }
                ?: throw McpToolsException("MCP initialize не вернул заголовок Mcp-Session-Id.")
            ensureNoJsonRpcError(
                stage = "initialize",
                rawBody = initializeBody
            )

            httpClient.post(VKUSVILL_MCP_URL) {
                header(SESSION_HEADER, sessionId)
                setBody(buildInitializedNotificationPayload())
            }

            val toolsResponse = httpClient.post(VKUSVILL_MCP_URL) {
                header(SESSION_HEADER, sessionId)
                setBody(buildToolsListPayload())
            }
            val toolsBody = toolsResponse.bodyAsText()
            ensureSuccessfulHttpResponse(
                stage = "tools/list",
                statusCode = toolsResponse.status.value,
                rawBody = toolsBody
            )
            ensureNoJsonRpcError(
                stage = "tools/list",
                rawBody = toolsBody
            )
            formatToolsMessage(extractToolInfosFromToolsListPayload(toolsBody))
        } finally {
            sessionId?.let { activeSessionId ->
                runCatching {
                    httpClient.delete(VKUSVILL_MCP_URL) {
                        header(SESSION_HEADER, activeSessionId)
                    }
                }
            }
            runCatching { httpClient.close() }
        }
    }

    private fun buildInitializePayload(): String {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(INITIALIZE_REQUEST_ID))
            put("method", JsonPrimitive(METHOD_INITIALIZE))
            put(
                "params",
                buildJsonObject {
                    put("protocolVersion", JsonPrimitive(PROTOCOL_VERSION))
                    put("capabilities", buildJsonObject { })
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", JsonPrimitive(CLIENT_NAME))
                            put("version", JsonPrimitive(CLIENT_VERSION))
                        }
                    )
                }
            )
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun buildInitializedNotificationPayload(): String {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(METHOD_INITIALIZED_NOTIFICATION))
            put("params", buildJsonObject { })
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun buildToolsListPayload(): String {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(TOOLS_LIST_REQUEST_ID))
            put("method", JsonPrimitive(METHOD_TOOLS_LIST))
            put("params", buildJsonObject { })
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun ensureSuccessfulHttpResponse(
        stage: String,
        statusCode: Int,
        rawBody: String
    ) {
        if (statusCode in 200..299) return
        val trimmedBody = rawBody.trim().ifEmpty { "<empty>" }
        throw McpToolsException(
            "MCP $stage вернул HTTP $statusCode: ${trimmedBody.take(MAX_ERROR_BODY_LENGTH)}"
        )
    }

    private fun ensureNoJsonRpcError(
        stage: String,
        rawBody: String
    ) {
        val root = parseJsonObjectOrNull(rawBody) ?: return
        val error = root["error"] as? JsonObject ?: return
        val code = error["code"]?.jsonPrimitive?.intOrNull
        val message = error["message"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.ifEmpty { "Unknown MCP error" }
            ?: "Unknown MCP error"
        val codePrefix = code?.let { "[$it] " }.orEmpty()
        throw McpToolsException("MCP $stage вернул JSON-RPC ошибку: $codePrefix$message")
    }

    private fun extractToolInfosFromToolsListPayload(rawBody: String): List<McpToolInfo> {
        val root = parseJsonObjectOrNull(rawBody)
            ?: throw McpToolsException("MCP tools/list вернул некорректный JSON.")
        val result = root["result"] as? JsonObject ?: return emptyList()
        val tools = result["tools"] as? JsonArray ?: return emptyList()
        return tools.mapNotNull { item ->
            val toolObject = item as? JsonObject ?: return@mapNotNull null
            val name = toolObject["name"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val description = toolObject["description"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.ifBlank { null }
            McpToolInfo(
                name = name,
                description = description
            )
        }
    }

    private fun parseJsonObjectOrNull(rawBody: String): JsonObject? {
        return runCatching {
            json.parseToJsonElement(rawBody)
        }.getOrNull() as? JsonObject
    }

    private fun formatToolsMessage(tools: List<McpToolInfo>): String {
        if (tools.isEmpty()) {
            return "MCP VKUSVILL подключен. Доступные tools не найдены."
        }
        return buildString {
            appendLine("MCP VKUSVILL: доступные tools")
            tools.forEachIndexed { index, tool ->
                append(index + 1)
                append(". ")
                appendLine(tool.name)
                tool.description?.let { description ->
                    append("Описание: ")
                    appendLine(description)
                }
            }
        }.trimEnd()
    }

    private fun wrapMcpFailure(throwable: Throwable): McpToolsException {
        val reason = throwable.message?.trim().orEmpty()
        if (reason.isEmpty()) {
            return McpToolsException("Не удалось получить список tools MCP VKUSVILL.", throwable)
        }
        return McpToolsException("Не удалось получить список tools MCP VKUSVILL: $reason", throwable)
    }

    private companion object {
        const val VKUSVILL_MCP_URL = "https://mcp001.vkusvill.ru/mcp"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val CLIENT_NAME = "codeagent-client"
        const val CLIENT_VERSION = "1.0.0"
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_INITIALIZE = "initialize"
        const val METHOD_INITIALIZED_NOTIFICATION = "notifications/initialized"
        const val METHOD_TOOLS_LIST = "tools/list"
        const val INITIALIZE_REQUEST_ID = 1
        const val TOOLS_LIST_REQUEST_ID = 2
        const val TOOLS_FETCH_ATTEMPTS = 2
        const val TOOLS_RETRY_DELAY_MS = 600L
        const val HTTP_CONNECT_TIMEOUT_MS = 20_000L
        const val HTTP_SOCKET_TIMEOUT_MS = 90_000L
        const val HTTP_REQUEST_TIMEOUT_MS = 90_000L
        const val HTTP_WRITE_TIMEOUT_MS = 30_000L
        const val HTTP_CALL_TIMEOUT_MS = 120_000L
        const val MAX_ERROR_BODY_LENGTH = 600
    }
}

private data class McpToolInfo(
    val name: String,
    val description: String?
)

private class McpToolsException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
