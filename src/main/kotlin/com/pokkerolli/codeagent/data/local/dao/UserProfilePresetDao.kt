package com.pokkerolli.codeagent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pokkerolli.codeagent.data.local.entity.UserProfilePresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfilePresetDao {
    @Query("SELECT * FROM user_profile_presets ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<UserProfilePresetEntity>>

    @Query("SELECT * FROM user_profile_presets WHERE profileName = :profileName LIMIT 1")
    suspend fun getByProfileName(profileName: String): UserProfilePresetEntity?

    @Upsert
    suspend fun upsert(profile: UserProfilePresetEntity)
}
