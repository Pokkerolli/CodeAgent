package com.pokkerolli.codeagent.data.datasource
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.pokkerolli.codeagent.data.local.dao.MessageDao
import com.pokkerolli.codeagent.data.local.dao.InvariantRuleDao
import com.pokkerolli.codeagent.data.local.dao.SessionDao
import com.pokkerolli.codeagent.data.local.dao.UserProfilePresetDao
import com.pokkerolli.codeagent.data.local.datastore.ActiveSessionPreferences
import com.pokkerolli.codeagent.data.local.db.AppDatabase
import com.pokkerolli.codeagent.data.local.entity.InvariantRuleEntity
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
import com.pokkerolli.codeagent.domain.model.InvariantRule
import com.pokkerolli.codeagent.domain.model.MessageRole
import com.pokkerolli.codeagent.domain.model.TaskStage
import com.pokkerolli.codeagent.domain.model.TaskStageTransitionPolicy
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
    private val invariantRuleDao: InvariantRuleDao,
    private val userProfilePresetDao: UserProfilePresetDao,
    private val deepSeekApi: DeepSeekApi,
    private val sseStreamParser: SseStreamParser,
    private val activeSessionPreferences: ActiveSessionPreferences,
    private val json: Json
) : ChatDataSource {

    private val summarizationMutexBySession = ConcurrentHashMap<String, Mutex>()
    private val taskPauseRequestedBySession = ConcurrentHashMap<String, Boolean>()

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
            isContextSummarizationInProgress = false,
            taskStage = sourceSession.taskStage,
            taskDescription = sourceSession.taskDescription,
            taskPlan = sourceSession.taskPlan,
            taskExecutionReport = sourceSession.taskExecutionReport,
            taskValidationReport = sourceSession.taskValidationReport,
            taskFinalResult = sourceSession.taskFinalResult,
            taskReplanReason = sourceSession.taskReplanReason,
            taskAutoReplanCount = sourceSession.taskAutoReplanCount,
            isInvariantCheckEnabled = sourceSession.isInvariantCheckEnabled,
            isTaskPaused = sourceSession.isTaskPaused,
            taskPausedPartialResponse = sourceSession.taskPausedPartialResponse
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
                            compressionState = source.compressionState,
                            taskStage = source.taskStage,
                            includeInModelContext = source.includeInModelContext
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
        taskPauseRequestedBySession.remove(sessionId)
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

    override suspend fun setSessionInvariantCheckEnabled(sessionId: String, enabled: Boolean) {
        val now = System.currentTimeMillis()
        ensureDefaultInvariantRules()
        val existingSession = sessionDao.getSessionById(sessionId)

        if (existingSession == null) {
            sessionDao.insertSession(
                SessionEntity(
                    id = sessionId,
                    title = DEFAULT_SESSION_TITLE,
                    createdAt = now,
                    updatedAt = now,
                    isInvariantCheckEnabled = enabled
                )
            )
            return
        }

        sessionDao.updateInvariantCheckEnabled(
            sessionId = sessionId,
            enabled = enabled,
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

    override fun observeInvariantRules(): Flow<List<InvariantRule>> = flow {
        ensureDefaultInvariantRules()
        invariantRuleDao.observeActiveRules().collect { rules ->
            emit(rules.map { it.toDomain() })
        }
    }

    private suspend fun ensureDefaultInvariantRules() {
        invariantRuleDao.deleteRulesByKeys(REMOVED_INVARIANT_RULE_KEYS)
        val existingRuleKeys = invariantRuleDao.getActiveRulesOnce()
            .map { it.ruleKey }
            .toSet()
        if (DEFAULT_INVARIANT_RULE_KEYS.all { it in existingRuleKeys }) return
        val now = System.currentTimeMillis()
        invariantRuleDao.insertRules(
            rules = listOf(
                InvariantRuleEntity(
                    ruleKey = INVARIANT_RULE_KEY_KOTLIN_ONLY,
                    title = "Код только на Kotlin",
                    description = "Запрещено предлагать или генерировать код на любом языке, кроме Kotlin.",
                    position = 1,
                    isActive = true,
                    createdAt = now,
                    updatedAt = now
                ),
                InvariantRuleEntity(
                    ruleKey = INVARIANT_RULE_KEY_FUNCTIONS_UNDER_10_LINES,
                    title = "Функции короче 10 строк",
                    description = "Каждая функция в коде должна содержать менее 10 строк.",
                    position = 2,
                    isActive = true,
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
    }

    private suspend fun runInvariantCheckIfEnabled(
        session: SessionEntity,
        validationStage: TaskStage,
        userInput: String
    ): InvariantCheckOutcome {
        if (!session.isInvariantCheckEnabled) return InvariantCheckOutcome.allowed()

        val normalizedInput = userInput.trim()
        if (normalizedInput.isEmpty()) return InvariantCheckOutcome.allowed()

        ensureDefaultInvariantRules()
        val activeRules = invariantRuleDao.getActiveRulesOnce()
        if (activeRules.isEmpty()) return InvariantCheckOutcome.allowed()
        val knownRuleKeys = activeRules
            .map { it.ruleKey }
            .toSet()

        val request = ChatCompletionRequest(
            model = DEFAULT_MODEL,
            messages = listOf(
                ChatCompletionMessage(
                    role = SYSTEM_ROLE,
                    content = buildInvariantValidationSystemPrompt(activeRules)
                ),
                ChatCompletionMessage(
                    role = USER_ROLE,
                    content = buildInvariantValidationPayload(
                        validationStage = validationStage,
                        userInput = normalizedInput
                    )
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

        val rawValidatorReply = body.choices
            .firstOrNull()
            ?.message
            ?.content
            ?.trim()
            .orEmpty()

        val resultMatch = INVARIANT_RESULT_REGEX.find(rawValidatorReply)
        val normalizedResult = resultMatch
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.lowercase()

        return when (normalizedResult) {
            "true" -> InvariantCheckOutcome.allowed()
            "false" -> {
                val violatedInvariantKeys = extractViolatedInvariantKeys(
                    rawValidatorReply = rawValidatorReply,
                    knownRuleKeys = knownRuleKeys
                )
                if (violatedInvariantKeys.isEmpty()) {
                    return InvariantCheckOutcome.allowed()
                }
                val reasonText = rawValidatorReply
                    .replace(INVARIANT_RESULT_LINE_REGEX, "")
                    .replace(INVARIANT_VIOLATED_LINE_REGEX, "")
                    .trim()
                    .ifEmpty { "Причина не указана." }
                InvariantCheckOutcome.rejected(
                    messageForUser = buildString {
                        append("Запрос отклонён: нарушены инварианты.\n")
                        append(reasonText)
                    }
                )
            }

            else -> {
                InvariantCheckOutcome.allowed()
            }
        }
    }

    private fun buildInvariantValidationSystemPrompt(rules: List<InvariantRuleEntity>): String {
        val rulesBlock = rules.joinToString(separator = "\n") { rule ->
            "- [${rule.ruleKey}] ${rule.title}: ${rule.description}"
        }
        return buildString {
            appendLine(INVARIANT_VALIDATION_BASE_PROMPT)
            appendLine()
            appendLine("ИНВАРИАНТЫ:")
            appendLine(rulesBlock)
            appendLine()
            appendLine("ФОРМАТ ОТВЕТА (ОБЯЗАТЕЛЬНО):")
            appendLine("RESULT=[true] или RESULT=[false]")
            appendLine("VIOLATED_INVARIANTS: <rule_key через запятую или NONE>")
            appendLine("REASON: <краткое объяснение>")
            appendLine("ALTERNATIVE: <безопасная альтернатива, если RESULT=[false]>")
        }.trim()
    }

    private fun extractViolatedInvariantKeys(
        rawValidatorReply: String,
        knownRuleKeys: Set<String>
    ): Set<String> {
        val rawLinePayload = INVARIANT_VIOLATED_KEYS_REGEX
            .find(rawValidatorReply)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        if (rawLinePayload.isEmpty()) return emptySet()

        val normalizedPayload = rawLinePayload
            .removePrefix("[")
            .removeSuffix("]")
            .trim()
        if (normalizedPayload.equals("NONE", ignoreCase = true)) return emptySet()

        return normalizedPayload
            .split(Regex("[,;\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it in knownRuleKeys }
            .toSet()
    }

    private fun buildInvariantValidationPayload(
        validationStage: TaskStage,
        userInput: String
    ): String {
        return buildString {
            append("STAGE:\n")
            append(validationStage.name)
            append("\n\n")
            append("USER_MESSAGE:\n")
            append(userInput)
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
            val currentTaskStage = TaskStage.fromStored(session.taskStage)

            val resolvedTitle = if (
                controlCommand == null &&
                currentTaskStage == TaskStage.CONVERSATION &&
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
            val includeUserMessageInModelContext =
                controlCommand == null && currentTaskStage == TaskStage.CONVERSATION

            var userMessageId: Long = 0L
            database.withTransactionCompat {
                sessionDao.insertSession(sessionSnapshot)
                userMessageId = messageDao.insertMessage(
                    MessageEntity(
                        sessionId = sessionId,
                        role = MessageRole.USER.name,
                        taskStage = currentTaskStage.name,
                        includeInModelContext = includeUserMessageInModelContext,
                        content = cleanedContent,
                        createdAt = now
                    )
                )
            }

            if (controlCommand == null && currentTaskStage != TaskStage.CONVERSATION) {
                val formatReply = if (sessionSnapshot.isTaskPaused) {
                    TASK_PAUSED_HINT
                } else {
                    buildTaskStageInputHint(currentTaskStage)
                }
                val doneTimestamp = System.currentTimeMillis()
                database.withTransactionCompat {
                    messageDao.insertMessage(
                        MessageEntity(
                            sessionId = sessionId,
                            role = MessageRole.ASSISTANT.name,
                            taskStage = currentTaskStage.name,
                            includeInModelContext = false,
                            content = formatReply,
                            createdAt = doneTimestamp
                        )
                    )
                    sessionDao.touchSession(sessionId, doneTimestamp)
                }
                emit(formatReply)
                return@flow
            }

            if (controlCommand != null) {
                when (controlCommand) {
                    is ChatControlCommand.Memory,
                    is ChatControlCommand.Work -> {
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
                                    taskStage = currentTaskStage.name,
                                    includeInModelContext = false,
                                    content = commandResponse.reply,
                                    createdAt = doneTimestamp
                                )
                            )
                            sessionDao.touchSession(sessionId, doneTimestamp)
                        }
                        emit(commandResponse.reply)
                    }

                    is ChatControlCommand.Task -> {
                        val taskCommandOutcome = handleTaskCommand(
                            sessionId = sessionId,
                            session = sessionSnapshot,
                            command = controlCommand.command,
                            emitPartial = { partial -> emit(partial) }
                        )
                        val doneTimestamp = System.currentTimeMillis()
                        val persistedSession = taskCommandOutcome.updatedSession.copy(
                            updatedAt = doneTimestamp
                        )
                        database.withTransactionCompat {
                            sessionDao.insertSession(persistedSession)
                            taskCommandOutcome.assistantMessages.forEachIndexed { index, assistantMessage ->
                                messageDao.insertMessage(
                                    MessageEntity(
                                        sessionId = sessionId,
                                        role = MessageRole.ASSISTANT.name,
                                        taskStage = assistantMessage.stage.name,
                                        includeInModelContext = false,
                                        content = assistantMessage.content,
                                        createdAt = doneTimestamp + index
                                    )
                                )
                            }
                        }
                        if (!taskCommandOutcome.updatedSession.isTaskPaused) {
                            clearTaskPauseRequest(sessionId)
                        }
                        if (taskCommandOutcome.updatedSession.isTaskPaused) {
                            throw CancellationException(TASK_PAUSE_CANCELLATION_REASON)
                        }
                    }
                }
                return@flow
            }

            if (currentTaskStage == TaskStage.CONVERSATION) {
                val invariantCheck = runInvariantCheckIfEnabled(
                    session = sessionSnapshot,
                    validationStage = TaskStage.CONVERSATION,
                    userInput = cleanedContent
                )
                if (!invariantCheck.allowed) {
                    val rejectReply = invariantCheck.messageForUser
                    val doneTimestamp = System.currentTimeMillis()
                    database.withTransactionCompat {
                        messageDao.insertMessage(
                            MessageEntity(
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT.name,
                                taskStage = currentTaskStage.name,
                                includeInModelContext = true,
                                content = rejectReply,
                                createdAt = doneTimestamp
                            )
                        )
                        sessionDao.touchSession(sessionId, doneTimestamp)
                    }
                    emit(rejectReply)
                    return@flow
                }
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
                currentWorkTask = parseCurrentWorkTask(sessionSnapshot.currentWorkTaskJson),
                taskDescription = sessionSnapshot.taskDescription,
                taskFinalResult = sessionSnapshot.taskFinalResult
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
                            taskStage = TaskStage.CONVERSATION.name,
                            includeInModelContext = true,
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

    override suspend fun requestTaskPause(sessionId: String) {
        val session = sessionDao.getSessionById(sessionId) ?: return
        val stage = TaskStage.fromStored(session.taskStage)
        if (stage == TaskStage.CONVERSATION) {
            return
        }
        taskPauseRequestedBySession[sessionId] = true
    }

    override fun resumeTaskStreaming(sessionId: String): Flow<String> = flow {
        try {
            val session = sessionDao.getSessionById(sessionId)
                ?: throw ChatApiException("Session not found")
            val stage = TaskStage.fromStored(session.taskStage)
            if (!session.isTaskPaused) {
                throw ChatApiException("Task is not paused")
            }
            if (stage == TaskStage.CONVERSATION) {
                throw ChatApiException("Nothing to resume in conversation stage")
            }

            clearTaskPauseRequest(sessionId)

            val resumeOutcome = handleTaskResume(
                session = session,
                emitPartial = { partial -> emit(partial) }
            )
            val doneTimestamp = System.currentTimeMillis()
            val persistedSession = resumeOutcome.updatedSession.copy(updatedAt = doneTimestamp)

            database.withTransactionCompat {
                sessionDao.insertSession(persistedSession)
                resumeOutcome.assistantMessages.forEachIndexed { index, assistantMessage ->
                    messageDao.insertMessage(
                        MessageEntity(
                            sessionId = sessionId,
                            role = MessageRole.ASSISTANT.name,
                            taskStage = assistantMessage.stage.name,
                            includeInModelContext = false,
                            content = assistantMessage.content,
                            createdAt = doneTimestamp + index
                        )
                    )
                }
            }
            if (!resumeOutcome.updatedSession.isTaskPaused) {
                clearTaskPauseRequest(sessionId)
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
        val baseMessages = when (contextMode) {
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
        if (baseMessages.isEmpty()) return emptyList()

        val invariantContextMessage = buildInvariantContextMessage(session)
        return if (invariantContextMessage == null) {
            baseMessages
        } else {
            listOf(invariantContextMessage) + baseMessages
        }
    }

    private suspend fun buildInvariantContextMessage(session: SessionEntity): ChatCompletionMessage? {
        if (!session.isInvariantCheckEnabled) return null

        ensureDefaultInvariantRules()
        val activeRules = invariantRuleDao.getActiveRulesOnce()
            .sortedBy { it.position }
        if (activeRules.isEmpty()) return null

        val rulesBlock = activeRules.joinToString(separator = "\n") { rule ->
            "- [${rule.ruleKey}] ${rule.title}: ${rule.description}"
        }

        val prompt = buildString {
            appendLine("[INVARIANTS]")
            appendLine("Эти ограничения обязательны в каждом ответе:")
            appendLine(rulesBlock)
            appendLine()
            appendLine("Если запрос конфликтует с инвариантами, откажитесь от нарушающей части и предложите корректную альтернативу.")
        }.trim()

        return ChatCompletionMessage(
            role = SYSTEM_ROLE,
            content = prompt
        )
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

            is ChatControlCommand.Task -> {
                throw ChatApiException("Task command must be handled by task state machine")
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

    private suspend fun handleTaskCommand(
        sessionId: String,
        session: SessionEntity,
        command: TaskCommand,
        emitPartial: suspend (String) -> Unit
    ): TaskCommandOutcome {
        val stage = TaskStage.fromStored(session.taskStage)

        fun singleMessageOutcome(targetStage: TaskStage, content: String): TaskCommandOutcome {
            return TaskCommandOutcome(
                updatedSession = session,
                assistantMessages = listOf(
                    StageAssistantMessage(
                        stage = targetStage,
                        content = content
                    )
                )
            )
        }

        if (session.isTaskPaused) {
            val reply = "Процесс задачи на паузе. Нажмите «Продолжить»."
            emitPartial(reply)
            return singleMessageOutcome(stage, reply)
        }

        return when (command) {
            is TaskCommand.Invalid -> {
                emitPartial(command.reply)
                singleMessageOutcome(stage, command.reply)
            }

            is TaskCommand.Start -> {
                if (stage != TaskStage.CONVERSATION) {
                    val reply = buildInvalidTaskCommandReply(stage)
                    emitPartial(reply)
                    return singleMessageOutcome(stage, reply)
                }

                val invariantCheck = runInvariantCheckIfEnabled(
                    session = session,
                    validationStage = TaskStage.PLANNING,
                    userInput = command.description
                )
                if (!invariantCheck.allowed) {
                    emitPartial(invariantCheck.messageForUser)
                    return singleMessageOutcome(stage, invariantCheck.messageForUser)
                }

                val planningSession = persistTaskSessionState(
                    transitionTaskStage(
                        session = session.copy(
                            taskDescription = command.description,
                            taskPlan = null,
                            taskExecutionReport = null,
                            taskValidationReport = null,
                            taskFinalResult = null,
                            taskReplanReason = null,
                            taskAutoReplanCount = 0,
                            isTaskPaused = false,
                            taskPausedPartialResponse = null
                        ),
                        target = TaskStage.PLANNING
                    )
                )

                val userProfilePayload = resolveUserProfilePayload(planningSession.userProfileName)
                val generatedPlan = try {
                    requestTaskPlanning(
                        sessionId = sessionId,
                        session = planningSession,
                        taskDescription = planningSession.taskDescription.orEmpty(),
                        userProfilePayload = userProfilePayload,
                        replanReason = null,
                        partialResponseSoFar = null,
                        emitPartial = emitPartial
                    )
                } catch (paused: TaskStagePausedException) {
                    return buildPausedTaskOutcome(
                        session = planningSession,
                        stage = paused.stage,
                        partialResponse = paused.partialResponse
                    )
                }
                val planningReply = buildPlanningApprovalReply(plan = generatedPlan)
                emitPartial(planningReply)

                TaskCommandOutcome(
                    updatedSession = planningSession.copy(
                        taskPlan = generatedPlan,
                        taskReplanReason = null,
                        isTaskPaused = false,
                        taskPausedPartialResponse = null
                    ),
                    assistantMessages = listOf(
                        StageAssistantMessage(
                            stage = TaskStage.PLANNING,
                            content = planningReply
                        )
                    )
                )
            }

            TaskCommand.Approve -> {
                when (stage) {
                    TaskStage.PLANNING -> {
                        val taskDescription = session.taskDescription
                            ?.trim()
                            ?.ifBlank { null }
                            ?: run {
                                val reply = "Не удалось найти описание задачи. Запустите новую задачу через /task <описание>."
                                emitPartial(reply)
                                return singleMessageOutcome(stage, reply)
                            }
                        val approvedPlan = session.taskPlan
                            ?.trim()
                            ?.ifBlank { null }
                            ?: run {
                                val reply = "План отсутствует. Используйте /retry <причина>, чтобы собрать новый план."
                                emitPartial(reply)
                                return singleMessageOutcome(stage, reply)
                            }

                        val userProfilePayload = resolveUserProfilePayload(session.userProfileName)
                        val executionStageSession = persistTaskSessionState(
                            transitionTaskStage(
                                session = clearTaskPauseState(session),
                                target = TaskStage.EXECUTION
                            )
                        )
                        runExecutionThenValidation(
                            sessionId = sessionId,
                            executionStageSession = executionStageSession,
                            taskDescription = taskDescription,
                            approvedPlan = approvedPlan,
                            userProfilePayload = userProfilePayload,
                            executionPartialResponse = null,
                            emitPartial = emitPartial
                        )
                    }

                    TaskStage.DONE -> {
                        val conversationSession = persistTaskSessionState(
                            transitionTaskStage(
                                session = session.copy(
                                    taskPlan = null,
                                    taskExecutionReport = null,
                                    taskValidationReport = null,
                                    taskReplanReason = null,
                                    taskAutoReplanCount = 0,
                                    isTaskPaused = false,
                                    taskPausedPartialResponse = null
                                ),
                                target = TaskStage.CONVERSATION
                            )
                        )
                        val reply = "Задача завершена. Возвращаемся в обычный режим диалога."
                        emitPartial(reply)
                        TaskCommandOutcome(
                            updatedSession = conversationSession,
                            assistantMessages = listOf(
                                StageAssistantMessage(
                                    stage = TaskStage.CONVERSATION,
                                    content = reply
                                )
                            )
                        )
                    }

                    else -> {
                        val reply = buildInvalidTaskCommandReply(stage)
                        emitPartial(reply)
                        singleMessageOutcome(stage, reply)
                    }
                }
            }

            is TaskCommand.Retry -> {
                if (stage != TaskStage.PLANNING) {
                    val reply = buildInvalidTaskCommandReply(stage)
                    emitPartial(reply)
                    return singleMessageOutcome(stage, reply)
                }

                val taskDescription = session.taskDescription
                    ?.trim()
                    ?.ifBlank { null }
                    ?: run {
                        val reply = "Не удалось найти описание задачи. Запустите /task <описание>."
                        emitPartial(reply)
                        return singleMessageOutcome(stage, reply)
                    }
                val userProfilePayload = resolveUserProfilePayload(session.userProfileName)

                val invariantCheck = runInvariantCheckIfEnabled(
                    session = session,
                    validationStage = TaskStage.PLANNING,
                    userInput = command.reason
                )
                if (!invariantCheck.allowed) {
                    emitPartial(invariantCheck.messageForUser)
                    return singleMessageOutcome(stage, invariantCheck.messageForUser)
                }

                val planningSession = persistTaskSessionState(
                    transitionTaskStage(
                        session = session.copy(
                            taskReplanReason = command.reason,
                            taskAutoReplanCount = 0,
                            isTaskPaused = false,
                            taskPausedPartialResponse = null
                        ),
                        target = TaskStage.PLANNING
                    )
                )
                val replanned = try {
                    requestTaskPlanning(
                        sessionId = sessionId,
                        session = planningSession,
                        taskDescription = taskDescription,
                        userProfilePayload = userProfilePayload,
                        replanReason = command.reason,
                        partialResponseSoFar = null,
                        emitPartial = emitPartial
                    )
                } catch (paused: TaskStagePausedException) {
                    return buildPausedTaskOutcome(
                        session = planningSession,
                        stage = paused.stage,
                        partialResponse = paused.partialResponse
                    )
                }
                val replannedReply = buildPlanningApprovalReply(
                    plan = replanned,
                    replanReason = command.reason
                )
                emitPartial(replannedReply)

                TaskCommandOutcome(
                    updatedSession = planningSession.copy(
                        taskPlan = replanned,
                        isTaskPaused = false,
                        taskPausedPartialResponse = null
                    ),
                    assistantMessages = listOf(
                        StageAssistantMessage(
                            stage = TaskStage.PLANNING,
                            content = replannedReply
                        )
                    )
                )
            }

            is TaskCommand.Error -> {
                if (stage != TaskStage.DONE) {
                    val reply = buildInvalidTaskCommandReply(stage)
                    emitPartial(reply)
                    return singleMessageOutcome(stage, reply)
                }

                val taskDescription = session.taskDescription
                    ?.trim()
                    ?.ifBlank { null }
                    ?: run {
                        val reply = "Не удалось найти описание задачи. Запустите /task <описание>."
                        emitPartial(reply)
                        return singleMessageOutcome(stage, reply)
                    }
                val userProfilePayload = resolveUserProfilePayload(session.userProfileName)

                val invariantCheck = runInvariantCheckIfEnabled(
                    session = session,
                    validationStage = TaskStage.PLANNING,
                    userInput = command.reason
                )
                if (!invariantCheck.allowed) {
                    emitPartial(invariantCheck.messageForUser)
                    return singleMessageOutcome(stage, invariantCheck.messageForUser)
                }

                val planningSession = persistTaskSessionState(
                    transitionTaskStage(
                        session = session.copy(
                            taskReplanReason = command.reason,
                            taskAutoReplanCount = 0,
                            isTaskPaused = false,
                            taskPausedPartialResponse = null
                        ),
                        target = TaskStage.PLANNING
                    )
                )
                val replanned = try {
                    requestTaskPlanning(
                        sessionId = sessionId,
                        session = planningSession,
                        taskDescription = taskDescription,
                        userProfilePayload = userProfilePayload,
                        replanReason = command.reason,
                        partialResponseSoFar = null,
                        emitPartial = emitPartial
                    )
                } catch (paused: TaskStagePausedException) {
                    return buildPausedTaskOutcome(
                        session = planningSession,
                        stage = paused.stage,
                        partialResponse = paused.partialResponse
                    )
                }
                val replannedReply = buildPlanningApprovalReply(
                    plan = replanned,
                    replanReason = command.reason
                )
                emitPartial(replannedReply)

                TaskCommandOutcome(
                    updatedSession = planningSession.copy(
                        taskPlan = replanned,
                        isTaskPaused = false,
                        taskPausedPartialResponse = null
                    ),
                    assistantMessages = listOf(
                        StageAssistantMessage(
                            stage = TaskStage.PLANNING,
                            content = replannedReply
                        )
                    )
                )
            }
        }
    }

    private suspend fun persistTaskSessionState(session: SessionEntity): SessionEntity {
        val updated = session.copy(updatedAt = System.currentTimeMillis())
        sessionDao.insertSession(updated)
        return updated
    }

    private fun transitionTaskStage(session: SessionEntity, target: TaskStage): SessionEntity {
        val current = TaskStage.fromStored(session.taskStage)
        if (!isTaskTransitionAllowed(current = current, target = target)) {
            throw ChatApiException("Invalid task state transition: ${current.name} -> ${target.name}")
        }
        return session.copy(taskStage = target.name)
    }

    private fun isTaskTransitionAllowed(current: TaskStage, target: TaskStage): Boolean {
        return TaskStageTransitionPolicy.isAllowed(current = current, target = target)
    }

    private fun clearTaskPauseState(session: SessionEntity): SessionEntity {
        return session.copy(
            isTaskPaused = false,
            taskPausedPartialResponse = null
        )
    }

    private fun isTaskPauseRequested(sessionId: String): Boolean {
        return taskPauseRequestedBySession[sessionId] == true
    }

    private fun clearTaskPauseRequest(sessionId: String) {
        taskPauseRequestedBySession.remove(sessionId)
    }

    private fun buildStageMessageKey(message: StageAssistantMessage): String {
        return "${message.stage.name}|${message.content.trim()}"
    }

    private suspend fun persistTaskAssistantMessage(
        sessionId: String,
        assistantMessage: StageAssistantMessage
    ) {
        val timestamp = System.currentTimeMillis()
        database.withTransactionCompat {
            messageDao.insertMessage(
                MessageEntity(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT.name,
                    taskStage = assistantMessage.stage.name,
                    includeInModelContext = false,
                    content = assistantMessage.content,
                    createdAt = timestamp
                )
            )
            sessionDao.touchSession(sessionId, timestamp)
        }
    }

    private suspend fun buildPausedTaskOutcome(
        session: SessionEntity,
        stage: TaskStage,
        partialResponse: String?
    ): TaskCommandOutcome {
        clearTaskPauseRequest(session.id)
        val pausedSession = persistTaskSessionState(
            session.copy(
                taskStage = stage.name,
                isTaskPaused = true,
                taskPausedPartialResponse = partialResponse
                    ?.trim()
                    ?.ifBlank { null }
            )
        )
        return TaskCommandOutcome(
            updatedSession = pausedSession,
            assistantMessages = emptyList()
        )
    }

    private suspend fun handleTaskResume(
        session: SessionEntity,
        emitPartial: suspend (String) -> Unit
    ): TaskCommandOutcome {
        val stage = TaskStage.fromStored(session.taskStage)
        val partialResponse = session.taskPausedPartialResponse
            ?.trim()
            ?.ifBlank { null }
        val resumedSession = clearTaskPauseState(session)

        fun singleResumeMessage(content: String): TaskCommandOutcome {
            return TaskCommandOutcome(
                updatedSession = resumedSession,
                assistantMessages = listOf(
                    StageAssistantMessage(
                        stage = stage,
                        content = content
                    )
                )
            )
        }

        return when (stage) {
            TaskStage.PLANNING -> {
                val taskDescription = resumedSession.taskDescription
                    ?.trim()
                    ?.ifBlank { null }
                    ?: run {
                        val reply =
                            "Не удалось возобновить planning: описание задачи отсутствует. Запустите /task <описание>."
                        emitPartial(reply)
                        return singleResumeMessage(reply)
                    }
                val userProfilePayload = resolveUserProfilePayload(resumedSession.userProfileName)
                val generatedPlan = try {
                    requestTaskPlanning(
                        sessionId = resumedSession.id,
                        session = resumedSession,
                        taskDescription = taskDescription,
                        userProfilePayload = userProfilePayload,
                        replanReason = resumedSession.taskReplanReason,
                        partialResponseSoFar = partialResponse,
                        emitPartial = emitPartial
                    )
                } catch (paused: TaskStagePausedException) {
                    return buildPausedTaskOutcome(
                        session = resumedSession,
                        stage = paused.stage,
                        partialResponse = paused.partialResponse
                    )
                }
                val planningReply = buildPlanningApprovalReply(
                    plan = generatedPlan,
                    replanReason = resumedSession.taskReplanReason
                )
                emitPartial(planningReply)
                TaskCommandOutcome(
                    updatedSession = resumedSession.copy(
                        taskPlan = generatedPlan
                    ),
                    assistantMessages = listOf(
                        StageAssistantMessage(
                            stage = TaskStage.PLANNING,
                            content = planningReply
                        )
                    )
                )
            }

            TaskStage.EXECUTION -> {
                val taskDescription = resumedSession.taskDescription
                    ?.trim()
                    ?.ifBlank { null }
                    ?: run {
                        val reply =
                            "Не удалось возобновить execution: описание задачи отсутствует. Запустите /task <описание>."
                        emitPartial(reply)
                        return singleResumeMessage(reply)
                    }
                val approvedPlan = resumedSession.taskPlan
                    ?.trim()
                    ?.ifBlank { null }
                    ?: run {
                        val reply =
                            "Не удалось возобновить execution: план отсутствует. Используйте /retry <причина>."
                        emitPartial(reply)
                        return singleResumeMessage(reply)
                    }
                val userProfilePayload = resolveUserProfilePayload(resumedSession.userProfileName)
                runExecutionThenValidation(
                    sessionId = resumedSession.id,
                    executionStageSession = resumedSession,
                    taskDescription = taskDescription,
                    approvedPlan = approvedPlan,
                    userProfilePayload = userProfilePayload,
                    executionPartialResponse = partialResponse,
                    emitPartial = emitPartial
                )
            }

            TaskStage.VALIDATION -> {
                val taskDescription = resumedSession.taskDescription
                    ?.trim()
                    ?.ifBlank { null }
                    ?: run {
                        val reply =
                            "Не удалось возобновить validation: описание задачи отсутствует. Запустите /task <описание>."
                        emitPartial(reply)
                        return singleResumeMessage(reply)
                    }
                val approvedPlan = resumedSession.taskPlan
                    ?.trim()
                    ?.ifBlank { null }
                    ?: run {
                        val reply =
                            "Не удалось возобновить validation: план отсутствует. Используйте /retry <причина>."
                        emitPartial(reply)
                        return singleResumeMessage(reply)
                    }
                val executionReport = resumedSession.taskExecutionReport
                    ?.trim()
                    ?.ifBlank { null }
                    ?: run {
                        val reply =
                            "Не удалось возобновить validation: отчёт execution отсутствует. Подтвердите план через /ok заново."
                        emitPartial(reply)
                        return singleResumeMessage(reply)
                    }
                val userProfilePayload = resolveUserProfilePayload(resumedSession.userProfileName)
                runValidationAndFinalize(
                    sessionId = resumedSession.id,
                    validationStageSession = resumedSession.copy(
                        taskExecutionReport = executionReport
                    ),
                    executionMessages = emptyList(),
                    taskDescription = taskDescription,
                    approvedPlan = approvedPlan,
                    executionReport = executionReport,
                    userProfilePayload = userProfilePayload,
                    validationPartialResponse = partialResponse,
                    emitPartial = emitPartial
                )
            }

            TaskStage.DONE -> {
                val doneReply = partialResponse ?: buildDoneStageReply(
                    finalResult = resumedSession.taskFinalResult ?: ""
                )
                emitPartial(doneReply)
                TaskCommandOutcome(
                    updatedSession = resumedSession,
                    assistantMessages = listOf(
                        StageAssistantMessage(
                            stage = TaskStage.DONE,
                            content = doneReply
                        )
                    )
                )
            }

            TaskStage.CONVERSATION -> {
                val reply = "В conversation этапе нечего возобновлять."
                emitPartial(reply)
                singleResumeMessage(reply)
            }
        }
    }

    private suspend fun runExecutionThenValidation(
        sessionId: String,
        executionStageSession: SessionEntity,
        taskDescription: String,
        approvedPlan: String,
        userProfilePayload: String?,
        executionPartialResponse: String?,
        emitPartial: suspend (String) -> Unit
    ): TaskCommandOutcome {
        val persistedExecutionMessageKeys = buildExecutionStreamingMessages(
            executionPartialResponse.orEmpty()
        )
            .mapTo(mutableSetOf()) { buildStageMessageKey(it) }

        val executionReport = try {
            requestTaskExecution(
                sessionId = sessionId,
                session = executionStageSession,
                taskDescription = taskDescription,
                approvedPlan = approvedPlan,
                userProfilePayload = userProfilePayload,
                partialResponseSoFar = executionPartialResponse,
                onExecutionMessage = { stageMessage ->
                    val key = buildStageMessageKey(stageMessage)
                    if (persistedExecutionMessageKeys.add(key)) {
                        persistTaskAssistantMessage(sessionId, stageMessage)
                    }
                }
            )
        } catch (paused: TaskStagePausedException) {
            return buildPausedTaskOutcome(
                session = executionStageSession,
                stage = paused.stage,
                partialResponse = paused.partialResponse
            )
        }

        val withExecution = clearTaskPauseState(executionStageSession).copy(
            taskExecutionReport = executionReport
        )
        val executionMessages = buildExecutionStageMessages(executionReport)
        executionMessages.forEach { stageMessage ->
            val key = buildStageMessageKey(stageMessage)
            if (persistedExecutionMessageKeys.add(key)) {
                persistTaskAssistantMessage(sessionId, stageMessage)
            }
        }
        val validationStageSession = persistTaskSessionState(
            transitionTaskStage(
                session = withExecution,
                target = TaskStage.VALIDATION
            )
        )

        return runValidationAndFinalize(
            sessionId = sessionId,
            validationStageSession = validationStageSession,
            executionMessages = emptyList(),
            taskDescription = taskDescription,
            approvedPlan = approvedPlan,
            executionReport = executionReport,
            userProfilePayload = userProfilePayload,
            validationPartialResponse = null,
            emitPartial = emitPartial
        )
    }

    private suspend fun runValidationAndFinalize(
        sessionId: String,
        validationStageSession: SessionEntity,
        executionMessages: List<StageAssistantMessage>,
        taskDescription: String,
        approvedPlan: String,
        executionReport: String,
        userProfilePayload: String?,
        validationPartialResponse: String?,
        emitPartial: suspend (String) -> Unit
    ): TaskCommandOutcome {
        val validationReport = try {
            requestTaskValidation(
                sessionId = sessionId,
                session = validationStageSession,
                taskDescription = taskDescription,
                approvedPlan = approvedPlan,
                executionReport = executionReport,
                userProfilePayload = userProfilePayload,
                partialResponseSoFar = validationPartialResponse,
                emitPartial = emitPartial
            )
        } catch (paused: TaskStagePausedException) {
            return buildPausedTaskOutcome(
                session = validationStageSession.copy(
                    taskExecutionReport = executionReport
                ),
                stage = paused.stage,
                partialResponse = paused.partialResponse
            )
        }
        val executionFinalResult = extractExecutionFinalResult(executionReport)
        val localCodeIssue = buildFinalResultCodeValidationIssue(executionFinalResult)
        val effectiveValidationReport = appendLocalValidationGuard(
            validationReport = validationReport,
            localIssue = localCodeIssue
        )
        val withValidation = clearTaskPauseState(validationStageSession).copy(
            taskExecutionReport = executionReport,
            taskValidationReport = effectiveValidationReport
        )
        val validationMessage = StageAssistantMessage(
            stage = TaskStage.VALIDATION,
            content = effectiveValidationReport
        )

        val decision = if (localCodeIssue != null) {
            ValidationDecision.REPLAN_REQUIRED
        } else {
            parseValidationDecision(effectiveValidationReport)
        }
        if (decision == ValidationDecision.SUCCESS) {
            val doneSession = persistTaskSessionState(
                transitionTaskStage(
                    session = withValidation.copy(
                        taskFinalResult = executionFinalResult,
                        taskReplanReason = null,
                        taskAutoReplanCount = 0
                    ),
                    target = TaskStage.DONE
                )
            )
            val doneReply = buildDoneStageReply(finalResult = executionFinalResult)
            if (isTaskPauseRequested(sessionId)) {
                return buildPausedTaskOutcome(
                    session = doneSession,
                    stage = TaskStage.DONE,
                    partialResponse = doneReply
                )
            }
            emitPartial(doneReply)
            return TaskCommandOutcome(
                updatedSession = doneSession,
                assistantMessages = executionMessages +
                    listOf(
                        validationMessage,
                        StageAssistantMessage(
                            stage = TaskStage.DONE,
                            content = doneReply
                        )
                    )
            )
        }

        val issues = mergeValidationIssues(
            modelIssues = extractValidationIssues(effectiveValidationReport),
            localIssue = localCodeIssue
        )
            ?: DEFAULT_VALIDATION_REPLAN_REASON
        val attempts = withValidation.taskAutoReplanCount + 1
        if (attempts >= MAX_TASK_AUTO_REPLAN_ATTEMPTS) {
            val doneSession = persistTaskSessionState(
                transitionTaskStage(
                    session = withValidation.copy(
                        taskReplanReason = issues,
                        taskAutoReplanCount = attempts
                    ),
                    target = TaskStage.DONE
                )
            )
            val doneReply = buildAutoReplanLimitReachedReply(
                issues = issues,
                attempts = attempts
            )
            if (isTaskPauseRequested(sessionId)) {
                return buildPausedTaskOutcome(
                    session = doneSession,
                    stage = TaskStage.DONE,
                    partialResponse = doneReply
                )
            }
            emitPartial(doneReply)
            return TaskCommandOutcome(
                updatedSession = doneSession,
                assistantMessages = executionMessages +
                    listOf(
                        validationMessage,
                        StageAssistantMessage(
                            stage = TaskStage.DONE,
                            content = doneReply
                        )
                    )
            )
        }

        val planningStageSession = persistTaskSessionState(
            transitionTaskStage(
                session = withValidation.copy(
                    taskReplanReason = issues,
                    taskAutoReplanCount = attempts
                ),
                target = TaskStage.PLANNING
            )
        )
        val replanned = try {
            requestTaskPlanning(
                sessionId = sessionId,
                session = planningStageSession,
                taskDescription = taskDescription,
                userProfilePayload = userProfilePayload,
                replanReason = issues,
                partialResponseSoFar = null,
                emitPartial = emitPartial
            )
        } catch (paused: TaskStagePausedException) {
            return buildPausedTaskOutcome(
                session = planningStageSession,
                stage = paused.stage,
                partialResponse = paused.partialResponse
            )
        }
        val replannedReply = buildPlanningApprovalReply(
            plan = replanned,
            replanReason = issues
        )
        emitPartial(replannedReply)

        return TaskCommandOutcome(
            updatedSession = planningStageSession.copy(
                taskPlan = replanned
            ),
            assistantMessages = executionMessages +
                listOf(
                    validationMessage,
                    StageAssistantMessage(
                        stage = TaskStage.PLANNING,
                        content = replannedReply
                    )
                )
        )
    }

    private suspend fun requestTaskPlanning(
        sessionId: String,
        session: SessionEntity,
        taskDescription: String,
        userProfilePayload: String?,
        replanReason: String?,
        partialResponseSoFar: String?,
        emitPartial: suspend (String) -> Unit
    ): String {
        val payload = buildPlanningPayload(
            taskDescription = taskDescription,
            userProfilePayload = userProfilePayload,
            replanReason = replanReason,
            partialResponseSoFar = partialResponseSoFar
        )
        val invariantMessage = buildInvariantContextMessage(session)
        return requestSingleAssistantMessageStreaming(
            sessionId = sessionId,
            stage = TaskStage.PLANNING,
            systemPrompt = TASK_PLANNING_SYSTEM_PROMPT,
            userPayload = payload,
            partialResponseSoFar = partialResponseSoFar,
            additionalSystemMessages = listOfNotNull(invariantMessage),
            emitPartial = emitPartial
        )
    }

    private suspend fun requestTaskExecution(
        sessionId: String,
        session: SessionEntity,
        taskDescription: String,
        approvedPlan: String,
        userProfilePayload: String?,
        partialResponseSoFar: String?,
        onExecutionMessage: suspend (StageAssistantMessage) -> Unit
    ): String {
        val payload = buildExecutionPayload(
            taskDescription = taskDescription,
            approvedPlan = approvedPlan,
            userProfilePayload = userProfilePayload,
            partialResponseSoFar = partialResponseSoFar
        )
        return requestTaskExecutionStreaming(
            sessionId = sessionId,
            session = session,
            userPayload = payload,
            partialResponseSoFar = partialResponseSoFar,
            onExecutionMessage = onExecutionMessage
        )
    }

    private suspend fun requestTaskValidation(
        sessionId: String,
        session: SessionEntity,
        taskDescription: String,
        approvedPlan: String,
        executionReport: String,
        userProfilePayload: String?,
        partialResponseSoFar: String?,
        emitPartial: suspend (String) -> Unit
    ): String {
        val payload = buildValidationPayload(
            taskDescription = taskDescription,
            approvedPlan = approvedPlan,
            executionReport = executionReport,
            userProfilePayload = userProfilePayload,
            partialResponseSoFar = partialResponseSoFar
        )
        val invariantMessage = buildInvariantContextMessage(session)
        return requestSingleAssistantMessageStreaming(
            sessionId = sessionId,
            stage = TaskStage.VALIDATION,
            systemPrompt = TASK_VALIDATION_SYSTEM_PROMPT,
            userPayload = payload,
            partialResponseSoFar = partialResponseSoFar,
            additionalSystemMessages = listOfNotNull(invariantMessage),
            emitPartial = emitPartial
        )
    }

    private suspend fun requestTaskExecutionStreaming(
        sessionId: String,
        session: SessionEntity,
        userPayload: String,
        partialResponseSoFar: String?,
        onExecutionMessage: suspend (StageAssistantMessage) -> Unit
    ): String {
        val invariantMessage = buildInvariantContextMessage(session)
        val request = ChatCompletionRequest(
            model = DEFAULT_MODEL,
            messages = buildList {
                add(
                    ChatCompletionMessage(
                        role = SYSTEM_ROLE,
                        content = TASK_EXECUTION_SYSTEM_PROMPT
                    )
                )
                invariantMessage?.let { add(it) }
                add(
                    ChatCompletionMessage(
                        role = USER_ROLE,
                        content = userPayload
                    )
                )
            },
            stream = true,
            streamOptions = StreamOptions(includeUsage = false)
        )

        val partialPrefix = partialResponseSoFar
            ?.trim()
            ?.ifBlank { null }
            .orEmpty()
        var continuationText = ""
        var finalAssistantText = partialPrefix
        val emittedExecutionMessageKeys = buildExecutionStreamingMessages(partialPrefix)
            .mapTo(mutableSetOf()) { buildStageMessageKey(it) }
        if (isTaskPauseRequested(sessionId)) {
            throw TaskStagePausedException(
                stage = TaskStage.EXECUTION,
                partialResponse = finalAssistantText.trim()
            )
        }
        val statement = deepSeekApi.prepareStreamChatCompletions(request)
        try {
            statement.execute { response ->
                val code = response.status.value
                if (code !in 200..299) {
                    val rawError = runCatching { response.bodyAsText() }.getOrNull()
                    throw ChatApiException(buildApiErrorMessage(code = code, rawError = rawError))
                }

                val responseBody = response.bodyAsChannel()
                sseStreamParser.streamEvents(responseBody).collect { event ->
                    if (event is SseStreamEvent.Text) {
                        continuationText = event.value
                        finalAssistantText = partialPrefix + continuationText
                        val streamingMessages = buildExecutionStreamingMessages(finalAssistantText)
                        streamingMessages.forEach { stageMessage ->
                            val key = buildStageMessageKey(stageMessage)
                            if (emittedExecutionMessageKeys.add(key)) {
                                onExecutionMessage(stageMessage)
                            }
                        }
                        if (isTaskPauseRequested(sessionId)) {
                            throw TaskStagePausedException(
                                stage = TaskStage.EXECUTION,
                                partialResponse = finalAssistantText.trim()
                            )
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (isTaskPauseRequested(sessionId)) {
                throw TaskStagePausedException(
                    stage = TaskStage.EXECUTION,
                    partialResponse = finalAssistantText.trim()
                )
            }
            throw cancelled
        }

        return finalAssistantText.trim()
    }

    private suspend fun requestSingleAssistantMessageStreaming(
        sessionId: String,
        stage: TaskStage,
        systemPrompt: String,
        userPayload: String,
        partialResponseSoFar: String?,
        additionalSystemMessages: List<ChatCompletionMessage> = emptyList(),
        emitPartial: suspend (String) -> Unit
    ): String {
        val request = ChatCompletionRequest(
            model = DEFAULT_MODEL,
            messages = buildList {
                add(
                    ChatCompletionMessage(
                        role = SYSTEM_ROLE,
                        content = systemPrompt
                    )
                )
                addAll(additionalSystemMessages)
                add(
                    ChatCompletionMessage(
                        role = USER_ROLE,
                        content = userPayload
                    )
                )
            },
            stream = true,
            streamOptions = StreamOptions(includeUsage = false)
        )

        val partialPrefix = partialResponseSoFar
            ?.trim()
            ?.ifBlank { null }
            .orEmpty()
        var continuationText = ""
        var finalAssistantText = partialPrefix
        if (isTaskPauseRequested(sessionId)) {
            throw TaskStagePausedException(
                stage = stage,
                partialResponse = finalAssistantText.trim()
            )
        }
        val statement = deepSeekApi.prepareStreamChatCompletions(request)
        try {
            statement.execute { response ->
                val code = response.status.value
                if (code !in 200..299) {
                    val rawError = runCatching { response.bodyAsText() }.getOrNull()
                    throw ChatApiException(buildApiErrorMessage(code = code, rawError = rawError))
                }

                val responseBody = response.bodyAsChannel()
                sseStreamParser.streamEvents(responseBody).collect { event ->
                    if (event is SseStreamEvent.Text) {
                        continuationText = event.value
                        finalAssistantText = partialPrefix + continuationText
                        if (finalAssistantText.isNotEmpty()) {
                            emitPartial(finalAssistantText)
                        }
                        if (isTaskPauseRequested(sessionId)) {
                            throw TaskStagePausedException(
                                stage = stage,
                                partialResponse = finalAssistantText.trim()
                            )
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (isTaskPauseRequested(sessionId)) {
                throw TaskStagePausedException(
                    stage = stage,
                    partialResponse = finalAssistantText.trim()
                )
            }
            throw cancelled
        }

        return finalAssistantText.trim()
    }

    private fun buildPlanningPayload(
        taskDescription: String,
        userProfilePayload: String?,
        replanReason: String?,
        partialResponseSoFar: String?
    ): String {
        return buildString {
            append("USER TASK:\n")
            append(taskDescription.trim())
            append("\n\n")

            if (!userProfilePayload.isNullOrBlank()) {
                append("USER PROFILE:\n")
                append(userProfilePayload.trim())
                append("\n\n")
            }

            if (!replanReason.isNullOrBlank()) {
                append("REPLAN REASON:\n")
                append(replanReason.trim())
            } else {
                append("REPLAN REASON:\nNONE")
            }
            append("\n\n")
            appendContinuationPayloadBlock(
                container = this,
                partialResponseSoFar = partialResponseSoFar
            )
        }
    }

    private fun buildExecutionPayload(
        taskDescription: String,
        approvedPlan: String,
        userProfilePayload: String?,
        partialResponseSoFar: String?
    ): String {
        return buildString {
            append("USER TASK:\n")
            append(taskDescription.trim())
            append("\n\n")
            append("APPROVED PLAN:\n")
            append(approvedPlan.trim())
            append("\n\n")
            append("USER PROFILE:\n")
            if (userProfilePayload.isNullOrBlank()) {
                append("NONE")
            } else {
                append(userProfilePayload.trim())
            }
            append("\n\n")
            appendContinuationPayloadBlock(
                container = this,
                partialResponseSoFar = partialResponseSoFar
            )
        }
    }

    private fun buildValidationPayload(
        taskDescription: String,
        approvedPlan: String,
        executionReport: String,
        userProfilePayload: String?,
        partialResponseSoFar: String?
    ): String {
        return buildString {
            append("USER TASK:\n")
            append(taskDescription.trim())
            append("\n\n")
            append("APPROVED PLAN:\n")
            append(approvedPlan.trim())
            append("\n\n")
            append("EXECUTION REPORT:\n")
            append(executionReport.trim())
            append("\n\n")
            append("USER PROFILE:\n")
            if (userProfilePayload.isNullOrBlank()) {
                append("NONE")
            } else {
                append(userProfilePayload.trim())
            }
            append("\n\n")
            appendContinuationPayloadBlock(
                container = this,
                partialResponseSoFar = partialResponseSoFar
            )
        }
    }

    private fun appendContinuationPayloadBlock(
        container: StringBuilder,
        partialResponseSoFar: String?
    ) {
        val normalizedPartial = partialResponseSoFar
            ?.trim()
            ?.ifBlank { null }
        if (normalizedPartial == null) {
            container.append("PARTIAL_RESPONSE_SO_FAR:\nNONE")
            return
        }

        container.append("PARTIAL_RESPONSE_SO_FAR:\n")
        container.append(normalizedPartial)
        container.append("\n\n")
        container.append("CONTINUE_INSTRUCTIONS:\n")
        container.append("Continue strictly from the point where the partial response stopped. ")
        container.append("Do not repeat already written content and keep the same output format.")
    }

    private fun parseValidationDecision(validationReport: String): ValidationDecision {
        val decisionRegex = Regex(
            pattern = "FINAL_DECISION:\\s*(SUCCESS|REPLAN_REQUIRED)",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val decision = decisionRegex.find(validationReport)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase()
        return if (decision == "SUCCESS") {
            ValidationDecision.SUCCESS
        } else {
            ValidationDecision.REPLAN_REQUIRED
        }
    }

    private fun buildFinalResultCodeValidationIssue(executionFinalResult: String): String? {
        return if (containsExplicitCode(executionFinalResult)) {
            null
        } else {
            "FINAL_RESULT не содержит явного итогового кода. Добавьте код (предпочтительно в fenced code block)."
        }
    }

    private fun containsExplicitCode(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        if (CODE_FENCE_REGEX.containsMatchIn(normalized)) return true
        return KOTLIN_CODE_LINE_REGEX.containsMatchIn(normalized)
    }

    private fun appendLocalValidationGuard(
        validationReport: String,
        localIssue: String?
    ): String {
        if (localIssue == null) return validationReport
        return buildString {
            append(validationReport.trim())
            append("\n\nLOCAL_VALIDATION_GUARD:\n")
            append(localIssue)
            append("\nFINAL_DECISION_OVERRIDE:\nREPLAN_REQUIRED")
        }
    }

    private fun mergeValidationIssues(modelIssues: String?, localIssue: String?): String? {
        val merged = buildList {
            modelIssues?.trim()?.ifBlank { null }?.let { add(it) }
            localIssue?.trim()?.ifBlank { null }?.let { add(it) }
        }.distinct()

        if (merged.isEmpty()) return null
        return merged.joinToString(separator = "\n")
    }

    private fun extractValidationIssues(validationReport: String): String? {
        val issuesRegex = Regex(
            pattern = "ISSUES_FOUND:\\s*(.*?)\\n\\s*RESULT_QUALITY:",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val rawIssues = issuesRegex.find(validationReport)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
            ?: return null
        return if (rawIssues.equals("NONE", ignoreCase = true)) {
            null
        } else {
            rawIssues
        }
    }

    private fun extractExecutionFinalResult(executionReport: String): String {
        val resultRegex = Regex(
            pattern = "FINAL_RESULT:\\s*(.*?)\\n\\s*NEXT_STATE:",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return resultRegex.find(executionReport)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
            ?: executionReport.trim()
    }

    private fun buildExecutionStageMessages(executionReport: String): List<StageAssistantMessage> {
        val parsed = parseExecutionReport(executionReport)
        val messages = mutableListOf<StageAssistantMessage>()

        messages += StageAssistantMessage(
            stage = TaskStage.EXECUTION,
            content = "STEPS_EXECUTED:"
        )

        if (parsed.steps.isEmpty()) {
            messages += StageAssistantMessage(
                stage = TaskStage.EXECUTION,
                content = "(нет данных)"
            )
        } else {
            parsed.steps.forEachIndexed { index, step ->
                val stepNumber = step.step ?: (index + 1)
                val stepTitle = step.description.ifBlank { "Шаг $stepNumber" }
                val actionText = step.action.ifBlank { "—" }
                val resultText = step.result.ifBlank { "—" }
                val messageText = buildString {
                    append("$stepNumber. $stepTitle")
                    append("\nДействие: $actionText")
                    append("\nРезультат: $resultText")
                    val toolCall = step.toolCall?.trim().orEmpty()
                    if (toolCall.isNotEmpty() && !toolCall.equals("NONE", ignoreCase = true)) {
                        append("\nИнструмент: $toolCall")
                    }
                }
                messages += StageAssistantMessage(
                    stage = TaskStage.EXECUTION,
                    content = messageText
                )
            }
        }

        messages += StageAssistantMessage(
            stage = TaskStage.EXECUTION,
            content = "FINAL_RESULT:\n${parsed.finalResult.ifBlank { "—" }}"
        )

        messages += StageAssistantMessage(
            stage = TaskStage.EXECUTION,
            content = "NEXT_STATE:\n${parsed.nextState?.ifBlank { "validation" } ?: "validation"}"
        )

        return messages
    }

    private fun buildExecutionStreamingMessages(executionReport: String): List<StageAssistantMessage> {
        val partial = parseExecutionReportPartial(executionReport)
        if (!partial.hasStructuredSections) {
            return emptyList()
        }

        val messages = mutableListOf<StageAssistantMessage>()

        if (partial.hasStepsSection) {
            messages += StageAssistantMessage(
                stage = TaskStage.EXECUTION,
                content = "STEPS_EXECUTED:"
            )
            partial.steps.forEachIndexed { index, step ->
                val stepNumber = step.step ?: (index + 1)
                val stepTitle = step.description.ifBlank { "Шаг $stepNumber" }
                val actionText = step.action.ifBlank { "—" }
                val resultText = step.result.ifBlank { "—" }
                val messageText = buildString {
                    append("$stepNumber. $stepTitle")
                    append("\nДействие: $actionText")
                    append("\nРезультат: $resultText")
                    val toolCall = step.toolCall?.trim().orEmpty()
                    if (toolCall.isNotEmpty() && !toolCall.equals("NONE", ignoreCase = true)) {
                        append("\nИнструмент: $toolCall")
                    }
                }
                messages += StageAssistantMessage(
                    stage = TaskStage.EXECUTION,
                    content = messageText
                )
            }
        }

        if (
            partial.hasFinalResultSection &&
            partial.hasNextStateSection &&
            partial.finalResult != null
        ) {
            messages += StageAssistantMessage(
                stage = TaskStage.EXECUTION,
                content = "FINAL_RESULT:\n${partial.finalResult}"
            )
        }

        if (partial.hasNextStateSection && partial.nextState != null) {
            messages += StageAssistantMessage(
                stage = TaskStage.EXECUTION,
                content = "NEXT_STATE:\n${partial.nextState}"
            )
        }

        return messages
    }

    private fun parseExecutionReport(executionReport: String): ParsedExecutionReport {
        val parsedSteps = parseExecutionStepsFromReport(executionReport)

        return ParsedExecutionReport(
            steps = parsedSteps,
            finalResult = extractExecutionFinalResult(executionReport),
            nextState = extractExecutionNextState(executionReport)
        )
    }

    private fun parseExecutionReportPartial(executionReport: String): PartialExecutionReport {
        val parsedSteps = parseExecutionStepsFromReport(
            executionReport = executionReport,
            requireCompleteFields = true
        )
        val hasFinalResultSection = FINAL_RESULT_MARKER_REGEX.containsMatchIn(executionReport)
        val hasNextStateSection = NEXT_STATE_MARKER_REGEX.containsMatchIn(executionReport)

        return PartialExecutionReport(
            hasStepsSection = STEPS_EXECUTED_MARKER_REGEX.containsMatchIn(executionReport),
            steps = parsedSteps,
            hasFinalResultSection = hasFinalResultSection,
            finalResult = extractExecutionFinalResultPartial(executionReport),
            hasNextStateSection = hasNextStateSection,
            nextState = extractExecutionNextState(executionReport),
            hasStructuredSections = STEPS_EXECUTED_MARKER_REGEX.containsMatchIn(executionReport) ||
                hasFinalResultSection ||
                hasNextStateSection
            )
    }

    private fun parseExecutionStepsFromReport(
        executionReport: String,
        requireCompleteFields: Boolean = false
    ): List<ParsedExecutionStep> {
        val strictParsed = extractExecutionStepsPayload(executionReport)
            ?.let { payload ->
                runCatching {
                    json.parseToJsonElement(payload)
                }.getOrNull() as? JsonArray
            }
            ?.mapNotNull { element ->
                val objectPayload = element as? JsonObject ?: return@mapNotNull null
                parseExecutionStepObject(
                    stepObject = objectPayload,
                    requireCompleteFields = requireCompleteFields
                )
            }
            .orEmpty()
        if (strictParsed.isNotEmpty()) return strictParsed

        val stepsSection = extractExecutionStepsSection(executionReport) ?: return emptyList()
        val tolerantCandidates = buildList {
            addAll(extractBalancedStepCandidates(stepsSection))
            addAll(extractAnchoredStepCandidates(stepsSection))
        }
            .map { sanitizeStepJsonCandidate(it) }
            .distinct()

        val tolerantParsed = tolerantCandidates
            .mapNotNull { payload ->
                parseExecutionStepPayload(
                    stepPayload = payload,
                    requireCompleteFields = requireCompleteFields
                )
            }
        if (tolerantParsed.isEmpty()) return emptyList()

        val deduplicated = mutableListOf<ParsedExecutionStep>()
        val seenSignatures = mutableSetOf<String>()
        tolerantParsed.forEach { step ->
            val signature = buildString {
                append(step.step ?: -1)
                append('|')
                append(step.description)
                append('|')
                append(step.action)
                append('|')
                append(step.result)
                append('|')
                append(step.toolCall.orEmpty())
            }
            if (seenSignatures.add(signature)) {
                deduplicated += step
            }
        }
        return deduplicated
    }

    private fun parseExecutionStepObject(
        stepObject: JsonObject,
        requireCompleteFields: Boolean
    ): ParsedExecutionStep? {
        val stepNumber = stepObject.readString("step")?.toIntOrNull()
        val description = stepObject.readString("description").orEmpty()
        val action = stepObject.readString("action").orEmpty()
        val toolCall = stepObject.readString("tool_call")
        val result = stepObject.readString("result").orEmpty()

        if (
            requireCompleteFields &&
            (
                stepNumber == null ||
                    description.isBlank() ||
                    action.isBlank() ||
                    result.isBlank()
                )
        ) {
            return null
        }

        if (stepNumber == null && description.isBlank() && action.isBlank() && result.isBlank()) {
            return null
        }

        return ParsedExecutionStep(
            step = stepNumber,
            description = description,
            action = action,
            toolCall = toolCall,
            result = result
        )
    }

    private fun parseExecutionStepPayload(
        stepPayload: String,
        requireCompleteFields: Boolean
    ): ParsedExecutionStep? {
        val stepObject = runCatching {
            json.parseToJsonElement(stepPayload)
        }.getOrNull() as? JsonObject ?: return null

        return parseExecutionStepObject(
            stepObject = stepObject,
            requireCompleteFields = requireCompleteFields
        )
    }

    private fun extractExecutionStepsSection(executionReport: String): String? {
        val markerIndex = STEPS_EXECUTED_MARKER_REGEX.find(executionReport)
            ?.range
            ?.last
            ?.plus(1)
            ?: return null
        val finalResultStart = FINAL_RESULT_MARKER_REGEX.find(
            input = executionReport,
            startIndex = markerIndex
        )?.range?.first
        val nextStateStart = NEXT_STATE_MARKER_REGEX.find(
            input = executionReport,
            startIndex = markerIndex
        )?.range?.first
        val sectionEnd = listOfNotNull(finalResultStart, nextStateStart).minOrNull()
            ?: executionReport.length
        if (sectionEnd <= markerIndex) return null
        return executionReport.substring(markerIndex, sectionEnd)
    }

    private fun extractBalancedStepCandidates(stepsSection: String): List<String> {
        val startIndex = stepsSection.indexOf('[').let { index ->
            if (index >= 0) index + 1 else 0
        }

        val completedObjects = mutableListOf<String>()
        var depth = 0
        var inQuotes = false
        var escaped = false
        var objectStart = -1

        for (index in startIndex until stepsSection.length) {
            val current = stepsSection[index]

            if (escaped) {
                escaped = false
                continue
            }
            if (current == '\\') {
                escaped = true
                continue
            }
            if (current == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (inQuotes) continue

            when (current) {
                '{' -> {
                    if (depth == 0) {
                        objectStart = index
                    }
                    depth += 1
                }

                '}' -> {
                    if (depth <= 0) continue
                    depth -= 1
                    if (depth == 0 && objectStart >= 0) {
                        completedObjects += stepsSection.substring(objectStart, index + 1)
                        objectStart = -1
                    }
                }

                ']' -> {
                    if (depth == 0) break
                }
            }
        }

        return completedObjects
    }

    private fun extractAnchoredStepCandidates(stepsSection: String): List<String> {
        val stepAnchors = STEP_KEY_ANCHOR_REGEX.findAll(stepsSection)
            .map { it.range.first }
            .toList()
        if (stepAnchors.isEmpty()) return emptyList()

        val candidates = mutableListOf<String>()
        stepAnchors.forEachIndexed { index, anchor ->
            val previousAnchor = stepAnchors.getOrNull(index - 1) ?: -1
            val nextAnchor = stepAnchors.getOrNull(index + 1) ?: stepsSection.length
            val objectStart = stepsSection.lastIndexOf('{', startIndex = anchor)
                .takeIf { it >= 0 && it > previousAnchor }
                ?: anchor
            val closingBrace = findCandidateClosingBrace(
                stepsSection = stepsSection,
                anchor = anchor,
                nextAnchor = nextAnchor
            ) ?: return@forEachIndexed

            if (closingBrace < objectStart) return@forEachIndexed
            var candidate = stepsSection.substring(objectStart, closingBrace + 1)
            if (!candidate.trimStart().startsWith("{")) {
                candidate = "{${candidate.trimStart()}"
            }
            if (!candidate.trimEnd().endsWith("}")) {
                candidate = "${candidate.trimEnd()}}"
            }
            candidates += candidate
        }
        return candidates
    }

    private fun findCandidateClosingBrace(
        stepsSection: String,
        anchor: Int,
        nextAnchor: Int
    ): Int? {
        var inQuotes = false
        var escaped = false
        for (index in anchor until stepsSection.length) {
            val current = stepsSection[index]

            if (escaped) {
                escaped = false
                continue
            }
            if (current == '\\') {
                escaped = true
                continue
            }
            if (current == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (inQuotes) continue

            if (current == '}') return index
            if (index >= nextAnchor && current == '{') return null
        }
        return null
    }

    private fun sanitizeStepJsonCandidate(candidate: String): String {
        val normalized = candidate
            .trim()
            .trimStart(',')
            .trim()
        val withBraces = buildString {
            if (!normalized.startsWith("{")) append('{')
            append(normalized)
            if (!normalized.endsWith("}")) append('}')
        }
        return withBraces.replace(TRAILING_COMMA_BEFORE_BRACE_REGEX, "$1")
    }

    private fun extractExecutionFinalResultPartial(executionReport: String): String? {
        val finalResultMatch = FINAL_RESULT_MARKER_REGEX.find(executionReport) ?: return null
        val contentStart = finalResultMatch.range.last + 1
        val nextStateMatch = NEXT_STATE_MARKER_REGEX.find(
            input = executionReport,
            startIndex = contentStart
        )
        val contentEnd = nextStateMatch?.range?.first ?: executionReport.length
        if (contentEnd <= contentStart) return null

        return executionReport.substring(contentStart, contentEnd)
            .trim()
            .ifBlank { null }
    }

    private fun extractExecutionStepsPayload(executionReport: String): String? {
        val markerIndex = STEPS_EXECUTED_MARKER_REGEX.find(executionReport)
            ?.range
            ?.last
            ?.plus(1)
            ?: return null
        val arrayStart = executionReport.indexOf('[', startIndex = markerIndex)
        if (arrayStart < 0) return null
        return extractBalancedBlock(
            payload = executionReport,
            startIndex = arrayStart,
            openChar = '[',
            closeChar = ']'
        )
    }

    private fun extractExecutionNextState(executionReport: String): String? {
        return NEXT_STATE_VALUE_REGEX.find(executionReport)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
            ?.lowercase()
    }

    private fun extractBalancedBlock(
        payload: String,
        startIndex: Int,
        openChar: Char,
        closeChar: Char
    ): String? {
        if (startIndex !in payload.indices || payload[startIndex] != openChar) return null

        var depth = 0
        var inQuotes = false
        var escaped = false
        for (index in startIndex until payload.length) {
            val current = payload[index]

            if (escaped) {
                escaped = false
                continue
            }
            if (current == '\\') {
                escaped = true
                continue
            }
            if (current == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (inQuotes) continue

            if (current == openChar) depth += 1
            if (current == closeChar) {
                depth -= 1
                if (depth == 0) {
                    return payload.substring(startIndex, index + 1)
                }
            }
        }

        return null
    }

    private fun JsonObject.readString(key: String): String? {
        val element = this[key] ?: return null
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.trim()
            else -> element.toString().trim()
        }?.ifBlank { null }
    }

    private fun buildPlanningApprovalReply(
        plan: String,
        replanReason: String? = null
    ): String {
        return buildString {
            if (!replanReason.isNullOrBlank()) {
                append("Перепланировал с учетом замечаний:\n")
                append(replanReason.trim())
                append("\n\n")
            }
            append(plan.trim())
            append("\n\n")
            append("Подтвердите план: /ok\n")
            append("Попросить перепланировать: /retry <что исправить>")
        }
    }

    private fun buildDoneStageReply(finalResult: String): String {
        return buildString {
            append("Итоговое решение:\n")
            append(finalResult.trim())
            append("\n\n")
            append("Задача выполнена?\n")
            append("Ответьте /ok или /err <что не так>")
        }
    }

    private fun buildAutoReplanLimitReachedReply(issues: String, attempts: Int): String {
        return buildString {
            append("Достигнут лимит автоперепланирования ($attempts).\n")
            append("Последняя причина валидации:\n")
            append(issues.trim())
            append("\n\n")
            append("Если задача не принята, ответьте /err <что исправить>.\n")
            append("Если все ок, ответьте /ok.")
        }
    }

    private fun buildInvalidTaskCommandReply(stage: TaskStage): String {
        return when (stage) {
            TaskStage.CONVERSATION -> "Для запуска задачи используйте /task <описание>."
            TaskStage.PLANNING ->
                "Сейчас этап planning. Доступно: /ok или /retry <что исправить>."
            TaskStage.EXECUTION ->
                "Сейчас этап execution. Дождитесь завершения выполнения."
            TaskStage.VALIDATION ->
                "Сейчас этап validation. Дождитесь завершения проверки."
            TaskStage.DONE ->
                "Сейчас этап done. Ответьте /ok или /err <что не так>."
        }
    }

    private fun buildTaskStageInputHint(stage: TaskStage): String {
        return when (stage) {
            TaskStage.CONVERSATION ->
                "Обычный режим диалога."
            TaskStage.PLANNING ->
                "Сейчас этап planning. Подтвердите план через /ok или отправьте /retry <причина>."
            TaskStage.EXECUTION ->
                "Сейчас этап execution. Дождитесь завершения."
            TaskStage.VALIDATION ->
                "Сейчас этап validation. Дождитесь завершения."
            TaskStage.DONE ->
                "Сейчас этап done. Ответьте /ok или /err <что не так>."
        }
    }

    private fun parseControlCommand(content: String): ChatControlCommand? {
        parseMemoryCommand(content)?.let { return ChatControlCommand.Memory(it) }
        parseWorkCommand(content)?.let { return ChatControlCommand.Work(it) }
        parseTaskCommand(content)?.let { return ChatControlCommand.Task(it) }
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

    private fun parseTaskCommand(content: String): TaskCommand? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("/")) return null

        if (trimmed == TASK_COMMAND_OK) {
            return TaskCommand.Approve
        }
        if (trimmed.startsWith("$TASK_COMMAND_OK ")) {
            return TaskCommand.Invalid("Команда /ok не принимает аргументы")
        }
        if (trimmed == TASK_COMMAND_RETRY) {
            return TaskCommand.Invalid("Укажи причину после /retry")
        }
        if (trimmed.startsWith("$TASK_COMMAND_RETRY ")) {
            val reason = trimmed.removePrefix(TASK_COMMAND_RETRY).trim()
            return if (reason.isEmpty()) {
                TaskCommand.Invalid("Укажи причину после /retry")
            } else {
                TaskCommand.Retry(reason)
            }
        }
        if (trimmed == TASK_COMMAND_ERR) {
            return TaskCommand.Invalid("Укажи причину после /err")
        }
        if (trimmed.startsWith("$TASK_COMMAND_ERR ")) {
            val reason = trimmed.removePrefix(TASK_COMMAND_ERR).trim()
            return if (reason.isEmpty()) {
                TaskCommand.Invalid("Укажи причину после /err")
            } else {
                TaskCommand.Error(reason)
            }
        }
        if (trimmed == TASK_COMMAND_START) {
            return TaskCommand.Invalid("Укажи описание после /task")
        }
        if (trimmed.startsWith("$TASK_COMMAND_START ")) {
            val description = trimmed.removePrefix(TASK_COMMAND_START).trim()
            return if (description.isEmpty()) {
                TaskCommand.Invalid("Укажи описание после /task")
            } else {
                TaskCommand.Start(description)
            }
        }
        return null
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
        currentWorkTask: WorkTaskContext,
        taskDescription: String?,
        taskFinalResult: String?
    ): String? {
        val userProfilePrompt = buildUserProfileSystemPrompt(userProfilePayload)
        val memoryPrompt = buildMemorySystemPrompt(memoryInstructions)
        val workTaskPrompt = buildWorkTaskSystemPrompt(currentWorkTask)
        val taskSummaryPrompt = buildTaskSummarySystemPrompt(
            taskDescription = taskDescription,
            taskFinalResult = taskFinalResult
        )
        val parts = buildList {
            baseSystemPrompt?.let { add(it) }
            userProfilePrompt?.let { add(it) }
            add(memoryPrompt)
            add(workTaskPrompt)
            taskSummaryPrompt?.let { add(it) }
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

    private fun buildTaskSummarySystemPrompt(
        taskDescription: String?,
        taskFinalResult: String?
    ): String? {
        val normalizedTaskDescription = taskDescription?.trim()?.ifBlank { null }
        val normalizedTaskFinalResult = taskFinalResult?.trim()?.ifBlank { null }
        if (normalizedTaskDescription == null && normalizedTaskFinalResult == null) {
            return null
        }

        return buildString {
            append(TASK_SUMMARY_SECTION_TITLE)
            normalizedTaskDescription?.let {
                append("\nTask:")
                append('\n')
                append(it)
            }
            normalizedTaskFinalResult?.let {
                append("\nResult:")
                append('\n')
                append(it)
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
        return filter { it.includeInModelContext }
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

        const val TASK_COMMAND_START = "/task"
        const val TASK_COMMAND_OK = "/ok"
        const val TASK_COMMAND_RETRY = "/retry"
        const val TASK_COMMAND_ERR = "/err"
        const val MAX_TASK_AUTO_REPLAN_ATTEMPTS = 3
        const val DEFAULT_VALIDATION_REPLAN_REASON = "Validation reported unresolved issues."
        const val TASK_PAUSE_CANCELLATION_REASON = "TASK_PAUSED"
        const val TASK_PAUSED_HINT = "Процесс задачи на паузе. Нажмите «Продолжить»."
        const val INVARIANT_RULE_KEY_KOTLIN_ONLY = "kotlin_only"
        const val INVARIANT_RULE_KEY_FUNCTIONS_UNDER_10_LINES = "functions_under_10_lines"
        val REMOVED_INVARIANT_RULE_KEYS = listOf(
            "no_data_loss_without_confirmation",
            "no_secret_disclosure",
            "no_harmful_or_illegal_guidance"
        )
        val DEFAULT_INVARIANT_RULE_KEYS = setOf(
            INVARIANT_RULE_KEY_KOTLIN_ONLY,
            INVARIANT_RULE_KEY_FUNCTIONS_UNDER_10_LINES
        )
        val INVARIANT_RESULT_REGEX = Regex(
            pattern = "RESULT\\s*=\\s*\\[(true|false)]",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val INVARIANT_RESULT_LINE_REGEX = Regex(
            pattern = "^\\s*RESULT\\s*=\\s*\\[(true|false)]\\s*$",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        val INVARIANT_VIOLATED_KEYS_REGEX = Regex(
            pattern = "VIOLATED_INVARIANTS\\s*:\\s*([^\\n\\r]+)",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val INVARIANT_VIOLATED_LINE_REGEX = Regex(
            pattern = "^\\s*VIOLATED_INVARIANTS\\s*:\\s*([^\\n\\r]+)\\s*$",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        val CODE_FENCE_REGEX = Regex(
            pattern = "```[\\w+-]*\\s*[\\s\\S]*?```",
            options = setOf(RegexOption.MULTILINE)
        )
        val KOTLIN_CODE_LINE_REGEX = Regex(
            pattern = "(?m)^\\s*(package\\s+\\S+|import\\s+\\S+|(@\\w+\\s*)*(suspend\\s+)?(fun|class|object|interface|enum\\s+class|sealed\\s+class|data\\s+class)\\b|val\\s+\\w+\\s*[:=]|var\\s+\\w+\\s*[:=])"
        )
        val STEPS_EXECUTED_MARKER_REGEX = Regex(
            pattern = "STEPS_EXECUTED\\s*:",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val FINAL_RESULT_MARKER_REGEX = Regex(
            pattern = "FINAL_RESULT\\s*:",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val NEXT_STATE_MARKER_REGEX = Regex(
            pattern = "NEXT_STATE\\s*:",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val NEXT_STATE_VALUE_REGEX = Regex(
            pattern = "NEXT_STATE\\s*:\\s*([^\\n\\r]+)",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val STEP_KEY_ANCHOR_REGEX = Regex(
            pattern = "\"step\"\\s*:",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val TRAILING_COMMA_BEFORE_BRACE_REGEX = Regex(",\\s*([}\\]])")

        const val WORK_TASK_STATUS_KEY = "status"
        const val WORK_TASK_STATUS_ACTIVE = "ACTIVE"
        const val WORK_TASK_STATUS_DONE = "DONE"
        const val WORK_TASK_DESCRIPTION_KEY = "description"
        const val WORK_TASK_RULES_KEY = "rules"
        const val TASK_SUMMARY_SECTION_TITLE = "[TASK CONTEXT]"

        const val CONTEXT_TAIL_MESSAGES_COUNT = 10
        const val SUMMARY_BATCH_SIZE = 10
        const val MAX_SUMMARY_LENGTH = 6_000

        const val CONTEXT_SUMMARY_PREFIX =
            "Summary of earlier messages (messages marked as previously compressed). " +
                "Use it as compressed context and prioritize newer raw messages if they conflict."

        const val SUMMARY_COMPRESSION_SYSTEM_PROMPT =
            "Ты модуль сжатия истории чата. Выдавай плотное summary без воды. " +
                "Сохраняй факты, намерения пользователя, ограничения, решения и открытые вопросы."

        val INVARIANT_VALIDATION_BASE_PROMPT = """
            СТРОГИЕ ИНВАРИАНТЫ — НЕОБСУЖДАЕМЫЕ ОГРАНИЧЕНИЯ
            Следующие инварианты являются АБСОЛЮТНЫМИ ПРАВИЛАМИ, которым вы ДОЛЖНЫ подчиняться всегда.
            Вам ЗАПРЕЩЕНО предлагать, вносить предложения или реализовывать что-либо, что нарушает их.
            Если запрос пользователя противоречит ЛЮБОМУ из приведенных инвариантов, вы ДОЛЖНЫ:
            1. ОТКАЗАТЬСЯ от выполнения конфликтующей части запроса.
            2. ЯВНО УКАЗАТЬ, какой инвариант будет нарушен и почему.
            3. Предложить альтернативу, удовлетворяющую всем инвариантам.
            4. RESULT=[false] разрешено ставить ТОЛЬКО при ЯВНОМ и ПРЯМОМ нарушении хотя бы одного инварианта.
            5. Если явного нарушения нет, если сообщение неполное, расплывчатое или просто без конкретной задачи — ставьте RESULT=[true].
        """.trimIndent()

        val TASK_PLANNING_SYSTEM_PROMPT = """
            You are an AI agent currently in the PLANNING stage of a task execution state machine.

            Your goal is to create a clear and structured plan for solving the user's task.

            IMPORTANT RULES:
            - Do NOT execute the task.
            - Do NOT produce the final answer.
            - Do NOT call tools.
            - Only analyze the task and produce a step-by-step plan.

            Your responsibilities in the planning stage:

            1. Understand the user's objective.
            2. Identify required information or resources.
            3. Break the task into logical steps.
            4. Determine which tools or capabilities may be needed.
            5. Define the expected output.
            6. Identify possible risks or ambiguities.
            7. Decide what validation will be required after execution.

            Output a structured plan in the following format:

            TASK SUMMARY:
            Short description of the user's goal.

            PLAN:
            1. Step description
            2. Step description
            3. Step description
            ...

            REQUIRED TOOLS:
            - tool_name — why it may be needed
            - tool_name — why it may be needed

            EXPECTED OUTPUT:
            Description of what a successful result should look like.

            VALIDATION STRATEGY:
            How the result will be checked for correctness.

            CONSTRAINTS:
            Any assumptions, limitations, or missing information.

            Remember:
            You are ONLY planning. Execution will happen in the next stage.
        """.trimIndent()

        val TASK_EXECUTION_SYSTEM_PROMPT = """
            You are an AI agent currently in the EXECUTION stage of a task execution state machine.

            Your goal is to execute the FULL plan that was produced during the PLANNING stage.

            INPUTS:
            - User task
            - Execution plan generated in the PLANNING stage
            - User profile

            IMPORTANT RULES:

            - Execute the entire plan step-by-step.
            - Follow the order of steps exactly as specified.
            - Do not modify the plan.
            - Do not re-plan the task.
            - Do not skip steps.
            - If a step produces intermediate data, keep it and use it in subsequent steps.
            - Do not evaluate correctness of the final result — this will be done in the VALIDATION stage.

            Execution procedure:

            1. Read the plan.
            2. Execute each step sequentially.
            3. Record the result of every step.
            4. Produce the final result based on all executed steps.
            5. Return the execution report.

            Output format:

            EXECUTION_REPORT:

            STEPS_EXECUTED:
            [
              {
                "step": 1,
                "description": "...",
                "action": "...",
                "tool_call": "... or NONE",
                "result": "..."
              },
              {
                "step": 2,
                "description": "...",
                "action": "...",
                "tool_call": "... or NONE",
                "result": "..."
              }
            ]

            FINAL_RESULT:
            The result produced after completing all steps.

            NEXT_STATE:
            validation
        """.trimIndent()

        val TASK_VALIDATION_SYSTEM_PROMPT = """
            You are an AI agent currently in the VALIDATION stage of a task execution state machine.

            Your goal is to evaluate whether the execution result correctly solves the user's task.

            INPUTS:
            - User task
            - Execution plan
            - Execution report (steps executed and final result)
            - User profile

            IMPORTANT RULES:

            - Do NOT execute the task again.
            - Do NOT call tools.
            - Do NOT modify the execution results.
            - Only analyze and evaluate the result.

            Your responsibilities:

            1. Check whether the execution followed the plan.
            2. Verify that the final result satisfies the user's request.
            3. Detect logical errors, missing steps, or incorrect reasoning.
            4. Check whether the output format matches expectations.
            5. Identify hallucinations or unsupported claims.
            6. Determine whether the task is completed successfully.
            7. Ensure FINAL_RESULT explicitly contains final code (prefer a fenced code block). If code is missing, FINAL_DECISION must be REPLAN_REQUIRED.

            Validation procedure:

            1. Compare the user task with the final result.
            2. Review each executed step.
            3. Check logical consistency.
            4. Decide whether the result is acceptable.

            Output format:

            VALIDATION_REPORT:

            TASK_MATCH:
            Does the final result solve the user's task?
            YES | PARTIALLY | NO

            PLAN_EXECUTION_CHECK:
            Did the execution follow the plan correctly?
            YES | NO

            ISSUES_FOUND:
            List any detected problems. If none, write "NONE".

            RESULT_QUALITY:
            HIGH | MEDIUM | LOW

            FINAL_DECISION:
            SUCCESS | REPLAN_REQUIRED

            REASONING:
            Short explanation of the decision.

            NEXT_STATE:
            done | planning
        """.trimIndent()

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

private class TaskStagePausedException(
    val stage: TaskStage,
    val partialResponse: String
) : RuntimeException("Task stage ${stage.name} paused")

private data class InvariantCheckOutcome(
    val allowed: Boolean,
    val messageForUser: String
) {
    companion object {
        fun allowed(): InvariantCheckOutcome {
            return InvariantCheckOutcome(
                allowed = true,
                messageForUser = ""
            )
        }

        fun rejected(messageForUser: String): InvariantCheckOutcome {
            return InvariantCheckOutcome(
                allowed = false,
                messageForUser = messageForUser
            )
        }
    }
}

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

private sealed interface TaskCommand {
    data class Start(val description: String) : TaskCommand
    object Approve : TaskCommand
    data class Retry(val reason: String) : TaskCommand
    data class Error(val reason: String) : TaskCommand
    data class Invalid(val reply: String) : TaskCommand
}

private sealed interface ChatControlCommand {
    data class Memory(val command: MemoryCommand) : ChatControlCommand
    data class Work(val command: WorkCommand) : ChatControlCommand
    data class Task(val command: TaskCommand) : ChatControlCommand
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

private enum class ValidationDecision {
    SUCCESS,
    REPLAN_REQUIRED
}

private data class StageAssistantMessage(
    val stage: TaskStage,
    val content: String
)

private data class TaskCommandOutcome(
    val updatedSession: SessionEntity,
    val assistantMessages: List<StageAssistantMessage>
)

private data class ParsedExecutionReport(
    val steps: List<ParsedExecutionStep>,
    val finalResult: String,
    val nextState: String?
)

private data class PartialExecutionReport(
    val hasStepsSection: Boolean,
    val steps: List<ParsedExecutionStep>,
    val hasFinalResultSection: Boolean,
    val finalResult: String?,
    val hasNextStateSection: Boolean,
    val nextState: String?,
    val hasStructuredSections: Boolean
)

private data class ParsedExecutionStep(
    val step: Int?,
    val description: String,
    val action: String,
    val toolCall: String?,
    val result: String
)
