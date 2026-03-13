package com.pokkerolli.codeagent.config

import java.io.File
import java.net.URI
import java.util.Properties

object AppConfig {
    private const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1/"
    private const val DEFAULT_MCP_SERVERS =
        "VKUSVILL|https://mcp001.vkusvill.ru/mcp;" +
            "WEATHER|http://127.0.0.1:3001/mcp;" +
            "REMINDER|http://127.0.0.1:3002/mcp"
    private const val MCP_STATUS_MESSAGES_KEY = "MCP_STATUS_MESSAGES"
    private const val MCP_SERVERS_KEY = "MCP_SERVERS"
    private const val MCP_DEBUG_MODE_KEY = "MCP_DEBUG_MODE"

    private val localProperties by lazy {
        Properties().apply {
            val file = File(System.getProperty("user.dir"), "local.properties")
            if (file.exists()) {
                file.inputStream().use(::load)
            }
        }
    }

    val DEEPSEEK_API_KEY: String by lazy {
        (System.getenv("DEEPSEEK_API_KEY")
            ?: localProperties.getProperty("DEEPSEEK_API_KEY")
            ?: "").trim()
    }

    val DEEPSEEK_BASE_URL: String by lazy {
        val raw = (System.getenv("DEEPSEEK_BASE_URL")
            ?: localProperties.getProperty("DEEPSEEK_BASE_URL")
            ?: DEFAULT_BASE_URL).trim()
        if (raw.endsWith('/')) raw else "$raw/"
    }

    val DEBUG: Boolean by lazy {
        val envFlag = System.getenv("CODEAGENT_DEBUG")?.trim()
        when {
            envFlag.equals("1") || envFlag.equals("true", ignoreCase = true) -> true
            envFlag.equals("0") || envFlag.equals("false", ignoreCase = true) -> false
            else -> java.lang.management.ManagementFactory
                .getRuntimeMXBean()
                .inputArguments
                .any { it.contains("jdwp") }
        }
    }

    val MCP_SERVERS: List<McpServerConfig> by lazy {
        val raw = (System.getenv(MCP_SERVERS_KEY)
            ?: localProperties.getProperty(MCP_SERVERS_KEY)
            ?: DEFAULT_MCP_SERVERS).trim()
        parseMcpServers(raw).ifEmpty { parseMcpServers(DEFAULT_MCP_SERVERS) }
    }

    val MCP_STATUS_MESSAGES_ENABLED: Boolean by lazy {
        val raw = (
            System.getenv(MCP_STATUS_MESSAGES_KEY)
                ?: localProperties.getProperty(MCP_STATUS_MESSAGES_KEY)
                ?: System.getenv(MCP_DEBUG_MODE_KEY)
                ?: localProperties.getProperty(MCP_DEBUG_MODE_KEY)
            )
            ?.trim()
            ?.lowercase()
            .orEmpty()
        raw == "1" || raw == "true" || raw == "yes" || raw == "on"
    }

    private fun parseMcpServers(raw: String): List<McpServerConfig> {
        return raw
            .split(';', '\n')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val (nameRaw, urlRaw) = parseServerEntry(entry) ?: return@mapNotNull null
                if (urlRaw.isBlank()) return@mapNotNull null
                val normalizedUrl = urlRaw.trim().trimEnd('/')
                if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
                    return@mapNotNull null
                }
                val normalizedName = nameRaw
                    .trim()
                    .ifBlank { deriveServerName(normalizedUrl) }
                McpServerConfig(
                    name = normalizedName,
                    url = normalizedUrl
                )
            }
            .distinctBy { "${it.name.lowercase()}|${it.url}" }
            .toList()
    }

    private fun parseServerEntry(entry: String): Pair<String, String>? {
        val parts = entry.split('|', limit = 2)
        return when (parts.size) {
            1 -> "" to parts[0]
            2 -> parts[0] to parts[1]
            else -> null
        }
    }

    private fun deriveServerName(url: String): String {
        val host = runCatching { URI(url).host?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        return host?.uppercase() ?: "MCP"
    }
}
