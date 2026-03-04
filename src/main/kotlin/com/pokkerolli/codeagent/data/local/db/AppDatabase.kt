package com.pokkerolli.codeagent.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pokkerolli.codeagent.data.local.dao.MessageDao
import com.pokkerolli.codeagent.data.local.dao.SessionDao
import com.pokkerolli.codeagent.data.local.dao.UserProfilePresetDao
import com.pokkerolli.codeagent.data.local.entity.MessageEntity
import com.pokkerolli.codeagent.data.local.entity.SessionEntity
import com.pokkerolli.codeagent.data.local.entity.UserProfilePresetEntity

@Database(
    entities = [SessionEntity::class, MessageEntity::class, UserProfilePresetEntity::class],
    version = 15,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun userProfilePresetDao(): UserProfilePresetDao
}
