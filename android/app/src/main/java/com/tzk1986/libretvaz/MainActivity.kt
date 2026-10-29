package com.tzk1986.libretvaz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSummary
import com.tzk1986.libretvaz.ui.DetailScreen
import com.tzk1986.libretvaz.ui.HomeScreen
import com.tzk1986.libretvaz.ui.LiveScreen
import com.tzk1986.libretvaz.ui.PlayerScreen
import com.tzk1986.libretvaz.ui.SearchScreen
import com.tzk1986.libretvaz.ui.SettingsScreen
import com.tzk1986.libretvaz.ui.SourcesScreen
import com.tzk1986.libretvaz.ui.theme.LibreTVazTheme

sealed class Screen {
    data object Home : Screen()
    data object Live : Screen()
    data object Sources : Screen()
    data object Settings : Screen()
    data object Search : Screen()
    data class Detail(val item: VodSummary) : Screen()
    data class Player(val url: String, val backTo: Screen) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceStore = SourceStore(this)
        val settingsStore = SettingsStore(this)
        setContent {
            LibreTVazTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                    when (val current = screen) {
                        Screen.Home -> HomeScreen(
                            sourceStore = sourceStore,
                            onSearch = { screen = Screen.Search },
                            onLive = { screen = Screen.Live },
                            onSources = { screen = Screen.Sources },
                            onSettings = { screen = Screen.Settings },
                        )
                        Screen.Live -> LiveScreen(
                            store = sourceStore,
                            onBack = { screen = Screen.Home },
                            onPlay = { url -> screen = Screen.Player(url, Screen.Live) },
                        )
                        Screen.Sources -> SourcesScreen(store = sourceStore, onBack = { screen = Screen.Home })
                        Screen.Settings -> SettingsScreen(store = settingsStore, onBack = { screen = Screen.Home })
                        Screen.Search -> SearchScreen(
                            store = sourceStore,
                            onBack = { screen = Screen.Home },
                            onPick = { screen = Screen.Detail(it) },
                        )
                        is Screen.Detail -> DetailScreen(
                            item = current.item,
                            onBack = { screen = Screen.Search },
                            onPlay = { url -> screen = Screen.Player(url, current) },
                        )
                        is Screen.Player -> PlayerScreen(
                            url = current.url,
                            onBack = { screen = current.backTo },
                        )
                    }
                }
            }
        }
    }
}
