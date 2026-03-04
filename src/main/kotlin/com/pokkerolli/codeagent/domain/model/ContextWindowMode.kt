package com.pokkerolli.codeagent.domain.model

enum class ContextWindowMode {
    FULL_HISTORY,
    SUMMARY_PLUS_LAST_10,
    SLIDING_WINDOW_LAST_10,
    STICKY_FACTS_KEY_VALUE;

    companion object {
        fun fromStored(value: String): ContextWindowMode {
            return entries.firstOrNull { it.name == value } ?: FULL_HISTORY
        }
    }
}
