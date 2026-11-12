package com.tzk1986.libretvaz.data

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 防盗链重试（用户 2026-11-12 批准建议1）：播放请求 401/403 时自动带同域 Referer 重试一次。
 * refererForUrl=纯函数取 scheme+host；拦截器只重试一次（二次仍 403=透传原码，交播放器失败兜底），
 * 请求已带 Referer 时不叠加。
 */
class RefererRetryTest {

    @Test
    fun refererForUrl_keepsSchemeAndHost() {
        assertEquals("https://cdn.x.com/", refererForUrl("https://cdn.x.com/a/b.m3u8?tok=1"))
        assertEquals("http://v.x.com/", refererForUrl("http://v.x.com"))
    }

    @Test
    fun refererForUrl_rejectsNoHostOrNonHttp() {
        assertNull(refererForUrl("not-a-url"))
        assertNull(refererForUrl("https:///a"))
        assertNull(refererForUrl("file://host/a"))
    }

    @Test
    fun interceptor403_retriesOnceWithSameHostReferer() {
        val r = Request.Builder().url("http://v.x.com/a/index.m3u8").build()
        val chain = FakeChain(r, listOf(403, 200))
        val res = RefererRetry403.intercept(chain)
        assertEquals(200, res.code)
        assertEquals(2, chain.seen.size)
        assertEquals("http://v.x.com/", chain.seen[1].header("Referer"))
    }

    @Test
    fun interceptor401_alsoRetries() {
        val r = Request.Builder().url("http://v.x.com/a").build()
        val chain = FakeChain(r, listOf(401, 200))
        assertEquals(200, RefererRetry403.intercept(chain).code)
    }

    @Test
    fun interceptor200_passthrough() {
        val r = Request.Builder().url("http://v.x.com/a").build()
        val chain = FakeChain(r, listOf(200))
        assertEquals(200, RefererRetry403.intercept(chain).code)
        assertEquals(1, chain.seen.size)
    }

    @Test
    fun interceptorStill403_noLoop() {
        val r = Request.Builder().url("http://v.x.com/a").build()
        val chain = FakeChain(r, listOf(403, 403))
        assertEquals(403, RefererRetry403.intercept(chain).code)
        assertEquals(2, chain.seen.size)
    }

    @Test
    fun interceptor403_existingReferer_notOverwritten() {
        val r = Request.Builder().url("http://v.x.com/a").header("Referer", "http://other/").build()
        val chain = FakeChain(r, listOf(403))
        assertEquals(403, RefererRetry403.intercept(chain).code)
        assertEquals(1, chain.seen.size)
    }

    private object EmptyBody : okhttp3.ResponseBody() {
        override fun contentLength(): Long = 0
        override fun contentType(): okhttp3.MediaType? = null
        override fun source(): okio.BufferedSource = okio.Buffer()
    }

    private class FakeChain(val req: Request, val codes: List<Int>) : Interceptor.Chain {
        val seen = mutableListOf<Request>()
        private var idx = 0
        override fun request(): Request = req
        override fun proceed(request: Request): Response {
            seen.add(request)
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(codes[idx++]).message("m").body(EmptyBody).build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
