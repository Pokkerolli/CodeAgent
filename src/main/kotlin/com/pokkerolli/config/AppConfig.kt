package com.pokkerolli.config

import java.io.File
import java.util.Properties

object AppConfig {
    private const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1/"

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
}
