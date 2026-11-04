package com.tzk1986.libretvaz.data

/**
 * 直播频道国家分类（本站扩展，web 无对应）：iptv-org 体系的 ISO 3166-1 alpha-2
 * 国家码从 tvg-id 尾段提取（「CCTV1.cn@SD」→ cn），按用户选定的展示顺序归类。
 * 无国家码的订阅（TVBox txt 等）全部归「其他」。
 */
object LiveGeo {
    /** 展示顺序：中国置顶，随后 日/韩/美/欧，其余兜底 */
    val CATS = listOf("中国", "日本", "韩国", "美国", "欧洲", "其他")
    private val EU = setOf(
        "al", "ad", "at", "ax", "be", "ba", "bg", "ch", "cz", "de", "dk", "ee", "es", "fi",
        "fo", "fr", "gb", "gg", "gi", "gr", "hr", "hu", "ie", "im", "is", "it", "li", "lt",
        "lu", "lv", "mc", "md", "me", "mk", "mt", "nl", "no", "pl", "pt", "ro", "rs", "se",
        "si", "sj", "sk", "sm", "ua", "va", "xk",
    )

    /** tvg-id 尾段国家码：「Name.cn@Qual」→ "cn"；不合规则 null */
    fun countryCode(tvgId: String?): String? {
        val t = tvgId?.substringAfterLast('.', "")?.substringBefore('@')?.lowercase() ?: return null
        return t.takeIf { it.length == 2 && it.all(Char::isLetter) }
    }

    fun catOf(tvgId: String?): String {
        val cc = countryCode(tvgId) ?: return "其他"
        return when (cc) {
            "cn" -> "中国"
            "jp" -> "日本"
            "kr" -> "韩国"
            "us" -> "美国"
            in EU -> "欧洲"
            else -> "其他"
        }
    }

    fun catOf(ch: LiveChannel): String = catOf(ch.tvgId)

    /** 频道表按 CATS 顺序分组（仅返回非空分类，保持展示序） */
    fun partition(chans: List<LiveChannel>): List<Pair<String, List<LiveChannel>>> =
        CATS.mapNotNull { c -> chans.filter { catOf(it) == c }.takeIf { it.isNotEmpty() }?.let { c to it } }
}
