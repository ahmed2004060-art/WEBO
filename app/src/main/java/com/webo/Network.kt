package com.webo

import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

// طلبات الشبكة والفحص والتنزيل المباشر
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.previewDirect(dir: File, pdir: File): File? {
    val mf = File(dir, "moovmeta.txt")
    if (mf.exists() && File(dir, "moov.bin").exists()) {
        val meta = mf.readText().split(",")
        val mdatStart = meta[0].toLong()
        val n = meta[1].toInt()
        val chunk = meta[2].toLong()
        val files = ArrayList<File>()
        var avail = 0L
        for (k in 0 until n) {
            val f = File(dir, "chunk_" + k + ".part")
            if (!f.exists()) break
            files.add(f)
            avail += f.length()
            if (f.length() < chunk) break
        }
        if (avail <= mdatStart + 64) return null
        val moov = File(dir, "moov.bin").readBytes()
        val ftyp = File(dir, "ftyp.bin").readBytes()
        patchMoov(moov, (ftyp.size + moov.size).toLong() - mdatStart)
        pdir.mkdirs()
        val out = File(pdir, "preview.mp4")
        out.outputStream().buffered().use { o ->
            o.write(ftyp)
            o.write(moov)
            var pos = 0L
            for (f in files) {
                val len = f.length()
                if (pos + len > mdatStart) {
                    val startIn = maxOf(0L, mdatStart - pos)
                    java.io.FileInputStream(f).use { i ->
                        i.channel.position(startIn)
                        i.copyTo(o)
                    }
                }
                pos += len
            }
        }
        return out
    }
    val c0 = File(dir, "chunk_0.part")
    if (c0.exists() && c0.length() > 0) return c0
    return dir.listFiles()?.filter { it.name.endsWith(".mp4") && it.length() > 0 }?.maxByOrNull { it.length() }
}

internal fun MainActivity.applyPools() {
    val a = prefs().getInt("anthreads", 16).coerceAtLeast(1)
    val b = prefs().getInt("prthreads", 8).coerceAtLeast(1)
    try { analyzer.maximumPoolSize = maxOf(a, analyzer.corePoolSize); analyzer.corePoolSize = a; analyzer.maximumPoolSize = a } catch (e: Throwable) { Diag.swallow("Network.kt:63 applyPools", e);}
    try { prober.maximumPoolSize = maxOf(b, prober.corePoolSize); prober.corePoolSize = b; prober.maximumPoolSize = b } catch (e: Throwable) { Diag.swallow("Network.kt:64 applyPools", e);}
}

internal fun MainActivity.capAudio() = prefs().getBoolean("capaudio", true)

internal fun MainActivity.capSeg() = prefs().getBoolean("capseg", true)

internal fun MainActivity.showKnownSeg() = prefs().getBoolean("showseg", false)

internal fun MainActivity.customHdrs(): List<Pair<String, String>> =
    prefs().getString("customhdr", "")!!.lines().mapNotNull {
        val i = it.indexOf(':')
        if (i <= 0) null else Pair(it.substring(0, i).trim(), it.substring(i + 1).trim())
    }.filter { it.first.isNotEmpty() }

internal fun MainActivity.probeReq(u: String, h: Map<String, String>, head: Boolean): ProbeRes? {
    var c: HttpURLConnection? = null
    try {
        val k = URL(u).openConnection() as HttpURLConnection
        c = k
        k.connectTimeout = 8000
        k.readTimeout = 8000
        if (head) k.requestMethod = "HEAD"
        for ((a, b) in h) if (b.isNotEmpty() && a.lowercase() !in skipHeaders) k.setRequestProperty(a, b)
        for ((ck, cv) in customHdrs()) k.setRequestProperty(ck, cv)
        if (!head) k.setRequestProperty("Range", "bytes=0-0")
        val code = k.responseCode
        val ct = k.contentType ?: ""
        val len = if (code == 206) (k.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull() ?: -1L
        else k.contentLengthLong
        return ProbeRes(code, ct, len)
    } catch (e: Exception) { Diag.swallow("Network.kt:95 probeReq", e);
        return null
    } finally {
        try { c?.disconnect() } catch (e: Throwable) { Diag.swallow("Network.kt:98 probeReq", e);}
    }
}

internal fun MainActivity.probeIsVideo(ct: String, len: Long, u: String): Boolean {
    val t = ct.lowercase()
    if (t.contains("mpegurl") || t.contains("dash+xml") || t.startsWith("video/") || (t.startsWith("audio/") && capAudio())) return true
    if (t.contains("octet-stream") || t.startsWith("binary/")) {
        val p = u.lowercase().substringBefore('?').substringBefore('#')
        val bad = listOf(".zip", ".apk", ".rar", ".7z", ".exe", ".pdf", ".iso", ".dmg", ".gz", ".tar", ".bin", ".jar", ".torrent")
        if (bad.any { p.endsWith(it) }) return false
        val big = len < 0 || len > 300000L
        return big && (videoExt.any { p.endsWith(it) } || Regex("video|stream|media|play|mp4|movie|film|vod").containsMatchIn(p))
    }
    return false
}

// فحص رأس الرد للطلبات التي لا تبدو ملفات ثابتة: HEAD أولاً، وإن رفضه الخادم نجرب GET ببايت واحد
internal fun MainActivity.probe(u: String, h: Map<String, String>, v: WebView) {
    if (!probed.add(u)) return
    try {
        prober.execute {
            try {
                var r = probeReq(u, h, true)
                if (r == null || r.code !in 200..299) r = probeReq(u, h, false)
                if (r != null && r.code in 200..299 && probeIsVideo(r.ct, r.len, u)) ui.post { consider(u, h, v, "فحص رأس الرد") }
            } catch (e: Throwable) { Diag.swallow("Network.kt:124 probe", e);
            }
        }
    } catch (e: Throwable) { Diag.swallow("Network.kt:127 probe", e);
        probed.remove(u) // الطابور ممتلئ: نسمح بإعادة الفحص لاحقاً بدل أن نخسر الرابط
    }
}

internal fun MainActivity.hdr(m: Map<String, String>, n: String): String? =
    m.entries.firstOrNull { it.key.equals(n, true) }?.value

internal fun <T> MainActivity.retry(n: Int, f: () -> T): T {
    var last: Exception? = null
    for (i in 0 until n) {
        try {
            return f()
        } catch (e: CancelledException) { Diag.swallow("Network.kt:140 retry", e);
            throw e
        } catch (e: Exception) { Diag.swallow("Network.kt:142 retry", e);
            last = e
            try { Thread.sleep(500L * (i + 1)) } catch (ie: InterruptedException) { Diag.swallow("Network.kt:144 retry", ie); throw e }
        }
    }
    throw last!!
}

// نفس الهيدرز (User-Agent / Referer / Cookie ...) لكل طلب، مع متابعة التحويلات يدوياً
internal fun MainActivity.conn(u0: String, h: Map<String, String>, range: String? = null): HttpURLConnection {
    var u = u0
    val maxHop = prefs().getInt("redirects", 30).let { if (it <= 0) 100000 else it }
    for (hop in 0..maxHop) {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = prefs().getInt("conntimeout", 45) * 1000
        c.readTimeout = prefs().getInt("readtimeout", 120) * 1000
        c.instanceFollowRedirects = false
        val sentNames = java.util.TreeSet<String>()
        for ((k, v) in h) {
            if (v.isNotEmpty() && k.lowercase() !in skipHeaders) { c.setRequestProperty(k, v); sentNames.add(k) }
        }
        for ((ck, cv) in customHdrs()) { c.setRequestProperty(ck, cv); sentNames.add(ck) }
        c.setRequestProperty("Accept-Encoding", "identity"); sentNames.add("Accept-Encoding")
        if (range != null) { c.setRequestProperty("Range", range); sentNames.add("Range") }
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location")
            c.disconnect()
            if (loc == null) { Diag.i("شبكة", "تحويل HTTP " + code + " بلا Location | " + u); throw Exception("HTTP $code") }
            u = URL(URL(u), loc).toString()
            continue
        }
        if (code >= 400) {
            if (code == 401 && logged401.add(u0)) {
                Diag.i("رفض-401", diagEsc(u) + "\nترويسات أرسلها التطبيق: " + sentNames.joinToString(", ") +
                    "\nترويسات أرسلتها الصفحة لنفس الرابط: " + (pageHdrNames[u0] ?: pageHdrNames[u] ?: "غير مسجّلة (الطلب لم يمر عبر المعترض)"))
            }
            Diag.i("شبكة", "HTTP " + code + " | " + diagEsc(u) + (if (range != null) " | Range " + range else "") + (if (u != u0) " | (بعد تحويل من " + diagEsc(u0) + ")" else ""))
            c.disconnect()
            throw Exception("HTTP $code")
        }
        return c
    }
    Diag.i("شبكة", "تحويلات كثيرة (> " + maxHop + ") | " + diagEsc(u0) + " → " + diagEsc(u))
    throw Exception("تحويلات كثيرة")
}

internal fun MainActivity.text(u: String, h: Map<String, String>): String =
    conn(u, h).inputStream.bufferedReader().use { it.readText() }

internal fun MainActivity.bytes(u: String, h: Map<String, String>): ByteArray =
    conn(u, h).inputStream.use { it.readBytes() }

// جزء HLS/DASH: لا يُقبل إلا إذا وصل بالحجم الذي أعلنه الخادم (الجزء المقطوع لا يُحفظ كأنه مكتمل)
internal fun MainActivity.segBytes(u: String, h: Map<String, String>): ByteArray {
    val c = conn(u, h)
    try {
        val expect = c.contentLengthLong
        val d = c.inputStream.use { it.readBytes() }
        if (d.isEmpty()) { Diag.i("شبكة", "جزء فارغ | " + u); throw Exception("جزء فارغ") }
        if (expect > 0 && d.size.toLong() != expect) { Diag.i("شبكة", "جزء ناقص: وصل " + d.size + " من " + expect + " | " + u); throw Exception("جزء ناقص: وصل " + d.size + " من " + expect) }
        return d
    } finally {
        try { c.disconnect() } catch (e: Throwable) { Diag.swallow("Network.kt:200 segBytes", e);}
    }
}

internal fun MainActivity.absUrl(base: String, ref: String): String = URL(URL(base), ref).toString()

internal fun MainActivity.direct(u: String, h: Map<String, String>, out: File, work: File, ctl: Ctl, progress: (Int, Int) -> Unit) {
    Diag.i("تحميل", "direct: بدء التنزيل المباشر | out=" + out.absolutePath + " | رؤوس=" + h.size + " | الرابط " + u)
    gate(ctl)
    var total = -1L
    var ranges = false
    try {
        val c = conn(u, h, "bytes=0-0")
        if (c.responseCode == 206) {
            val t = (c.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull()
            if (t != null && t > 0) {
                total = t
                ranges = true
            }
        } else {
            total = c.contentLengthLong
        }
        c.disconnect()
    } catch (e: Exception) { Diag.swallow("Network.kt:222 direct", e);
    }

    // الخادم لا يدعم التجزئة أو الملف صغير: تحميل عادي
    if (!ranges || total < 2000000L) {
        val c = conn(u, h)
        val tl = c.contentLengthLong
        var done = 0L
        c.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    gate(ctl)
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    ctl.bytes.addAndGet(n.toLong())
                    throttle(n)
                    if (tl > 0) progress((done * 100 / tl).toInt(), 100) else progress(0, 0)
                }
            }
        }
        return
    }

    // تحميل متوازٍ: كل جزء من الملف في اتصال مستقل، وكل جزء قابل للاستئناف
    // خوارزمية ذكية: عدد الاتصالات = ما اخترته لكن لا يقل نصيب الاتصال عن 1 م.ب (حتى لا تُهدر اتصالات على ملف صغير)
    val want = prefs().getInt("threads", 8).coerceIn(2, 128)
    val n = minOf(want, maxOf(2, (total / (1024L * 1024)).toInt()))
    val size = (total + n - 1) / n
    val got = java.util.concurrent.atomic.AtomicLong(0)
    prepMoov(u, h, total, work, n, size)
    val files = ArrayList<File>()
    for (k in 0 until n) files.add(File(work, "chunk_" + k + ".part"))
    for (f in files) got.addAndGet(f.length())
    val pool = diagFixed("directParts", n)
    try {
        val fs = (0 until n).map { k ->
            pool.submit(Callable<Unit> {
                val start = k * size
                val end = minOf(total - 1, start + size - 1)
                val f = files[k]
                var tries = 0
                while (true) {
                    gate(ctl)
                    val have = f.length()
                    if (start + have > end) break
                    try {
                        val c = conn(u, h, "bytes=" + (start + have) + "-" + end)
                        if (c.responseCode != 206) throw Exception("الخادم لم يدعم التحميل المتجزئ")
                        c.inputStream.use { i ->
                            java.io.FileOutputStream(f, true).use { o ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    gate(ctl)
                                    val m = i.read(buf)
                                    if (m < 0) break
                                    o.write(buf, 0, m)
                                    val g = got.addAndGet(m.toLong())
                                    ctl.bytes.addAndGet(m.toLong())
                    throttle(m)
                                    progress((g * 100 / total).toInt(), 100)
                                }
                            }
                        }
                    } catch (e: CancelledException) { Diag.swallow("Network.kt:288 direct", e);
                        throw e
                    } catch (e: Exception) { Diag.swallow("Network.kt:290 direct", e);
                        tries++
                        if (tries > 20) throw e
                        Thread.sleep(minOf(600L * tries, 6000L))
                    }
                    if (f.length() == have) {
                        tries++
                        if (tries > 20) throw Exception("توقف وصول البيانات من الخادم")
                        Thread.sleep(minOf(600L * tries, 6000L))
                    }
                }
            })
        }
        for (f in fs) {
            try {
                f.get()
            } catch (e: ExecutionException) { Diag.swallow("Network.kt:306 direct", e);
                val c = e.cause
                if (c is CancelledException) throw c
                throw Exception(c?.message ?: "فشل تحميل جزء من الملف")
            }
        }
    } finally {
        pool.shutdownNow()
    }
    out.outputStream().use { o ->
        for (f in files) f.inputStream().use { it.copyTo(o, 256 * 1024) }
    }
    for (f in files) f.delete()
}
