package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tzk1986.libretvaz.data.CmsParser
import com.tzk1986.libretvaz.data.VodSummary

/** 成人内容仅标记提示，不过滤结果 */
@Composable
fun AdultTag(adult: Boolean) {
    if (!adult) return
    Text(
        text = "成人",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

fun adultOf(item: VodSummary): Boolean = item.isAdult || CmsParser.isAdultType(item.typeName)
