package com.pokkerolli.codeagent.domain.model

enum class TaskStage {
    CONVERSATION,
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE;

    companion object {
        fun fromStored(value: String): TaskStage {
            return entries.firstOrNull { it.name == value } ?: CONVERSATION
        }
    }
}
