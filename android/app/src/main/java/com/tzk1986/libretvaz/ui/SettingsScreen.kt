package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tzk1986.libretvaz.data.ApiStatusClient
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.data.StatusResult

@Composable
fun SettingsScreen(
    store: SettingsStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf(store.serverUrl) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<StatusResult?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("服务器设置", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("LibreTV 服务器地址，如 http://192.168.1.10:8080") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
            modifier = Modifier.fillMaxWidth(0.7f).focusRequester(focusRequester),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = {
                store.serverUrl = url
                testing = true
                result = null
            }) { Text("保存并测试连接") }
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        if (testing) {
            LaunchedEffect(Unit) {
                result = ApiStatusClient.fetchStatus(store.serverUrl)
                testing = false
            }
        }
        Spacer(Modifier.height(24.dp))
        when (val r = result) {
            is StatusResult.Success -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "连接成功 · 服务端版本 ${r.status.version}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    buildString {
                        append(if (r.status.passwordRequired) "已启用访问密码" else "未设置访问密码")
                        append(" · 预置点播源 ${r.status.defaultSourceCount}")
                        append(" · 预置直播源 ${r.status.defaultLiveSourceCount}")
                        append(" · 预置订阅 ${r.status.defaultSubscriptionCount}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is StatusResult.Failure -> Text(
                r.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
            null -> Unit
        }
    }
}
