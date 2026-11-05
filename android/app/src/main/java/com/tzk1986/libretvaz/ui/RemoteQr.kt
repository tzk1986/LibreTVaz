package com.tzk1986.libretvaz.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tzk1986.libretvaz.data.RemoteHub
import kotlinx.coroutines.delay

/** zxing-core 离线出码（web qrCanvas 同配色：深底浅点）；失败返回 null 由弹窗退化为纯链接文本 */
private fun qrBitmap(text: String, px: Int): Bitmap? = runCatching {
    val m = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, px, px,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    val dark = 0xFF14171A.toInt()
    val light = 0xFFE8EAED.toInt()
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m.get(x, y)) light else dark)
    bmp
}.getOrNull()

/**
 * 扫码远程输入弹窗（web openQrModal 等价）：mode=search 手机页推词、电视收到即搜当前池；
 * mode=sub 推订阅地址、源管理页按 qrAuto 直导/遥控确认。base 未就绪（未配服务器/网络断）时给指引不放码。
 */
@Composable
fun RemoteQrDialog(mode: String, onDismiss: () -> Unit) {
    fun curUrl() = if (RemoteHub.base.isEmpty()) "" else RemoteHub.qrUrl(mode)
    var url by remember { mutableStateOf(curUrl()) }
    // 循环在途/刚配服务器时 base 尚未就绪：开窗后 1s×20 轮询补画
    LaunchedEffect(Unit) {
        var tries = 0
        while (url.isEmpty() && tries < 20) {
            delay(1000); url = curUrl(); tries++
        }
    }
    val bmp = remember(url) { if (url.isEmpty()) null else qrBitmap(url, 640) }
    AlertDialog(
    modifier = Modifier.width(tmDialogW()),
    properties = tmDialogProps,
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TmBtn(onClick = onDismiss) { Text("关闭") } },
        title = { Text(if (mode == "sub") "扫码导入订阅" else "扫码远程输入") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (url.isEmpty()) {
                    Text("未配置服务器地址：请到「设置 → 服务器地址」填写调试台地址（如 http://192.168.1.10:8787）后重开本窗",
                        color = MaterialTheme.colorScheme.error)
                } else {
                    bmp?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "远程输入二维码",
                            modifier = Modifier.size(260.dp),
                        )
                    } ?: Text("二维码生成失败，请在手机浏览器打开下方链接", color = MaterialTheme.colorScheme.error)
                    Text(url, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (mode == "sub")
                        Text("电视收到后按「扫码自动导入」开关决定直接导入或弹窗确认", style = MaterialTheme.typography.bodySmall)
                }
                if (url.isNotEmpty()) Text(
                    if (mode == "sub") "手机相机扫码 → 粘贴/输入订阅地址发送"
                    else "手机相机扫码 → 输入关键词发送，电视收到后自动搜索",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        },
    )
}
