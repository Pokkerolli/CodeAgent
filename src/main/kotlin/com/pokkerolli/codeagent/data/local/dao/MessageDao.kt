package com.pokkerolli.codeagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pokkerolli.codeagent.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessagesOnce(sessionId: String): List<MessageEntity>

    @Query("UPDATE chat_messages SET compressionState = :state WHERE id IN (:messageIds)")
    suspend fun updateCompressionStateForIds(messageIds: List<Long>, state: String)

    @Query(
        """
        SELECT COUNT(*)
        FROM chat_messages
        WHERE sessionId = :sessionId AND compressionState = :state
        """
    )
    suspend fun countByCompressionState(sessionId: String, state: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query(
        """
        UPDATE chat_messages
        SET promptTokens = :promptTokens,
            promptCacheHitTokens = :promptCacheHitTokens,
            promptCacheMissTokens = :promptCacheMissTokens
        WHERE id = :messageId
        """
    )
    suspend fun updateUserMessageUsage(
        messageId: Long,
        promptTokens: Int?,
        promptCacheHitTokens: Int?,
        promptCacheMissTokens: Int?
    )
}
