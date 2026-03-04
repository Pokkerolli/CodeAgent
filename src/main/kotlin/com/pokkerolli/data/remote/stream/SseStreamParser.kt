package com.pokkerolli.data.remote.stream

import com.pokkerolli.data.remote.dto.ChatCompletionUsage
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class SseStreamParser(
    private val json: Json
) {
    fun streamEvents(channel: ByteReadChannel): Flow<SseStreamEvent> = flow {
        val accumulated = StringBuilder()
        val eventPayload = StringBuilder()

        while (currentCoroutineContext().isActive) {
            val rawLine = channel.readUTF8Line() ?: break
            val line = rawLine.trimEnd('\r')

            if (line.startsWith(":")) continue

            if (line.isBlank()) {
                consumeBufferedPayload(eventPayload, accumulated, emit = ::emit)
                continue
            }

            val lineWithoutLeadingSpaces = line.trimStart()
            if (!lineWithoutLeadingSpaces.startsWith("data:")) continue
            val dataPart = lineWithoutLeadingSpaces.substringAfter("data:").trimStart()

            if (dataPart == DONE_TOKEN) break

            if (consumePayloadIfComplete(dataPart, accumulated, emit = ::emit)) {
                eventPayload.clear()
                continue
            }

            if (eventPayload.isNotEmpty()) eventPayload.append('\n')
            eventPayload.append(dataPart)

            consumeBufferedPayload(eventPayload, accumulated, emit = ::emit)
        }

        consumeBufferedPayload(eventPayload, accumulated, emit = ::emit)
    }

    private suspend fun consumeBufferedPayload(
        payloadBuffer: StringBuilder,
        accumulated: StringBuilder,
        emit: suspend (SseStreamEvent) -> Unit
    ) {
        val payload = payloadBuffer.toString().trim()
        if (payload.isEmpty() || payload == DONE_TOKEN) {
            payloadBuffer.clear()
            return
        }

        if (consumePayloadIfComplete(payload, accumulated, emit)) {
            payloadBuffer.clear()
        }
    }

    private suspend fun consumePayloadIfComplete(
        payload: String,
        accumulated: StringBuilder,
        emit: suspend (SseStreamEvent) -> Unit
    ): Boolean {
        val root = runCatching {
            json.parseToJsonElement(payload).jsonObject
        }.getOrNull() ?: return false

        val piece = root.extractDeltaText().ifEmpty {
            root.extractFallbackText()
        }

        val accumulatedText = accumulated.toString()
        val newPiece = when {
            piece.isEmpty() -> ""
            accumulatedText.isEmpty() -> piece
            piece.startsWith(accumulatedText) -> piece.removePrefix(accumulatedText)
            accumulatedText.endsWith(piece) -> ""
            else -> piece
        }
        if (newPiece.isNotEmpty()) {
            accumulated.append(newPiece)
            emit(SseStreamEvent.Text(accumulated.toString()))
        }

        root.extractUsage(json)?.let { usage ->
            emit(SseStreamEvent.Usage(usage))
        }

        return true
    }

    private fun JsonObject.extractDeltaText(): String {
        val choices = this["choices"] as? JsonArray ?: return ""
        return buildString {
            for (choiceElement in choices) {
                val choice = choiceElement as? JsonObject ?: continue
                val delta = choice["delta"] as? JsonObject ?: continue
                val contentPiece = delta.extractFlexibleText("content")
                    .ifEmpty { delta.extractFlexibleText("text") }
                val fallbackReasoning = if (contentPiece.isEmpty()) {
                    delta.extractFlexibleText("reasoning_content")
                } else {
                    ""
                }
                append(contentPiece)
                append(fallbackReasoning)
            }
        }
    }

    private fun JsonObject.extractFallbackText(): String {
        val direct = buildString {
            append(extractFlexibleText("delta"))
            append(extractFlexibleText("text"))
            append(extractFlexibleText("content"))
            append(extractFlexibleText("output_text"))
            append(extractFlexibleText("reasoning_content"))
        }
        if (direct.isNotEmpty()) return direct

        return findTextByKnownKeys(depth = 0, maxDepth = 8)
    }

    private fun JsonObject.extractUsage(json: Json): ChatCompletionUsage? {
        val usageElement = this["usage"] ?: return null
        return runCatching {
            json.decodeFromJsonElement(ChatCompletionUsage.serializer(), usageElement)
        }.getOrNull()
    }

    private fun JsonObject.extractFlexibleText(key: String): String {
        val element = this[key] ?: return ""
        return element.extractFlexibleText()
    }

    private fun JsonElement.extractFlexibleText(): String {
        return when (this) {
            is JsonPrimitive -> contentOrNull.orEmpty()
            is JsonArray -> buildString {
                for (item in this@extractFlexibleText) {
                    append(item.extractFlexibleText())
                }
            }

            is JsonObject -> {
                (this["text"] as? JsonPrimitive)?.contentOrNull
                    ?: (this["content"] as? JsonPrimitive)?.contentOrNull
                    ?: ""
            }
        }
    }

    private fun JsonObject.findTextByKnownKeys(depth: Int, maxDepth: Int): String {
        if (depth > maxDepth) return ""

        val collected = buildString {
            for ((key, value) in this@findTextByKnownKeys) {
                if (key in KNOWN_TEXT_KEYS) {
                    append(value.extractFlexibleText())
                    continue
                }

                when (value) {
                    is JsonObject -> append(value.findTextByKnownKeys(depth + 1, maxDepth))
                    is JsonArray -> append(value.findTextByKnownKeys(depth + 1, maxDepth))
                    else -> Unit
                }
            }
        }
        return collected
    }

    private fun JsonArray.findTextByKnownKeys(depth: Int, maxDepth: Int): String {
        if (depth > maxDepth) return ""
        return buildString {
            for (item in this@findTextByKnownKeys) {
                when (item) {
                    is JsonObject -> append(item.findTextByKnownKeys(depth + 1, maxDepth))
                    is JsonArray -> append(item.findTextByKnownKeys(depth + 1, maxDepth))
                    else -> Unit
                }
            }
        }
    }

    private companion object {
        const val DONE_TOKEN = "[DONE]"
        val KNOWN_TEXT_KEYS = setOf(
            "content",
            "text",
            "delta",
            "output_text",
            "reasoning_content"
        )
    }
}

sealed interface SseStreamEvent {
    data class Text(val value: String) : SseStreamEvent
    data class Usage(val value: ChatCompletionUsage) : SseStreamEvent
}
