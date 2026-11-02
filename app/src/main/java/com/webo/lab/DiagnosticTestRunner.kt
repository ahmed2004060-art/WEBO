package com.webo.lab

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

class LabTestResult(val name: String, val passed: Boolean, val durationMs: Long, val detail: String)

/** Fast, repeatable on-device probes. Each probe uses local app storage and the local virtual site. */
class DiagnosticTestRunner(private val context: Context, private val server: VirtualSiteServer) {
    fun run(name: String): LabTestResult {
        val correlation = DiagnosticEngine.newCorrelationId()
        val started = SystemClock.elapsedRealtime()
        return try {
            val detail = when (name.lowercase()) {
                "storage" -> testStorage()
                "database" -> testDatabase()
                "virtual", "sites", "virtual-sites" -> testVirtualSites()
                "http", "network" -> testHttp()
                "hls" -> testHls()
                "dash" -> testDash()
                "downloads", "download" -> testDownload()
                "fullscan-download" -> testFullScanDownload()
                "icons", "icon" -> testIcons()
                "lifecycle" -> testLifecyclePersistence()
                else -> throw IllegalArgumentException("Unknown test: $name")
            }
            val duration = SystemClock.elapsedRealtime() - started
            DiagnosticEngine.record("INFO", "test_runner", name, "PASS", detail, duration, correlation)
            LabTestResult(name, true, duration, detail)
        } catch (e: Throwable) {
            val duration = SystemClock.elapsedRealtime() - started
            DiagnosticEngine.record("ERROR", "test_runner", name, "FAIL", e.message ?: e.javaClass.simpleName, duration, correlation, e)
            LabTestResult(name, false, duration, (e.message ?: e.javaClass.simpleName) + "\n" + e.javaClass.name)
        }
    }

    private fun requireOk(value: Boolean, message: String) { if (!value) throw IllegalStateException(message) }

    private fun testStorage(): String {
        val dir = File(context.cacheDir, "lab_probes").apply { mkdirs() }
        val file = File(dir, "storage_probe.bin")
        val bytes = ByteArray(256 * 1024) { i -> ((i * 37 + 11) and 0xff).toByte() }
        file.outputStream().use { it.write(bytes); it.flush() }
        val read = file.readBytes()
        requireOk(bytes.contentEquals(read), "Storage read/write checksum mismatch")
        val hashA = sha256(bytes)
        val hashB = sha256(read)
        requireOk(hashA == hashB, "SHA-256 mismatch")
        requireOk(file.delete(), "Test file could not be deleted")
        return "App-private storage write/read/delete OK; 262144 bytes; SHA-256=$hashA"
    }

    private fun testDatabase(): String {
        val before = DiagnosticEngine.count(context)
        val marker = "db-probe-${System.currentTimeMillis()}"
        DiagnosticEngine.record("INFO", "test_runner", marker, "PASS", "SQLite persistence marker")
        requireOk(DiagnosticEngine.flush(), "Diagnostic writer queue did not flush")
        val found = DiagnosticEngine.recent(context, 100).any { it.operation == marker && it.result == "PASS" }
        requireOk(found, "Diagnostic event was not readable after write")
        return "SQLite insert/query OK; events before=$before after=${DiagnosticEngine.count(context)}"
    }

    private fun testVirtualSites(): String {
        val base = server.start()
        val health = request("$base/api/health")
        requireOk(health.code == 200, "Virtual health endpoint returned ${health.code}")
        val json = JSONObject(String(health.body, Charsets.UTF_8))
        requireOk(json.optString("status") == "ok", "Virtual server health payload invalid")
        val html = request(base)
        requireOk(html.code == 200 && String(html.body, Charsets.UTF_8).contains("VIRTUAL_SITE_READY"), "Virtual home page invalid")
        val pages = listOf("site/news", "site/news/article", "site/shop", "site/social", "site/reader", "gallery", "video", "hls", "dash", "downloads")
        for (path in pages) {
            val page = request("$base/$path")
            requireOk(page.code == 200 && String(page.body, Charsets.UTF_8).contains("<html"), "Virtual page /$path returned ${page.code}")
        }
        val images = listOf("sample.jpg", "photo_1.jpg", "photo_2.jpg", "photo_3.jpg", "photo_4.jpg", "favicon.png")
        for (image in images) {
            val data = request("$base/assets/$image")
            requireOk(data.code == 200 && BitmapFactory.decodeByteArray(data.body, 0, data.body.size) != null, "Virtual image $image is invalid")
        }
        val videos = listOf("sample.mp4", "sample2.mp4")
        for (video in videos) {
            val data = request("$base/assets/$video", mapOf("Range" to "bytes=0-31"))
            requireOk(data.code == 206 && data.body.size == 32, "Virtual video range failed for $video")
        }
        return "Loopback virtual sites OK: ${pages.size} pages, ${images.size} image files, ${videos.size} video files, JSON health endpoint"
    }

    private data class HttpResult(val code: Int, val body: ByteArray, val location: String?)

    private fun request(url: String, headers: Map<String, String> = emptyMap(), follow: Boolean = true): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 5000
        c.instanceFollowRedirects = follow
        for ((k, v) in headers) c.setRequestProperty(k, v)
        return try {
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            HttpResult(code, bytes, c.getHeaderField("Location"))
        } finally { c.disconnect() }
    }

    private fun testHttp(): String {
        val base = server.start()
        val ok = request("$base/api/health")
        requireOk(ok.code == 200, "HTTP 200 probe failed")
        val redirect = request("$base/redirect", follow = false)
        requireOk(redirect.code == 302 && redirect.location == "/assets/sample.mp4", "HTTP redirect probe failed")
        val missing = request("$base/status/404")
        requireOk(missing.code == 404, "HTTP 404 probe returned ${missing.code}")
        val serverError = request("$base/status/500")
        requireOk(serverError.code == 500, "HTTP 500 probe returned ${serverError.code}")
        val range = request("$base/downloads/sample.bin", mapOf("Range" to "bytes=0-15"))
        requireOk(range.code == 206 && range.body.size == 16, "HTTP Range probe failed: ${range.code}/${range.body.size}")
        val slow = request("$base/slow?ms=180")
        requireOk(slow.code == 200 && String(slow.body).startsWith("SLOW_RESPONSE_OK"), "Slow-response endpoint failed")
        return "HTTP 200/302/404/500, byte-range 206 (16 bytes) and delayed response OK"
    }

    private fun testHls(): String {
        val base = server.start()
        val manifest = request("$base/assets/sample.m3u8")
        val text = String(manifest.body, Charsets.UTF_8)
        requireOk(manifest.code == 200 && text.startsWith("#EXTM3U"), "HLS manifest invalid")
        requireOk(text.contains("#EXTINF"), "HLS duration tag missing")
        requireOk(text.lineSequence().any { it.trim().isNotEmpty() && !it.trim().startsWith("#") }, "HLS media segment URI missing")
        val segments = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        var totalBytes = 0
        for (segmentPath in segments) {
            val segment = request(URL(URL("$base/assets/sample.m3u8"), segmentPath).toString())
            requireOk(segment.code == 200 && segment.body.size > 188, "HLS segment $segmentPath could not be retrieved")
            requireOk(segment.body[0] == 0x47.toByte(), "HLS TS sync byte is invalid in $segmentPath")
            totalBytes += segment.body.size
        }
        return "HLS playlist parsed; ${segments.size} MPEG-TS segments retrieved ($totalBytes bytes)"
    }

    private fun testDash(): String {
        val base = server.start()
        val response = request("$base/assets/sample.mpd")
        val xml = String(response.body, Charsets.UTF_8)
        requireOk(response.code == 200 && xml.contains("<MPD"), "DASH MPD is invalid")
        val initName = Regex("initialization=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)?.replace("\$RepresentationID\$", "0")
            ?: throw IllegalStateException("DASH initialization template missing")
        val mediaTemplate = Regex("media=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
            ?: throw IllegalStateException("DASH media template missing")
        val mediaNames = (1..5).map { n -> mediaTemplate.replace("\$RepresentationID\$", "0").replace("\$Number%05d\$", "%05d".format(n)) }
        val init = request("$base/assets/$initName")
        requireOk(init.code == 200 && init.body.isNotEmpty(), "DASH initialization segment unavailable")
        var mediaBytes = 0
        for (mediaName in mediaNames) {
            val media = request("$base/assets/$mediaName")
            requireOk(media.code == 200 && media.body.isNotEmpty(), "DASH media segment $mediaName unavailable")
            mediaBytes += media.body.size
        }
        return "DASH MPD parsed; initialization plus ${mediaNames.size} media segments retrieved (${init.body.size + mediaBytes} bytes)"
    }

    private fun testDownload(): String {
        val base = server.start()
        val response = request("$base/downloads/sample.bin")
        requireOk(response.code == 200 && response.body.size == 64 * 1024, "Test payload size/status mismatch")
        val out = File(context.cacheDir, "lab_probes/download_probe.bin")
        out.parentFile?.mkdirs()
        out.outputStream().use { it.write(response.body) }
        val saved = out.readBytes()
        requireOk(sha256(saved) == sha256(response.body), "Downloaded test payload checksum mismatch")
        val name = "${saved.size} bytes; sha256=${sha256(saved)}"
        out.delete()
        return "HTTP download + local file persistence + checksum OK; $name"
    }

    private fun testFullScanDownload(): String {
        val base = server.start()
        val response = request("$base/downloads/fullscan.bin")
        requireOk(response.code == 200 && response.body.size == 1024 * 1024, "FullScan payload must be exactly 1 MiB")
        val range = request("$base/downloads/fullscan.bin", mapOf("Range" to "bytes=65536-131071"))
        requireOk(range.code == 206 && range.body.size == 65536, "FullScan range/resume endpoint failed")
        return "FullScan real-download fixture OK; size=${response.body.size}; range=${range.body.size}; SHA-256=${sha256(response.body)}"
    }

    private fun testIcons(): String {
        val base = server.start()
        val response = request("$base/assets/favicon.png")
        requireOk(response.code == 200, "Virtual favicon request failed")
        val bitmap = BitmapFactory.decodeByteArray(response.body, 0, response.body.size)
            ?: throw IllegalStateException("Favicon PNG decoder rejected the image")
        requireOk(SiteIconCache.save(context, "webo-lab.virtual", bitmap), "Could not persist favicon")
        val loaded = SiteIconCache.load(context, "webo-lab.virtual")
        requireOk(loaded != null && loaded.width == bitmap.width && loaded.height == bitmap.height, "Disk icon cache readback mismatch")
        requireOk(SiteIconCache.exists(context, "webo-lab.virtual"), "Icon cache file missing")
        return "PNG decode + durable site-icon cache write/read OK; ${bitmap.width}x${bitmap.height}"
    }

    private fun testLifecyclePersistence(): String {
        val prefs = context.getSharedPreferences("webo_lab_lifecycle_probe", Context.MODE_PRIVATE)
        val token = "${System.currentTimeMillis()}-${Thread.currentThread().id}"
        requireOk(prefs.edit().putString("last_probe", token).commit(), "Could not persist lifecycle marker")
        requireOk(prefs.getString("last_probe", null) == token, "Lifecycle marker did not survive preference readback")
        DiagnosticEngine.record("INFO", "lifecycle", "state_persist_probe", "OK", "marker=$token")
        return "Persistent lifecycle marker write/read OK; real process-death test requires the restart command"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
