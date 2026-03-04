package com.pokkerolli.codeagent.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokkerolli.codeagent.domain.model.ChatMessage
import com.pokkerolli.codeagent.domain.model.ChatSession
import com.pokkerolli.codeagent.domain.model.ContextWindowMode
import com.pokkerolli.codeagent.domain.model.MessageRole
import com.pokkerolli.codeagent.domain.model.UserProfileBuilderMessage
import com.pokkerolli.codeagent.domain.usecase.CreateCustomUserProfilePresetFromDraftUseCase
import com.pokkerolli.codeagent.domain.usecase.CreateSessionBranchUseCase
import com.pokkerolli.codeagent.domain.usecase.CreateSessionUseCase
import com.pokkerolli.codeagent.domain.usecase.DeleteSessionUseCase
import com.pokkerolli.codeagent.domain.usecase.GetActiveSessionUseCase
import com.pokkerolli.codeagent.domain.usecase.ObserveMessagesUseCase
import com.pokkerolli.codeagent.domain.usecase.ObserveUserProfilePresetsUseCase
import com.pokkerolli.codeagent.domain.usecase.ObserveSessionsUseCase
import com.pokkerolli.codeagent.domain.usecase.RunContextSummarizationIfNeededUseCase
import com.pokkerolli.codeagent.domain.usecase.SendMessageUseCase
import com.pokkerolli.codeagent.domain.usecase.SetActiveSessionUseCase
import com.pokkerolli.codeagent.domain.usecase.SetSessionContextWindowModeUseCase
import com.pokkerolli.codeagent.domain.usecase.SetSessionSystemPromptUseCase
import com.pokkerolli.codeagent.domain.usecase.SetSessionUserProfileUseCase
import com.pokkerolli.codeagent.domain.usecase.StreamUserProfileBuilderReplyUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.UnknownHostException

class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val observeMessagesUseCase: ObserveMessagesUseCase,
    private val observeSessionsUseCase: ObserveSessionsUseCase,
    private val observeUserProfilePresetsUseCase: ObserveUserProfilePresetsUseCase,
    private val createSessionUseCase: CreateSessionUseCase,
    private val createSessionBranchUseCase: CreateSessionBranchUseCase,
    private val createCustomUserProfilePresetFromDraftUseCase: CreateCustomUserProfilePresetFromDraftUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val setActiveSessionUseCase: SetActiveSessionUseCase,
    private val setSessionSystemPromptUseCase: SetSessionSystemPromptUseCase,
    private val setSessionUserProfileUseCase: SetSessionUserProfileUseCase,
    private val setSessionContextWindowModeUseCase: SetSessionContextWindowModeUseCase,
    private val streamUserProfileBuilderReplyUseCase: StreamUserProfileBuilderReplyUseCase,
    private val runContextSummarizationIfNeededUseCase: RunContextSummarizationIfNeededUseCase,
    private val getActiveSessionUseCase: GetActiveSessionUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sessionsCache: List<ChatSession> = emptyList()
    private var activeSessionId: String? = null

    private var messageJob: Job? = null
    private var streamJob: Job? = null
    private var systemPromptJob: Job? = null
    private var customProfileBuilderJob: Job? = null
    private var creatingInitialSession = false

    init {
        observeSessions()
        observeUserProfilePresets()
        observeActiveSession()
    }

    fun onInputChanged(input: String) {
        _uiState.update { it.copy(input = input) }
    }

    fun onSendClicked() {
        val currentState = _uiState.value
        val sessionId = currentState.activeSessionId ?: return
        val userText = currentState.input.trim()
        if (userText.isBlank() || currentState.isSending) return

        _uiState.update {
            it.copy(
                input = "",
                isSending = true,
                streamingText = "",
                errorMessage = null
            )
        }

        streamJob?.cancel()
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            var failed = false
            var completedSuccessfully = false
            try {
                systemPromptJob?.join()
                sendMessageUseCase.execute(sessionId, userText).getOrThrow().collect { partial ->
                    withContext(Dispatchers.Main.immediate) {
                        _uiState.update { state ->
                            if (state.activeSessionId == sessionId) {
                                state.copy(streamingText = partial)
                            } else {
                                state
                            }
                        }
                    }
                }
                completedSuccessfully = true
            } catch (_: CancellationException) {
                // Stream was cancelled explicitly (e.g. session switch).
            } catch (throwable: Throwable) {
                failed = true
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { state ->
                        state.copy(
                            errorMessage = throwable.toUiMessage(),
                            streamingText = ""
                        )
                    }
                }
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { state ->
                        if (state.activeSessionId == sessionId) {
                            if (failed) {
                                state.copy(
                                    isSending = false,
                                    streamingText = ""
                                )
                            } else {
                                // If the final assistant message is already in Room state, drop the temporary stream bubble now.
                                val hasFinalAssistantMessage =
                                    state.streamingText.isNotEmpty() &&
                                        state.messages.lastOrNull()?.let { last ->
                                            last.role == MessageRole.ASSISTANT &&
                                                isEquivalentAssistantText(last.content, state.streamingText)
                                        } == true

                                state.copy(
                                    isSending = false,
                                    streamingText = if (hasFinalAssistantMessage) "" else state.streamingText
                                )
                            }
                        } else {
                            state.copy(
                                isSending = false,
                                streamingText = ""
                            )
                        }
                    }
                }

                if (completedSuccessfully) {
                    viewModelScope.launch {
                        try {
                            runContextSummarizationIfNeededUseCase.execute(sessionId).getOrThrow()
                        } catch (_: CancellationException) {
                            // Ignore explicit cancellation.
                        } catch (_: Throwable) {
                            // Compression failures must not break chat response flow.
                        }
                    }
                }
            }
        }
    }

    fun onSessionSelected(sessionId: String) {
        if (sessionId == activeSessionId) return

        cancelCurrentStream()
        viewModelScope.launch {
            setActiveSessionUseCase.execute(sessionId).getOrThrow()
        }
    }

    fun onCreateNewSession() {
        cancelCurrentStream()
        viewModelScope.launch {
            val session = createSessionUseCase.execute().getOrThrow()
            setActiveSessionUseCase.execute(session.id).getOrThrow()
        }
    }

    fun onCreateBranchClicked(sourceMessageId: Long) {
        val state = _uiState.value
        val sourceSessionId = state.activeSessionId ?: return
        if (state.isSending) return

        viewModelScope.launch {
            runCatching {
                createSessionBranchUseCase.execute(
                    sourceSessionId = sourceSessionId,
                    upToMessageIdInclusive = sourceMessageId
                ).getOrThrow()
            }.onSuccess { branchSession ->
                _uiState.update { ui ->
                    ui.copy(errorMessage = null)
                }
                setActiveSessionUseCase.execute(branchSession.id).getOrThrow()
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(errorMessage = throwable.toUiMessage())
                }
            }
        }
    }

    fun onDeleteSession(sessionId: String) {
        viewModelScope.launch {
            deleteSessionUseCase.execute(sessionId).getOrThrow()
        }
    }

    fun onSystemPromptSelected(systemPrompt: String) {
        val state = _uiState.value
        val sessionId = state.activeSessionId ?: return
        if (state.messages.isNotEmpty() || state.isSending) return

        val normalizedPrompt = systemPrompt.trim().ifBlank { "" }
        if ((state.activeSessionSystemPrompt ?: "") == normalizedPrompt) {
            return
        }

        _uiState.update { it.copy(activeSessionSystemPrompt = normalizedPrompt) }
        systemPromptJob?.cancel()
        systemPromptJob = viewModelScope.launch {
            setSessionSystemPromptUseCase.execute(sessionId, normalizedPrompt).getOrThrow()
        }
    }

    fun onUserProfileSelected(userProfileName: String?) {
        val state = _uiState.value
        val sessionId = state.activeSessionId ?: return
        if (state.messages.isNotEmpty() || state.isSending) {
            _uiState.update {
                it.copy(errorMessage = "Профиль можно менять только до первой реплики")
            }
            return
        }
        if (state.activeSessionUserProfileName == userProfileName) return

        _uiState.update { it.copy(activeSessionUserProfileName = userProfileName) }
        viewModelScope.launch {
            setSessionUserProfileUseCase.execute(sessionId, userProfileName).getOrThrow()
        }
    }

    fun onOpenCustomProfileBuilder() {
        val state = _uiState.value
        val sessionId = state.activeSessionId ?: return
        if (state.messages.isNotEmpty() || state.isSending) return
        if (state.isCustomProfileBuilderVisible) return

        _uiState.update {
            it.copy(
                isCustomProfileBuilderVisible = true,
                customProfileBuilderSourceSessionId = sessionId,
                customProfileBuilderInput = "",
                customProfileBuilderMessages = emptyList(),
                customProfileBuilderStreamingText = "",
                isCustomProfileBuilderSending = true,
                canApplyCustomProfile = false,
                customProfileBuilderErrorMessage = null
            )
        }
        runCustomProfileBuilderRequest(userMessage = null)
    }

    fun onCloseCustomProfileBuilder() {
        customProfileBuilderJob?.cancel()
        customProfileBuilderJob = null
        _uiState.update {
            it.copy(
                isCustomProfileBuilderVisible = false,
                customProfileBuilderSourceSessionId = null,
                customProfileBuilderInput = "",
                customProfileBuilderMessages = emptyList(),
                customProfileBuilderStreamingText = "",
                isCustomProfileBuilderSending = false,
                canApplyCustomProfile = false,
                customProfileBuilderErrorMessage = null
            )
        }
    }

    fun onCustomProfileBuilderInputChanged(value: String) {
        _uiState.update { it.copy(customProfileBuilderInput = value) }
    }

    fun onCustomProfileBuilderSendClicked() {
        val state = _uiState.value
        if (!state.isCustomProfileBuilderVisible || state.isCustomProfileBuilderSending) return

        val userText = state.customProfileBuilderInput.trim()
        if (userText.isEmpty()) return

        val updatedMessages = state.customProfileBuilderMessages + ProfileBuilderMessageUi(
            stableId = "custom_builder_user_${System.currentTimeMillis()}",
            role = MessageRole.USER,
            content = userText
        )

        _uiState.update {
            it.copy(
                customProfileBuilderMessages = updatedMessages,
                customProfileBuilderInput = "",
                customProfileBuilderStreamingText = "",
                isCustomProfileBuilderSending = true,
                customProfileBuilderErrorMessage = null
            )
        }

        runCustomProfileBuilderRequest(userMessage = userText)
    }

    fun onApplyCustomProfileClicked() {
        val state = _uiState.value
        if (!state.isCustomProfileBuilderVisible || state.isCustomProfileBuilderSending) return

        val assistantDraft = state.customProfileBuilderMessages
            .asReversed()
            .firstOrNull { it.role == MessageRole.ASSISTANT && it.content.containsUserProfileDraft() }
            ?.content
            ?: return

        customProfileBuilderJob?.cancel()
        customProfileBuilderJob = viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                createCustomUserProfilePresetFromDraftUseCase.execute(assistantDraft).getOrThrow()
            }.onSuccess { createdPreset ->
                val sourceSessionId = _uiState.value.customProfileBuilderSourceSessionId
                    ?: _uiState.value.activeSessionId
                if (sourceSessionId != null) {
                    setSessionUserProfileUseCase.execute(
                        sourceSessionId,
                        createdPreset.profileName
                    ).getOrThrow()
                }

                _uiState.update {
                    it.copy(
                        activeSessionUserProfileName = createdPreset.profileName,
                        isCustomProfileBuilderVisible = false,
                        customProfileBuilderSourceSessionId = null,
                        customProfileBuilderInput = "",
                        customProfileBuilderMessages = emptyList(),
                        customProfileBuilderStreamingText = "",
                        isCustomProfileBuilderSending = false,
                        canApplyCustomProfile = false,
                        customProfileBuilderErrorMessage = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(customProfileBuilderErrorMessage = throwable.toUiMessage())
                }
            }
        }
    }

    fun consumeCustomProfileBuilderError() {
        _uiState.update { it.copy(customProfileBuilderErrorMessage = null) }
    }

    fun onContextWindowModeSelected(mode: ContextWindowMode) {
        val state = _uiState.value
        val sessionId = state.activeSessionId ?: return
        if (state.messages.isNotEmpty() || state.isSending) return
        if (state.activeSessionContextWindowMode == mode) return

        _uiState.update { it.copy(activeSessionContextWindowMode = mode) }
        viewModelScope.launch {
            setSessionContextWindowModeUseCase.execute(sessionId, mode).getOrThrow()
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun observeUserProfilePresets() {
        viewModelScope.launch {
            observeUserProfilePresetsUseCase.execute().getOrThrow().collect { presets ->
                _uiState.update {
                    it.copy(
                        availableUserProfiles = presets.map { preset ->
                            UserProfilePresetUi(
                                profileName = preset.profileName,
                                label = preset.label,
                                isBuiltIn = preset.isBuiltIn
                            )
                        }
                    )
                }
            }
        }
    }

    private fun observeSessions() {
        viewModelScope.launch {
            observeSessionsUseCase.execute().getOrThrow().collect { sessions ->
                sessionsCache = sessions

                if (sessions.isEmpty() && !creatingInitialSession) {
                    creatingInitialSession = true
                    val created = createSessionUseCase.execute().getOrThrow()
                    setActiveSessionUseCase.execute(created.id).getOrThrow()
                    creatingInitialSession = false
                    return@collect
                }

                val currentActive = activeSessionId
                if (currentActive == null || sessions.none { it.id == currentActive }) {
                    sessions.firstOrNull()?.id?.let { firstSessionId ->
                        setActiveSessionUseCase.execute(firstSessionId).getOrThrow()
                    }
                }

                syncHeaderAndSessions()
            }
        }
    }

    private fun observeActiveSession() {
        viewModelScope.launch {
            getActiveSessionUseCase.execute().getOrThrow().collect { sessionId ->
                if (sessionId == null) return@collect
                if (sessionId == activeSessionId) return@collect

                activeSessionId = sessionId
                subscribeToMessages(sessionId)
                syncHeaderAndSessions()
            }
        }
    }

    private fun subscribeToMessages(sessionId: String) {
        messageJob?.cancel()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                streamingText = "",
                usage = ConversationUsageUi()
            )
        }

        messageJob = viewModelScope.launch {
            observeMessagesUseCase.execute(sessionId).getOrThrow().collect { messages ->
                val usageResult = messages.toUiModelsWithUsage()
                val mappedMessages = usageResult.messages
                _uiState.update { state ->
                    val lastMessage = mappedMessages.lastOrNull()
                    val shouldDropTemporaryStreamingBubble =
                        state.streamingText.isNotEmpty() &&
                            lastMessage?.role == MessageRole.ASSISTANT &&
                            isEquivalentAssistantText(lastMessage.content, state.streamingText)

                    state.copy(
                        messages = mappedMessages,
                        usage = usageResult.usage,
                        streamingText = if (shouldDropTemporaryStreamingBubble) {
                            ""
                        } else {
                            state.streamingText
                        }
                    )
                }
            }
        }
    }

    private fun syncHeaderAndSessions() {
        val selectedSession = sessionsCache.firstOrNull { it.id == activeSessionId }
        _uiState.update { state ->
            state.copy(
                sessions = sessionsCache.map {
                    ChatSessionUi(
                        id = it.id,
                        title = it.title,
                        updatedAt = it.updatedAt,
                        systemPrompt = it.systemPrompt,
                        userProfileName = it.userProfileName,
                        contextWindowMode = it.contextWindowMode,
                        isStickyFactsExtractionInProgress = it.isStickyFactsExtractionInProgress,
                        isContextSummarizationInProgress = it.isContextSummarizationInProgress
                    )
                },
                activeSessionId = selectedSession?.id,
                activeSessionTitle = selectedSession?.title ?: "New chat",
                activeSessionSystemPrompt = selectedSession?.systemPrompt,
                activeSessionUserProfileName = selectedSession?.userProfileName,
                activeSessionContextWindowMode =
                    selectedSession?.contextWindowMode ?: ContextWindowMode.FULL_HISTORY,
                isActiveSessionStickyFactsExtractionInProgress =
                    selectedSession?.isStickyFactsExtractionInProgress ?: false,
                isActiveSessionContextSummarizationInProgress =
                    selectedSession?.isContextSummarizationInProgress ?: false
            )
        }
    }

    private fun cancelCurrentStream() {
        streamJob?.cancel()
        streamJob = null
        _uiState.update {
            it.copy(
                isSending = false,
                streamingText = ""
            )
        }
    }

    private fun runCustomProfileBuilderRequest(userMessage: String?) {
        customProfileBuilderJob?.cancel()
        customProfileBuilderJob = viewModelScope.launch(Dispatchers.IO) {
            var finalAssistantText = ""
            try {
                val stateMessages = _uiState.value.customProfileBuilderMessages
                val messagesForHistory = if (
                    userMessage != null &&
                    stateMessages.lastOrNull()?.role == MessageRole.USER &&
                    stateMessages.lastOrNull()?.content == userMessage
                ) {
                    stateMessages.dropLast(1)
                } else {
                    stateMessages
                }

                val history = messagesForHistory
                    .map {
                        UserProfileBuilderMessage(
                            role = it.role,
                            content = it.content
                        )
                    }

                streamUserProfileBuilderReplyUseCase.execute(
                    history = history,
                    userMessage = userMessage
                ).getOrThrow().collect { partial ->
                    withContext(Dispatchers.Main.immediate) {
                        finalAssistantText = partial
                        _uiState.update { state ->
                            state.copy(customProfileBuilderStreamingText = partial)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update {
                        it.copy(
                            isCustomProfileBuilderSending = false,
                            customProfileBuilderStreamingText = "",
                            customProfileBuilderErrorMessage = throwable.toUiMessage()
                        )
                    }
                }
                return@launch
            }

            withContext(Dispatchers.Main.immediate) {
                _uiState.update { state ->
                    val normalizedAssistant = finalAssistantText.trim()
                    val newMessages = if (normalizedAssistant.isEmpty()) {
                        state.customProfileBuilderMessages
                    } else {
                        state.customProfileBuilderMessages + ProfileBuilderMessageUi(
                            stableId = "custom_builder_assistant_${System.currentTimeMillis()}",
                            role = MessageRole.ASSISTANT,
                            content = normalizedAssistant
                        )
                    }

                    state.copy(
                        customProfileBuilderMessages = newMessages,
                        customProfileBuilderStreamingText = "",
                        isCustomProfileBuilderSending = false,
                        canApplyCustomProfile = newMessages.any {
                            it.role == MessageRole.ASSISTANT && it.content.containsUserProfileDraft()
                        }
                    )
                }
            }
        }
    }

    private fun String.containsUserProfileDraft(): Boolean {
        val normalized = lowercase()
        return normalized.contains("\"profile_name\"") && contains('{') && contains('}')
    }

    private fun List<ChatMessage>.toUiModelsWithUsage(): UsageCalculation {
        if (isEmpty()) {
            return UsageCalculation(
                messages = emptyList(),
                usage = ConversationUsageUi()
            )
        }

        val pendingRequests = ArrayDeque<PendingRequestCost>()
        val totalDialogTokens = sumOf { message ->
            (message.totalTokens ?: 0).coerceAtLeast(0)
        }
        var cumulativeInputCostCacheHitUsd = 0.0
        var cumulativeInputCostCacheMissUsd = 0.0
        var cumulativeOutputCostUsd = 0.0

        val uiMessages = map { message ->
            when (message.role) {
                MessageRole.USER -> {
                    val input = message.toInputCostBreakdown()
                    input?.let { usage ->
                        cumulativeInputCostCacheHitUsd += usage.inputCostCacheHitUsd
                        cumulativeInputCostCacheMissUsd += usage.inputCostCacheMissUsd
                        pendingRequests.addLast(
                            PendingRequestCost(
                                inputTokens = usage.promptTokens,
                                inputCacheHitTokens = usage.promptCacheHitTokens,
                                inputCacheMissTokens = usage.promptCacheMissTokens,
                                inputCostCacheHitUsd = usage.inputCostCacheHitUsd,
                                inputCostCacheMissUsd = usage.inputCostCacheMissUsd,
                                inputTotalCostUsd = usage.inputTotalCostUsd
                            )
                        )
                    }
                    message.toUiModel(
                        userTokens = input?.promptTokens,
                        userCacheHitTokens = input?.promptCacheHitTokens,
                        userCacheMissTokens = input?.promptCacheMissTokens,
                        inputCostCacheHitUsd = input?.inputCostCacheHitUsd,
                        inputCostCacheMissUsd = input?.inputCostCacheMissUsd
                    )
                }

                MessageRole.ASSISTANT -> {
                    val outputTokens = message.completionTokens
                    val outputCostUsd = outputTokens?.let(TokenPricing::outputCostUsd)
                    if (outputCostUsd != null) {
                        cumulativeOutputCostUsd += outputCostUsd
                    }

                    val matchedRequest = if (pendingRequests.isEmpty()) {
                        null
                    } else {
                        pendingRequests.removeFirst()
                    }

                    val requestTotalTokens = message.totalTokens ?: if (
                        matchedRequest != null &&
                        outputTokens != null
                    ) {
                        matchedRequest.inputTokens + outputTokens
                    } else {
                        null
                    }

                    message.toUiModel(
                        userTokens = matchedRequest?.inputTokens,
                        userCacheHitTokens = matchedRequest?.inputCacheHitTokens,
                        userCacheMissTokens = matchedRequest?.inputCacheMissTokens,
                        inputCostCacheHitUsd = matchedRequest?.inputCostCacheHitUsd,
                        inputCostCacheMissUsd = matchedRequest?.inputCostCacheMissUsd,
                        assistantTokens = outputTokens,
                        requestTotalTokens = requestTotalTokens,
                        outputCostUsd = outputCostUsd,
                        requestTotalCostUsd = if (matchedRequest != null && outputCostUsd != null) {
                            matchedRequest.inputTotalCostUsd + outputCostUsd
                        } else {
                            null
                        }
                    )
                }
            }
        }

        val cumulativeTotalCostUsd = cumulativeInputCostCacheHitUsd + cumulativeInputCostCacheMissUsd + cumulativeOutputCostUsd

        return UsageCalculation(
            messages = uiMessages,
            usage = ConversationUsageUi(
                contextLength = totalDialogTokens,
                cumulativeTotalCostUsd = cumulativeTotalCostUsd
            )
        )
    }

    private fun ChatMessage.toUiModel(
        userTokens: Int? = null,
        userCacheHitTokens: Int? = null,
        userCacheMissTokens: Int? = null,
        inputCostCacheHitUsd: Double? = null,
        inputCostCacheMissUsd: Double? = null,
        assistantTokens: Int? = null,
        requestTotalTokens: Int? = null,
        outputCostUsd: Double? = null,
        requestTotalCostUsd: Double? = null
    ): ChatMessageUi {
        return ChatMessageUi(
            stableId = id.toString(),
            sourceMessageId = id,
            role = role,
            content = content,
            timestamp = timestamp,
            isStreaming = false,
            userTokens = userTokens,
            userCacheHitTokens = userCacheHitTokens,
            userCacheMissTokens = userCacheMissTokens,
            inputCostCacheHitUsd = inputCostCacheHitUsd,
            inputCostCacheMissUsd = inputCostCacheMissUsd,
            assistantTokens = assistantTokens,
            requestTotalTokens = requestTotalTokens,
            outputCostUsd = outputCostUsd,
            requestTotalCostUsd = requestTotalCostUsd
        )
    }

    private fun ChatMessage.toInputCostBreakdown(): InputCostBreakdown? {
        val rawPromptTokens = promptTokens ?: return null
        if (rawPromptTokens <= 0) return null

        val normalizedHitTokens = (promptCacheHitTokens ?: 0).coerceAtLeast(0)
        val normalizedMissTokens = (promptCacheMissTokens ?: (rawPromptTokens - normalizedHitTokens))
            .coerceAtLeast(0)
        val normalizedPromptTokens = maxOf(rawPromptTokens, normalizedHitTokens + normalizedMissTokens)

        val inputCostCacheHitUsd = TokenPricing.inputCostCacheHitUsd(normalizedHitTokens)
        val inputCostCacheMissUsd = TokenPricing.inputCostCacheMissUsd(normalizedMissTokens)

        return InputCostBreakdown(
            promptTokens = normalizedPromptTokens,
            promptCacheHitTokens = normalizedHitTokens,
            promptCacheMissTokens = normalizedMissTokens,
            inputCostCacheHitUsd = inputCostCacheHitUsd,
            inputCostCacheMissUsd = inputCostCacheMissUsd,
            inputTotalCostUsd = inputCostCacheHitUsd + inputCostCacheMissUsd
        )
    }

    private fun Throwable.toUiMessage(): String {
        return when (this) {
            is UnknownHostException -> "No internet connection."
            is IOException -> "Network error. Check your connection and try again."
            else -> message ?: "Request failed"
        }
    }

    private fun isEquivalentAssistantText(saved: String, streaming: String): Boolean {
        return saved.normalizeAssistantText() == streaming.normalizeAssistantText()
    }

    private fun String.normalizeAssistantText(): String {
        return replace("\r\n", "\n").trimEnd()
    }

    private data class PendingRequestCost(
        val inputTokens: Int,
        val inputCacheHitTokens: Int,
        val inputCacheMissTokens: Int,
        val inputCostCacheHitUsd: Double,
        val inputCostCacheMissUsd: Double,
        val inputTotalCostUsd: Double
    )

    private data class InputCostBreakdown(
        val promptTokens: Int,
        val promptCacheHitTokens: Int,
        val promptCacheMissTokens: Int,
        val inputCostCacheHitUsd: Double,
        val inputCostCacheMissUsd: Double,
        val inputTotalCostUsd: Double
    )

    private data class UsageCalculation(
        val messages: List<ChatMessageUi>,
        val usage: ConversationUsageUi
    )
}
