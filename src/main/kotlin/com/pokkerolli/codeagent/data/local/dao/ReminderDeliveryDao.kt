package com.pokkerolli.codeagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pokkerolli.codeagent.data.local.entity.ReminderDeliveryEntity

@Dao
interface ReminderDeliveryDao {
    @Query("SELECT EXISTS(SELECT 1 FROM reminder_deliveries WHERE reminderId = :reminderId)")
    suspend fun hasDelivery(reminderId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDelivery(delivery: ReminderDeliveryEntity): Long
}
