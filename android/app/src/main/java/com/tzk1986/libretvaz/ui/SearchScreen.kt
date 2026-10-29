package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSummary
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    store: SourceStore,
    onBack: () -> Unit,
    onPick: (VodSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = remember { CmsRepository() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(Pair(0, 0)) }
    var results by remember { mutableStateOf<List<VodSummary>?>(null) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var hint by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BackHandler { onBack() }

    Column(modifier = modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("返回") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("输入片名搜索") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            Button(
                enabled = !searching && query.isNotBlank(),
                onClick = {
                    val sources = store.vodSources()
                    if (sources.isEmpty()) {
                        hint = "尚未添加点播源，请先到「源管理」添加或导入"
                        return@Button
                    }
                    hint = null
                    searching = true
                    results = null
                    errors = emptyList()
                    progress = 0 to sources.size
                    scope.launch {
                        val outcome = repo.search(query, sources) { done, total -> progress = done to total }
                        results = outcome.list
                        errors = outcome.errors
                        searching = false
                    }
                }
            ) { Text("搜索") }
        }

        when {
            searching -> Text("正在并行搜索 ${progress.first}/${progress.second} 个源…")
            hint != null -> Text(hint.orEmpty(), color = MaterialTheme.colorScheme.error)
            results != null && results!!.isEmpty() -> Text("没有结果：可尝试更换关键词或增加数据源")
        }
        if (errors.isNotEmpty()) {
            Text("部分源失败：${errors.joinToString("、").take(120)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        results?.let { list ->
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(list) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(item.year, item.area, item.typeName, item.remarks).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            )
                        }
                        Text(item.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
