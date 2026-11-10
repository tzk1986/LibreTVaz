package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.ui.theme.TvColors

/**
 * TVBox 复刻（用户 2026-11-09）全屏左右半区竖向手势纯逻辑。
 * 左半＝亮度，右半＝音量；dy 为纵向累计位移（手指上滑为负→升、下滑为正→降）。
 */
enum class EdgeZone { Brightness, Volume }

/** 落点横坐标属于哪一半区 */
fun edgeZone(x: Float, width: Float): EdgeZone = if (x >= width / 2f) EdgeZone.Volume else EdgeZone.Brightness

/** TVBox 双击三分区：左1/3=退、中=切播放、右1/3=进 */
enum class TapZone { Left, Center, Right }

fun tapZone(x: Float, width: Float): TapZone = when {
    x < width / 3f -> TapZone.Left
    x >= width * 2f / 3f -> TapZone.Right
    else -> TapZone.Center
}

/** 由起始等级与纵向位移推新等级（clamp 0..1）：dy/h 为半屏归一位移，上拖增大 */
fun dragToLevel(dy: Float, height: Float, startLevel: Float): Float =
    (startLevel - dy / height).coerceIn(0f, 1f)

/** TVBox 竖向滑条（5dp×90dp）：40%白槽底 + 主题红自底填充 level(0..1)。松手由调用方延迟隐藏 */
@Composable
fun TvSliderBar(level: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Box(modifier = modifier.width(5.dp).height(90.dp).clip(shape).background(TvColors.Track)) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth().fillMaxHeight(level.coerceIn(0f, 1f))
                .background(TvColors.Red, shape),
        )
    }
}
