package com.pokkerolli.codeagent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "invariant_rules",
    indices = [Index(value = ["ruleKey"], unique = true)]
)
data class InvariantRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ruleKey: String,
    val title: String,
    val description: String,
    val position: Int,
    val isActive: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)
