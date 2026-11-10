package com.tzk1986.libretvaz.data

import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.ArrayDeque

/**
 * TVBox 同构自托管远程输入服务（用户 2026-11-10 裁决：扫码流程零配置，APK 即服务器）。
 * 纯函数可单测（RemoteServerTest）：parseSendQuery/pickLanIp/RemoteInbox/renderRemotePage；
 * RemoteServer 为迷你 HTTP/1.0（GET only），手机扫码开内置 H5 页 → /send 入队 → 电视端收件派发。
 */

/** /send 查询解析：m∈{search,sub}（缺省 search），t URL 解码后非空；非法返回 null */
fun parseSendQuery(q: String): Pair<String, String>? {
    var mode = "search"
    var text: String? = null
    for (kv in q.split('&')) {
        val i = kv.indexOf('=')
        if (i < 0) continue
        val k = kv.substring(0, i)
        val v = runCatching { URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.getOrNull() ?: continue
        when (k) {
            "m" -> mode = v
            "t" -> if (v.isNotEmpty()) text = v
        }
    }
    if (mode != "search" && mode != "sub") return null
    val t = text ?: return null
    return mode to t
}

/** 真实局域网 IPv4 优选（用户规矩：排除虚拟/回环/APIPA，wlan > eth > 其他站点本地地址） */
fun pickLanIp(ifs: List<Pair<String, String>>): String {
    val virtual = listOf("lo", "dummy", "br-", "veth", "docker", "vmnet", "tun", "tap", "ap", "ifb", "radio", "p2p")
    fun usable(name: String, ip: String): Boolean {
        if (virtual.any { name.startsWith(it) }) return false
        if (ip.startsWith("127.") || ip.startsWith("169.254.")) return false
        val o = ip.split('.').mapOrNull4() ?: return false
        return o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31)
    }
    val ok = ifs.filter { (n, ip) -> usable(n, ip) }
    return ok.firstOrNull { it.first.startsWith("wlan") }?.second
        ?: ok.firstOrNull { it.first.startsWith("eth") }?.second
        ?: ok.firstOrNull()?.second.orEmpty()
}

private fun List<String>.mapOrNull4(): List<Int>? = runCatching { map { it.toInt() } }.getOrNull()?.takeIf { it.size == 4 }

/** 手机→电视收件队列：FIFO，超容量丢最旧（电视长时间未开弹窗时不至堆积） */
class RemoteInbox(private val cap: Int = 8) {
    private val dq = ArrayDeque<Pair<String, String>>()
    fun put(mode: String, text: String) = synchronized(dq) {
        dq.addLast(mode to text)
        while (dq.size > cap) dq.removeFirst()
    }

    fun poll(): Pair<String, String>? = synchronized(dq) { if (dq.isEmpty()) null else dq.removeFirst() }
}

/** 手机 H5 输入页（TVBox 红主题版式）：标题+输入+发送+状态，纯内联无外链 */
fun renderRemotePage(mode: String): String {
    val title = if (mode == "sub") "电视订阅导入" else "电视远程输入"
    val hint = if (mode == "sub") "粘贴或输入订阅地址" else "输入关键词"
    return """
<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>body{background:#14171A;color:#fff;font-family:sans-serif;display:flex;flex-direction:column;
align-items:center;padding:32px 20px}h2{color:#F80000}input{width:100%;max-width:420px;padding:12px;
font-size:16px;border-radius:8px;border:1px solid #444;background:#222;color:#fff}
button{margin-top:16px;padding:10px 40px;font-size:16px;color:#fff;background:#F80000;border:0;
border-radius:8px}#st{margin-top:14px;color:#bbb;min-height:20px}</style></head><body>
<h2>$title</h2><input id="t" placeholder="$hint" ${if (mode == "sub") "type=\"url\"" else ""}>
<button onclick="send()">发送</button><div id="st"></div>
<script>function send(){var v=document.getElementById('t').value.trim();var st=document.getElementById('st');
if(!v){st.textContent='内容为空';return}st.textContent='发送中…';
fetch('/send?m=$mode&t='+encodeURIComponent(v)).then(r=>r.json()).then(j=>{st.textContent=j.ok?'已发送到电视':'发送失败'})
.catch(()=>{st.textContent='网络错误'})}</script></body></html>
""".trimIndent()
}

/** 迷你 HTTP 服务：GET /remote?m= 出手机页、GET /send 入队；其余 404。daemon 线程，随进程退 */
class RemoteServer(private val inbox: RemoteInbox, private val port: Int = PORT) {
    companion object { const val PORT = 9978 }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            runCatching {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(java.net.InetSocketAddress(port))
                    while (running) runCatching { ss.accept().use(::handle) }
                }
            }
        }.apply { isDaemon = true; name = "remote-serve"; start() }
    }

    fun stop() {
        running = false
        runCatching { thread?.interrupt() }
        thread = null
    }

    private fun handle(s: Socket) {
        // 注意：readLine 不能经 use 包 BufferedReader——关 reader 会连 socket 一起关掉，响应写不出去
        val line = java.io.BufferedReader(s.getInputStream().reader()).readLine() ?: return
        if (!line.startsWith("GET")) return
        val pathQ = line.split(' ').getOrNull(1).orEmpty()
        val path = pathQ.substringBefore('?', "")
        val query = pathQ.substringAfter('?', "")
        when {
            path == "/remote" -> {
                val mode = if (query.contains("m=sub")) "sub" else "search"
                respond(s, 200, "text/html;charset=utf-8", renderRemotePage(mode))
            }
            path == "/send" -> {
                val parsed = parseSendQuery(query)
                if (parsed == null) respond(s, 400, "application/json", """{"ok":0}""")
                else {
                    inbox.put(parsed.first, parsed.second)
                    respond(s, 200, "application/json", """{"ok":1}""")
                }
            }
            else -> respond(s, 404, "text/plain;charset=utf-8", "not found")
        }
    }

    private fun respond(s: Socket, code: Int, ctype: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        s.getOutputStream().apply {
            write("HTTP/1.0 $code OK\r\nContent-Type: $ctype\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }
}

/** 枚举本机网卡取真实局域网 IPv4；无可用返回 ""（弹窗据此提示检查 WiFi） */
fun lanIp(): String = runCatching {
    val ifs = NetworkInterface.getNetworkInterfaces().toList().flatMap { nif ->
        if (!nif.isUp || nif.isLoopback) emptyList()
        else nif.inetAddresses.toList().filter { it.isSiteLocalAddress }.map { nif.name to it.hostAddress }
    }
    pickLanIp(ifs)
}.getOrDefault("")
