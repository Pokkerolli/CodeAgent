package com.pokkerolli.codeagent.data.datasource
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.pokkerolli.codeagent.data.local.dao.MessageDao
import com.pokkerolli.codeagent.data.local.dao.SessionDao
import com.pokkerolli.codeagent.data.local.dao.UserProfilePresetDao
import com.pokkerolli.codeagent.data.local.datastore.ActiveSessionPreferences
import com.pokkerolli.codeagent.data.local.db.AppDatabase
import com.pokkerolli.codeagent.data.local.entity.MessageCompressionState
import com.pokkerolli.codeagent.data.local.entity.MessageEntity
import com.pokkerolli.codeagent.data.local.entity.SessionEntity
import com.pokkerolli.codeagent.data.local.entity.UserProfilePresetEntity
import com.pokkerolli.codeagent.data.local.mapper.toDomain
import com.pokkerolli.codeagent.data.remote.api.DeepSeekApi
import com.pokkerolli.codeagent.data.remote.dto.ChatCompletionMessage
import com.pokkerolli.codeagent.data.remote.dto.ChatCompletionRequest
import com.pokkerolli.codeagent.data.remote.dto.ChatCompletionUsage
import com.pokkerolli.codeagent.data.remote.dto.StreamOptions
import com.pokkerolli.codeagent.data.remote.stream.SseStreamEvent
import com.pokkerolli.codeagent.data.remote.stream.SseStreamParser
import com.pokkerolli.codeagent.domain.datasource.ChatDataSource
import com.pokkerolli.codeagent.domain.model.ChatMessage
import com.pokkerolli.codeagent.domain.model.ChatSession
import com.pokkerolli.codeagent.domain.model.ContextWindowMode
import com.pokkerolli.codeagent.domain.model.MessageRole
import com.pokkerolli.codeagent.domain.model.USER_PROFILE_PRESETS
import com.pokkerolli.codeagent.domain.model.UserProfileBuilderMessage
import com.pokkerolli.codeagent.domain.model.UserProfilePreset
import com.pokkerolli.codeagent.domain.model.findBuiltInUserProfilePreset
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatDataSourceImpl(
    private val database: AppDatabase,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val userProfilePresetDao: UserProfilePresetDao,
    private val deepSeekApi: DeepSeekApi,
    private val sseStreamParser: SseStreamParser,
    private val activeSessionPreferences: ActiveSessionPreferences,
    private val json: Json
) : ChatDataSource {

    private val summarizationMutexBySession = ConcurrentHashMap<String, Mutex>()

    override fun observeSessions(): Flow<List<ChatSession>> {
        return sessionDao.observeSessions().map { sessions ->
            sessions.map { it.toDomain() }
        }
    }

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> {
        return messageDao.observeMessages(sessionId).map { messages ->
            messages.map { it.toDomain() }
        }
    }

    override suspend fun createSession(): ChatSession {
        val now = System.currentTimeMillis()
        val entity = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = DEFAULT_SESSION_TITLE,
            createdAt = now,
            updatedAt = now
        )
        sessionDao.insertSession(entity)
        return entity.toDomain()
    }

    override suspend fun createSessionBranch(
        sourceSessionId: String,
        upToMessageIdInclusive: Long
    ): ChatSession {
        val sourceSession = sessionDao.getSessionById(sourceSessionId)
            ?: throw ChatApiException("Source session not found")
        val allSourceMessages = messageDao.getMessagesOnce(sourceSessionId)
        val branchBoundaryIndex = allSourceMessages.indexOfFirst { it.id == upToMessageIdInclusive }
        if (branchBoundaryIndex < 0) {
            throw ChatApiException("Source message not found")
        }
        val sourceMessages = allSourceMessages.take(branchBoundaryIndex + 1)
        val now = System.currentTimeMillis()

        val branchSession = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = BRANCH_TITLE_PREFIX + sourceSession.title,
            createdAt = now,
            updatedAt = now,
            systemPrompt = sourceSession.systemPrompt,
            userProfileName = sourceSession.userProfileName,
            contextWindowMode = sourceSession.contextWindowMode,
            longTermMemoryJson = sourceSession.longTermMemoryJson,
            currentWorkTaskJson = sourceSession.currentWorkTaskJson,
            stickyFactsJson = sourceSession.stickyFactsJson,
            isStickyFactsExtractionInProgress = false,
            contextSummary = sourceSession.contextSummary,
            summarizedMessagesCount = sourceSession.summarizedMessagesCount,
            isContextSummarizationInProgress = false
        )

        database.withTransactionCompat {
            sessionDao.insertSession(branchSession)
            if (sourceMessages.isNotEmpty()) {
                messageDao.insertMessages(
                    sourceMessages.map { source ->
                        MessageEntity(
                            sessionId = branchSession.id,
                            role = source.role,
                            content = source.content,
                            createdAt = source.createdAt,
                            promptTokens = source.promptTokens,
                            promptCacheHitTokens = source.promptCacheHitTokens,
                            promptCacheMissTokens = source.promptCacheMissTokens,
                            completionTokens = source.completionTokens,
                            totalTokens = source.totalTokens,
                            compressionState = source.compressionState
                        )
                    }
                )
            }
        }

        return branchSession.toDomain()
    }

    override suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSessionById(sessionId)
        summarizationMutexBySession.remove(sessionId)
    }

    override suspend fun setActiveSession(sessionId: String) {
        activeSessionPreferences.setActiveSessionId(sessionId)
    }

    override suspend fun setSessionSystemPrompt(sessionId: String, systemPrompt: String?) {
        val now = System.currentTimeMillis()
        val normalizedPrompt = systemPrompt.normalizeSystemPrompt()
        val existingSession = sessionDao.getSessionById(sessionId)

        if (existingSession == null) {
            sessionDao.insertSession(
                SessionEntity(
                    id = sessionId,
                    title = DEFAULT_SESSION_TITLE,
                    createdAt = now,
                    updatedAt = now,
                    systemPrompt = normalizedPrompt
                )
            )
            return
        }

        sessionDao.updateSystemPrompt(
            sessionId = sessionId,
            systemPrompt = normalizedPrompt,
            updatedAt = now
        )
    }

    override suspend fun setSessionUserProfile(sessionId: String, userProfileName: String?) {
        val now = System.currentTimeMillis()
        if (messageDao.getMessagesOnce(sessionId).isNotEmpty()) return

        val requestedProfileName = userProfileName?.trim()?.ifBlank { null }
        val normalizedProfileName = when {
            requestedProfileName == null -> null
            findBuiltInUserProfilePreset(requestedProfileName) != null -> requestedProfileName
            userProfilePresetDao.getByProfileName(requestedProfileName) != null -> requestedProfileName
            else -> return
        }
        val existingSession = sessionDao.getSessionById(sessionId)

        if (existingSession == null) {
            sessionDao.insertSession(
                SessionEntity(
                    id = sessionId,
                    title = DEFAULT_SESSION_TITLE,
                    createdAt = now,
                    updatedAt = now,
                    userProfileName = normalizedProfileName
                )
            )
            return
        }

        sessionDao.updateUserProfileName(
            sessionId = sessionId,
            userProfileName = normalizedProfileName,
            updatedAt = now
        )
    }

    override suspend fun setSessionContextWindowMode(sessionId: String, mode: ContextWindowMode) {
        val now = System.currentTimeMillis()
        if (messageDao.getMessagesOnce(sessionId).isNotEmpty()) return

        val existingSession = sessionDao.getSessionById(sessionId)

        if (existingSession == null) {
            sessionDao.insertSession(
                SessionEntity(
                    id = sessionId,
                    title = DEFAULT_SESSION_TITLE,
                    createdAt = now,
                    updatedAt = now,
                    contextWindowMode = mode.name,
                    contextSummary = null,
                    summarizedMessagesCount = 0
                )
            )
            return
        }

        sessionDao.updateContextWindowMode(
            sessionId = sessionId,
            contextWindowMode = mode.name,
            contextSummary = null,
            summarizedMessagesCount = 0,
            updatedAt = now
        )
    }

    override suspend fun runContextSummarizationIfNeeded(sessionId: String) {
        val mutex = summarizationMutexBySession.getOrPut(sessionId) { Mutex() }
        mutex.withLock {
            compressReadyMessages(sessionId)
        }
    }

    override fun observeActiveSessionId(): Flow<String?> {
        return activeSessionPreferences.observeActiveSessionId()
    }

    override fun observeUserProfilePresets(): Flow<List<UserProfilePreset>> {
        return userProfilePresetDao.observeAll().map { customPresets ->
            USER_PROFILE_PRESETS + customPresets.map { it.toDomain() }
        }
    }

    override fun streamUserProfileBuilderReply(
        history: List<UserProfileBuilderMessage>,
        userMessage: String?
    ): Flow<String> = flow {
        val normalizedHistory = history
            .mapNotNull { message ->
                val content = message.content.trim()
                if (content.isEmpty()) return@mapNotNull null
                ChatCompletionMessage(
                    role = message.role.apiValue,
                    content = content
                )
            }

        val normalizedUserMessage = userMessage?.trim()?.ifBlank { null }

        val requestMessages = buildList {
            add(
                ChatCompletionMessage(
                    role = SYSTEM_ROLE,
                    content = USER_PROFILE_BUILDER_SYSTEM_PROMPT
                )
            )
            addAll(normalizedHistory)
            add(
                ChatCompletionMessage(
                    role = USER_ROLE,
                    content = normalizedUserMessage ?: USER_PROFILE_BUILDER_KICKOFF_MESSAGE
                )
            )
        }

        val request = ChatCompletionRequest(
            model = DEFAULT_MODEL,
            messages = requestMessages,
            stream = true,
            streamOptions = StreamOptions(includeUsage = false)
        )

        val statement = deepSeekApi.prepareStreamChatCompletions(request)
        statement.execute { response ->
            val code = response.status.value
            if (code !in 200..299) {
                val rawError = runCatching { response.bodyAsText() }.getOrNull()
                throw ChatApiException(buildApiErrorMessage(code = code, rawError = rawError))
            }

            val responseBody = response.bodyAsChannel()
            sseStreamParser.streamEvents(responseBody).collect { event ->
                if (event is SseStreamEvent.Text && event.value.isNotEmpty()) {
                    emit(event.value)
                }
            }
        }
    }

    override suspend fun createCustomUserProfilePresetFromDraft(rawDraft: String): UserProfilePreset {
        val draftPayload = extractJsonObjectCandidate(rawDraft)
            ?: throw ChatApiException("Не удалось найти JSON с USER_PROFILE в ответе ассистента")

        val rootObject = runCatching {
            json.parseToJsonElement(draftPayload)
        }.getOrNull() as? JsonObject
            ?: throw ChatApiException("USER_PROFILE должен быть корректным JSON")

        val profileObject = extractProfileObject(rootObject)
            ?: throw ChatApiException("В JSON не найден объект USER_PROFILE")

        val requestedProfileName = (profileObject[USER_PROFILE_NAME_KEY] as? JsonPrimitive)
            ?.contentOrNull
            .normalizeProfileName()
            ?: DEFAULT_CUSTOM_PROFILE_NAME

        val uniqueProfileName = resolveUniqueProfileName(requestedProfileName)
        val normalizedLabel = uniqueProfileName.toProfileLabel()
        val normalizedProfileObject = buildJsonObject {
            profileObject.forEach { (key, value) ->
                put(key, value)
            }
            put(USER_PROFILE_NAME_KEY, JsonPrimitive(uniqueProfileName))
        }
        val payloadJson = json.encodeToString(JsonObject.serializer(), normalizedProfileObject)
        val now = System.currentTimeMillis()

        userProfilePresetDao.upsert(
            UserProfilePresetEntity(
                profileName = uniqueProfileName,
                label = normalizedLabel,
                payloadJson = payloadJson,
                createdAt = now,
                updatedAt = now
            )
        )

        return UserProfilePreset(
            profileName = uniqueProfileName,
            label = normalizedLabel,
            payloadJson = payloadJson,
            isBuiltIn = false
        )
    }

    override fun sendMessageStreaming(sessionId: String, content: String): Flow<String> = flow {
        try {
            val cleanedContent = content.trim()
            if (cleanedContent.isEmpty()) return@flow

            val now = System.currentTimeMillis()
            val existingSession = sessionDao.getSessionById(sessionId)
            val controlCommand = parseControlCommand(cleanedContent)
            val session = existingSession ?: SessionEntity(
                id = sessionId,
                title = DEFAULT_SESSION_TITLE,
                createdAt = now,
                updatedAt = now
            )

            val resolvedTitle = if (
                controlCommand == null &&
                session.title == DEFAULT_SESSION_TITLE
            ) {
                cleanedContent.toSessionTitle()
            } else {
                session.title
            }

            val sessionSnapshot = session.copy(
                title = resolvedTitle,
                updatedAt = now
            )

            var userMessageId: Long = 0L
            database.withTransactionCompat {
                sessionDao.insertSession(sessionSnapshot)
                userMessageId = messageDao.insertMessage(
                    MessageEntity(
                        sessionId = sessionId,
                        role = MessageRole.USER.name,
                        content = cleanedContent,
                        createdAt = now
                    )
                )
            }

            if (controlCommand != null) {
                val commandResponse = handleControlCommand(
                    session = sessionSnapshot,
                    command = controlCommand
                )
                val doneTimestamp = System.currentTimeMillis()
                database.withTransactionCompat {
                    if (commandResponse.memoryChanged) {
                        sessionDao.updateLongTermMemory(
                            sessionId = sessionId,
                            longTermMemoryJson = serializeLongTermMemoryInstructions(
                                commandResponse.memoryInstructions.orEmpty()
                            ),
                            updatedAt = doneTimestamp
                        )
                    }
                    if (commandResponse.workTaskChanged) {
                        sessionDao.updateCurrentWorkTask(
                            sessionId = sessionId,
                            currentWorkTaskJson = serializeCurrentWorkTask(commandResponse.workTaskContext),
                            updatedAt = doneTimestamp
                        )
                    }
                    messageDao.insertMessage(
                        MessageEntity(
                            sessionId = sessionId,
                            role = MessageRole.ASSISTANT.name,
                            content = commandResponse.reply,
                            createdAt = doneTimestamp
                        )
                    )
                    sessionDao.touchSession(sessionId, doneTimestamp)
                }
                emit(commandResponse.reply)
                return@flow
            }

            val allMessages = messageDao.getMessagesOnce(sessionId)
            val modelMessages = allMessages.filterControlCommandMessagesForModelContext()
            val contextMode = ContextWindowMode.fromStored(sessionSnapshot.contextWindowMode)
            val contextMessages = buildUserRequestContextMessages(
                sessionId = sessionId,
                session = sessionSnapshot,
                allMessages = modelMessages
            )
            val userProfilePayload = resolveUserProfilePayload(sessionSnapshot.userProfileName)

            val contextBlock = buildContextBlock(
                baseSystemPrompt = sessionSnapshot.systemPrompt.normalizeSystemPrompt(),
                userProfilePayload = userProfilePayload,
                memoryInstructions = parseLongTermMemoryInstructions(sessionSnapshot.longTermMemoryJson),
                currentWorkTask = parseCurrentWorkTask(sessionSnapshot.currentWorkTaskJson)
            )
            val requestMessages = buildList {
                if (contextBlock != null) {
                    add(
                        ChatCompletionMessage(
                            role = SYSTEM_ROLE,
                            content = contextBlock
                        )
                    )
                }
                addAll(contextMessages)
            }

            val request = ChatCompletionRequest(
                model = DEFAULT_MODEL,
                messages = requestMessages,
                stream = true,
                streamOptions = StreamOptions(includeUsage = true)
            )
            var finalAssistantText = ""
            var finalUsage: ChatCompletionUsage? = null

            val statement = deepSeekApi.prepareStreamChatCompletions(request)
            statement.execute { response ->
                val code = response.status.value
                if (code !in 200..299) {
                    val rawError = runCatching { response.bodyAsText() }.getOrNull()
                    throw ChatApiException(buildApiErrorMessage(code = code, rawError = rawError))
                }

                val responseBody = response.bodyAsChannel()
                sseStreamParser.streamEvents(responseBody).collect { event ->
                    when (event) {
                        is SseStreamEvent.Text -> {
                            finalAssistantText = event.value
                            if (finalAssistantText.isNotEmpty()) {
                                emit(finalAssistantText)
                            }
                        }

                        is SseStreamEvent.Usage -> {
                            finalUsage = event.value
                        }
                    }
                }
            }

            val promptUsage = resolvePromptUsage(finalUsage)
            val doneTimestamp = System.currentTimeMillis()

            database.withTransactionCompat {
                if (promptUsage != null && userMessageId > 0L) {
                    messageDao.updateUserMessageUsage(
                        messageId = userMessageId,
                        promptTokens = promptUsage.promptTokens,
                        promptCacheHitTokens = promptUsage.promptCacheHitTokens,
                        promptCacheMissTokens = promptUsage.promptCacheMissTokens
                    )
                }

                if (finalAssistantText.isNotBlank()) {
                    messageDao.insertMessage(
                        MessageEntity(
                            sessionId = sessionId,
                            role = MessageRole.ASSISTANT.name,
                            content = finalAssistantText,
                            createdAt = doneTimestamp,
                            completionTokens = finalUsage?.completionTokens,
                            totalTokens = finalUsage?.totalTokens
                        )
                    )
                    sessionDao.touchSession(sessionId, doneTimestamp)
                }
            }

            if (
                contextMode == ContextWindowMode.STICKY_FACTS_KEY_VALUE &&
                finalAssistantText.isNotBlank()
            ) {
                sessionDao.updateStickyFactsExtractionInProgress(sessionId, true)
                try {
                    val updatedFacts = requestStickyFactsUpdate(
                        currentFactsJson = sessionSnapshot.stickyFactsJson,
                        userMessage = cleanedContent,
                        assistantMessage = finalAssistantText
                    )

                    if (updatedFacts != null) {
                        sessionDao.updateStickyFacts(
                            sessionId = sessionId,
                            stickyFactsJson = serializeStickyFacts(updatedFacts)
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Memory extraction must not break user-visible response flow.
                } finally {
                    sessionDao.updateStickyFactsExtractionInProgress(sessionId, false)
                }
            }

        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun buildUserRequestContextMessages(
        sessionId: String,
        session: SessionEntity,
        allMessages: List<MessageEntity>
    ): List<ChatCompletionMessage> {
        val contextMode = ContextWindowMode.fromStored(session.contextWindowMode)
        if (allMessages.isEmpty()) return emptyList()

        return when (contextMode) {
            ContextWindowMode.FULL_HISTORY -> allMessages.toApiMessages()
            ContextWindowMode.SLIDING_WINDOW_LAST_10 -> {
                val currentRequest = allMessages.last()
                val recentHistory = allMessages
                    .dropLast(1)
                    .takeLast(CONTEXT_TAIL_MESSAGES_COUNT)
                (recentHistory + currentRequest).toApiMessages()
            }
            ContextWindowMode.SUMMARY_PLUS_LAST_10 -> buildSummaryPlusTailContextMessages(
                sessionId = sessionId,
                session = session,
                allMessages = allMessages
            )
            ContextWindowMode.STICKY_FACTS_KEY_VALUE -> buildStickyFactsContextMessages(
                session = session,
                allMessages = allMessages
            )
        }
    }

    private fun buildStickyFactsContextMessages(
        session: SessionEntity,
        allMessages: List<MessageEntity>
    ): List<ChatCompletionMessage> {
        if (allMessages.isEmpty()) return emptyList()

        val recentMessages = allMessages.takeLast(CONTEXT_TAIL_MESSAGES_COUNT)
        val stickyFacts = parseStickyFactsMap(session.stickyFactsJson)

        return buildList {
            if (stickyFacts.isNotEmpty()) {
                add(
                    ChatCompletionMessage(
                        role = SYSTEM_ROLE,
                        content = buildStickyFactsContextPrompt(stickyFacts)
                    )
                )
            }
            addAll(recentMessages.toApiMessages())
        }
    }

    private suspend fun buildSummaryPlusTailContextMessages(
        sessionId: String,
        session: SessionEntity,
        allMessages: List<MessageEntity>
    ): List<ChatCompletionMessage> {
        if (allMessages.size <= CONTEXT_TAIL_MESSAGES_COUNT) {
            return allMessages.toApiMessages()
        }

        val splitIndex = (allMessages.size - CONTEXT_TAIL_MESSAGES_COUNT).coerceAtLeast(0)
        val olderMessages = allMessages.take(splitIndex)
        val recentMessages = allMessages.drop(splitIndex)

        markOlderMessagesReadyForCompression(
            sessionId = sessionId,
            olderMessages = olderMessages
        )

        val readyMessages = olderMessages.filter {
            it.compressionState.toCompressionState() != MessageCompressionState.SUMMARIZED
        }

        return buildList {
            session.contextSummary.normalizeSummary()?.let { summary ->
                add(
                    ChatCompletionMessage(
                        role = SYSTEM_ROLE,
                        content = CONTEXT_SUMMARY_PREFIX + "\n" + summary
                    )
                )
            }
            addAll(readyMessages.toApiMessages())
            addAll(recentMessages.toApiMessages())
        }
    }

    private suspend fun markOlderMessagesReadyForCompression(
        sessionId: String,
        olderMessages: List<MessageEntity>
    ) {
        val idsToReady = olderMessages
            .filter { it.compressionState.toCompressionState() == MessageCompressionState.ACTIVE }
            .map { it.id }

        if (idsToReady.isEmpty()) return

        messageDao.updateCompressionStateForIds(
            messageIds = idsToReady,
            state = MessageCompressionState.READY_FOR_SUMMARY.name
        )
    }

    private suspend fun compressReadyMessages(sessionId: String) {
        try {
            while (true) {
                val session = sessionDao.getSessionById(sessionId) ?: return
                if (
                    ContextWindowMode.fromStored(session.contextWindowMode) !=
                    ContextWindowMode.SUMMARY_PLUS_LAST_10
                ) {
                    return
                }

                val allMessages = messageDao.getMessagesOnce(sessionId)
                val modelMessages = allMessages.filterControlCommandMessagesForModelContext()
                if (modelMessages.size <= CONTEXT_TAIL_MESSAGES_COUNT) return

                val splitIndex = (modelMessages.size - CONTEXT_TAIL_MESSAGES_COUNT).coerceAtLeast(0)
                val olderMessages = modelMessages.take(splitIndex)

                markOlderMessagesReadyForCompression(
                    sessionId = sessionId,
                    olderMessages = olderMessages
                )

                val readyBatch = olderMessages
                    .filter { it.compressionState.toCompressionState() != MessageCompressionState.SUMMARIZED }
                    .take(SUMMARY_BATCH_SIZE)

                if (readyBatch.size < SUMMARY_BATCH_SIZE) return

                sessionDao.updateContextSummarizationInProgress(sessionId, true)
                val updatedSummary = requestCompressedSummary(
                    currentSummary = session.contextSummary.normalizeSummary(),
                    messagesBatch = readyBatch
                ) ?: return

                val batchIds = readyBatch.map { it.id }

                database.withTransactionCompat {
                    messageDao.updateCompressionStateForIds(
                        messageIds = batchIds,
                        state = MessageCompressionState.SUMMARIZED.name
                    )

                    val summarizedCount = messageDao.getMessagesOnce(sessionId)
                        .filterControlCommandMessagesForModelContext()
                        .count {
                            it.compressionState.toCompressionState() ==
                                MessageCompressionState.SUMMARIZED
                        }

                    sessionDao.updateContextSummary(
                        sessionId = sessionId,
                        contextSummary = updatedSummary,
                        summarizedMessagesCount = summarizedCount
                    )
                }
            }
        } finally {
            sessionDao.updateContextSummarizationInProgress(sessionId, false)
        }
    }

    private suspend fun requestCompressedSummary(
        currentSummary: String?,
        messagesBatch: List<MessageEntity>
    ): String? {
        val payload = buildSummaryCompressionPayload(
            currentSummary = currentSummary,
            messagesBatch = messagesBatch
        )

        val request = ChatCompletionRequest(
            model = DEFAULT_MODEL,
            messages = listOf(
                ChatCompletionMessage(
                    role = SYSTEM_ROLE,
                    content = SUMMARY_COMPRESSION_SYSTEM_PROMPT
                ),
                ChatCompletionMessage(
                    role = USER_ROLE,
                    content = payload
                )
            ),
            stream = false
        )

        val response = deepSeekApi.chatCompletions(request)
        val body = response.body

        if (!response.isSuccessful || body == null) {
            throw ChatApiException(
                buildApiErrorMessage(
                    code = response.code,
                    rawError = response.rawError
                )
            )
        }

        return body
            .choices
            .firstOrNull()
            ?.message
            ?.content
            .normalizeSummary()
            ?.trimSummaryLength()
    }

    private fun buildSummaryCompressionPayload(
        currentSummary: String?,
        messagesBatch: List<MessageEntity>
    ): String {
        val batchText = messagesBatch.joinToString(separator = "\n") { message ->
            val roleLabel = when (MessageRole.fromStored(message.role)) {
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Assistant"
            }
            "- $roleLabel: ${message.content.trim()}"
        }

        return buildString {
            append("Обнови краткое summary истории диалога.\n")
            append("Правила:\n")
            append("1) Сохрани факты, цели, ограничения, решения, договоренности и важные предпочтения.\n")
            append("2) Не добавляй выдумки и не теряй смысл.\n")
            append("3) Пиши без воды, компактно.\n")
            append("4) Верни только новое summary, без пояснений.\n")

            append("\nТекущее summary:\n")
            append(currentSummary?.ifBlank { "(пусто)" } ?: "(пусто)")

            append("\n\nНовые 10 сообщений для сжатия:\n")
            append(batchText)
        }
    }

    private fun buildStickyFactsContextPrompt(facts: Map<String, String>): String {
        val factsJson = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                facts.toSortedMap().forEach { (key, value) ->
                    put(key, JsonPrimitive(value))
                }
            }
        )

        return buildString {
            append("Sticky facts for this session in key/value JSON.\n")
            append("Use them as durable context. If user message conflicts, prioritize new message.\n")
            append("Facts JSON:\n")
            append(factsJson)
        }
    }

    private suspend fun requestStickyFactsUpdate(
        currentFactsJson: String?,
        userMessage: String,
        assistantMessage: String
    ): Map<String, String>? {
        val currentFacts = parseStickyFactsMap(currentFactsJson)
        val extractionPayload = buildStickyFactsExtractionPayload(
            currentFacts = currentFacts,
            userMessage = userMessage,
            assistantMessage = assistantMessage
        )

        val request = ChatCompletionRequest(
            model = DEFAULT_MODEL,
            messages = listOf(
                ChatCompletionMessage(
                    role = SYSTEM_ROLE,
                    content = STICKY_FACTS_EXTRACTION_SYSTEM_PROMPT
                ),
                ChatCompletionMessage(
                    role = USER_ROLE,
                    content = extractionPayload
                )
            ),
            stream = false
        )

        val response = deepSeekApi.chatCompletions(request)
        val body = response.body

        if (!response.isSuccessful || body == null) {
            throw ChatApiException(
                buildApiErrorMessage(
                    code = response.code,
                    rawError = response.rawError
                )
            )
        }

        val rawContent = body.choices.firstOrNull()?.message?.content.orEmpty()
        return parseStickyFactsExtractionResult(rawContent)
    }

    private fun buildStickyFactsExtractionPayload(
        currentFacts: Map<String, String>,
        userMessage: String,
        assistantMessage: String
    ): String {
        val factsJson = if (currentFacts.isEmpty()) {
            "{}"
        } else {
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    currentFacts.toSortedMap().forEach { (key, value) ->
                        put(key, JsonPrimitive(value))
                    }
                }
            )
        }

        return buildString {
            append("Текущие сохраненные факты:\n")
            append(factsJson)
            append("\n\nПоследний вопрос пользователя:\n")
            append(userMessage.trim())
            append("\n\nПоследний ответ ассистента:\n")
            append(assistantMessage.trim())
        }
    }

    private fun parseStickyFactsExtractionResult(rawText: String): Map<String, String>? {
        val jsonPayload = extractJsonObjectCandidate(rawText) ?: return null
        val root = runCatching {
            json.parseToJsonElement(jsonPayload)
        }.getOrNull() as? JsonObject ?: return null

        val factsObject = root["facts"] as? JsonObject ?: return null
        return factsObject.entries.mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            if (normalizedKey.isEmpty()) return@mapNotNull null

            val normalizedValue = when (value) {
                is JsonPrimitive -> value.contentOrNull ?: value.toString()
                else -> value.toString()
            }.trim()

            if (normalizedValue.isEmpty()) null else normalizedKey to normalizedValue
        }.toMap()
    }

    private fun parseStickyFactsMap(stickyFactsJson: String?): Map<String, String> {
        val payload = stickyFactsJson?.trim().orEmpty()
        if (payload.isEmpty()) return emptyMap()

        val root = runCatching {
            json.parseToJsonElement(payload)
        }.getOrNull() as? JsonObject ?: return emptyMap()

        return root.entries.mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            if (normalizedKey.isEmpty()) return@mapNotNull null

            val normalizedValue = when (value) {
                is JsonPrimitive -> value.contentOrNull ?: value.toString()
                else -> value.toString()
            }.trim()

            if (normalizedValue.isEmpty()) null else normalizedKey to normalizedValue
        }.toMap()
    }

    private fun serializeStickyFacts(
        facts: Map<String, String>
    ): String? {
        if (facts.isEmpty()) return null

        val factsObject = buildJsonObject {
            facts.toSortedMap().forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
        return json.encodeToString(JsonObject.serializer(), factsObject)
    }

    private fun extractJsonObjectCandidate(rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        val unfenced = trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (unfenced.startsWith('{') && unfenced.endsWith('}')) {
            return unfenced
        }

        val startIndex = unfenced.indexOf('{')
        val endIndex = unfenced.lastIndexOf('}')
        if (startIndex < 0 || endIndex <= startIndex) return null
        return unfenced.substring(startIndex, endIndex + 1)
    }

    private fun handleControlCommand(
        session: SessionEntity,
        command: ChatControlCommand
    ): ControlCommandResponse {
        return when (command) {
            is ChatControlCommand.Memory -> {
                handleMemoryCommand(
                    currentInstructions = parseLongTermMemoryInstructions(session.longTermMemoryJson),
                    command = command.command
                )
            }

            is ChatControlCommand.Work -> {
                handleWorkCommand(
                    workTaskContext = parseCurrentWorkTask(session.currentWorkTaskJson),
                    command = command.command
                )
            }
        }
    }

    private fun handleMemoryCommand(
        currentInstructions: List<String>,
        command: MemoryCommand
    ): ControlCommandResponse {
        return when (command) {
            is MemoryCommand.Add -> {
                val updatedInstructions = currentInstructions + command.instruction
                ControlCommandResponse(
                    reply = MEMORY_ADD_SUCCESS_REPLY,
                    memoryChanged = true,
                    memoryInstructions = updatedInstructions
                )
            }

            is MemoryCommand.Delete -> {
                val index = command.number - 1
                if (index !in currentInstructions.indices) {
                    return ControlCommandResponse(
                        reply = "Инструкция с номером ${command.number} не найдена"
                    )
                }

                val removedInstruction = currentInstructions[index]
                val updatedInstructions = currentInstructions.toMutableList().apply {
                    removeAt(index)
                }

                ControlCommandResponse(
                    reply = "Удалил инструкцию $removedInstruction",
                    memoryChanged = true,
                    memoryInstructions = updatedInstructions
                )
            }

            MemoryCommand.Show -> {
                ControlCommandResponse(reply = buildMemoryShowReply(currentInstructions))
            }

            is MemoryCommand.Invalid -> {
                ControlCommandResponse(reply = command.reply)
            }
        }
    }

    private fun handleWorkCommand(
        workTaskContext: WorkTaskContext,
        command: WorkCommand
    ): ControlCommandResponse {
        val currentTask = workTaskContext.activeTask

        return when (command) {
            is WorkCommand.Start -> {
                if (currentTask != null) {
                    return ControlCommandResponse(
                        reply = "Текущая задача уже задана: ${currentTask.description}"
                    )
                }

                ControlCommandResponse(
                    reply = WORK_START_SUCCESS_REPLY,
                    workTaskChanged = true,
                    workTaskContext = WorkTaskContext(
                        activeTask = WorkTaskState(
                            description = command.description,
                            rules = emptyList()
                        )
                    )
                )
            }

            WorkCommand.Done -> {
                if (currentTask == null) {
                    return ControlCommandResponse(
                        reply = if (workTaskContext.isCompleted) {
                            WORK_COMPLETED_REPLY
                        } else {
                            WORK_EMPTY_REPLY
                        }
                    )
                }
                ControlCommandResponse(
                    reply = WORK_DONE_SUCCESS_REPLY,
                    workTaskChanged = true,
                    workTaskContext = WorkTaskContext(isCompleted = true)
                )
            }

            is WorkCommand.Rule -> {
                if (currentTask == null) {
                    return ControlCommandResponse(
                        reply = if (workTaskContext.isCompleted) {
                            WORK_COMPLETED_REPLY
                        } else {
                            WORK_EMPTY_REPLY
                        }
                    )
                }
                val updatedTask = currentTask.copy(rules = currentTask.rules + command.rule)
                ControlCommandResponse(
                    reply = WORK_RULE_ADD_SUCCESS_REPLY,
                    workTaskChanged = true,
                    workTaskContext = WorkTaskContext(activeTask = updatedTask)
                )
            }

            is WorkCommand.Delete -> {
                if (currentTask == null) {
                    return ControlCommandResponse(
                        reply = if (workTaskContext.isCompleted) {
                            WORK_COMPLETED_REPLY
                        } else {
                            WORK_EMPTY_REPLY
                        }
                    )
                }

                val index = command.number - 1
                if (index !in currentTask.rules.indices) {
                    return ControlCommandResponse(
                        reply = "Правило с номером ${command.number} не найдено"
                    )
                }

                val removedRule = currentTask.rules[index]
                val updatedRules = currentTask.rules.toMutableList().apply {
                    removeAt(index)
                }

                ControlCommandResponse(
                    reply = "Удалил правило $removedRule",
                    workTaskChanged = true,
                    workTaskContext = WorkTaskContext(
                        activeTask = currentTask.copy(rules = updatedRules)
                    )
                )
            }

            WorkCommand.Show -> {
                ControlCommandResponse(reply = buildWorkShowReply(workTaskContext))
            }

            is WorkCommand.Invalid -> {
                ControlCommandResponse(reply = command.reply)
            }
        }
    }

    private fun parseControlCommand(content: String): ChatControlCommand? {
        parseMemoryCommand(content)?.let { return ChatControlCommand.Memory(it) }
        parseWorkCommand(content)?.let { return ChatControlCommand.Work(it) }
        return null
    }

    private fun parseMemoryCommand(content: String): MemoryCommand? {
        val trimmed = content.trim()
        if (!trimmed.startsWith(MEMORY_COMMAND_PREFIX)) return null

        val payload = trimmed.removePrefix(MEMORY_COMMAND_PREFIX).trim()
        if (payload.isEmpty()) return MemoryCommand.Invalid(MEMORY_COMMAND_HELP)

        val parts = payload.split(Regex("\\s+"), limit = 2)
        val operation = parts.firstOrNull()?.lowercase().orEmpty()
        val argument = parts.getOrNull(1)?.trim().orEmpty()

        return when (operation) {
            MEMORY_OPERATION_ADD -> {
                if (argument.isEmpty()) {
                    MemoryCommand.Invalid("Укажи инструкцию после /memory add")
                } else {
                    MemoryCommand.Add(argument)
                }
            }

            MEMORY_OPERATION_DELETE -> {
                val number = argument.toIntOrNull()
                if (number == null || number <= 0) {
                    MemoryCommand.Invalid("Укажи корректный номер после /memory delete")
                } else {
                    MemoryCommand.Delete(number)
                }
            }

            MEMORY_OPERATION_SHOW -> {
                if (argument.isNotEmpty()) {
                    MemoryCommand.Invalid(MEMORY_COMMAND_HELP)
                } else {
                    MemoryCommand.Show
                }
            }

            else -> MemoryCommand.Invalid(MEMORY_COMMAND_HELP)
        }
    }

    private fun parseWorkCommand(content: String): WorkCommand? {
        val trimmed = content.trim()
        if (!trimmed.startsWith(WORK_COMMAND_PREFIX)) return null

        val payload = trimmed.removePrefix(WORK_COMMAND_PREFIX).trim()
        if (payload.isEmpty()) return WorkCommand.Invalid(WORK_COMMAND_HELP)

        val parts = payload.split(Regex("\\s+"), limit = 2)
        val operation = parts.firstOrNull()?.lowercase().orEmpty()
        val argument = parts.getOrNull(1)?.trim().orEmpty()

        return when (operation) {
            WORK_OPERATION_START -> {
                if (argument.isEmpty()) {
                    WorkCommand.Invalid("Укажи описание после /work start")
                } else {
                    WorkCommand.Start(argument)
                }
            }

            WORK_OPERATION_DONE -> {
                if (argument.isNotEmpty()) {
                    WorkCommand.Invalid(WORK_COMMAND_HELP)
                } else {
                    WorkCommand.Done
                }
            }

            WORK_OPERATION_RULE -> {
                if (argument.isEmpty()) {
                    WorkCommand.Invalid("Укажи описание правила после /work rule")
                } else {
                    WorkCommand.Rule(argument)
                }
            }

            WORK_OPERATION_DELETE -> {
                val number = argument.toIntOrNull()
                if (number == null || number <= 0) {
                    WorkCommand.Invalid("Укажи корректный номер после /work delete")
                } else {
                    WorkCommand.Delete(number)
                }
            }

            WORK_OPERATION_SHOW -> {
                if (argument.isNotEmpty()) {
                    WorkCommand.Invalid(WORK_COMMAND_HELP)
                } else {
                    WorkCommand.Show
                }
            }

            else -> WorkCommand.Invalid(WORK_COMMAND_HELP)
        }
    }

    private fun parseLongTermMemoryInstructions(memoryJson: String?): List<String> {
        val payload = memoryJson?.trim().orEmpty()
        if (payload.isEmpty()) return emptyList()

        val root = runCatching {
            json.parseToJsonElement(payload)
        }.getOrNull() as? JsonArray ?: return emptyList()

        return root.mapNotNull { element ->
            val value = (element as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            value.ifEmpty { null }
        }
    }

    private fun serializeLongTermMemoryInstructions(
        instructions: List<String>
    ): String? {
        val normalized = instructions
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return null

        val jsonArray = buildJsonArray {
            normalized.forEach { add(JsonPrimitive(it)) }
        }
        return json.encodeToString(JsonArray.serializer(), jsonArray)
    }

    private fun parseCurrentWorkTask(currentWorkTaskJson: String?): WorkTaskContext {
        val payload = currentWorkTaskJson?.trim().orEmpty()
        if (payload.isEmpty()) return WorkTaskContext()

        val root = runCatching {
            json.parseToJsonElement(payload)
        }.getOrNull() as? JsonObject ?: return WorkTaskContext()

        val status = (root[WORK_TASK_STATUS_KEY] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.uppercase()

        val description = (root[WORK_TASK_DESCRIPTION_KEY] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        if (description.isEmpty()) {
            return if (status == WORK_TASK_STATUS_DONE) {
                WorkTaskContext(isCompleted = true)
            } else {
                WorkTaskContext()
            }
        }

        val rules = (root[WORK_TASK_RULES_KEY] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
            .orEmpty()

        return WorkTaskContext(
            activeTask = WorkTaskState(
                description = description,
                rules = rules
            )
        )
    }

    private fun serializeCurrentWorkTask(workTaskContext: WorkTaskContext?): String? {
        if (workTaskContext == null) return null
        val activeTask = workTaskContext.activeTask

        if (activeTask != null) {
            val description = activeTask.description.trim()
            if (description.isEmpty()) return null

            val rules = activeTask.rules
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val jsonPayload = buildJsonObject {
                put(WORK_TASK_STATUS_KEY, JsonPrimitive(WORK_TASK_STATUS_ACTIVE))
                put(WORK_TASK_DESCRIPTION_KEY, JsonPrimitive(description))
                put(
                    WORK_TASK_RULES_KEY,
                    buildJsonArray {
                        rules.forEach { add(JsonPrimitive(it)) }
                    }
                )
            }

            return json.encodeToString(JsonObject.serializer(), jsonPayload)
        }

        if (!workTaskContext.isCompleted) return null

        val jsonPayload = buildJsonObject {
            put(WORK_TASK_STATUS_KEY, JsonPrimitive(WORK_TASK_STATUS_DONE))
        }
        return json.encodeToString(JsonObject.serializer(), jsonPayload)
    }

    private fun buildContextBlock(
        baseSystemPrompt: String?,
        userProfilePayload: String?,
        memoryInstructions: List<String>,
        currentWorkTask: WorkTaskContext
    ): String? {
        val userProfilePrompt = buildUserProfileSystemPrompt(userProfilePayload)
        val memoryPrompt = buildMemorySystemPrompt(memoryInstructions)
        val workTaskPrompt = buildWorkTaskSystemPrompt(currentWorkTask)
        val parts = buildList {
            baseSystemPrompt?.let { add(it) }
            userProfilePrompt?.let { add(it) }
            add(memoryPrompt)
            add(workTaskPrompt)
        }
        return parts.joinToString(separator = "\n\n")
    }

    private fun buildUserProfileSystemPrompt(profilePayload: String?): String? {
        val normalizedPayload = profilePayload?.trim()?.ifBlank { null } ?: return null
        return buildString {
            append(USER_PROFILE_SECTION_TITLE)
            append('\n')
            append(USER_PROFILE_PRIORITY_HINT)
            append('\n')
            append(normalizedPayload)
        }
    }

    private fun buildMemorySystemPrompt(instructions: List<String>): String {
        return buildString {
            append(LONG_TERM_MEMORY_SECTION_TITLE)
            if (instructions.isEmpty()) {
                append('\n')
                append(LONG_TERM_MEMORY_EMPTY_HINT)
                return@buildString
            }

            append('\n')
            append(LONG_TERM_MEMORY_PRIORITY_HINT)
            instructions.forEachIndexed { index, instruction ->
                append('\n')
                append(index + 1)
                append(". ")
                append(instruction)
            }
        }
    }

    private fun buildWorkTaskSystemPrompt(workTaskContext: WorkTaskContext): String {
        val task = workTaskContext.activeTask

        return buildString {
            append(CURRENT_WORK_SECTION_TITLE)
            if (task == null) {
                append('\n')
                append(CURRENT_WORK_EMPTY_HINT)
                return@buildString
            }

            append('\n')
            append(CURRENT_WORK_PRIORITY_HINT)
            append("\nЗадача:")
            append('\n')
            append(task.description)

            if (task.rules.isNotEmpty()) {
                append("\nПравила задачи:")
                task.rules.forEachIndexed { index, rule ->
                    append('\n')
                    append(index + 1)
                    append(". ")
                    append(rule)
                }
            }
        }
    }

    private fun buildMemoryShowReply(instructions: List<String>): String {
        if (instructions.isEmpty()) return MEMORY_EMPTY_REPLY

        return buildString {
            append(MEMORY_SHOW_TITLE)
            instructions.forEachIndexed { index, instruction ->
                append('\n')
                append(index + 1)
                append(' ')
                append(instruction)
            }
        }
    }

    private fun buildWorkShowReply(workTaskContext: WorkTaskContext): String {
        val task = workTaskContext.activeTask
        if (task == null) {
            return if (workTaskContext.isCompleted) {
                WORK_COMPLETED_REPLY
            } else {
                WORK_EMPTY_REPLY
            }
        }

        return buildString {
            append(WORK_SHOW_TITLE)
            append('\n')
            append(task.description)
            append('\n')
            append(WORK_RULES_TITLE)

            if (task.rules.isEmpty()) {
                append('\n')
                append(WORK_RULES_EMPTY)
            } else {
                task.rules.forEachIndexed { index, rule ->
                    append('\n')
                    append(index + 1)
                    append(' ')
                    append(rule)
                }
            }
        }
    }

    private fun List<MessageEntity>.filterControlCommandMessagesForModelContext(): List<MessageEntity> {
        if (isEmpty()) return emptyList()

        val filtered = ArrayList<MessageEntity>(size)
        var skipNextAssistant = false
        for (message in this) {
            val role = MessageRole.fromStored(message.role)

            if (skipNextAssistant && role == MessageRole.ASSISTANT) {
                skipNextAssistant = false
                continue
            }
            skipNextAssistant = false

            if (role == MessageRole.USER && parseControlCommand(message.content) != null) {
                skipNextAssistant = true
                continue
            }

            filtered += message
        }
        return filtered
    }

    private fun List<MessageEntity>.toApiMessages(): List<ChatCompletionMessage> {
        return map { message ->
            ChatCompletionMessage(
                role = MessageRole.fromStored(message.role).apiValue,
                content = message.content
            )
        }
    }

    private fun buildApiErrorMessage(code: Int, rawError: String?): String {
        val parsedMessage = extractApiErrorMessage(rawError)
        if (!parsedMessage.isNullOrBlank()) {
            return "API error ($code): $parsedMessage"
        }

        return when (code) {
            400 -> "API error (400): invalid request. Check baseUrl (/v1/), model and payload."
            401 -> "API error (401): unauthorized. Check DEEPSEEK_API_KEY."
            403 -> "API error (403): access denied for this key/model."
            404 -> "API error (404): endpoint not found. Check DEEPSEEK_BASE_URL."
            429 -> "API error (429): rate limit exceeded."
            in 500..599 -> "API error ($code): DeepSeek server is unavailable."
            else -> {
                val safeRaw = rawError?.take(MAX_RAW_ERROR_LENGTH).orEmpty()
                if (safeRaw.isNotBlank()) "API error ($code): $safeRaw" else "API error ($code)"
            }
        }
    }

    private fun extractApiErrorMessage(rawError: String?): String? {
        if (rawError.isNullOrBlank()) return null

        val root = runCatching {
            json.parseToJsonElement(rawError)
        }.getOrNull() as? JsonObject ?: return null

        val nestedErrorMessage = (root["error"] as? JsonObject)
            ?.get("message")
            ?.jsonPrimitive
            ?.contentOrNull
        if (!nestedErrorMessage.isNullOrBlank()) return nestedErrorMessage

        val topLevelMessage = root["message"]?.jsonPrimitive?.contentOrNull
        if (!topLevelMessage.isNullOrBlank()) return topLevelMessage

        return null
    }

    private fun String.toSessionTitle(): String {
        val firstLine = lineSequence().firstOrNull().orEmpty().trim()
        return firstLine.take(48).ifBlank { DEFAULT_SESSION_TITLE }
    }

    private fun String?.normalizeSystemPrompt(): String? {
        return this?.trim()?.ifBlank { null }
    }

    private suspend fun resolveUserProfilePayload(profileName: String?): String? {
        val normalizedName = profileName?.trim()?.ifBlank { null } ?: return null
        findBuiltInUserProfilePreset(normalizedName)?.let { builtIn ->
            return builtIn.payloadJson
        }
        return userProfilePresetDao.getByProfileName(normalizedName)?.payloadJson
    }

    private fun extractProfileObject(rootObject: JsonObject): JsonObject? {
        if (
            rootObject.containsKey(USER_PROFILE_NAME_KEY) ||
            rootObject.containsKey("language") ||
            rootObject.containsKey("tone")
        ) {
            return rootObject
        }

        val nested = rootObject[USER_PROFILE_SECTION_OBJECT_KEY] as? JsonObject
        if (nested != null) return nested

        val nestedLower = rootObject[USER_PROFILE_SECTION_OBJECT_KEY_LOWER] as? JsonObject
        if (nestedLower != null) return nestedLower

        return null
    }

    private suspend fun resolveUniqueProfileName(requestedProfileName: String): String {
        var candidate = requestedProfileName
        var suffix = 2
        while (isProfileNameTaken(candidate)) {
            candidate = "${requestedProfileName}_$suffix"
            suffix += 1
        }
        return candidate
    }

    private suspend fun isProfileNameTaken(profileName: String): Boolean {
        if (findBuiltInUserProfilePreset(profileName) != null) return true
        return userProfilePresetDao.getByProfileName(profileName) != null
    }

    private fun String?.normalizeProfileName(): String? {
        val normalized = this
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9_]+"), "_")
            ?.replace(Regex("_+"), "_")
            ?.trim('_')
            .orEmpty()

        if (normalized.isEmpty()) return null
        return if (normalized.startsWith(CUSTOM_PROFILE_PREFIX)) {
            normalized
        } else {
            CUSTOM_PROFILE_PREFIX + normalized
        }
    }

    private fun String.toProfileLabel(): String {
        val words = split('_')
            .filter { it.isNotBlank() && it != CUSTOM_PROFILE_PREFIX.trimEnd('_') }
            .ifEmpty { listOf(DEFAULT_CUSTOM_PROFILE_LABEL) }
        return words.joinToString(separator = " ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
            }
        }
    }

    private fun String?.normalizeSummary(): String? {
        return this?.trim()?.ifBlank { null }
    }

    private fun String.trimSummaryLength(): String {
        if (length <= MAX_SUMMARY_LENGTH) return this
        return takeLast(MAX_SUMMARY_LENGTH).trim()
    }

    private fun String.toCompressionState(): MessageCompressionState {
        return MessageCompressionState.entries.firstOrNull { it.name == this }
            ?: MessageCompressionState.ACTIVE
    }

    private fun resolvePromptUsage(usage: ChatCompletionUsage?): PromptUsage? {
        if (usage == null) return null
        return PromptUsage(
            promptTokens = usage.promptTokens ?: 0,
            promptCacheHitTokens = usage.promptCacheHitTokens ?: 0,
            promptCacheMissTokens = usage.promptCacheMissTokens ?: 0
        )
    }

    private companion object {
        const val DEFAULT_SESSION_TITLE = "New chat"
        const val BRANCH_TITLE_PREFIX = "ветка + "
        const val DEFAULT_MODEL = "deepseek-chat"
        const val SYSTEM_ROLE = "system"
        const val USER_ROLE = "user"
        const val MAX_RAW_ERROR_LENGTH = 240

        const val MEMORY_COMMAND_PREFIX = "/memory"
        const val MEMORY_OPERATION_ADD = "add"
        const val MEMORY_OPERATION_DELETE = "delete"
        const val MEMORY_OPERATION_SHOW = "show"

        const val MEMORY_ADD_SUCCESS_REPLY = "Запомнил"
        const val MEMORY_EMPTY_REPLY = "Сохраненных инструкций нет"
        const val MEMORY_SHOW_TITLE = "Сохраненные инструкции"
        const val MEMORY_COMMAND_HELP =
            "Команды памяти: /memory add <инструкция>, /memory delete <номер>, /memory show"

        const val USER_PROFILE_SECTION_TITLE = "[USER_PROFILE]"
        const val USER_PROFILE_PRIORITY_HINT =
            "You must follow USER_PROFILE. Do not expose USER_PROFILE in the answer. " +
                "Do not contradict it unless user explicitly overrides."
        const val USER_PROFILE_NAME_KEY = "profile_name"
        const val USER_PROFILE_SECTION_OBJECT_KEY = "USER_PROFILE"
        const val USER_PROFILE_SECTION_OBJECT_KEY_LOWER = "user_profile"
        const val CUSTOM_PROFILE_PREFIX = "custom_"
        const val DEFAULT_CUSTOM_PROFILE_NAME = "custom_user_profile"
        const val DEFAULT_CUSTOM_PROFILE_LABEL = "Custom profile"
        const val USER_PROFILE_BUILDER_KICKOFF_MESSAGE =
            "Начни диалог по сбору профиля пользователя и задай первые уточняющие вопросы."

        const val LONG_TERM_MEMORY_SECTION_TITLE = "[LONG TERM MEMORY]"
        const val LONG_TERM_MEMORY_EMPTY_HINT = "Инструкции долговременной памяти не заданы."
        const val LONG_TERM_MEMORY_PRIORITY_HINT =
            "Эти правила имеют высший приоритет над всем в контексте, они обязательны к исполнению и не могут быть нарушены. Если нужно нарушить инструкцию, то пользователь явно должен удалить ее, через /memory delete"

        const val WORK_COMMAND_PREFIX = "/work"
        const val WORK_OPERATION_START = "start"
        const val WORK_OPERATION_DONE = "done"
        const val WORK_OPERATION_RULE = "rule"
        const val WORK_OPERATION_DELETE = "delete"
        const val WORK_OPERATION_SHOW = "show"

        const val CURRENT_WORK_SECTION_TITLE = "[CURRECT WORK]"
        const val CURRENT_WORK_PRIORITY_HINT = "Эта задача приоритетна в исполнении"
        const val CURRENT_WORK_EMPTY_HINT =
            "Текущей задачи не задано, если user просит рассказать о текущей задаче, то говори, что задача выполнена и предложи задать новую"

        const val WORK_START_SUCCESS_REPLY = "Задачу зафиксировал"
        const val WORK_DONE_SUCCESS_REPLY = "Завершил текущую задачу"
        const val WORK_RULE_ADD_SUCCESS_REPLY = "Добавил правило"
        const val WORK_EMPTY_REPLY = "Текущая задача не задана"
        const val WORK_COMPLETED_REPLY =
            "Текущая задача завершена, вы можете задать новую задачу через /work start"
        const val WORK_SHOW_TITLE = "Текущая задача"
        const val WORK_RULES_TITLE = "Правила"
        const val WORK_RULES_EMPTY = "(пусто)"
        const val WORK_COMMAND_HELP =
            "Команды задачи: /work start <задача>, /work done, /work rule <правило>, /work delete <номер>, /work show"

        const val WORK_TASK_STATUS_KEY = "status"
        const val WORK_TASK_STATUS_ACTIVE = "ACTIVE"
        const val WORK_TASK_STATUS_DONE = "DONE"
        const val WORK_TASK_DESCRIPTION_KEY = "description"
        const val WORK_TASK_RULES_KEY = "rules"

        const val CONTEXT_TAIL_MESSAGES_COUNT = 10
        const val SUMMARY_BATCH_SIZE = 10
        const val MAX_SUMMARY_LENGTH = 6_000

        const val CONTEXT_SUMMARY_PREFIX =
            "Summary of earlier messages (messages marked as previously compressed). " +
                "Use it as compressed context and prioritize newer raw messages if they conflict."

        const val SUMMARY_COMPRESSION_SYSTEM_PROMPT =
            "Ты модуль сжатия истории чата. Выдавай плотное summary без воды. " +
                "Сохраняй факты, намерения пользователя, ограничения, решения и открытые вопросы."

        val USER_PROFILE_BUILDER_SYSTEM_PROMPT = """
            Ты ассистент, который помогает пользователю собрать USER_PROFILE для чата.

            Цель:
            1) Собрать предпочтения пользователя по языку, тону, уровню экспертности, структуре и ограничениям.
            2) Если данных недостаточно, задавать точные уточняющие вопросы.
            3) Когда данных достаточно, выдать USER_PROFILE в JSON и спросить подтверждение.

            Формат и правила:
            - Самое первое сообщение начинай строго с фразы: "Привет! Давай попробуем собрать профиль пользователя под тебя".
            - Пиши по-русски.
            - Не менее 2 и не более 5 вопросов за один шаг.
            - Не придумывай данные за пользователя.
            - Когда данных достаточно, верни блок JSON USER_PROFILE, соответствующий формату приложения:
              {
                "profile_name": "snake_case_name",
                "language": "...",
                "expertise_level": "...",
                "verbosity": "...",
                "tone": "...",
                "humor": "...",
                "style": "...",
                "structure": "...",
                "disagreement_mode": "...",
                "challenge_user": true/false,
                "examples": "...",
                "emoji_usage": "...",
                "constraints": { ... }
              }
            - Перед подтверждением объясни кратко, что именно зафиксировано.
            - Если пользователь просит поменять профиль, обновляй JSON и показывай новую версию.
            - Если пользователь пишет, что согласен, выдай финальный JSON без лишнего текста.
        """.trimIndent()

        val STICKY_FACTS_EXTRACTION_SYSTEM_PROMPT = """
            Ты — модуль извлечения памяти в среде выполнения AI-агента.

            Твоя задача — обновить набор фактов по диалогу, чтобы агент лучше помогал пользователю в будущем.

            На входе: текущие сохраненные факты, последний вопрос пользователя и последний ответ ассистента.
            На выходе: полный обновленный набор фактов.

            Возвращай ТОЛЬКО факты, которые стоит сохранить.
            Важно: извлекай БОЛЬШЕ полезных фактов, чем раньше, включая явно сообщенные пользователем детали (числа, списки, параметры), если они могут помочь в будущих задачах.

            Что считать фактом для сохранения (приоритет по убыванию):
            1) Профиль пользователя: возраст, город/страна/язык, профессия/роль, семья (если явно сказано), уровень навыков, доступные ресурсы/инструменты.
            2) Устойчивые предпочтения: что нравится/не нравится, ограничения, диеты, любимые бренды, бюджетные рамки, формат ответов, тон общения, единицы измерения, часовой пояс.
            3) Цели и проекты: долгосрочные цели, текущие проекты, домены интересов, контекст “что строим/зачем”.
            4) Списки и наборы, которые могут пригодиться: список покупок, список задач, список требований, список идей, список вещей “нужно/хочу”.
            5) Числовые и параметрические данные, явно сообщенные пользователем: возраст, рост/вес (если уместно), бюджет, дедлайны, количества, размеры, предпочтительные даты/время.
            6) “Временные, но полезные” факты: если пользователь даёт список покупок или план на сегодня/неделю — СОХРАНЯЙ, но помечай как временное в значении (например, добавь префикс "temp:"), если нет признаков, что это навсегда.

            Что игнорировать:
            - Светская беседа без полезной информации.
            - Риторика, эмоции без фактов (кроме устойчивых предпочтений типа “я ненавижу острое”).
            - Случайные одноразовые детали, которые не помогут позже, ЕСЛИ они явно одноразовые и не относятся к целям/проектам/планам.
            - Вопросы как вопросы (но если внутри вопроса есть факт о пользователе — извлекай этот факт).
            - Объяснения ассистента.

            Правила обновления:
            - Не выдумывай.
            - Если новый факт противоречит старому — замени старый.
            - Неконфликтующие факты оставь.
            - Нормализуй значения: короткие строки.
            - Предпочитай формат ключ-значение.
            - Ключи: короткие, snake_case.
            - Если информация — список, сохраняй как строку с разделителем ", " (или краткий JSON-строковый список, но всё равно значение должно быть строкой).

            Рекомендованные ключи (используй при совпадении смысла):
            - age
            - location
            - timezone
            - language
            - occupation
            - goals
            - current_project
            - preferences
            - dislikes
            - constraints
            - budget
            - shopping_list
            - todo_list

            Формат ответа (ТОЛЬКО корректный JSON, без пояснений):

            {
              "facts": {
                "key": "value"
              }
            }

            Если фактов нет вообще, верни:
            {
              "facts": {}
            }
        """.trimIndent()
    }
}

private suspend inline fun <T> AppDatabase.withTransactionCompat(
    crossinline block: suspend () -> T
): T {
    return useWriterConnection { transactor ->
        transactor.immediateTransaction {
            block()
        }
    }
}

private class ChatApiException(message: String) : RuntimeException(message)

private data class PromptUsage(
    val promptTokens: Int,
    val promptCacheHitTokens: Int,
    val promptCacheMissTokens: Int
)

private sealed interface MemoryCommand {
    data class Add(val instruction: String) : MemoryCommand
    data class Delete(val number: Int) : MemoryCommand
    object Show : MemoryCommand
    data class Invalid(val reply: String) : MemoryCommand
}

private sealed interface WorkCommand {
    data class Start(val description: String) : WorkCommand
    object Done : WorkCommand
    data class Rule(val rule: String) : WorkCommand
    data class Delete(val number: Int) : WorkCommand
    object Show : WorkCommand
    data class Invalid(val reply: String) : WorkCommand
}

private sealed interface ChatControlCommand {
    data class Memory(val command: MemoryCommand) : ChatControlCommand
    data class Work(val command: WorkCommand) : ChatControlCommand
}

private data class WorkTaskState(
    val description: String,
    val rules: List<String>
)

private data class WorkTaskContext(
    val activeTask: WorkTaskState? = null,
    val isCompleted: Boolean = false
)

private data class ControlCommandResponse(
    val reply: String,
    val memoryChanged: Boolean = false,
    val memoryInstructions: List<String>? = null,
    val workTaskChanged: Boolean = false,
    val workTaskContext: WorkTaskContext? = null
)
