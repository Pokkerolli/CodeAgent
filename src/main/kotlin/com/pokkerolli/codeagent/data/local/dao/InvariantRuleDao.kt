package com.pokkerolli.codeagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pokkerolli.codeagent.data.local.entity.InvariantRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InvariantRuleDao {
    @Query(
        """
        SELECT * FROM invariant_rules
        WHERE isActive = 1
        ORDER BY position ASC, id ASC
        """
    )
    fun observeActiveRules(): Flow<List<InvariantRuleEntity>>

    @Query(
        """
        SELECT * FROM invariant_rules
        WHERE isActive = 1
        ORDER BY position ASC, id ASC
        """
    )
    suspend fun getActiveRulesOnce(): List<InvariantRuleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRules(rules: List<InvariantRuleEntity>)

    @Query("DELETE FROM invariant_rules WHERE ruleKey IN (:ruleKeys)")
    suspend fun deleteRulesByKeys(ruleKeys: List<String>)
}
