package com.webo

import android.os.Looper
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

// الفحص الذاتي: ملف جديد كلياً (لا علاقة له بأي SelfTest قديم).
// يشغَّل من زر داخل التطبيق ويفحص التطبيق وهو يعمل: كل اختبار ينفّذ عملية حقيقية
// (كتابة وقراءة فعلية، خادم محلي حقيقي على 127.0.0.1، WebView حقيقي، مشغّل Media3 حقيقي).
// عند أي فشل يُسجَّل الاستثناء الكامل مع الموضع والسبب الجذري في المسجّل وفي التقرير.
internal class CheckResult(val group: String, val name: String, val ok: Boolean, val detail: String, val ms: Long)

internal class Case(val group: String, val name: String, val run: () -> String)

internal class SelfServer {
    private val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val iso = Charsets.ISO_8859_1
    val files = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, String>>()
    val hits = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    @Volatile private var closed = false
    val port: Int get() = ss.localPort
    fun base(): String = "http://127.0.0.1:" + port

    fun put(path: String, data: ByteArray, type: String = "application/octet-stream") {
        files[path] = Pair(data, type)
    }

    fun putText(path: String, text: String, type: String) {
        put(path, text.toByteArray(Charsets.UTF_8), type)
    }

    fun start() {
        diagThread("selfcheck-accept") {
            while (!closed) {
                val s = try { ss.accept() } catch (e: Throwable) { break }
                diagThread("selfcheck-conn") { handle(s) }.start()
            }
        }.start()
    }

    fun stop() {
        closed = true
        try { ss.close() } catch (e: Throwable) { Diag.swallow("SelfCheck.kt SelfServer.stop", e) }
    }

    private fun handle(s: java.net.Socket) {
        try {
            s.soTimeout = 10000
            val rd = s.getInputStream().bufferedReader(iso)
            val reqLine = rd.readLine() ?: return
            var range: String? = null
            while (true) {
                val l = rd.readLine() ?: break
                if (l.isEmpty()) break
                if (l.lowercase().startsWith("range:")) range = l.substring(6).trim()
            }
            val parts = reqLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }.substringBefore('?')
            hits.getOrPut(path) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            val out = s.getOutputStream()
            fun hdr(code: String, extra: String, len: Long) {
                out.write(("HTTP/1.1 " + code + "\r\n" + extra + "Content-Length: " + len + "\r\nConnection: close\r\n\r\n").toByteArray(iso))
            }
            if (path == "/redir") {
                hdr("302 Found", "Location: /file.bin\r\n", 0)
            } else if (path == "/short") {
                hdr("200 OK", "Content-Type: application/octet-stream\r\n", 1000)
                out.write(ByteArray(500) { 7 })
            } else {
                val f = files[path]
                if (f == null) {
                    hdr("404 Not Found", "", 0)
                } else {
                    val data = f.first
                    val m = if (range != null) Regex("bytes=(\\d+)-(\\d*)").matchEntire(range!!) else null
                    if (m != null) {
                        val a = m.groupValues[1].toInt()
                        val b = (m.groupValues[2].toIntOrNull() ?: (data.size - 1)).coerceAtMost(data.size - 1)
                        hdr("206 Partial Content", "Content-Type: " + f.second + "\r\nContent-Range: bytes " + a + "-" + b + "/" + data.size + "\r\n", (b - a + 1).toLong())
                        if (method != "HEAD") out.write(data, a, b - a + 1)
                    } else {
                        hdr("200 OK", "Content-Type: " + f.second + "\r\nAccept-Ranges: bytes\r\n", data.size.toLong())
                        if (method != "HEAD") out.write(data)
                    }
                }
            }
            out.flush()
        } catch (e: Throwable) {
            Diag.swallow("SelfCheck.kt SelfServer.handle", e)
        } finally {
            try { s.close() } catch (e: Throwable) { Diag.swallow("SelfCheck.kt SelfServer.handle.close", e) }
        }
    }
}

internal class SelfCheck(private val a: MainActivity) {
    companion object {
        @Volatile var running = false
        @Volatile var lastResults: List<CheckResult> = emptyList()
        @Volatile var lastReport: String = ""
    }

    private val ts = System.currentTimeMillis().toString()
    private val workDir = File(a.cacheDir, "selfcheck_" + ts)
    private var server: SelfServer? = null

    private fun need(c: Boolean, msg: String) {
        if (!c) throw AssertionError(msg)
    }

    private fun eq(x: Any?, y: Any?, what: String) {
        if (x != y) throw AssertionError(what + ": المتوقع=" + y + " الفعلي=" + x)
    }

    private fun pat(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 31 + seed) and 0xff).toByte() }

    private fun <T> onUi(timeoutMs: Long = 15000, f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val latch = CountDownLatch(1)
        var res: Any? = null
        var err: Throwable? = null
        a.runOnUiThread {
            try { res = f() } catch (t: Throwable) { err = t } finally { latch.countDown() }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw Exception("انتهت مهلة انتظار الخيط الرئيسي (" + timeoutMs + "ms): الواجهة لا تستجيب")
        val e = err
        if (e != null) throw e
        @Suppress("UNCHECKED_CAST")
        return res as T
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(100)
        }
        return cond()
    }

    private fun root(t: Throwable): Throwable {
        var x = t
        var n = 0
        while (x.cause != null && x.cause !== x && n < 20) { x = x.cause!!; n++ }
        return x
    }

    private fun describe(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        fun loc(x: Throwable): String =
            x.stackTrace.firstOrNull { it.className.startsWith("com.webo") }?.let { (it.fileName ?: "؟") + ":" + it.lineNumber + " " + it.className.substringAfterLast('.') + "." + it.methodName }
                ?: (x.stackTrace.firstOrNull()?.toString() ?: "؟")
        val r = root(t)
        return "الاستثناء: " + t.javaClass.name + ": " + (t.message ?: "") +
            "\nالموضع: " + loc(t) +
            "\nالسبب الجذري: " + r.javaClass.name + ": " + (r.message ?: "") + " @ " + loc(r) +
            "\n" + sw.toString()
    }

    private fun cases(): List<Case> {
        val c = ArrayList<Case>()
        fun t(g: String, n: String, f: () -> String) { c.add(Case(g, n, f)) }
        val srv = { server ?: throw IllegalStateException("الخادم المحلي للفحص لم يعمل") }

        // ---------- 1. المسجّل ----------
        t("المسجّل", "الكتابة الفورية إلى القرص") {
            val m = "SC_W_" + System.nanoTime()
            Diag.i("فحص", m)
            need(Diag.contains(m), "الحدث لم يظهر في ملف السجل بعد الكتابة")
            "ملفات السجل=" + Diag.files().size
        }
        t("المسجّل", "تسجيل استثناء مع المكدّس الكامل") {
            val m = "SC_E_" + System.nanoTime()
            Diag.e("فحص", m, IllegalStateException("استثناء اختبار " + m, RuntimeException("سبب داخلي")))
            need(Diag.contains(m), "نص الاستثناء غير موجود")
            need(Diag.contains("Caused by: java.lang.RuntimeException"), "سلسلة الأسباب غير موجودة")
            "تم"
        }
        t("المسجّل", "عدم اقتطاع النص الطويل") {
            val m = "SC_L_" + System.nanoTime()
            Diag.i("فحص", "S".repeat(30000) + m)
            need(Diag.contains("S".repeat(1000) + m), "النص الطويل اقتُطع")
            "30000 حرف سليمة"
        }
        t("المسجّل", "المنفّذ يسجّل استثناء المهمة") {
            val m = "SC_P_" + System.nanoTime()
            val pool = DiagPool("selfcheck", 1, 1, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>())
            try {
                val f = pool.submit(Callable<Unit> { throw IllegalStateException(m) })
                try { f.get(5, TimeUnit.SECONDS) } catch (e: ExecutionException) { Diag.swallow("SelfCheck.kt pool-test (متوقع)", e) }
                need(waitFor(4000) { Diag.contains("استثناء غير ملتقط داخل المنفّذ selfcheck") && Diag.contains(m) }, "المنفّذ لم يسجّل الاستثناء")
            } finally { pool.shutdownNow() }
            "سُجّل"
        }
        t("المسجّل", "مراقب ANR يعمل") {
            val alive = Thread.getAllStackTraces().keys.any { it.name == "webo-watch" && it.isAlive }
            need(alive, "خيط webo-watch غير موجود أو متوقف")
            "حيّ"
        }
        t("المسجّل", "تتبّع التحميل dlTrace") {
            val m = "SC_T_" + System.nanoTime()
            val r = onUi {
                val ctl = Ctl()
                val row = Row(android.widget.ProgressBar(a), android.widget.TextView(a), android.view.View(a), android.widget.TextView(a), android.widget.TextView(a), ctl, android.view.View(a))
                row.id = "sc" + ts
                row.url = "http://selfcheck.invalid/" + m
                ctl.bytes.set(1234)
                row
            }
            dlTrace("فحص-" + m, r)
            need(Diag.contains("فحص-" + m) && Diag.contains("بايتات=1234") && Diag.contains(r.url), "سطر التتبّع ناقص")
            "تم"
        }

        // ---------- 2. التخزين ----------
        t("التخزين", "كتابة وقراءة وحذف ملف داخلي") {
            workDir.mkdirs()
            val f = File(workDir, "rw.bin")
            val d = pat(262144, 3)
            f.writeBytes(d)
            need(f.readBytes().contentEquals(d), "المحتوى المقروء مختلف")
            need(f.delete() && !f.exists(), "فشل الحذف")
            "256KB"
        }
        t("التخزين", "المساحة الحرة") {
            val free = a.filesDir.usableSpace
            need(free > 10L * 1048576, "المساحة الحرة أقل من 10MB: " + free)
            (free / 1048576).toString() + " MB"
        }
        t("التخزين", "مجلد العمل الخارجي وrenameTo") {
            val base = a.getExternalFilesDir(null) ?: a.filesDir
            val d = File(base, "selfcheck_" + ts)
            need(d.mkdirs() || d.isDirectory, "تعذر إنشاء المجلد " + d)
            val x = File(d, "a.tmp")
            val y = File(d, "a.part")
            x.writeBytes(pat(1000, 1))
            need(x.renameTo(y), "فشل renameTo داخل " + d)
            need(y.length() == 1000L, "الحجم بعد النقل خاطئ")
            d.deleteRecursively()
            d.absolutePath
        }
        t("التخزين", "الحفظ في مجلد التحميلات وقراءته وحذفه") {
            val name = "webo_selfcheck_" + ts + ".txt"
            val body = "WEBO selfcheck " + ts
            val loc = FileUtil.saveToDownloads(a, ByteArrayInputStream(body.toByteArray()), name, "text/plain")
            try {
                val back = if (loc.startsWith("content:")) a.contentResolver.openInputStream(android.net.Uri.parse(loc))!!.use { String(it.readBytes()) } else File(loc).readText()
                eq(back, body, "محتوى الملف المحفوظ")
            } finally {
                try {
                    if (loc.startsWith("content:")) a.contentResolver.delete(android.net.Uri.parse(loc), null, null) else File(loc).delete()
                } catch (e: Throwable) { Diag.swallow("SelfCheck.kt cleanup downloads", e) }
            }
            loc
        }
        t("التخزين", "الأدوات: mimeOf وfmtSize") {
            eq(FileUtil.mimeOf("a.mp4"), "video/mp4", "mimeOf(mp4)")
            eq(FileUtil.fmtSize(1048576L), "1.0 MB", "fmtSize")
            "سليمة"
        }
        t("التخزين", "الإعدادات (SharedPreferences)") {
            val k = "selfcheck_probe"
            need(a.prefs().edit().putString(k, ts).commit(), "فشل commit")
            eq(a.prefs().getString(k, null), ts, "القيمة المقروءة")
            a.prefs().edit().remove(k).commit()
            "سليمة"
        }

        // ---------- 3. حفظ المهام واسترجاعها ----------
        t("المهام", "معرّف المهمة ثابت وفريد") {
            val x = TaskStore.id("https://a/1.m3u8")
            eq(TaskStore.id("https://a/1.m3u8"), x, "ثبات المعرّف")
            need(x != TaskStore.id("https://a/2.m3u8"), "رابطان مختلفان أعطيا المعرّف نفسه")
            eq(x.length, 20, "طول المعرّف")
            x
        }
        t("المهام", "حفظ مهمة وصورتها واسترجاعها وحذفها") {
            val url = "http://selfcheck.invalid/task_" + ts + ".m3u8"
            val id = TaskStore.id(url)
            val meta = org.json.JSONObject().put("k", "v")
            val rec = TaskRec(id, url, mapOf("Referer" to "http://r/", "User-Agent" to "UA"), "عنوان", "selfcheck.invalid", meta,
                3, 5, 10, 12345L, "رسالة", 2, "HLS", 1, "content://x", null, false, true, "x.mp4", "video/mp4", 111L, 222L, "https://p/")
            try {
                TaskStore.save(a, rec)
                val bm = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
                TaskStore.saveThumb(a, id, bm)
                val got = TaskStore.load(a).firstOrNull { it.id == id } ?: throw AssertionError("المهمة المحفوظة لم تُسترجع")
                eq(got.url, url, "url"); eq(got.state, 3, "state"); eq(got.done, 5, "done"); eq(got.total, 10, "total")
                eq(got.bytes, 12345L, "bytes"); eq(got.msg, "رسالة", "msg"); eq(got.kindFmt, "HLS", "kindFmt")
                eq(got.finalLoc, "content://x", "finalLoc"); eq(got.fname, "x.mp4", "fname"); eq(got.pageUrl, "https://p/", "pageUrl")
                eq(got.hdr["Referer"], "http://r/", "hdr.Referer"); eq(got.meta?.optString("k"), "v", "meta")
                need(got.thumbBmp != null, "الصورة المصغّرة لم تُسترجع")
            } finally {
                TaskStore.delete(a, id)
            }
            need(TaskStore.load(a).none { it.id == id }, "المهمة ما زالت موجودة بعد الحذف")
            "حفظ/استرجاع/حذف سليم"
        }

        // ---------- 4. التبويبات والواجهة ----------
        t("التبويبات", "فتح تبويب جديد وإغلاقه وإرجاع الحالة") {
            onUi {
                val before = a.cur
                val n0 = a.tabs.size
                a.newTab("")
                eq(a.tabs.size, n0 + 1, "عدد التبويبات بعد الفتح")
                val nt = a.tabs.last()
                need(a.cur === nt, "التبويب الجديد ليس الحالي")
                a.closeTab(nt)
                eq(a.tabs.size, n0, "عدد التبويبات بعد الإغلاق")
                need(!a.tabs.contains(nt), "التبويب ما زال في القائمة")
                if (before != null && a.tabs.contains(before)) a.switchTo(before)
                "من " + n0 + " إلى " + (n0 + 1) + " ثم " + a.tabs.size
            }
        }
        t("التبويبات", "WebView حقيقي: أخطاء JS وكونسول والجسر") {
            val m = System.nanoTime().toString()
            val web = onUi {
                val w = a.makeWeb()
                w.loadDataWithBaseURL("http://selfcheck.local/", "<html><body><script>try{TDL.dg('SCDG_" + m + "');}catch(e){}console.error('SCCON_" + m + "');throw new Error('SCJS_" + m + "');</script></body></html>", "text/html", "utf-8", null)
                w
            }
            try {
                need(waitFor(10000) { Diag.contains("SCDG_" + m) }, "الجسر TDL.dg لم يسجّل (جافاسكربت-مبتلع)")
                need(waitFor(5000) { Diag.contains("SCCON_" + m) }, "console.error لم يُسجَّل")
                need(waitFor(5000) { Diag.contains("SCJS_" + m) }, "الخطأ غير الملتقط في الصفحة لم يُسجَّل")
            } finally {
                onUi { try { web.destroy() } catch (e: Throwable) { Diag.swallow("SelfCheck.kt web.destroy", e) } }
            }
            "الثلاثة سُجّلت"
        }

        // ---------- 5. الروابط ----------
        t("الروابط", "كشف HLS وDASH والنوع") {
            need(a.isHls("https://x/a.m3u8?t=1"), "m3u8")
            need(a.isHls("https://x/a?type=application/vnd.apple.mpegurl"), "mpegurl")
            need(!a.isHls("https://x/a.mp4"), "mp4 ليس HLS")
            need(a.isDash("https://x/a.mpd?x=1"), "mpd")
            need(!a.isDash("https://x/a.m3u8"), "m3u8 ليس DASH")
            eq(PlayerCore.kindOf("https://x/a.m3u8", ""), "HLS", "kindOf m3u8")
            eq(PlayerCore.kindOf("https://x/a.mpd#tdlrep=v1", ""), "DASH", "kindOf mpd")
            eq(PlayerCore.kindOf("https://x/a.mp4", ""), "FILE", "kindOf mp4")
            eq(PlayerCore.extOf("https://x/a/b.MP4?x=1#y"), "mp4", "extOf")
            "سليمة"
        }
        t("الروابط", "تطبيع العنوان والمضيف والروابط النسبية") {
            eq(a.fix("example.com"), "https://example.com", "fix(domain)")
            eq(a.fix("http://x.y/a"), "http://x.y/a", "fix(http)")
            need(a.fix("كلمة بحث").startsWith("http"), "نص البحث لم يتحول لرابط بحث")
            eq(a.hostOf("https://www.example.com/a"), "example.com", "hostOf")
            eq(a.absUrl("https://h/a/b.m3u8", "c.ts"), "https://h/a/c.ts", "absUrl نسبي")
            eq(a.absUrl("https://h/a/b.m3u8", "../x.ts"), "https://h/x.ts", "absUrl ..")
            "سليمة"
        }
        t("الروابط", "قاعدة اعتبار الرد فيديو (probeIsVideo)") {
            need(a.probeIsVideo("application/vnd.apple.mpegurl", -1, "https://x/a"), "mpegurl")
            need(a.probeIsVideo("video/mp4", 10, "https://x/a"), "video/mp4")
            need(!a.probeIsVideo("text/html", 5000000, "https://x/a"), "html")
            need(a.probeIsVideo("application/octet-stream", 5000000, "https://h/movie.mp4"), "octet-stream كبير بامتداد فيديو")
            need(!a.probeIsVideo("application/octet-stream", 5000000, "https://h/a.zip"), "zip")
            "سليمة"
        }

        // ---------- 6. HLS ----------
        t("HLS", "قراءة القائمة الرئيسية") {
            val pl = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS=\"avc1.4d401e,mp4a.40.2\",AUDIO=\"aud\"\nlow/index.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=2400000,AVERAGE-BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f\",FRAME-RATE=29.97\nhttps://cdn2.example.com/hi.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\naudio/only.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,CODECS=\"avc1.4d401e\"\nlow/index.m3u8\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"ar\",URI=\"aud/ar.m3u8\"\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"en\",DEFAULT=YES,URI=\"aud/en.m3u8\"\n"
            val vs = a.parseMaster("https://cdn.example.com/v/master.m3u8", pl)
            eq(vs.size, 3, "عدد المسارات بعد حذف المكرر")
            val low = vs.first { it.url == "https://cdn.example.com/v/low/index.m3u8" }
            eq(low.bw, 900000L, "أعلى نطاق للرابط المكرر"); eq(low.w, 640, "w"); eq(low.h, 360, "h")
            val hi = vs.first { it.url == "https://cdn2.example.com/hi.m3u8" }
            eq(hi.avg, 2000000L, "avg"); eq(hi.fps, "29.97", "fps"); eq(hi.h, 720, "h hi")
            need(!vs.first { it.url.endsWith("audio/only.m3u8") }.hasVideo, "مسار الصوت فقط عُدّ فيديو")
            val pick = a.pickHls(vs)!!
            eq(pick.url, if (a.lowQ()) "https://cdn.example.com/v/low/index.m3u8" else "https://cdn2.example.com/hi.m3u8", "المسار المختار")
            eq(a.hlsAudioUrl("https://cdn.example.com/v/master.m3u8", pl, "aud"), "https://cdn.example.com/v/aud/en.m3u8", "مسار الصوت الافتراضي")
            "3 مسارات"
        }
        t("HLS", "المتجه الابتدائي IV") {
            val b = a.makeIv(null, 5)
            eq(b.size, 16, "الطول"); eq(b[15].toInt(), 5, "آخر بايت")
            val h = a.makeIv("0A", 0)
            eq(h[15].toInt(), 10, "IV سداسي")
            "سليم"
        }
        t("HLS", "تنزيل فعلي: قائمة عادية من خادم محلي") {
            val s = srv()
            val segs = (0 until 3).map { pat(1000 * (it + 1), it) }
            segs.forEachIndexed { i, d -> s.put("/h/s" + i + ".ts", d) }
            s.putText("/h/media.m3u8", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4,\ns0.ts\n#EXTINF:4,\ns1.ts\n#EXTINF:4,\ns2.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
            val w = File(workDir, "hls1"); w.mkdirs()
            val ctl = Ctl()
            val last = java.util.concurrent.atomic.AtomicInteger(0)
            val job = a.hls(s.base() + "/h/media.m3u8", emptyMap(), w, ctl) { d, _ -> last.accumulateAndGet(d) { x, y -> maxOf(x, y) } }
            eq(job.parts.size, 3, "عدد الأجزاء")
            for (i in 0 until 3) need(job.parts[i].readBytes().contentEquals(segs[i]), "محتوى الجزء " + i + " مختلف")
            need(ctl.bytes.get() >= 6000, "عدّاد البايتات لم يزد: " + ctl.bytes.get())
            eq(last.get(), 3, "آخر تقدّم")
            "3 أجزاء، " + ctl.bytes.get() + " بايت"
        }
        t("HLS", "تنزيل فعلي: تشفير AES-128 وفكّه") {
            val s = srv()
            val key = pat(16, 99)
            val iv = pat(16, 7)
            val plain = (0 until 2).map { pat(3000 + it * 17, 50 + it) }
            s.put("/e/key.bin", key)
            plain.forEachIndexed { i, d ->
                val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                s.put("/e/s" + i + ".ts", c.doFinal(d))
            }
            val ivHex = iv.joinToString("") { "%02x".format(it) }
            s.putText("/e/enc.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x" + ivHex + "\n#EXTINF:4,\ns0.ts\n#EXTINF:4,\ns1.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
            val w = File(workDir, "hls2"); w.mkdirs()
            val job = a.hls(s.base() + "/e/enc.m3u8", emptyMap(), w, Ctl()) { _, _ -> }
            for (i in 0 until 2) need(job.parts[i].readBytes().contentEquals(plain[i]), "الجزء " + i + " بعد فك التشفير لا يطابق الأصل")
            "فُكّ بنجاح"
        }
        t("HLS", "تنزيل فعلي: قائمة رئيسية مع مسار صوت منفصل") {
            val s = srv()
            s.put("/m/lo0.ts", pat(500, 1)); s.put("/m/hi0.ts", pat(700, 2)); s.put("/m/au0.ts", pat(300, 3)); s.put("/m/au1.ts", pat(310, 4))
            s.putText("/m/lo.m3u8", "#EXTM3U\n#EXTINF:4,\nlo0.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
            s.putText("/m/hi.m3u8", "#EXTM3U\n#EXTINF:4,\nhi0.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
            s.putText("/m/au.m3u8", "#EXTM3U\n#EXTINF:4,\nau0.ts\n#EXTINF:4,\nau1.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
            s.putText("/m/master.m3u8", "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"g\",NAME=\"en\",DEFAULT=YES,URI=\"au.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=320x180,CODECS=\"avc1.4d401e\",AUDIO=\"g\"\nlo.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f\",AUDIO=\"g\"\nhi.m3u8\n", "application/vnd.apple.mpegurl")
            val w = File(workDir, "hls3"); w.mkdirs()
            val job = a.hls(s.base() + "/m/master.m3u8", emptyMap(), w, Ctl()) { _, _ -> }
            val low = a.lowQ()
            eq(job.parts.size, 1, "أجزاء الصورة")
            eq(job.parts[0].length(), if (low) 500L else 700L, "حجم جزء الصورة (المسار المختار)")
            val au = job.audio ?: throw AssertionError("مسار الصوت المنفصل لم يُحمَّل")
            eq(au.parts.size, 2, "أجزاء الصوت")
            "الصورة + الصوت"
        }

        // ---------- 7. DASH ----------
        t("DASH", "المدة والقالب") {
            eq(a.isoDur("PT1H2M3.5S"), 3723.5, "isoDur")
            eq(a.isoDur("P1DT0S"), 86400.0, "isoDur يوم")
            eq(a.dashFill("\$RepresentationID\$_\$Number%05d\$_\$Time\$_\$Bandwidth\$", "v1", 500L, 7L, 900L), "v1_00007_900_500", "dashFill")
            "سليمة"
        }
        t("DASH", "قراءة MPD: مسارات وأجزاء ومدة") {
            val xml = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT12S\"><Period>" +
                "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\"><SegmentTemplate timescale=\"1000\" initialization=\"v/\$RepresentationID\$/init.mp4\" media=\"v/\$RepresentationID\$/\$Number\$.m4s\" startNumber=\"1\"><SegmentTimeline><S t=\"0\" d=\"4000\" r=\"2\"/></SegmentTimeline></SegmentTemplate>" +
                "<Representation id=\"v1\" bandwidth=\"800000\" width=\"640\" height=\"360\" codecs=\"avc1.4d401e\"/><Representation id=\"v2\" bandwidth=\"2000000\" width=\"1280\" height=\"720\" codecs=\"avc1.4d401f\"/></AdaptationSet>" +
                "<AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\" lang=\"en\"><SegmentTemplate timescale=\"1000\" initialization=\"a/init.mp4\" media=\"a/\$Number\$.m4s\"><SegmentTimeline><S d=\"4000\" r=\"2\"/></SegmentTimeline></SegmentTemplate>" +
                "<Representation id=\"a1\" bandwidth=\"128000\" codecs=\"mp4a.40.2\"/></AdaptationSet></Period></MPD>"
            val info = a.parseMpd("https://cdn.example.com/dash/m.mpd", xml)
            need(!info.live && !info.drm, "live/drm خاطئ")
            eq(info.dur, 12.0, "المدة"); eq(info.video.size, 2, "عدد الفيديو"); eq(info.audio.size, 1, "عدد الصوت")
            val v2 = info.video.first { it.id == "v2" }
            eq(v2.segs.size, 3, "أجزاء v2"); eq(v2.segs[2], "https://cdn.example.com/dash/v/v2/3.m4s", "رابط الجزء 3"); eq(v2.init, "https://cdn.example.com/dash/v/v2/init.mp4", "init")
            eq(info.audio[0].segs[0], "https://cdn.example.com/dash/a/1.m4s", "رابط صوت")
            eq(a.pickDashVideo(info.video)?.id, if (a.lowQ()) "v1" else "v2", "اختيار الفيديو")
            eq(a.pickDashAudio(info.audio)?.id, "a1", "اختيار الصوت")
            "2 فيديو + 1 صوت"
        }
        t("DASH", "كشف DRM وملف واحد BaseURL") {
            val drm = a.parseMpd("https://x/m.mpd", "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period duration=\"PT4S\"><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\"><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/><Representation id=\"r\" bandwidth=\"1\" height=\"10\"><BaseURL>video.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>")
            need(drm.drm, "ContentProtection لم يُكشف")
            eq(drm.video.firstOrNull()?.single, "https://x/video.mp4", "الملف الواحد")
            "سليمة"
        }
        t("DASH", "تنزيل فعلي: صورة وصوت من خادم محلي") {
            val s = srv()
            s.put("/d/v/init.mp4", pat(100, 1)); s.put("/d/a/init.mp4", pat(50, 2))
            val vseg = (1..2).map { pat(400 + it, 10 + it) }
            val aseg = (1..2).map { pat(200 + it, 20 + it) }
            vseg.forEachIndexed { i, d -> s.put("/d/v/" + (i + 1) + ".m4s", d) }
            aseg.forEachIndexed { i, d -> s.put("/d/a/" + (i + 1) + ".m4s", d) }
            s.putText("/d/m.mpd", "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" mediaPresentationDuration=\"PT8S\"><Period>" +
                "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\"><SegmentTemplate timescale=\"1\" initialization=\"v/init.mp4\" media=\"v/\$Number\$.m4s\"><SegmentTimeline><S d=\"4\" r=\"1\"/></SegmentTimeline></SegmentTemplate><Representation id=\"v\" bandwidth=\"1000\" width=\"320\" height=\"180\" codecs=\"avc1.4d401e\"/></AdaptationSet>" +
                "<AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\"><SegmentTemplate timescale=\"1\" initialization=\"a/init.mp4\" media=\"a/\$Number\$.m4s\"><SegmentTimeline><S d=\"4\" r=\"1\"/></SegmentTimeline></SegmentTemplate><Representation id=\"a\" bandwidth=\"100\" codecs=\"mp4a.40.2\"/></AdaptationSet></Period></MPD>", "application/dash+xml")
            val w = File(workDir, "dash1"); w.mkdirs()
            val job = a.dash(s.base() + "/d/m.mpd", emptyMap(), w, Ctl()) { _, _ -> }
            val ev = pat(100, 1) + vseg[0] + vseg[1]
            val ea = pat(50, 2) + aseg[0] + aseg[1]
            need(job.v.readBytes().contentEquals(ev), "ملف الصورة المجمّع لا يطابق (init + أجزاء)")
            need(job.a != null && job.a!!.readBytes().contentEquals(ea), "ملف الصوت المجمّع لا يطابق")
            "صورة " + job.v.length() + "B + صوت " + job.a!!.length() + "B"
        }

        // ---------- 8. المشغّل وDRM ----------
        t("المشغّل", "فحص القوائم: HLS وDASH والحماية") {
            val s = srv()
            s.putText("/p/clear.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\ns.ts\n", "application/vnd.apple.mpegurl")
            s.putText("/p/wv.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\",URI=\"data:text/plain;base64,AA==\"\n#EXTINF:4,\ns.ts\n", "application/vnd.apple.mpegurl")
            s.putText("/p/clear.mpd", "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period/></MPD>", "application/dash+xml")
            s.putText("/p/prot.mpd", "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period><AdaptationSet><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/></AdaptationSet></Period></MPD>", "application/dash+xml")
            val p1 = PlayerCore.probe(s.base() + "/p/clear.m3u8", emptyMap(), "")
            eq(p1.kind, "HLS", "kind HLS"); eq(p1.prot, 0, "AES-128 العادي ليس DRM")
            val p2 = PlayerCore.probe(s.base() + "/p/wv.m3u8", emptyMap(), "")
            eq(p2.prot, 1, "Widevine HLS محمي"); need(p2.widevine, "Widevine لم يُكشف")
            val p3 = PlayerCore.probe(s.base() + "/p/clear.mpd", emptyMap(), "")
            eq(p3.kind, "DASH", "kind DASH"); eq(p3.prot, 0, "MPD بلا حماية")
            val p4 = PlayerCore.probe(s.base() + "/p/prot.mpd", emptyMap(), "")
            eq(p4.prot, 1, "MPD محمي"); need(p4.widevine, "Widevine MPD")
            "سليم"
        }
        t("المشغّل", "تهيئة Media3 ExoPlayer الحقيقي") {
            onUi {
                val p = androidx.media3.exoplayer.ExoPlayer.Builder(a).build()
                try {
                    eq(p.playbackState, androidx.media3.common.Player.STATE_IDLE, "حالة المشغّل الأولى")
                } finally { p.release() }
                "STATE_IDLE ثم release"
            }
        }
        t("المشغّل", "دعم Widevine في الجهاز") {
            val wv = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
            val ok = android.media.MediaDrm.isCryptoSchemeSupported(wv)
            if (ok) "Widevine مدعوم" else "Widevine غير مدعوم على هذا الجهاز (معلومة وليست فشلاً)"
        }
        t("المشغّل", "تخزين DRM: القائمة والكاش") {
            val n = OfflineDrm.list(a).size
            val sp = OfflineDrm.cache(a).cacheSpace
            "عناصر محفوظة=" + n + " | حجم الكاش=" + sp
        }

        // ---------- 9. الصلاحيات ----------
        t("الصلاحيات", "الصلاحيات المعلنة في المانيفست") {
            val req = a.packageManager.getPackageInfo(a.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions?.toList() ?: emptyList()
            for (p in listOf("INTERNET", "ACCESS_NETWORK_STATE", "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "MODIFY_AUDIO_SETTINGS"))
                need(req.contains("android.permission." + p), "الصلاحية غير معلنة: " + p)
            req.size.toString() + " صلاحية معلنة"
        }
        t("الصلاحيات", "حالة المنح الفعلية") {
            fun g(p: String) = a.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            need(g(android.Manifest.permission.INTERNET), "INTERNET غير ممنوحة")
            val sb = StringBuilder()
            sb.append("كاميرا=").append(g(android.Manifest.permission.CAMERA))
            sb.append(" | ميكروفون=").append(g(android.Manifest.permission.RECORD_AUDIO))
            sb.append(" | موقع=").append(g(android.Manifest.permission.ACCESS_FINE_LOCATION))
            if (android.os.Build.VERSION.SDK_INT <= 28) {
                val w = g(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                need(w, "WRITE_EXTERNAL_STORAGE مطلوبة على هذا الإصدار (API<=28) وغير ممنوحة")
                sb.append(" | تخزين=").append(w)
            }
            sb.toString() + "  (الكاميرا والميكروفون والموقع تُطلب عند الحاجة، فعدم منحها ليس فشلاً)"
        }

        // ---------- الحالة الجذرية: التبويبات المحفوظة، المعترضات، المهام مقابل الملفات ----------
        t("الحالة الجذرية", "حفظ التبويبات وإرجاعها (saveTabsPrefs)") {
            onUi {
                need(!a.restoringTabs, "restoringTabs=true: حفظ التبويبات معطّل الآن")
                fun snap(): Triple<List<String>, Int, Int> {
                    a.saveTabsPrefs()
                    val urls = (a.prefs().getString("tabs_urls", "") ?: "").split("\n")
                    return Triple(urls, a.prefs().getInt("tabs_cur", -1), (a.prefs().getString("tabs_starts", "") ?: "").split(",").size)
                }
                val s0 = snap()
                eq(s0.first.size, a.tabs.size, "عدد الروابط المحفوظة = عدد التبويبات")
                eq(s0.third, a.tabs.size, "عدد tabs_starts")
                eq(s0.second, a.tabs.indexOf(a.cur).coerceAtLeast(0), "فهرس التبويب الحالي")
                val before = a.cur
                a.newTab("")
                val nt = a.tabs.last()
                val s1 = snap()
                eq(s1.first.size, s0.first.size + 1, "بعد فتح تبويب")
                eq(s1.second, a.tabs.indexOf(a.cur), "التبويب الحالي بعد الفتح")
                a.closeTab(nt)
                if (before != null && a.tabs.contains(before)) a.switchTo(before)
                val s2 = snap()
                eq(s2.first, s0.first, "الحالة بعد الإغلاق تطابق الأصل")
                eq(s2.second, s0.second, "الفهرس بعد الإغلاق")
                "محفوظ ومسترجع: " + s2.first.size + " تبويب"
            }
        }
        t("الحالة الجذرية", "معترض الروابط الفعلي (shouldOverrideUrlLoading)") {
            if (android.os.Build.VERSION.SDK_INT < 26) return@t "تخطّي: getWebViewClient يتطلب API 26 (الجهاز " + android.os.Build.VERSION.SDK_INT + ")"
            val web = onUi { a.makeWeb() }
            try {
                val cl = web.webViewClient
                fun req(u: String, main: Boolean) = object : android.webkit.WebResourceRequest {
                    override fun getUrl(): android.net.Uri = android.net.Uri.parse(u)
                    override fun isForMainFrame(): Boolean = main
                    override fun isRedirect(): Boolean = false
                    override fun hasGesture(): Boolean = false
                    override fun getMethod(): String = "GET"
                    override fun getRequestHeaders(): Map<String, String> = emptyMap()
                }
                need(!cl.shouldOverrideUrlLoading(web, req("https://selfcheck.invalid/a", true)), "https يجب أن يُحمَّل داخل التطبيق")
                need(!cl.shouldOverrideUrlLoading(web, req("data:text/html,x", true)), "data: يجب أن يُحمَّل داخلياً")
                need(!cl.shouldOverrideUrlLoading(web, req("blob:https://selfcheck.invalid/x", true)), "blob: يجب أن يُحمَّل داخلياً")
                need(cl.shouldOverrideUrlLoading(web, req("webo-selfcheck-scheme://x", true)), "مخطط غير معروف يجب أن يُعالَج خارجياً (true) بلا انهيار")
                val plain = cl.shouldInterceptRequest(web, req("https://selfcheck.invalid/page.html", true))
                need(plain == null, "صفحة عادية يجب أن تمر (null)")
                if (a.adBlock) {
                    val host = a.adHosts.first()
                    val r = cl.shouldInterceptRequest(web, req("https://" + host + "/x.js", false))
                    need(r != null, "رابط إعلان لم يُحجب (host=" + host + ")")
                    eq(r!!.data.readBytes().size, 0, "جسم الرد المحجوب")
                    "https/data/blob داخلي، مخطط غريب خارجي، إعلان محجوب (" + host + ")، صفحة تمر"
                } else "المعترض سليم؛ حجب الإعلانات معطّل في الإعدادات فلم يُختبر"
            } finally {
                onUi { try { web.destroy() } catch (e: Throwable) { Diag.swallow("SelfCheck.kt web.destroy 2", e) } }
            }
        }
        t("الحالة الجذرية", "المهام المحفوظة مقابل الملفات على القرص") {
            val base = a.getExternalFilesDir(null) ?: a.filesDir
            val tasks = TaskStore.load(a)
            val ids = tasks.map { it.id }.toSet()
            val live = a.liveRows.keys.toSet()
            val dirs = base.listFiles()?.filter { it.isDirectory && it.name.startsWith("work_") } ?: emptyList()
            val orphans = dirs.filter { !ids.contains(it.name.removePrefix("work_")) && !live.contains(it.absolutePath) }
            val leaked = tasks.filter { it.state == 3 && dirs.any { d -> d.name == "work_" + it.id } && !live.contains(File(base, "work_" + it.id).absolutePath) }
            need(leaked.isEmpty(), "مهام مكتملة بقي مجلد عملها: " + leaked.joinToString { it.id + " (" + it.url + ")" })
            var missing = 0
            val miss = StringBuilder()
            for (tk in tasks) {
                val loc = tk.finalLoc ?: continue
                val ok = try {
                    if (loc.startsWith("content:")) { a.contentResolver.openInputStream(android.net.Uri.parse(loc))?.use { true } ?: false } else File(loc).exists()
                } catch (e: Throwable) { false }
                if (!ok) { missing++; if (miss.length < 600) miss.append("\n  ").append(tk.id).append(" → ").append(loc) }
            }
            "مهام=" + tasks.size + " | مجلدات عمل=" + dirs.size + " | يتيمة (بلا مهمة)=" + orphans.size + " | ملفات نهائية مفقودة=" + missing + miss
        }

        // ---------- 10. الشبكة ----------
        t("الشبكة", "خادم محلي: اتصال وتحويل HTTP") {
            val s = srv()
            val d = pat(50000, 5)
            s.put("/file.bin", d)
            val c = a.conn(s.base() + "/redir", emptyMap())
            val got = c.inputStream.use { it.readBytes() }
            need(got.contentEquals(d), "الملف بعد التحويل لا يطابق")
            "تحويل 302 ثم 50000B"
        }
        t("الشبكة", "طلب Range جزئي (206)") {
            val s = srv()
            val d = pat(50000, 5)
            s.put("/file.bin", d)
            val c = a.conn(s.base() + "/file.bin", emptyMap(), "bytes=10-19")
            eq(c.responseCode, 206, "الرمز")
            val got = c.inputStream.use { it.readBytes() }
            need(got.contentEquals(d.copyOfRange(10, 20)), "البايتات 10-19 لا تطابق")
            "206"
        }
        t("الشبكة", "رد 404 يُسجَّل برابطه") {
            val s = srv()
            val u = s.base() + "/missing_" + ts
            var thrown = false
            try { a.conn(u, emptyMap()) } catch (e: Exception) { thrown = (e.message ?: "").contains("404") }
            need(thrown, "لم يُرمَ استثناء HTTP 404")
            need(Diag.contains("HTTP 404 | " + u), "رد 404 لم يُسجَّل في المسجّل")
            "سُجّل"
        }
        t("الشبكة", "الجزء الناقص يُرفض ويُسجَّل") {
            val s = srv()
            val u = s.base() + "/short"
            var thrown = false
            var msg = ""
            try { a.segBytes(u, emptyMap()) } catch (e: Throwable) { thrown = true; msg = e.javaClass.simpleName + ": " + (e.message ?: "") }
            need(thrown, "الجزء المقطوع (500 من 1000) قُبل كأنه مكتمل")
            msg
        }
        t("الشبكة", "تنزيل مباشر direct() من خادم محلي") {
            val s = srv()
            val d = pat(50000, 5)
            s.put("/file.bin", d)
            val w = File(workDir, "direct1"); w.mkdirs()
            val out = File(w, "out.mp4")
            val ctl = Ctl()
            a.direct(s.base() + "/file.bin", emptyMap(), out, w, ctl) { _, _ -> }
            need(out.readBytes().contentEquals(d), "الملف الناتج لا يطابق")
            need(Diag.contains("direct: بدء التنزيل المباشر"), "تتبّع بدء التحميل غير موجود")
            eq(ctl.bytes.get(), 50000L, "عدّاد البايتات")
            "50000B"
        }
        t("الشبكة", "الإنترنت الحقيقي (HTTPS)") {
            val c = a.conn("https://connectivitycheck.gstatic.com/generate_204", emptyMap())
            try { eq(c.responseCode, 204, "رمز الرد") } finally { c.disconnect() }
            "204"
        }
        return c
    }

    fun runAll(onEach: (CheckResult) -> Unit, onDone: (List<CheckResult>, String) -> Unit): Boolean {
        if (running) return false
        running = true
        diagThread("selfcheck") {
            val results = ArrayList<CheckResult>()
            val t0 = System.currentTimeMillis()
            try {
                Diag.i("فحص", "بدء الفحص الذاتي | " + ts)
                workDir.mkdirs()
                try {
                    server = SelfServer().also { it.start() }
                } catch (e: Throwable) {
                    Diag.e("فحص", "تعذر تشغيل الخادم المحلي للفحص", e)
                }
                for (cs in cases()) {
                    val s0 = System.currentTimeMillis()
                    val res: CheckResult = try {
                        val d = cs.run()
                        Diag.i("فحص", "نجح | " + cs.group + " › " + cs.name + " | " + d)
                        CheckResult(cs.group, cs.name, true, d, System.currentTimeMillis() - s0)
                    } catch (t: Throwable) {
                        Diag.e("فحص", "فشل | " + cs.group + " › " + cs.name, t)
                        CheckResult(cs.group, cs.name, false, describe(t), System.currentTimeMillis() - s0)
                    }
                    results.add(res)
                    onEach(res)
                }
            } catch (t: Throwable) {
                Diag.e("فحص", "انهيار داخل مشغّل الفحص نفسه", t)
                val r = CheckResult("الفحص", "مشغّل الفحص", false, describe(t), 0)
                results.add(r)
                onEach(r)
            } finally {
                try { server?.stop() } catch (e: Throwable) { Diag.swallow("SelfCheck.kt stop server", e) }
                try { workDir.deleteRecursively() } catch (e: Throwable) { Diag.swallow("SelfCheck.kt cleanup", e) }
                running = false
            }
            val rep = buildReport(results, System.currentTimeMillis() - t0)
            lastResults = results
            lastReport = rep
            Diag.i("فحص", "انتهى الفحص الذاتي | نجح " + results.count { it.ok } + " من " + results.size)
            onDone(results, rep)
        }.start()
        return true
    }

    private fun buildReport(rs: List<CheckResult>, ms: Long): String {
        val sb = StringBuilder()
        sb.append("تقرير الفحص الذاتي | نجح ").append(rs.count { it.ok }).append(" من ").append(rs.size).append(" | ").append(ms).append("ms\n")
        for (r in rs) {
            sb.append(if (r.ok) "[نجح] " else "[فشل] ").append(r.group).append(" › ").append(r.name).append(" (").append(r.ms).append("ms)\n")
            sb.append(r.detail).append("\n\n")
        }
        return sb.toString()
    }
}
