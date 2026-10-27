package com.tzk1986.libretvaz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.ui.HomeScreen
import com.tzk1986.libretvaz.ui.SettingsScreen
import com.tzk1986.libretvaz.ui.theme.LibreTVazTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        setContent {
            LibreTVazTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(store.serverUrl.isBlank()) }
                    if (showSettings) {
                        SettingsScreen(store = store, onBack = { showSettings = false })
                    } else {
                        HomeScreen(store = store, onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}
