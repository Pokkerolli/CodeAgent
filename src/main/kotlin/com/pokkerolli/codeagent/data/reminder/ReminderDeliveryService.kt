package com.pokkerolli.codeagent.data.reminder

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.pokkerolli.codeagent.data.local.dao.MessageDao
import com.pokkerolli.codeagent.data.local.dao.ReminderDeliveryDao
import com.pokkerolli.codeagent.data.local.dao.SessionDao
import com.pokkerolli.codeagent.data.local.db.AppDatabase
import com.pokkerolli.codeagent.data.local.entity.MessageEntity
import com.pokkerolli.codeagent.data.local.entity.ReminderDeliveryEntity
import com.pokkerolli.codeagent.data.remote.mcp.McpDiscoveredTool
import com.pokkerolli.codeagent.data.remote.mcp.McpToolsHelper
import com.pokkerolli.codeagent.domain.model.MessageRole
import com.pokkerolli.codeagent.domain.model.TaskStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class ReminderDeliveryService(
    private val database: AppDatabase,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val reminderDeliveryDao: ReminderDeliveryDao,
    private val mcpToolsHelper: McpToolsHelper,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val consumerId = "codeagent-${UUID.randomUUID()}"

    init {
        scope.launch {
            while (isActive) {
                pollOnceSafely()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun pollOnceSafely() {
        try {
            pollOnce()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            println("ReminderDeliveryService poll failed: ${throwable.message}")
        }
    }

    private suspend fun pollOnce() {
        val discovery = runCatching {
            mcpToolsHelper.discoverTools()
        }.getOrElse { return }

        val claimTool = discovery.tools.firstOrNull { it.name == CLAIM_DUE_REMINDERS_TOOL } ?: return
        val acknowledgeTool = discovery.tools.firstOrNull { it.name == ACKNOWLEDGE_REMINDERS_TOOL } ?: return

        val claimOutput = mcpToolsHelper.callTool(
            tool = claimTool,
            rawArguments = json.encodeJsonObject(
                buildJsonObject {
                    put("consumer_id", JsonPrimitive(consumerId))
                    put("limit", JsonPrimitive(CLAIM_LIMIT))
                }
            )
        ).output

        val claimResponse = runCatching {
            json.decodeFromString<ClaimDueRemindersToolResponse>(claimOutput)
        }.getOrElse { throwable ->
            println("ReminderDeliveryService failed to decode claim output: ${throwable.message}")
            return
        }

        if (claimResponse.reminders.isEmpty()) return

        val acknowledgedIds = mutableListOf<String>()
        claimResponse.reminders.forEach { reminder ->
            if (handleClaimedReminder(reminder)) {
                acknowledgedIds += reminder.reminderId
            }
        }

        if (acknowledgedIds.isEmpty()) return

        runCatching {
            mcpToolsHelper.callTool(
                tool = acknowledgeTool,
                rawArguments = json.encodeJsonObject(
                    buildJsonObject {
                        put("consumer_id", JsonPrimitive(consumerId))
                        put(
                            "reminder_ids",
                            buildJsonArray {
                                acknowledgedIds.forEach { reminderId ->
                                    add(JsonPrimitive(reminderId))
                                }
                            }
                        )
                    }
                )
            )
        }.onFailure { throwable ->
            println("ReminderDeliveryService acknowledge failed: ${throwable.message}")
        }
    }

    private suspend fun handleClaimedReminder(
        reminder: ClaimedReminderPayload
    ): Boolean {
        return runCatching {
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    if (reminderDeliveryDao.hasDelivery(reminder.reminderId)) {
                        return@immediateTransaction
                    }

                    val now = System.currentTimeMillis()
                    val session = sessionDao.getSessionById(reminder.chatSessionId)
                    val messageId = if (session != null) {
                        messageDao.insertMessage(
                            MessageEntity(
                                sessionId = reminder.chatSessionId,
                                role = MessageRole.ASSISTANT.name,
                                taskStage = TaskStage.CONVERSATION.name,
                                includeInModelContext = true,
                                content = reminder.message.toChatReminderText(),
                                createdAt = now
                            )
                        )
                    } else {
                        null
                    }

                    reminderDeliveryDao.insertDelivery(
                        ReminderDeliveryEntity(
                            reminderId = reminder.reminderId,
                            sessionId = reminder.chatSessionId,
                            chatMessageId = messageId,
                            deliveredAt = now
                        )
                    )

                    if (session != null) {
                        sessionDao.touchSession(reminder.chatSessionId, now)
                    }
                }
            }
            true
        }.getOrElse { throwable ->
            println(
                "ReminderDeliveryService failed to persist reminder ${reminder.reminderId}: " +
                    throwable.message
            )
            false
        }
    }
}

@Serializable
private data class ClaimDueRemindersToolResponse(
    val status: String,
    val consumerId: String,
    val claimedCount: Int,
    val reminders: List<ClaimedReminderPayload>
)

@Serializable
private data class ClaimedReminderPayload(
    val reminderId: String,
    val chatSessionId: String,
    val message: String,
    val remindAt: String,
    val createdAt: String,
    val claimedAt: String,
    val sourceText: String? = null
)

private fun Json.encodeJsonObject(value: JsonObject): String {
    return encodeToString(JsonObject.serializer(), value)
}

private fun String.toChatReminderText(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return "Напоминание."
    return if (trimmed.startsWith("Напоминание", ignoreCase = true)) {
        trimmed
    } else {
        "Напоминание: $trimmed"
    }
}

private const val CLAIM_DUE_REMINDERS_TOOL = "claim_due_reminders"
private const val ACKNOWLEDGE_REMINDERS_TOOL = "acknowledge_reminders"
private const val CLAIM_LIMIT = 20
private const val POLL_INTERVAL_MS = 10_000L
