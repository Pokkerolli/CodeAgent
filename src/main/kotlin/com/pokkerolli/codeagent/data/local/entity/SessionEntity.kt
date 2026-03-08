package com.pokkerolli.codeagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val systemPrompt: String? = null,
    val userProfileName: String? = null,
    val contextWindowMode: String = "FULL_HISTORY",
    val longTermMemoryJson: String? = null,
    val currentWorkTaskJson: String? = null,
    val stickyFactsJson: String? = null,
    val isStickyFactsExtractionInProgress: Boolean = false,
    val contextSummary: String? = null,
    val summarizedMessagesCount: Int = 0,
    val isContextSummarizationInProgress: Boolean = false,
    val taskStage: String = "CONVERSATION",
    val taskDescription: String? = null,
    val taskPlan: String? = null,
    val taskExecutionReport: String? = null,
    val taskValidationReport: String? = null,
    val taskFinalResult: String? = null,
    val taskReplanReason: String? = null,
    val taskAutoReplanCount: Int = 0,
    val isInvariantCheckEnabled: Boolean = false,
    val isTaskPaused: Boolean = false,
    val taskPausedPartialResponse: String? = null
)
