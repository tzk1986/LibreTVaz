package com.tzk1986.libretvaz.data

/**
 * 防盗链重试（用户 2026-11-12 批准建议1）：播放请求 401/403 时自动带同域 Referer 重试一次，
 * 仍失败=透传原码交播放器失败兜底/换线。借 CatVod/FongMi 惯例 Referer=scheme://host/（直接链同域）。
 */
fun refererForUrl(url: String): String? {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val rest = url.substringAfter("://")
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    if (host.isEmpty()) return null
    return "$scheme://$host/"
}

object RefererRetry403 : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val resp = chain.proceed(req)
        val code = resp.code
        if ((code == 401 || code == 403) && req.header("Referer") == null) {
            val ref = refererForUrl(req.url.toString())
            if (ref != null) {
                resp.close()
                return chain.proceed(req.newBuilder().header("Referer", ref).build())
            }
        }
        return resp
    }
}
