package com.pokkerolli.codeagent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reminder_deliveries",
    indices = [
        Index(value = ["sessionId"])
    ]
)
data class ReminderDeliveryEntity(
    @PrimaryKey val reminderId: String,
    val sessionId: String,
    val chatMessageId: Long? = null,
    val deliveredAt: Long
)
