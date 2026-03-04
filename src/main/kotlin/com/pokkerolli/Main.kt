package com.pokkerolli

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pokkerolli.core.navigation.AppNavHost
import com.pokkerolli.presentation.theme.DeepSeekChatTheme
import org.koin.core.context.startKoin

private val appKoin = startKoin {
    modules(
        com.pokkerolli.core.di.databaseModule,
        com.pokkerolli.core.di.networkModule,
        com.pokkerolli.core.di.repositoryModule,
        com.pokkerolli.core.di.useCaseModule,
        com.pokkerolli.core.di.viewModelModule
    )
}

fun main() = application {
    appKoin
    val windowState = rememberWindowState(
        size = DpSize(1440.dp, 900.dp),
        placement = WindowPlacement.Maximized
    )
    Window(
        state = windowState,
        onCloseRequest = ::exitApplication,
        title = "CodeAgent"
    ) {
        DeepSeekChatTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppNavHost()
            }
        }
    }
}
