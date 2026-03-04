package com.pokkerolli.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["createdAt"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val promptTokens: Int? = null,
    val promptCacheHitTokens: Int? = null,
    val promptCacheMissTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val compressionState: String = MessageCompressionState.ACTIVE.name
)
