package com.pokkerolli.codeagent.core.di

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.pokkerolli.codeagent.config.AppConfig
import com.pokkerolli.codeagent.data.local.dao.MessageDao
import com.pokkerolli.codeagent.data.local.dao.SessionDao
import com.pokkerolli.codeagent.data.local.dao.UserProfilePresetDao
import com.pokkerolli.codeagent.data.local.datastore.ActiveSessionPreferences
import com.pokkerolli.codeagent.data.local.db.AppDatabase
import com.pokkerolli.codeagent.data.datasource.ChatDataSourceImpl
import com.pokkerolli.codeagent.data.remote.api.DeepSeekApi
import com.pokkerolli.codeagent.data.remote.stream.SseStreamParser
import com.pokkerolli.codeagent.domain.datasource.ChatDataSource
import com.pokkerolli.codeagent.domain.repository.ChatRepository
import com.pokkerolli.codeagent.domain.usecase.CreateCustomUserProfilePresetFromDraftUseCase
import com.pokkerolli.codeagent.domain.usecase.CreateSessionBranchUseCase
import com.pokkerolli.codeagent.domain.usecase.CreateSessionUseCase
import com.pokkerolli.codeagent.domain.usecase.DeleteSessionUseCase
import com.pokkerolli.codeagent.domain.usecase.GetActiveSessionUseCase
import com.pokkerolli.codeagent.domain.usecase.ObserveMessagesUseCase
import com.pokkerolli.codeagent.domain.usecase.ObserveSessionsUseCase
import com.pokkerolli.codeagent.domain.usecase.ObserveUserProfilePresetsUseCase
import com.pokkerolli.codeagent.domain.usecase.RequestTaskPauseUseCase
import com.pokkerolli.codeagent.domain.usecase.RunContextSummarizationIfNeededUseCase
import com.pokkerolli.codeagent.domain.usecase.ResumeTaskStreamingUseCase
import com.pokkerolli.codeagent.domain.usecase.SendMessageUseCase
import com.pokkerolli.codeagent.domain.usecase.SetActiveSessionUseCase
import com.pokkerolli.codeagent.domain.usecase.SetSessionContextWindowModeUseCase
import com.pokkerolli.codeagent.domain.usecase.SetSessionSystemPromptUseCase
import com.pokkerolli.codeagent.domain.usecase.SetSessionUserProfileUseCase
import com.pokkerolli.codeagent.domain.usecase.StreamUserProfileBuilderReplyUseCase
import com.pokkerolli.codeagent.presentation.chat.ChatViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import java.io.File

val databaseModule = module {
    single<AppDatabase> {
        val dataDir = File(System.getProperty("user.home"), ".codeagent")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }

        Room.databaseBuilder<AppDatabase>(
            name = File(dataDir, "deepseek_chat.db").absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    single<SessionDao> { get<AppDatabase>().sessionDao() }
    single<MessageDao> { get<AppDatabase>().messageDao() }
    single<UserProfilePresetDao> { get<AppDatabase>().userProfilePresetDao() }
    single { ActiveSessionPreferences() }
}

val networkModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    single<HttpClient> {
        val json: Json = get()
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
                requestTimeoutMillis = 180_000
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        println(message)
                    }
                }
                level = if (AppConfig.DEBUG) {
                    LogLevel.INFO
                } else {
                    LogLevel.NONE
                }
            }
            defaultRequest {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.CacheControl, "no-cache")
                header(HttpHeaders.AcceptEncoding, "identity")
                if (AppConfig.DEEPSEEK_API_KEY.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer ${AppConfig.DEEPSEEK_API_KEY}")
                }
            }
        }
    }

    single { DeepSeekApi(client = get(), baseUrl = AppConfig.DEEPSEEK_BASE_URL) }
    single { SseStreamParser(get()) }
}

val repositoryModule = module {
    single<ChatDataSource> {
        ChatDataSourceImpl(
            database = get(),
            sessionDao = get(),
            messageDao = get(),
            userProfilePresetDao = get(),
            deepSeekApi = get(),
            sseStreamParser = get(),
            activeSessionPreferences = get(),
            json = get()
        )
    }

    single {
        ChatRepository(get())
    }
}

val useCaseModule = module {
    factory { SendMessageUseCase(get()) }
    factory { ObserveMessagesUseCase(get()) }
    factory { ObserveSessionsUseCase(get()) }
    factory { ObserveUserProfilePresetsUseCase(get()) }
    factory { CreateSessionUseCase(get()) }
    factory { CreateSessionBranchUseCase(get()) }
    factory { CreateCustomUserProfilePresetFromDraftUseCase(get()) }
    factory { DeleteSessionUseCase(get()) }
    factory { SetActiveSessionUseCase(get()) }
    factory { SetSessionSystemPromptUseCase(get()) }
    factory { SetSessionUserProfileUseCase(get()) }
    factory { SetSessionContextWindowModeUseCase(get()) }
    factory { StreamUserProfileBuilderReplyUseCase(get()) }
    factory { RunContextSummarizationIfNeededUseCase(get()) }
    factory { GetActiveSessionUseCase(get()) }
    factory { RequestTaskPauseUseCase(get()) }
    factory { ResumeTaskStreamingUseCase(get()) }
}

val viewModelModule = module {
    single {
        ChatViewModel(
            sendMessageUseCase = get(),
            observeMessagesUseCase = get(),
            observeSessionsUseCase = get(),
            observeUserProfilePresetsUseCase = get(),
            createSessionUseCase = get(),
            createSessionBranchUseCase = get(),
            createCustomUserProfilePresetFromDraftUseCase = get(),
            deleteSessionUseCase = get(),
            setActiveSessionUseCase = get(),
            setSessionSystemPromptUseCase = get(),
            setSessionUserProfileUseCase = get(),
            setSessionContextWindowModeUseCase = get(),
            streamUserProfileBuilderReplyUseCase = get(),
            runContextSummarizationIfNeededUseCase = get(),
            getActiveSessionUseCase = get(),
            requestTaskPauseUseCase = get(),
            resumeTaskStreamingUseCase = get()
        )
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN systemPrompt TEXT")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE chat_messages ADD COLUMN promptTokens INTEGER")
        connection.execSql("ALTER TABLE chat_messages ADD COLUMN promptCacheHitTokens INTEGER")
        connection.execSql("ALTER TABLE chat_messages ADD COLUMN promptCacheMissTokens INTEGER")
        connection.execSql("ALTER TABLE chat_messages ADD COLUMN completionTokens INTEGER")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE chat_messages ADD COLUMN totalTokens INTEGER")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            "ALTER TABLE chat_sessions " +
                "ADD COLUMN contextCompressionEnabled INTEGER NOT NULL DEFAULT 0"
        )
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN contextSummary TEXT")
        connection.execSql(
            "ALTER TABLE chat_sessions " +
                "ADD COLUMN summarizedMessagesCount INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            "ALTER TABLE chat_messages " +
                "ADD COLUMN compressionState TEXT NOT NULL DEFAULT 'ACTIVE'"
        )
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            "ALTER TABLE chat_sessions " +
                "ADD COLUMN isContextSummarizationInProgress INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            "ALTER TABLE chat_sessions " +
                "ADD COLUMN contextWindowMode TEXT NOT NULL DEFAULT 'FULL_HISTORY'"
        )
        connection.execSql(
            "UPDATE chat_sessions " +
                "SET contextWindowMode = 'SUMMARY_PLUS_LAST_10' " +
                "WHERE contextCompressionEnabled = 1"
        )
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS chat_sessions_new (
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                systemPrompt TEXT,
                contextWindowMode TEXT NOT NULL DEFAULT 'FULL_HISTORY',
                contextSummary TEXT,
                summarizedMessagesCount INTEGER NOT NULL DEFAULT 0,
                isContextSummarizationInProgress INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )

        connection.execSql(
            """
            INSERT INTO chat_sessions_new (
                id,
                title,
                createdAt,
                updatedAt,
                systemPrompt,
                contextWindowMode,
                contextSummary,
                summarizedMessagesCount,
                isContextSummarizationInProgress
            )
            SELECT
                id,
                title,
                createdAt,
                updatedAt,
                systemPrompt,
                contextWindowMode,
                contextSummary,
                summarizedMessagesCount,
                isContextSummarizationInProgress
            FROM chat_sessions
            """.trimIndent()
        )

        connection.execSql("DROP TABLE chat_sessions")
        connection.execSql("ALTER TABLE chat_sessions_new RENAME TO chat_sessions")
    }
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN stickyFactsJson TEXT")
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            "ALTER TABLE chat_sessions " +
                "ADD COLUMN isStickyFactsExtractionInProgress INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN longTermMemoryJson TEXT")
    }
}

private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN currentWorkTaskJson TEXT")
    }
}

private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN userProfileName TEXT")
    }
}

private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS user_profile_presets (
                profileName TEXT NOT NULL,
                label TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(profileName)
            )
            """.trimIndent()
        )
    }
}

private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            "ALTER TABLE chat_sessions " +
                "ADD COLUMN taskStage TEXT NOT NULL DEFAULT 'CONVERSATION'"
        )
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN taskDescription TEXT")
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN taskPlan TEXT")
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN taskExecutionReport TEXT")
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN taskValidationReport TEXT")
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN taskFinalResult TEXT")
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN taskReplanReason TEXT")
        connection.execSql(
            "ALTER TABLE chat_sessions " +
                "ADD COLUMN taskAutoReplanCount INTEGER NOT NULL DEFAULT 0"
        )
        connection.execSql(
            "ALTER TABLE chat_messages " +
                "ADD COLUMN taskStage TEXT NOT NULL DEFAULT 'CONVERSATION'"
        )
        connection.execSql(
            "ALTER TABLE chat_messages " +
                "ADD COLUMN includeInModelContext INTEGER NOT NULL DEFAULT 1"
        )
    }
}

private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            "ALTER TABLE chat_sessions " +
                "ADD COLUMN isTaskPaused INTEGER NOT NULL DEFAULT 0"
        )
        connection.execSql("ALTER TABLE chat_sessions ADD COLUMN taskPausedPartialResponse TEXT")
    }
}

private fun SQLiteConnection.execSql(sql: String) {
    prepare(sql).use { statement ->
        statement.step()
    }
}
