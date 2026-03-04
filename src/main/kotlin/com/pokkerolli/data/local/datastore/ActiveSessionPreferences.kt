package com.pokkerolli.data.local.datastore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class ActiveSessionPreferences {
    private val file by lazy {
        val dir = File(System.getProperty("user.home"), ".codeagent")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        File(dir, "active_session_id.txt")
    }

    private val activeSessionIdFlow = MutableStateFlow(readActiveSessionId())

    fun observeActiveSessionId(): Flow<String?> {
        return activeSessionIdFlow.asStateFlow()
    }

    suspend fun setActiveSessionId(sessionId: String) {
        withContext(Dispatchers.IO) {
            file.writeText(sessionId)
        }
        activeSessionIdFlow.value = sessionId
    }

    private fun readActiveSessionId(): String? {
        if (!file.exists()) return null
        val value = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        return value.ifEmpty { null }
    }
}
