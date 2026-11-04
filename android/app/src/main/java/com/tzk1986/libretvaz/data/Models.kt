package com.tzk1986.libretvaz.data

data class VodSource(
    val name: String,
    val url: String,
    val detail: String? = null,
    val isAdult: Boolean = false,
)

data class LiveSource(
    val name: String,
    val url: String,
    val epg: String? = null,
)

data class VodSummary(
    val sourceKey: String,
    val sourceName: String,
    val sourceUrl: String,
    val vodId: String,
    val name: String,
    val pic: String? = null,
    val typeName: String? = null,
    val year: String? = null,
    val area: String? = null,
    val remarks: String? = null,
    val isAdult: Boolean = false,
)

/** 一集：名称 + 播放地址（对应 web episodes[line].list 元素） */
data class PlayEpisode(val name: String, val link: String)

/** 一条线路（flag 如「极速」+ 全部剧集，对齐 web 多线路结构） */
data class PlayLine(val flag: String, val list: List<PlayEpisode>)

data class VideoDetail(
    val title: String,
    val cover: String? = null,
    val desc: String? = null,
    val typeName: String? = null,
    val year: String? = null,
    val area: String? = null,
    val actor: String? = null,
    val director: String? = null,
    val remarks: String? = null,
    val sourceName: String,
    val lines: List<PlayLine>,
)

data class VodType(
    val id: String,
    val name: String,
    /** 父分类 id（type_pid）；空或 "0"=顶层。聚合墙取子类、地区定向靠它 */
    val pid: String = "",
    /** type_extend 扩展字段（web parseExtend）：area/year/lang 逗号串=二级筛选数据源 */
    val extend: Map<String, String> = emptyMap(),
)

/** 二级筛选数据源（web buildFilters 产物）：分类树子类 + type_extend 的地区/年份/语言可选值 */
data class VodFilters(
    val subs: List<VodType> = emptyList(),
    val area: List<String> = emptyList(),
    val year: List<String> = emptyList(),
    val lang: List<String> = emptyList(),
) {
    fun isEmpty() = subs.isEmpty() && area.isEmpty() && year.isEmpty() && lang.isEmpty()
}

data class LiveChannel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
)

data class LivePlaylist(
    val name: String?,
    val channels: List<LiveChannel>,
    val groups: List<String>,
)

data class SearchProgress(
    val done: Int,
    val total: Int,
    val errors: List<String> = emptyList(),
)
