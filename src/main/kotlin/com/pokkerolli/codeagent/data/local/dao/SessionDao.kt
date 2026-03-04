package com.pokkerolli.codeagent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pokkerolli.codeagent.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Upsert
    suspend fun insertSession(session: SessionEntity)

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateTitleAndTimestamp(sessionId: String, title: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAt: Long)

    @Query(
        "UPDATE chat_sessions SET systemPrompt = :systemPrompt, updatedAt = :updatedAt " +
            "WHERE id = :sessionId"
    )
    suspend fun updateSystemPrompt(sessionId: String, systemPrompt: String?, updatedAt: Long)

    @Query(
        "UPDATE chat_sessions SET userProfileName = :userProfileName, updatedAt = :updatedAt " +
            "WHERE id = :sessionId"
    )
    suspend fun updateUserProfileName(sessionId: String, userProfileName: String?, updatedAt: Long)

    @Query(
        "UPDATE chat_sessions SET longTermMemoryJson = :longTermMemoryJson, updatedAt = :updatedAt " +
            "WHERE id = :sessionId"
    )
    suspend fun updateLongTermMemory(
        sessionId: String,
        longTermMemoryJson: String?,
        updatedAt: Long
    )

    @Query(
        "UPDATE chat_sessions SET currentWorkTaskJson = :currentWorkTaskJson, updatedAt = :updatedAt " +
            "WHERE id = :sessionId"
    )
    suspend fun updateCurrentWorkTask(
        sessionId: String,
        currentWorkTaskJson: String?,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE chat_sessions
        SET contextWindowMode = :contextWindowMode,
            contextSummary = :contextSummary,
            summarizedMessagesCount = :summarizedMessagesCount,
            isStickyFactsExtractionInProgress = 0,
            isContextSummarizationInProgress = 0,
            updatedAt = :updatedAt
        WHERE id = :sessionId
        """
    )
    suspend fun updateContextWindowMode(
        sessionId: String,
        contextWindowMode: String,
        contextSummary: String?,
        summarizedMessagesCount: Int,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE chat_sessions
        SET contextSummary = :contextSummary,
            summarizedMessagesCount = :summarizedMessagesCount
        WHERE id = :sessionId
        """
    )
    suspend fun updateContextSummary(
        sessionId: String,
        contextSummary: String?,
        summarizedMessagesCount: Int
    )

    @Query(
        """
        UPDATE chat_sessions
        SET stickyFactsJson = :stickyFactsJson
        WHERE id = :sessionId
        """
    )
    suspend fun updateStickyFacts(sessionId: String, stickyFactsJson: String?)

    @Query(
        """
        UPDATE chat_sessions
        SET isStickyFactsExtractionInProgress = :inProgress
        WHERE id = :sessionId
        """
    )
    suspend fun updateStickyFactsExtractionInProgress(sessionId: String, inProgress: Boolean)

    @Query(
        """
        UPDATE chat_sessions
        SET isContextSummarizationInProgress = :inProgress
        WHERE id = :sessionId
        """
    )
    suspend fun updateContextSummarizationInProgress(sessionId: String, inProgress: Boolean)
}
