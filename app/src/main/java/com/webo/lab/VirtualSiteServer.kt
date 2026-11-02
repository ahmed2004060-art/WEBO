package com.webo.lab

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Loopback-only HTTP server with local web pages, images, video, HLS, DASH and failure fixtures. */
class VirtualSiteServer(private val context: Context) {
    data class RequestStat(val count: Long, val bytes: Long)
    private data class Payload(val bytes: ByteArray, val mime: String, val status: Int = 200, val extraHeaders: Map<String, String> = emptyMap())
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "webo-virtual-site-worker").apply { isDaemon = true } }
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val byteCounters = ConcurrentHashMap<String, AtomicLong>()
    private val fullScanPayload: ByteArray by lazy { ByteArray(1024 * 1024) { i -> ((i * 31 + 17) and 0xff).toByte() } }
    @Volatile private var server: ServerSocket? = null
    @Volatile var baseUrl: String = ""
        private set

    fun start(): String {
        if (running.get() && server?.isClosed == false) return baseUrl
        val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = socket
        baseUrl = "http://127.0.0.1:${socket.localPort}"
        running.set(true)
        Thread({
            while (running.get()) {
                try { val client = socket.accept(); pool.execute { handle(client) } }
                catch (e: Throwable) { if (running.get()) DiagnosticEngine.record("WARN", "virtual_server", "accept", "FAIL", e.message ?: e.javaClass.simpleName) }
            }
        }, "webo-virtual-site-accept").apply { isDaemon = true; start() }
        DiagnosticEngine.record("INFO", "virtual_server", "start", "OK", "Listening on loopback port ${socket.localPort}")
        return baseUrl
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Throwable) {}
        server = null
        try { pool.shutdownNow() } catch (_: Throwable) {}
        DiagnosticEngine.record("INFO", "virtual_server", "stop", "OK", "Loopback test server stopped")
    }

    fun isRunning(): Boolean = running.get() && server?.isClosed == false
    fun stats(): Map<String, RequestStat> = counters.keys.associateWith { RequestStat(counters[it]?.get() ?: 0L, byteCounters[it]?.get() ?: 0L) }
    fun url(path: String = "/"): String = baseUrl + if (path.startsWith("/")) path else "/$path"
    private fun asset(name: String): ByteArray = context.assets.open("lab/$name").use { it.readBytes() }

    private fun page(title: String, content: String): ByteArray {
        val html = """<!doctype html><html lang="ar"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title><style>body{font-family:sans-serif;background:#101820;color:#eef;padding:18px}a{color:#79b8ff;display:block;padding:9px}img,video{max-width:100%;border-radius:10px;margin:8px 0}.card{background:#1d2935;padding:14px;border-radius:12px;margin:10px 0}</style></head><body><h2>$title</h2>$content</body></html>"""
        return html.toByteArray(Charsets.UTF_8)
    }

    private fun route(target: String): Payload {
        val path = target.substringBefore('?').ifBlank { "/" }
        val query = target.substringAfter('?', "")
        val qs = query.split('&').mapNotNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.isEmpty()) null else URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        }.toMap()
        return when {
            path == "/" -> Payload(page("WEBO Virtual Test Site", """
                <div class=card><b>WEBO Virtual Test Site</b><p>موقع محلي للاختبار دون إنترنت خارجي.</p></div>
                <a href="/site/news">موقع الأخبار</a><a href="/site/shop">موقع المتجر</a><a href="/site/social">موقع اجتماعي</a><a href="/site/reader">موقع المقالات</a>
                <a href="/gallery">معرض الصور</a><a href="/video">مشغل الفيديو</a><a href="/hls">بث HLS</a><a href="/dash">بث DASH</a>
                <a href="/downloads">ملفات التنزيل</a><a href="/redirect">تحويل HTTP 302</a><a href="/slow?ms=900">استجابة بطيئة</a>
                <a href="/status/404">حالة HTTP 404</a><a href="/status/500">حالة HTTP 500</a>
                <p id="ready">VIRTUAL_SITE_READY</p><img src="/assets/favicon.png" width="32" height="32">
            """), "text/html; charset=utf-8")
            path == "/site/news" -> Payload(page("WEBO News", "<div class=card>عنوان تجريبي للأخبار</div><a href='/site/news/article'>اقرأ الخبر</a><a href='/gallery'>الصور</a>"), "text/html; charset=utf-8")
            path == "/site/news/article" -> Payload(page("WEBO News Article", "<article><h3>مقال اختبار محلي</h3><p>محتوى تجريبي لفحص القراءة والتمرير والروابط.</p><a href='/'>الرئيسية</a></article>"), "text/html; charset=utf-8")
            path == "/site/shop" -> Payload(page("WEBO Shop", """<div class=card>منتج تجريبي 01</div><img src="/assets/photo_1.jpg"><button onclick="document.getElementById('cart').textContent='ADDED'">إضافة للسلة</button><p id="cart">EMPTY</p>"""), "text/html; charset=utf-8")
            path == "/site/social" -> Payload(page("WEBO Social", """<input id="post" placeholder="اكتب منشوراً"><button onclick="document.getElementById('result').textContent=document.getElementById('post').value">نشر</button><p id="result"></p>"""), "text/html; charset=utf-8")
            path == "/site/reader" -> Payload(page("WEBO Reader", "<h3>صفحة قراءة تجريبية</h3><p>نص طويل لاختبار تمرير الصفحة وعناصرها.</p>".repeat(8)), "text/html; charset=utf-8")
            path == "/gallery" -> Payload(page("WEBO Test Gallery", "<div class=card>صور تجريبية متعددة</div><img src='/assets/sample.jpg'><img src='/assets/photo_1.jpg'><img src='/assets/photo_2.jpg'><img src='/assets/photo_3.jpg'><img src='/assets/photo_4.jpg'><img src='/assets/favicon.png'>"), "text/html; charset=utf-8")
            path == "/video" -> Payload(page("WEBO Video Lab", "<video controls autoplay muted src='/assets/sample.mp4'></video><video controls muted src='/assets/sample2.mp4'></video><a href='/assets/sample.mp4'>MP4 1</a><a href='/assets/sample2.mp4'>MP4 2</a>"), "text/html; charset=utf-8")
            path == "/hls" -> Payload(page("WEBO HLS Test", "<video controls src='/assets/sample.m3u8'></video><a href='/assets/sample.m3u8'>HLS Manifest</a>"), "text/html; charset=utf-8")
            path == "/dash" -> Payload(page("WEBO DASH Test", "<video controls src='/assets/sample.mpd'></video><a href='/assets/sample.mpd'>DASH Manifest</a>"), "text/html; charset=utf-8")
            path == "/downloads" -> Payload(page("WEBO Downloads", "<a href='/downloads/sample.bin'>تنزيل ملف اختباري 64 KB</a><a href='/assets/sample.mp4'>تنزيل MP4</a><a href='/assets/sample2.mp4'>تنزيل MP4 2</a><a href='/assets/sample.jpg'>تنزيل JPG</a><a href='/assets/photo_1.jpg'>تنزيل JPG 2</a>"), "text/html; charset=utf-8")
            path == "/api/health" -> Payload("""{"status":"ok","service":"webo-virtual-site","fixtures":["image","mp4","hls","dash","download"]}""".toByteArray(), "application/json; charset=utf-8")
            path == "/redirect" -> Payload(ByteArray(0), "text/plain", 302, mapOf("Location" to "/assets/sample.mp4"))
            path == "/status/404" -> Payload("NOT_FOUND_TEST".toByteArray(), "text/plain", 404)
            path == "/status/500" -> Payload("SERVER_ERROR_TEST".toByteArray(), "text/plain", 500)
            path == "/slow" -> { val ms = (qs["ms"]?.toLongOrNull() ?: 900L).coerceIn(100L, 3000L); Thread.sleep(ms); Payload("SLOW_RESPONSE_OK:$ms".toByteArray(), "text/plain") }
            path == "/assets/favicon.png" -> Payload(asset("favicon.png"), "image/png")
            path == "/assets/sample.jpg" -> Payload(asset("sample.jpg"), "image/jpeg")
            path.matches(Regex("/assets/photo_[1-4]\\.jpg")) -> Payload(asset(path.substringAfterLast('/')), "image/jpeg")
            path == "/assets/sample.mp4" || path == "/assets/sample2.mp4" -> Payload(asset(path.substringAfterLast('/')), "video/mp4", extraHeaders = mapOf("Accept-Ranges" to "bytes"))
            path == "/assets/sample.m3u8" -> Payload(asset("sample.m3u8"), "application/vnd.apple.mpegurl")
            path.matches(Regex("/assets/hls_seg_\\d{3}\\.ts")) -> Payload(asset(path.substringAfterLast('/')), "video/mp2t")
            path == "/assets/sample.mpd" -> Payload(asset("sample.mpd"), "application/dash+xml")
            path == "/assets/init-stream0.m4s" -> Payload(asset("init-stream0.m4s"), "video/iso.segment")
            path.matches(Regex("/assets/chunk-stream0-\\d{5}\\.m4s")) -> Payload(asset(path.substringAfterLast('/')), "video/iso.segment")
            path == "/downloads/sample.bin" -> Payload(asset("sample.bin"), "application/octet-stream", extraHeaders = mapOf("Accept-Ranges" to "bytes", "Content-Disposition" to "attachment; filename=webo-test.bin"))
            path == "/downloads/fullscan.bin" -> Payload(fullScanPayload, "application/octet-stream", extraHeaders = mapOf("Accept-Ranges" to "bytes", "Content-Disposition" to "attachment; filename=webo-fullscan-1mb.bin"))
            else -> Payload("NOT_FOUND".toByteArray(), "text/plain", 404)
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 5000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: "GET"
                val target = parts.getOrNull(1) ?: "/"
                val headers = HashMap<String, String>()
                while (true) { val line = readLine(input) ?: break; if (line.isEmpty()) break; val idx = line.indexOf(':'); if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim() }
                val path = target.substringBefore('?')
                counters.getOrPut(path) { AtomicLong() }.incrementAndGet()
                val payload = route(target)
                var code = payload.status
                var body = payload.bytes
                val range = headers["range"]
                var rangeHeader: String? = null
                if (code == 200 && !range.isNullOrBlank() && (path.endsWith(".mp4") || path.endsWith(".bin"))) {
                    val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
                    if (m != null) {
                        val start = m.groupValues[1].toIntOrNull() ?: 0
                        val end = m.groupValues[2].toIntOrNull()?.coerceAtMost(body.size - 1) ?: body.size - 1
                        if (start in body.indices && end >= start) { val full = body.size; body = body.copyOfRange(start, end + 1); code = 206; rangeHeader = "bytes $start-$end/$full" }
                    }
                }
                val phrase = when (code) { 200 -> "OK"; 206 -> "Partial Content"; 302 -> "Found"; 404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "Response" }
                val output = BufferedOutputStream(socket.getOutputStream())
                val responseHeaders = LinkedHashMap<String, String>().apply {
                    put("Content-Type", payload.mime); put("Content-Length", body.size.toString()); put("Connection", "close"); put("Cache-Control", "no-store")
                    putAll(payload.extraHeaders); if (rangeHeader != null) put("Content-Range", rangeHeader)
                }
                output.write("HTTP/1.1 $code $phrase\r\n".toByteArray(Charsets.US_ASCII))
                for ((k, v) in responseHeaders) output.write("$k: $v\r\n".toByteArray(Charsets.US_ASCII))
                output.write("\r\n".toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") {
                    if (path == "/downloads/fullscan.bin" && code == 200 && range == null) {
                        // Keep the real download active long enough for the original FullScan to pause/resume it.
                        var offset = 0
                        while (offset < body.size) {
                            val count = minOf(16 * 1024, body.size - offset)
                            output.write(body, offset, count)
                            output.flush()
                            offset += count
                            if (offset < body.size) Thread.sleep(35L)
                        }
                    } else output.write(body)
                }
                output.flush()
                byteCounters.getOrPut(path) { AtomicLong() }.addAndGet(body.size.toLong())
                DiagnosticEngine.record("DEBUG", "virtual_server", "request", code.toString(), "$method $path bytes=${body.size}")
            } catch (e: Throwable) { DiagnosticEngine.record("WARN", "virtual_server", "client", "FAIL", e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
            if (b == 10) break
            if (b != 13) bytes.write(b)
        }
        return bytes.toString("US-ASCII")
    }
}
