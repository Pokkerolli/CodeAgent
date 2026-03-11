package com.pokkerolli.codeagent.data.remote.mcp

import com.pokkerolli.codeagent.config.McpServerConfig
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class McpToolsHelper(
    private val json: Json,
    private val servers: List<McpServerConfig>
) {
    @Volatile
    private var toolsCache: McpToolsCacheEntry? = null

    suspend fun requestToolsMessage(): String {
        val results = fetchToolsFromAllServers()
        ensureAtLeastOneSuccessfulServer(results)
        return formatToolsMessage(results)
    }

    suspend fun discoverTools(): McpToolsDiscoveryResult {
        val now = System.currentTimeMillis()
        val cachedTools = toolsCache
            ?.takeIf { it.expiresAtMillis > now }
            ?.tools
        if (!cachedTools.isNullOrEmpty()) {
            return McpToolsDiscoveryResult(
                tools = cachedTools,
                source = McpToolsDiscoverySource.CACHE
            )
        }

        val results = fetchToolsFromAllServers()
        ensureAtLeastOneSuccessfulServer(results)
        val discoveredTools = results
            .filterIsInstance<McpServerToolsFetchResult.Success>()
            .flatMap { result ->
                result.tools.map { tool ->
                    McpDiscoveredTool(
                        server = result.server,
                        name = tool.name,
                        description = tool.description,
                        inputSchema = tool.inputSchema
                    )
                }
            }
        toolsCache = McpToolsCacheEntry(
            tools = discoveredTools,
            expiresAtMillis = now + TOOLS_CACHE_TTL_MS
        )
        return McpToolsDiscoveryResult(
            tools = discoveredTools,
            source = McpToolsDiscoverySource.MCP
        )
    }

    suspend fun callTool(
        tool: McpDiscoveredTool,
        rawArguments: String?
    ): McpToolCallResult {
        val arguments = parseToolArguments(rawArguments)
        var lastFailure: Throwable? = null
        for (attempt in 0 until TOOLS_CALL_ATTEMPTS) {
            try {
                val output = callToolOnce(
                    server = tool.server,
                    toolName = tool.name,
                    arguments = arguments
                )
                return McpToolCallResult(
                    arguments = arguments,
                    output = output
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                lastFailure = throwable
                val isRetriable = throwable is SocketTimeoutException ||
                    throwable.message?.contains("timeout", ignoreCase = true) == true
                val canRetry = isRetriable && attempt < TOOLS_CALL_ATTEMPTS - 1
                if (!canRetry) {
                    throw wrapMcpFailure(tool.server, throwable)
                }
            }
            delay(TOOLS_RETRY_DELAY_MS)
        }
        throw wrapMcpFailure(
            tool.server,
            lastFailure ?: McpToolsException("Неизвестная ошибка вызова tool ${tool.name}.")
        )
    }

    private suspend fun fetchToolsFromAllServers(): List<McpServerToolsFetchResult> {
        if (servers.isEmpty()) {
            throw McpToolsException("Не удалось получить список tools: MCP серверы не настроены.")
        }
        return coroutineScope {
            servers.map { server ->
                async {
                    try {
                        McpServerToolsFetchResult.Success(
                            server = server,
                            tools = requestTools(server)
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        McpServerToolsFetchResult.Failure(
                            server = server,
                            error = wrapMcpFailure(server, throwable)
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private fun ensureAtLeastOneSuccessfulServer(results: List<McpServerToolsFetchResult>) {
        if (results.any { it is McpServerToolsFetchResult.Success }) return
        val reason = results
            .filterIsInstance<McpServerToolsFetchResult.Failure>()
            .joinToString(separator = "; ") { failure ->
                failure.error.message?.trim().orEmpty()
            }
            .ifBlank { "Неизвестная ошибка." }
        throw McpToolsException(reason)
    }

    private suspend fun requestTools(server: McpServerConfig): List<McpToolInfo> {
        var lastFailure: Throwable? = null
        for (attempt in 0 until TOOLS_FETCH_ATTEMPTS) {
            try {
                return requestToolsOnce(server)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                lastFailure = throwable
                val isRetriable = throwable is SocketTimeoutException ||
                    throwable.message?.contains("timeout", ignoreCase = true) == true
                val canRetry = isRetriable && attempt < TOOLS_FETCH_ATTEMPTS - 1
                if (!canRetry) {
                    throw throwable
                }
            }
            delay(TOOLS_RETRY_DELAY_MS)
        }
        throw lastFailure ?: McpToolsException("Неизвестная ошибка MCP ${server.name}.")
    }

    private suspend fun requestToolsOnce(server: McpServerConfig): List<McpToolInfo> {
        return withMcpSession(server) { httpClient, sessionId ->
            val toolsResponse = httpClient.post(server.url) {
                header(SESSION_HEADER, sessionId)
                setBody(buildToolsListPayload())
            }
            val toolsBody = toolsResponse.bodyAsText()
            ensureSuccessfulHttpResponse(
                serverName = server.name,
                stage = "tools/list",
                statusCode = toolsResponse.status.value,
                rawBody = toolsBody
            )
            ensureNoJsonRpcError(
                serverName = server.name,
                stage = "tools/list",
                rawBody = toolsBody
            )
            extractToolInfosFromToolsListPayload(toolsBody)
        }
    }

    private suspend fun callToolOnce(
        server: McpServerConfig,
        toolName: String,
        arguments: JsonObject
    ): String {
        return withMcpSession(server) { httpClient, sessionId ->
            val callResponse = httpClient.post(server.url) {
                header(SESSION_HEADER, sessionId)
                setBody(
                    buildToolsCallPayload(
                        toolName = toolName,
                        arguments = arguments
                    )
                )
            }
            val callBody = callResponse.bodyAsText()
            ensureSuccessfulHttpResponse(
                serverName = server.name,
                stage = "tools/call",
                statusCode = callResponse.status.value,
                rawBody = callBody
            )
            ensureNoJsonRpcError(
                serverName = server.name,
                stage = "tools/call",
                rawBody = callBody
            )
            extractToolCallTextFromPayload(
                serverName = server.name,
                toolName = toolName,
                rawBody = callBody
            )
        }
    }

    private suspend fun <T> withMcpSession(
        server: McpServerConfig,
        block: suspend (HttpClient, String) -> T
    ): T {
        val httpClient = createHttpClient()
        var sessionId: String? = null
        return try {
            val initializeResponse = httpClient.post(server.url) {
                setBody(buildInitializePayload())
            }
            val initializeBody = initializeResponse.bodyAsText()
            ensureSuccessfulHttpResponse(
                serverName = server.name,
                stage = "initialize",
                statusCode = initializeResponse.status.value,
                rawBody = initializeBody
            )
            sessionId = initializeResponse.headers[SESSION_HEADER]
                ?.trim()
                ?.ifBlank { null }
                ?: throw McpToolsException("MCP ${server.name}: initialize не вернул заголовок $SESSION_HEADER.")
            ensureNoJsonRpcError(
                serverName = server.name,
                stage = "initialize",
                rawBody = initializeBody
            )

            val initializedResponse = httpClient.post(server.url) {
                header(SESSION_HEADER, sessionId)
                setBody(buildInitializedNotificationPayload())
            }
            val initializedBody = initializedResponse.bodyAsText()
            ensureSuccessfulHttpResponse(
                serverName = server.name,
                stage = "notifications/initialized",
                statusCode = initializedResponse.status.value,
                rawBody = initializedBody
            )
            ensureNoJsonRpcError(
                serverName = server.name,
                stage = "notifications/initialized",
                rawBody = initializedBody
            )

            block(httpClient, sessionId)
        } finally {
            sessionId?.let { activeSessionId ->
                runCatching {
                    httpClient.delete(server.url) {
                        header(SESSION_HEADER, activeSessionId)
                    }
                }
            }
            runCatching { httpClient.close() }
        }
    }

    private fun createHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = HTTP_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = HTTP_SOCKET_TIMEOUT_MS
                requestTimeoutMillis = HTTP_REQUEST_TIMEOUT_MS
            }
            defaultRequest {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, MCP_ACCEPT_HEADER_VALUE)
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

    private fun buildToolsCallPayload(
        toolName: String,
        arguments: JsonObject
    ): String {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(TOOLS_CALL_REQUEST_ID))
            put("method", JsonPrimitive(METHOD_TOOLS_CALL))
            put(
                "params",
                buildJsonObject {
                    put("name", JsonPrimitive(toolName))
                    put("arguments", arguments)
                }
            )
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun parseToolArguments(rawArguments: String?): JsonObject {
        val trimmed = rawArguments?.trim().orEmpty()
        if (trimmed.isEmpty()) return buildJsonObject { }
        val parsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            ?: throw McpToolsException("Аргументы tool не являются валидным JSON.")
        return parsed as? JsonObject
            ?: throw McpToolsException("Аргументы tool должны быть JSON-объектом.")
    }

    private fun ensureSuccessfulHttpResponse(
        serverName: String,
        stage: String,
        statusCode: Int,
        rawBody: String
    ) {
        if (statusCode in 200..299) return
        val trimmedBody = rawBody.trim().ifEmpty { "<empty>" }
        throw McpToolsException(
            "MCP $serverName $stage вернул HTTP $statusCode: ${trimmedBody.take(MAX_ERROR_BODY_LENGTH)}"
        )
    }

    private fun ensureNoJsonRpcError(
        serverName: String,
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
        throw McpToolsException("MCP $serverName $stage вернул JSON-RPC ошибку: $codePrefix$message")
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
            val inputSchema = toolObject["inputSchema"] as? JsonObject
            McpToolInfo(
                name = name,
                description = description,
                inputSchema = inputSchema
            )
        }
    }

    private fun extractToolCallTextFromPayload(
        serverName: String,
        toolName: String,
        rawBody: String
    ): String {
        val root = parseJsonObjectOrNull(rawBody)
            ?: throw McpToolsException("MCP $serverName tools/call вернул некорректный JSON.")
        val result = root["result"] as? JsonObject
            ?: throw McpToolsException("MCP $serverName tools/call вернул ответ без result.")

        val contentArray = result["content"] as? JsonArray
        val textOutput = contentArray
            ?.mapNotNull { item ->
                val contentItem = item as? JsonObject ?: return@mapNotNull null
                contentItem["text"]?.jsonPrimitive?.contentOrNull
            }
            ?.joinToString(separator = "\n")
            ?.trim()
            .orEmpty()

        val fallbackOutput = runCatching {
            json.encodeToString(JsonObject.serializer(), result)
        }.getOrElse { rawBody }
        val output = if (textOutput.isNotBlank()) textOutput else fallbackOutput

        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
        if (isError) {
            throw McpToolsException("MCP $serverName tool $toolName вернул ошибку: $output")
        }
        return output
    }

    private fun parseJsonObjectOrNull(rawBody: String): JsonObject? {
        return runCatching {
            json.parseToJsonElement(rawBody)
        }.getOrNull() as? JsonObject
    }

    private fun formatToolsMessage(results: List<McpServerToolsFetchResult>): String {
        if (results.isEmpty()) {
            return "MCP серверы подключены. Доступные tools не найдены."
        }
        return results.joinToString(separator = "\n\n") { result ->
            when (result) {
                is McpServerToolsFetchResult.Success -> formatToolsMessage(result.server, result.tools)
                is McpServerToolsFetchResult.Failure -> result.error.message
                    ?.trim()
                    ?.ifBlank { null }
                    ?: "Не удалось получить список tools MCP ${result.server.name}."
            }
        }
    }

    private fun formatToolsMessage(
        server: McpServerConfig,
        tools: List<McpToolInfo>
    ): String {
        if (tools.isEmpty()) {
            return "MCP ${server.name} подключен. Доступные tools не найдены."
        }
        return buildString {
            appendLine("MCP ${server.name}: доступные tools")
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

    private fun wrapMcpFailure(
        server: McpServerConfig,
        throwable: Throwable
    ): McpToolsException {
        val reason = throwable.message?.trim().orEmpty()
        if (reason.isEmpty()) {
            return McpToolsException("Не удалось получить список tools MCP ${server.name}.", throwable)
        }
        return McpToolsException("Не удалось получить список tools MCP ${server.name}: $reason", throwable)
    }

    private companion object {
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val CLIENT_NAME = "codeagent-client"
        const val CLIENT_VERSION = "1.0.0"
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_INITIALIZE = "initialize"
        const val METHOD_INITIALIZED_NOTIFICATION = "notifications/initialized"
        const val METHOD_TOOLS_LIST = "tools/list"
        const val METHOD_TOOLS_CALL = "tools/call"
        const val INITIALIZE_REQUEST_ID = 1
        const val TOOLS_LIST_REQUEST_ID = 2
        const val TOOLS_CALL_REQUEST_ID = 3
        const val TOOLS_FETCH_ATTEMPTS = 2
        const val TOOLS_CALL_ATTEMPTS = 2
        const val TOOLS_RETRY_DELAY_MS = 600L
        const val TOOLS_CACHE_TTL_MS = 60_000L
        const val HTTP_CONNECT_TIMEOUT_MS = 20_000L
        const val HTTP_SOCKET_TIMEOUT_MS = 90_000L
        const val HTTP_REQUEST_TIMEOUT_MS = 90_000L
        const val HTTP_WRITE_TIMEOUT_MS = 30_000L
        const val HTTP_CALL_TIMEOUT_MS = 120_000L
        const val MAX_ERROR_BODY_LENGTH = 600
        const val MCP_ACCEPT_HEADER_VALUE = "application/json, text/event-stream"
    }
}

private sealed interface McpServerToolsFetchResult {
    val server: McpServerConfig

    data class Success(
        override val server: McpServerConfig,
        val tools: List<McpToolInfo>
    ) : McpServerToolsFetchResult

    data class Failure(
        override val server: McpServerConfig,
        val error: McpToolsException
    ) : McpServerToolsFetchResult
}

private data class McpToolInfo(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject?
)

data class McpDiscoveredTool(
    val server: McpServerConfig,
    val name: String,
    val description: String?,
    val inputSchema: JsonObject?
)

data class McpToolsDiscoveryResult(
    val tools: List<McpDiscoveredTool>,
    val source: McpToolsDiscoverySource
)

enum class McpToolsDiscoverySource {
    MCP,
    CACHE
}

data class McpToolCallResult(
    val arguments: JsonObject,
    val output: String
)

private data class McpToolsCacheEntry(
    val tools: List<McpDiscoveredTool>,
    val expiresAtMillis: Long
)

private class McpToolsException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
