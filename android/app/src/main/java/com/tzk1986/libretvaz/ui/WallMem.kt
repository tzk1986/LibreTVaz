package com.tzk1986.libretvaz.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.tzk1986.libretvaz.data.VodType

/**
 * 墙类页签跨页记忆（用户 2026-11-04 修复「返回不按路径」）：
 * 导航为单状态条件渲染，进详情/播放再返回会重建 Browse/Zone，remember 态复位到默认「最新」。
 * 页签选择提升为进程级，返回即回原分类；进程终止复位属预期。
 * 2026-11-05 底部筛选面板：地区预设选择同样进程级——重开面板须显上次选中（只有刷新/选全部才重置）。
 */
object WallMem {
    val browseCat = mutableIntStateOf(-1) // 同 aggCat：-1=最新，0..3=固定四大类
    val zoneCat = mutableIntStateOf(-1)   // 资源口一级：-1=最新，0..=Zone.ZONE_CATS
    val zoneFlt = mutableIntStateOf(-1)   // 资源口二级筛选：-1=全部，0..=Zone.ZONE_FILTERS
    val aggPreset = mutableStateOf<Pair<String, List<String>>?>(null) // 聚合墙地区预设
    val typePreset = mutableStateOf<Pair<String, List<String>>?>(null) // 单源地区预设（preset）
    val typePresetSrv = mutableStateOf<List<VodType>?>(null)           // 对应 pSrv（服务端合并 ids）
}
