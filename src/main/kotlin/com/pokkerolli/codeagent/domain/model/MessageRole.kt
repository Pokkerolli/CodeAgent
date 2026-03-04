package com.pokkerolli.codeagent.domain.model

enum class MessageRole(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromStored(value: String): MessageRole {
            return entries.firstOrNull { it.name == value } ?: ASSISTANT
        }
    }
}
