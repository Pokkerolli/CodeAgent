package com.pokkerolli.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile_presets")
data class UserProfilePresetEntity(
    @PrimaryKey val profileName: String,
    val label: String,
    val payloadJson: String,
    val createdAt: Long,
    val updatedAt: Long
)
