package com.pokkerolli.codeagent.presentation.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pokkerolli.codeagent.domain.model.ContextWindowMode
import com.pokkerolli.codeagent.domain.model.MessageRole
import com.pokkerolli.codeagent.domain.model.TaskStage
import com.pokkerolli.codeagent.presentation.theme.DeepSeekChatTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject

@Composable
fun ChatRoute(
    viewModel: ChatViewModel = koinInject()
) {
    val state by viewModel.uiState.collectAsState()

    ChatScreen(
        state = state,
        onInputChanged = viewModel::onInputChanged,
        onSendClick = viewModel::onSendClicked,
        onSessionSelected = viewModel::onSessionSelected,
        onSessionDeleted = viewModel::onDeleteSession,
        onCreateSession = viewModel::onCreateNewSession,
        onCreateBranchClick = viewModel::onCreateBranchClicked,
        onTaskPauseResumeClick = viewModel::onTaskPauseResumeClicked,
        onSystemPromptSelected = viewModel::onSystemPromptSelected,
        onUserProfileSelected = viewModel::onUserProfileSelected,
        onOpenCustomProfileBuilder = viewModel::onOpenCustomProfileBuilder,
        onCustomProfileBuilderInputChanged = viewModel::onCustomProfileBuilderInputChanged,
        onCustomProfileBuilderSendClick = viewModel::onCustomProfileBuilderSendClicked,
        onApplyCustomProfileClick = viewModel::onApplyCustomProfileClicked,
        onCloseCustomProfileBuilder = viewModel::onCloseCustomProfileBuilder,
        onContextWindowModeSelected = viewModel::onContextWindowModeSelected,
        onConsumeError = viewModel::consumeError
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    state: ChatUiState,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onSessionDeleted: (String) -> Unit,
    onCreateSession: () -> Unit,
    onCreateBranchClick: (Long) -> Unit,
    onTaskPauseResumeClick: () -> Unit,
    onSystemPromptSelected: (String) -> Unit,
    onUserProfileSelected: (String?) -> Unit,
    onOpenCustomProfileBuilder: () -> Unit,
    onCustomProfileBuilderInputChanged: (String) -> Unit,
    onCustomProfileBuilderSendClick: () -> Unit,
    onApplyCustomProfileClick: () -> Unit,
    onCloseCustomProfileBuilder: () -> Unit,
    onContextWindowModeSelected: (ContextWindowMode) -> Unit,
    onConsumeError: () -> Unit
) {
    var didInitialScrollForSession by remember(state.activeSessionId) { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var shouldAutoScroll by remember { mutableStateOf(true) }

    val displayMessages = remember(
        state.messages,
        state.streamingText,
        state.activeSessionId,
        state.isSending,
        state.isActiveSessionStickyFactsExtractionInProgress,
        state.activeSessionTaskStage
    ) {
        val shouldShowStreamingBubble = state.streamingText.isNotEmpty() ||
            (
                state.isSending &&
                    state.activeSessionTaskStage == TaskStage.CONVERSATION &&
                    !state.isActiveSessionStickyFactsExtractionInProgress
                )
        if (!shouldShowStreamingBubble) {
            state.messages
        } else {
            state.messages + ChatMessageUi(
                stableId = "streaming_${state.activeSessionId}",
                role = MessageRole.ASSISTANT,
                taskStage = state.activeSessionTaskStage,
                content = state.streamingText,
                timestamp = System.currentTimeMillis(),
                isStreaming = state.isSending &&
                    !state.isActiveSessionStickyFactsExtractionInProgress
            )
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) {
                true
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= totalItems - 3
            }
        }
            .distinctUntilChanged()
            .collect { shouldFollow ->
                shouldAutoScroll = shouldFollow
            }
    }

    LaunchedEffect(state.activeSessionId, state.messages.size) {
        if (!didInitialScrollForSession && state.messages.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToBottom()
            withFrameNanos { }
            listState.scrollToBottom()
            didInitialScrollForSession = true
            shouldAutoScroll = true
        }
    }

    LaunchedEffect(
        displayMessages.lastOrNull()?.stableId,
        displayMessages.lastOrNull()?.content,
        state.streamingText,
        state.isSending
    ) {
        if (displayMessages.isEmpty()) return@LaunchedEffect
        if (shouldAutoScroll) {
            withFrameNanos { }
            listState.scrollToBottom()
        }
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onConsumeError()
    }

    Row(modifier = Modifier.fillMaxSize()) {
        SessionsSidebar(
            sessions = state.sessions,
            activeSessionId = state.activeSessionId,
            onSessionSelected = onSessionSelected,
            onDeleteSession = onSessionDeleted,
            onCreateNewSession = onCreateSession,
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp)
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = state.activeSessionTitle,
                            maxLines = 1
                        )
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                MessageInputBar(
                    value = state.input,
                    isSending = state.isSending,
                    showSystemPromptPresets = state.messages.isEmpty() && !state.isSending,
                    selectedUserProfileName = state.activeSessionUserProfileName,
                    userProfilePresets = state.availableUserProfiles,
                    selectedContextWindowMode = state.activeSessionContextWindowMode,
                    isStickyFactsExtractionInProgress = state.isActiveSessionStickyFactsExtractionInProgress,
                    isContextSummarizationInProgress = state.isActiveSessionContextSummarizationInProgress,
                    onValueChanged = onInputChanged,
                    onUserProfileSelected = onUserProfileSelected,
                    onOpenCustomProfileBuilder = onOpenCustomProfileBuilder,
                    onContextWindowModeSelected = onContextWindowModeSelected,
                    onSendClick = onSendClick
                )
            }
        ) { contentPadding ->
            val bottomPadding = contentPadding.calculateBottomPadding()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = contentPadding.calculateTopPadding(),
                        bottom = bottomPadding
                    ),
            ) {
                ConversationUsagePanel(
                    usage = state.usage,
                    isAssistantResponding = state.isSending,
                    currentTaskStage = state.activeSessionTaskStage,
                    isTaskPaused = state.activeSessionTaskPaused,
                    onTaskPauseResumeClick = onTaskPauseResumeClick
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = displayMessages,
                        key = { it.stableId }
                    ) { message ->
                        MessageBubble(
                            message = message,
                            onCreateBranchClick = onCreateBranchClick
                        )
                    }
                }
            }
        }
    }

    if (state.isCustomProfileBuilderVisible) {
        CustomProfileBuilderDialog(
            messages = state.customProfileBuilderMessages,
            streamingText = state.customProfileBuilderStreamingText,
            input = state.customProfileBuilderInput,
            isSending = state.isCustomProfileBuilderSending,
            canApplyProfile = state.canApplyCustomProfile,
            errorMessage = state.customProfileBuilderErrorMessage,
            onDismissRequest = onCloseCustomProfileBuilder,
            onInputChanged = onCustomProfileBuilderInputChanged,
            onSendClick = onCustomProfileBuilderSendClick,
            onApplyProfileClick = onApplyCustomProfileClick
        )
    }
}

@Composable
private fun ConversationUsagePanel(
    usage: ConversationUsageUi,
    isAssistantResponding: Boolean,
    currentTaskStage: TaskStage,
    isTaskPaused: Boolean,
    onTaskPauseResumeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total tokens: ${TokenPricing.formatTokens(usage.contextLength)} (${TokenPricing.formatUsd(usage.cumulativeTotalCostUsd)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isAssistantResponding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp
                    )
                }
                Spacer(modifier = Modifier.weight(1f))

                if (currentTaskStage != TaskStage.CONVERSATION && (isAssistantResponding || isTaskPaused)) {
                    TextButton(onClick = onTaskPauseResumeClick) {
                        Icon(
                            imageVector = if (isAssistantResponding) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (isAssistantResponding) "Пауза" else "Продолжить")
                    }
                }
            }

            if (currentTaskStage != TaskStage.CONVERSATION) {
                TaskStageProgress(currentTaskStage = currentTaskStage)
            }
        }
    }
}

@Composable
private fun TaskStageProgress(
    currentTaskStage: TaskStage,
    modifier: Modifier = Modifier
) {
    val sequence = listOf(
        TaskStage.PLANNING,
        TaskStage.EXECUTION,
        TaskStage.VALIDATION,
        TaskStage.DONE
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sequence.forEachIndexed { index, stage ->
            val isActive = stage == currentTaskStage
            Text(
                text = stage.name.lowercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (index != sequence.lastIndex) {
                Text(
                    text = "→",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessageUi,
    onCreateBranchClick: (Long) -> Unit
) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor = if (message.taskStage == TaskStage.CONVERSATION) {
        if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    } else {
        when (message.taskStage) {
            TaskStage.PLANNING -> MaterialTheme.colorScheme.tertiaryContainer
            TaskStage.EXECUTION -> MaterialTheme.colorScheme.secondaryContainer
            TaskStage.VALIDATION -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
            TaskStage.DONE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
            TaskStage.CONVERSATION -> if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.82f

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Column(
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                Surface(
                    color = bubbleColor,
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = if (isUser) 1.dp else 0.dp
                ) {
                    val cursorVisible = rememberBlinkingCursorVisible(enabled = message.isStreaming)
                    val renderedText = if (message.isStreaming && cursorVisible) {
                        "${message.content}▍"
                    } else {
                        message.content
                    }

                    Text(
                        text = renderedText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }

                buildMessageUsageText(message)?.let { usageText ->
                    Text(
                        text = usageText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (
                    message.role == MessageRole.ASSISTANT &&
                    message.taskStage == TaskStage.CONVERSATION &&
                    !message.isStreaming
                ) {
                    TextButton(
                        onClick = {
                            message.sourceMessageId?.let(onCreateBranchClick)
                        },
                        modifier = Modifier.padding(bottom = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(90f)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = "Создать ветку в новом чате")
                    }
                }
            }
        }
    }
}

private fun buildMessageUsageText(message: ChatMessageUi): String? {
    return when (message.role) {
        MessageRole.USER -> null

        MessageRole.ASSISTANT -> {
            val outputTokens = message.assistantTokens ?: return null
            val outputCostUsd = message.outputCostUsd ?: return null
            val totalCostUsd = message.requestTotalCostUsd
            val requestTotalTokens = message.requestTotalTokens
            val inputTokens = message.userTokens
            val inputCacheHitTokens = message.userCacheHitTokens
            val inputCacheMissTokens = message.userCacheMissTokens
            val inputCostCacheHitUsd = message.inputCostCacheHitUsd
            val inputCostCacheMissUsd = message.inputCostCacheMissUsd

            val lines = mutableListOf<String>()

            if (
                inputTokens != null &&
                inputCacheHitTokens != null &&
                inputCacheMissTokens != null &&
                inputCostCacheHitUsd != null &&
                inputCostCacheMissUsd != null
            ) {
                val sumCost = TokenPricing.formatUsd(inputCostCacheHitUsd + inputCostCacheMissUsd)
                lines += "Prompt tokens: ${TokenPricing.formatTokens(inputTokens)} ($sumCost) Hit ${TokenPricing.formatTokens(inputCacheHitTokens)}, miss ${TokenPricing.formatTokens(inputCacheMissTokens)}"
            }

            lines += "Completion tokens: ${TokenPricing.formatTokens(outputTokens)} (${TokenPricing.formatUsd(outputCostUsd)})"

            if (requestTotalTokens != null && totalCostUsd != null) {
                lines += "Total tokens: ${TokenPricing.formatTokens(requestTotalTokens)} out of ${TokenPricing.formatTokens(TokenPricing.MAX_CONTEXT_LENGTH)} (${TokenPricing.formatUsd(totalCostUsd)})"
            }

            lines.joinToString(separator = "\n")
        }
    }
}

@Composable
private fun MessageInputBar(
    value: String,
    isSending: Boolean,
    showSystemPromptPresets: Boolean,
    selectedUserProfileName: String?,
    userProfilePresets: List<UserProfilePresetUi>,
    selectedContextWindowMode: ContextWindowMode,
    isStickyFactsExtractionInProgress: Boolean,
    isContextSummarizationInProgress: Boolean,
    onValueChanged: (String) -> Unit,
    onUserProfileSelected: (String?) -> Unit,
    onOpenCustomProfileBuilder: () -> Unit,
    onContextWindowModeSelected: (ContextWindowMode) -> Unit,
    onSendClick: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showSystemPromptPresets) {
                Text(
                    text = "Профиль пользователя",
                    modifier = Modifier.padding(top = 5.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    item(key = "no_profile") {
                        FilterChip(
                            selected = selectedUserProfileName == null,
                            onClick = { onUserProfileSelected(null) },
                            label = {
                                Text(text = "Без профиля")
                            }
                        )
                    }

                    items(
                        items = userProfilePresets,
                        key = { it.profileName }
                    ) { preset ->
                        val isSelected = selectedUserProfileName == preset.profileName
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val selected = if (isSelected) null else preset.profileName
                                onUserProfileSelected(selected)
                            },
                            label = {
                                Text(text = preset.label)
                            }
                        )
                    }

                    item(key = "custom_profile_builder") {
                        FilterChip(
                            selected = false,
                            onClick = onOpenCustomProfileBuilder,
                            label = {
                                Text(text = "Custom")
                            }
                        )
                    }
                }

                Text(
                    text = "Сжатие контекста",
                    modifier = Modifier.padding(top = 5.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(
                        items = CONTEXT_WINDOW_MODE_OPTIONS,
                        key = { it.mode.name }
                    ) { option ->
                        val isSelected = selectedContextWindowMode == option.mode
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val nextMode = if (isSelected) {
                                    ContextWindowMode.FULL_HISTORY
                                } else {
                                    option.mode
                                }
                                onContextWindowModeSelected(nextMode)
                            },
                            label = {
                                Text(text = option.label)
                            }
                        )
                    }
                }
            }

            if (isContextSummarizationInProgress) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Суммаризация контекста...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isStickyFactsExtractionInProgress) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Собираем факты...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChanged,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 6,
                    shape = MaterialTheme.shapes.extraLarge,
                    placeholder = {
                        Text(text = "Send a message...")
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilledIconButton(
                    onClick = onSendClick,
                    enabled = value.isNotBlank() && !isSending
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomProfileBuilderDialog(
    messages: List<ProfileBuilderMessageUi>,
    streamingText: String,
    input: String,
    isSending: Boolean,
    canApplyProfile: Boolean,
    errorMessage: String?,
    onDismissRequest: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onApplyProfileClick: () -> Unit
) {
    val listState = rememberLazyListState()

    val displayMessages = remember(messages, streamingText, isSending) {
        val shouldShowStreamingBubble = streamingText.isNotEmpty() || isSending
        if (!shouldShowStreamingBubble) {
            messages
        } else {
            messages + ProfileBuilderMessageUi(
                stableId = "custom_profile_streaming",
                role = MessageRole.ASSISTANT,
                content = streamingText
            )
        }
    }

    LaunchedEffect(
        displayMessages.lastOrNull()?.stableId,
        displayMessages.lastOrNull()?.content
    ) {
        if (displayMessages.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        listState.scrollToBottom()
    }

    Dialog(
        onDismissRequest = onDismissRequest
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(text = "Custom профиль") },
                        navigationIcon = {
                            IconButton(onClick = onDismissRequest) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close"
                                )
                            }
                        },
                        actions = {
                            TextButton(
                                enabled = canApplyProfile && !isSending,
                                onClick = onApplyProfileClick
                            ) {
                                Text(text = "Применить")
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            TextField(
                                value = input,
                                onValueChange = onInputChanged,
                                modifier = Modifier.weight(1f),
                                minLines = 1,
                                maxLines = 6,
                                shape = MaterialTheme.shapes.extraLarge,
                                placeholder = {
                                    Text(text = "Ответьте ассистенту...")
                                },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences
                                ),
                                colors = androidx.compose.material3.TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    errorIndicatorColor = Color.Transparent
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            FilledIconButton(
                                onClick = onSendClick,
                                enabled = input.isNotBlank() && !isSending
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send"
                                )
                            }
                        }
                    }
                }
            ) { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = contentPadding.calculateTopPadding(),
                            bottom = contentPadding.calculateBottomPadding()
                        )
                ) {
                    errorMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 8.dp),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = displayMessages,
                            key = { it.stableId }
                        ) { message ->
                            CustomProfileBuilderMessageBubble(
                                message = message,
                                isStreaming = isSending && message.stableId == "custom_profile_streaming"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomProfileBuilderMessageBubble(
    message: ProfileBuilderMessageUi,
    isStreaming: Boolean
) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.86f

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                color = bubbleColor,
                shape = MaterialTheme.shapes.large,
                tonalElevation = if (isUser) 1.dp else 0.dp
            ) {
                val cursorVisible = rememberBlinkingCursorVisible(enabled = isStreaming)
                val renderedText = if (isStreaming && cursorVisible) {
                    "${message.content}▍"
                } else {
                    message.content
                }

                Text(
                    text = renderedText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun rememberBlinkingCursorVisible(enabled: Boolean): Boolean {
    if (!enabled) return false

    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    return alpha > 0.5f
}

private suspend fun LazyListState.scrollToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return

    var lastItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex }
    if (lastItemInfo == null) {
        scrollToItem(lastIndex)
        withFrameNanos { }
        lastItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex }
    }

    lastItemInfo?.let { item ->
        val viewportBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
        val overflow = (item.offset + item.size) - viewportBottom
        if (overflow > 0) {
            scrollBy(overflow.toFloat())
        }
    }
}

private data class SystemPromptPreset(
    val label: String,
    val prompt: String
)

private data class ContextWindowModeOption(
    val mode: ContextWindowMode,
    val label: String
)

private val CONTEXT_WINDOW_MODE_OPTIONS = listOf(
    ContextWindowModeOption(
        mode = ContextWindowMode.SUMMARY_PLUS_LAST_10,
        label = "Summary + последние 10"
    ),
    ContextWindowModeOption(
        mode = ContextWindowMode.SLIDING_WINDOW_LAST_10,
        label = "Sliding Window (10 последних)"
    ),
    ContextWindowModeOption(
        mode = ContextWindowMode.STICKY_FACTS_KEY_VALUE,
        label = "Sticky Facts/Key-Value Memory"
    )
)

@Composable
private fun ChatScreenPreview() {
    DeepSeekChatTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChatScreen(
                state = ChatUiState(
                    sessions = listOf(
                        ChatSessionUi(
                            id = "preview-session",
                            title = "Preview chat",
                            updatedAt = System.currentTimeMillis(),
                            systemPrompt = null,
                            userProfileName = "mentor_expert",
                            contextWindowMode = ContextWindowMode.SUMMARY_PLUS_LAST_10,
                            isStickyFactsExtractionInProgress = true,
                            isContextSummarizationInProgress = true
                        )
                    ),
                    activeSessionId = "preview-session",
                    activeSessionTitle = "Preview chat",
                    activeSessionSystemPrompt = null,
                    activeSessionContextWindowMode = ContextWindowMode.SUMMARY_PLUS_LAST_10,
                    activeSessionUserProfileName = "mentor_expert",
                    availableUserProfiles = listOf(
                        UserProfilePresetUi(
                            profileName = "casual_friendly",
                            label = "Бытовой чат",
                            isBuiltIn = true
                        ),
                        UserProfilePresetUi(
                            profileName = "mentor_expert",
                            label = "Технический / экспертный диалог",
                            isBuiltIn = true
                        )
                    ),
                    isActiveSessionStickyFactsExtractionInProgress = true,
                    isActiveSessionContextSummarizationInProgress = true,
                    messages = listOf(
                        ChatMessageUi(
                            stableId = "preview-user-1",
                            role = MessageRole.USER,
                            content = "Сделай краткий план запуска MVP за 2 недели.",
                            timestamp = System.currentTimeMillis() - 90_000,
                            userTokens = 854,
                            userCacheHitTokens = 832,
                            userCacheMissTokens = 22,
                            inputCostCacheHitUsd = TokenPricing.inputCostCacheHitUsd(832),
                            inputCostCacheMissUsd = TokenPricing.inputCostCacheMissUsd(22)
                        ),
                        ChatMessageUi(
                            stableId = "preview-assistant-1",
                            role = MessageRole.ASSISTANT,
                            content = "1) Определить scope и ключевые фичи.\n2) Поднять backend/API.\n3) Собрать UI и базовую аналитику.",
                            timestamp = System.currentTimeMillis() - 60_000,
                            userTokens = 854,
                            userCacheHitTokens = 832,
                            userCacheMissTokens = 22,
                            inputCostCacheHitUsd = TokenPricing.inputCostCacheHitUsd(832),
                            inputCostCacheMissUsd = TokenPricing.inputCostCacheMissUsd(22),
                            assistantTokens = 48,
                            requestTotalTokens = 902,
                            outputCostUsd = TokenPricing.outputCostUsd(48),
                            requestTotalCostUsd = TokenPricing.inputCostCacheHitUsd(832) +
                                    TokenPricing.inputCostCacheMissUsd(22) +
                                    TokenPricing.outputCostUsd(48)
                        )
                    ),
                    input = "Добавь риски и метрики.",
                    isSending = true,
                    streamingText = "4) Уточнить риски релиза и задать KPI...",
                    usage = ConversationUsageUi(
                        contextLength = 902,
                        cumulativeTotalCostUsd = TokenPricing.inputCostCacheHitUsd(832) +
                                TokenPricing.inputCostCacheMissUsd(22) +
                                TokenPricing.outputCostUsd(48)
                    )
                ),
                onInputChanged = {},
                onSendClick = {},
                onSessionSelected = {},
                onSessionDeleted = {},
                onCreateSession = {},
                onCreateBranchClick = {},
                onTaskPauseResumeClick = {},
                onSystemPromptSelected = {},
                onUserProfileSelected = {},
                onOpenCustomProfileBuilder = {},
                onCustomProfileBuilderInputChanged = {},
                onCustomProfileBuilderSendClick = {},
                onApplyCustomProfileClick = {},
                onCloseCustomProfileBuilder = {},
                onContextWindowModeSelected = {},
                onConsumeError = {}
            )
        }
    }
}

@Composable
private fun ChatScreenWithCustomProfileBuilderPreview() {
    DeepSeekChatTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChatScreen(
                state = ChatUiState(
                    sessions = listOf(
                        ChatSessionUi(
                            id = "preview-session",
                            title = "Preview chat",
                            updatedAt = System.currentTimeMillis(),
                            systemPrompt = null,
                            userProfileName = "mentor_expert",
                            contextWindowMode = ContextWindowMode.SUMMARY_PLUS_LAST_10,
                            isStickyFactsExtractionInProgress = false,
                            isContextSummarizationInProgress = false
                        )
                    ),
                    activeSessionId = "preview-session",
                    activeSessionTitle = "Preview chat",
                    activeSessionSystemPrompt = null,
                    activeSessionContextWindowMode = ContextWindowMode.SUMMARY_PLUS_LAST_10,
                    activeSessionUserProfileName = "mentor_expert",
                    availableUserProfiles = listOf(
                        UserProfilePresetUi(
                            profileName = "casual_friendly",
                            label = "Бытовой чат",
                            isBuiltIn = true
                        ),
                        UserProfilePresetUi(
                            profileName = "mentor_expert",
                            label = "Технический / экспертный диалог",
                            isBuiltIn = true
                        )
                    ),
                    messages = listOf(
                        ChatMessageUi(
                            stableId = "preview-user-1",
                            role = MessageRole.USER,
                            content = "Сделай краткий план запуска MVP за 2 недели.",
                            timestamp = System.currentTimeMillis() - 90_000
                        )
                    ),
                    input = "Сделай чуть короче.",
                    isCustomProfileBuilderVisible = true,
                    customProfileBuilderInput = "Хочу дружелюбно, но по делу.",
                    customProfileBuilderMessages = listOf(
                        ProfileBuilderMessageUi(
                            stableId = "custom_builder_assistant_1",
                            role = MessageRole.ASSISTANT,
                            content = "Привет! Давай попробуем собрать профиль пользователя под тебя.\n1) Какой тон тебе комфортен?\n2) Насколько подробные ответы хочешь?\n3) Нужны ли эмодзи?"
                        ),
                        ProfileBuilderMessageUi(
                            stableId = "custom_builder_user_1",
                            role = MessageRole.USER,
                            content = "Тон спокойный, ответы средние по длине, эмодзи редко."
                        )
                    ),
                    customProfileBuilderStreamingText = "Супер, фиксирую это. Еще уточню: нужен ли формат с шагами?",
                    isCustomProfileBuilderSending = true,
                    canApplyCustomProfile = true
                ),
                onInputChanged = {},
                onSendClick = {},
                onSessionSelected = {},
                onSessionDeleted = {},
                onCreateSession = {},
                onCreateBranchClick = {},
                onTaskPauseResumeClick = {},
                onSystemPromptSelected = {},
                onUserProfileSelected = {},
                onOpenCustomProfileBuilder = {},
                onCustomProfileBuilderInputChanged = {},
                onCustomProfileBuilderSendClick = {},
                onApplyCustomProfileClick = {},
                onCloseCustomProfileBuilder = {},
                onContextWindowModeSelected = {},
                onConsumeError = {}
            )
        }
    }
}
