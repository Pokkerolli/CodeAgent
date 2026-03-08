package com.pokkerolli.codeagent.domain.usecase

import com.pokkerolli.codeagent.domain.model.InvariantRule
import com.pokkerolli.codeagent.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class ObserveInvariantRulesUseCase(
    private val repository: ChatRepository
) {
    fun execute(): Result<Flow<List<InvariantRule>>> {
        return runCatching {
            repository.observeInvariantRules()
        }
    }
}
