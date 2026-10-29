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

data class VideoDetail(
    val title: String,
    val cover: String? = null,
    val desc: String? = null,
    val typeName: String? = null,
    val year: String? = null,
    val area: String? = null,
    val actor: String? = null,
    val director: String? = null,
    val sourceName: String,
    val episodes: List<String>,
)

data class VodType(
    val id: String,
    val name: String,
)

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
