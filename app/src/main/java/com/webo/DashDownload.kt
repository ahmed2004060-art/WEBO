package com.webo

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// تحليل وتنزيل DASH وأدوات moov
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.isDash(u: String): Boolean {
    val b = u.substringBefore("#tdlrep=")
    return b.lowercase().substringBefore('?').endsWith(".mpd") || dashSet.contains(b)
}

internal fun MainActivity.isoDur(s: String?): Double {
    if (s.isNullOrBlank()) return 0.0
    val m = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?").matchEntire(s.trim()) ?: return 0.0
    val g = m.groupValues
    return (g[1].toDoubleOrNull() ?: 0.0) * 86400 + (g[2].toDoubleOrNull() ?: 0.0) * 3600 +
        (g[3].toDoubleOrNull() ?: 0.0) * 60 + (g[4].toDoubleOrNull() ?: 0.0)
}

internal fun MainActivity.xKids(e: org.w3c.dom.Element, name: String): List<org.w3c.dom.Element> {
    val out = ArrayList<org.w3c.dom.Element>()
    val ns = e.childNodes
    for (i in 0 until ns.length) {
        val n = ns.item(i)
        if (n is org.w3c.dom.Element && n.tagName.substringAfter(':') == name) out.add(n)
    }
    return out
}

internal fun MainActivity.xBase(base: String, e: org.w3c.dom.Element): String {
    val b = xKids(e, "BaseURL").firstOrNull()?.textContent?.trim()
    return if (b.isNullOrEmpty()) base else absUrl(base, b)
}

internal fun MainActivity.dashFill(t: String, id: String, bw: Long, number: Long, time: Long): String {
    var r = t.replace("\$RepresentationID\$", id).replace("\$Bandwidth\$", bw.toString())
    r = Regex("\\\$Number(?:%0(\\d+)d)?\\\$").replace(r) { m ->
        number.toString().padStart(m.groupValues[1].toIntOrNull() ?: 0, '0')
    }
    r = Regex("\\\$Time(?:%0(\\d+)d)?\\\$").replace(r) { m ->
        time.toString().padStart(m.groupValues[1].toIntOrNull() ?: 0, '0')
    }
    return r.replace("\$\$", "\$")
}

internal fun MainActivity.parseMpd(u: String, xml: String): DashInfo {
    val db = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val doc = db.parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    val root = doc.documentElement
    val live = root.getAttribute("type") == "dynamic"
    val total = isoDur(root.getAttribute("mediaPresentationDuration"))
    var drm = false
    val base0 = xBase(u, root)
    var bestV: List<DRep> = emptyList()
    var bestA: List<DRep> = emptyList()
    var bestDur = -1.0
    for (per in xKids(root, "Period")) {
        val pBase = xBase(base0, per)
        val pd0 = isoDur(per.getAttribute("duration"))
        val pd = if (pd0 > 0) pd0 else total
        val vs = ArrayList<DRep>()
        val au = ArrayList<DRep>()
        for (aset in xKids(per, "AdaptationSet")) {
            val aBase = xBase(pBase, aset)
            if (xKids(aset, "ContentProtection").isNotEmpty()) drm = true
            for (rep in xKids(aset, "Representation")) {
                if (xKids(rep, "ContentProtection").isNotEmpty()) drm = true
                val mime = rep.getAttribute("mimeType").ifEmpty { aset.getAttribute("mimeType") }.lowercase()
                val ctype = aset.getAttribute("contentType").lowercase()
                val wd = rep.getAttribute("width").toIntOrNull() ?: aset.getAttribute("width").toIntOrNull() ?: 0
                val ht = rep.getAttribute("height").toIntOrNull() ?: aset.getAttribute("height").toIntOrNull() ?: 0
                val isA = mime.startsWith("audio") || ctype == "audio"
                val isV = mime.startsWith("video") || ctype == "video" || ht > 0
                if (!isA && !isV) continue
                val rid = rep.getAttribute("id")
                val bw = rep.getAttribute("bandwidth").toLongOrNull() ?: 0L
                val rBase = xBase(aBase, rep)
                // القالب قد يتوزع على التمثيل ومجموعة التكيّف والفترة: نقرأ كل خاصية من أقرب مستوى يحددها
                val tmpls = listOfNotNull(
                    xKids(rep, "SegmentTemplate").firstOrNull(),
                    xKids(aset, "SegmentTemplate").firstOrNull(),
                    xKids(per, "SegmentTemplate").firstOrNull()
                )
                val tmpl = tmpls.firstOrNull()
                fun ta(n: String): String = tmpls.map { it.getAttribute(n) }.firstOrNull { it.isNotEmpty() } ?: ""
                var init: String? = null
                val segs = ArrayList<String>()
                var single: String? = null
                if (tmpl != null) {
                    val ts = ta("timescale").toLongOrNull() ?: 1L
                    val sn = ta("startNumber").toLongOrNull() ?: 1L
                    val media = ta("media")
                    val ini = ta("initialization")
                    if (ini.isNotEmpty()) init = absUrl(rBase, dashFill(ini, rid, bw, 0L, 0L))
                    val tl = tmpls.firstNotNullOfOrNull { xKids(it, "SegmentTimeline").firstOrNull() }
                    if (tl != null) {
                        var t = 0L
                        var num = sn
                        var startT = -1L
                        for (s in xKids(tl, "S")) {
                            val st = s.getAttribute("t")
                            if (st.isNotEmpty()) t = st.toLong()
                            if (startT < 0) startT = t
                            val d = s.getAttribute("d").toLongOrNull() ?: continue
                            var rr = s.getAttribute("r").toIntOrNull() ?: 0
                            if (rr < 0) {
                                rr = if (pd > 0 && d > 0) (((pd * ts - (t - startT)) / d).toInt() - 1).coerceAtLeast(0) else 0
                            }
                            for (k in 0..rr) {
                                segs.add(absUrl(rBase, dashFill(media, rid, bw, num, t)))
                                t += d
                                num++
                            }
                        }
                    } else {
                        val d = ta("duration").toLongOrNull() ?: 0L
                        if (d > 0 && pd > 0) {
                            val cnt = Math.ceil(pd * ts / d).toLong()
                            for (k in 0 until cnt) segs.add(absUrl(rBase, dashFill(media, rid, bw, sn + k, k * d)))
                        }
                    }
                } else {
                    val sl = xKids(rep, "SegmentList").firstOrNull() ?: xKids(aset, "SegmentList").firstOrNull()
                    if (sl != null) {
                        val src = xKids(sl, "Initialization").firstOrNull()?.getAttribute("sourceURL") ?: ""
                        if (src.isNotEmpty()) init = absUrl(rBase, src)
                        for (su in xKids(sl, "SegmentURL")) {
                            val m = su.getAttribute("media")
                            if (m.isNotEmpty()) segs.add(absUrl(rBase, m))
                        }
                    } else if (xKids(rep, "BaseURL").isNotEmpty() ||
                        ((xKids(rep, "SegmentBase").isNotEmpty() || xKids(aset, "SegmentBase").isNotEmpty()) && rBase != u)) {
                        // ملف واحد (BaseURL أو SegmentBase بفهرس sidx)
                        single = rBase
                    }
                }
                if (single == null && segs.isEmpty()) continue
                val role = xKids(aset, "Role").firstOrNull()?.getAttribute("value") ?: ""
                val isMain = role.isEmpty() || role == "main"
                val cod = rep.getAttribute("codecs").ifEmpty { aset.getAttribute("codecs") }
                val fr = rep.getAttribute("frameRate").ifEmpty { aset.getAttribute("frameRate") }
                val dr = DRep(rid, bw, wd, ht, init, segs, single, aset.getAttribute("lang"), isMain, cod, fr)
                if (isA && !isV) au.add(dr) else vs.add(dr)
            }
        }
        if (pd > bestDur) {
            bestDur = pd
            bestV = vs
            bestA = au
        }
    }
    return DashInfo(live, if (bestDur > 0) bestDur else total, bestV, bestA, drm)
}

// الصوت: المسار الرئيسي (لا الوصف الصوتي ولا التعليق) بلغة أول مجموعة، ثم الأعلى نطاقاً
internal fun MainActivity.pickDashAudio(a: List<DRep>): DRep? {
    if (a.isEmpty()) return null
    val m = a.filter { it.main }.ifEmpty { a }
    val lang = m.first().lang
    val g = m.filter { it.lang == lang }.ifEmpty { m }
    return if (lowQ()) g.minByOrNull { it.bw } else g.maxByOrNull { it.bw }
}

// الصورة: الأعلى نطاقاً، مع تفضيل H.264 إن كان الأعلى بترميز يصعب دمجه ويوجد H.264 بنفس الارتفاع أو أعلى
internal fun MainActivity.pickDashVideo(v: List<DRep>): DRep? {
    if (v.isEmpty()) return null
    if (lowQ()) return v.minByOrNull { it.bw }
    val best = v.maxByOrNull { it.bw }!!
    val isAvc = { r: DRep -> r.codecs.isEmpty() || r.codecs.lowercase().startsWith("avc") }
    if (isAvc(best)) return best
    return v.filter { isAvc(it) && it.h >= best.h }.maxByOrNull { it.bw } ?: best
}

internal fun MainActivity.dashVariants(u: String, h: Map<String, String>): List<Variant> {
    val base = u.substringBefore("#tdlrep=")
    val info = parseMpd(base, text(base, h))
    val ab = pickDashAudio(info.audio)?.bw ?: 0L
    return info.video.sortedByDescending { it.bw }.map {
        val res = if (it.h > 0) it.h.toString() + "p" else ""
        val fr = it.fps.substringBefore('/').toDoubleOrNull()?.let { f -> if (f > 0) Math.round(f).toString() + "fps" else "" } ?: ""
        val ct = codecTag(it.codecs)
        val label = (if (res.isNotEmpty()) res else "جودة") + (if (fr.isNotEmpty()) " " + fr else "") +
            (if (ct.isNotEmpty()) " " + ct else "") + " • " + (it.bw / 1000) + " kbps"
        Variant(label, base + "#tdlrep=" + it.id, it.bw + ab, res)
    }
}

internal fun MainActivity.dashRep(r: DRep, tag: String, work: File, h: Map<String, String>, ctl: Ctl,
                    done: AtomicInteger, total: Int, progress: (Int, Int) -> Unit): File {
    val out = File(work, "dash_$tag.mp4")
    if (r.single != null) {
        val sub = File(work, tag)
        sub.mkdirs()
        direct(r.single, h, out, sub, ctl) { d, t -> progress(d, t) }
        return out
    }
    val iniF = r.init?.let { iu ->
        val f = File(work, tag + "_init.mp4")
        if (!(f.exists() && f.length() > 0)) f.writeBytes(retry(3) { segBytes(iu, h) })
        f
    }
    val parts = r.segs.indices.map { File(work, tag + "_%05d.seg".format(it)) }
    val pool = diagFixed("dashSegments", prefs().getInt("threads", 8).coerceIn(1, 128))
    try {
        val fs = r.segs.mapIndexed { i, su ->
            pool.submit(Callable<Unit> {
                gate(ctl)
                val part = parts[i]
                if (!(part.exists() && part.length() > 0)) {
                    val d = retry(5) { gate(ctl); segBytes(su, h) }
                    ctl.bytes.addAndGet(d.size.toLong())
                    throttle(d.size)
                    val tmp = File(work, tag + "_%05d.tmp".format(i))
                    tmp.writeBytes(d)
                    if (!tmp.renameTo(part)) {
                        Diag.i("تخزين", "DASH: فشل renameTo " + tmp.absolutePath + " ← " + part.absolutePath + " | مساحة حرة " + (work.usableSpace / 1048576) + " MB")
                        part.writeBytes(d)
                        tmp.delete()
                    }
                }
                progress(done.incrementAndGet(), total)
            })
        }
        for (f in fs) {
            try {
                f.get()
            } catch (e: ExecutionException) { Diag.swallow("DashDownload.kt:241 dashRep", e);
                val c = e.cause
                if (c is CancelledException) throw c
                throw Exception(c?.message ?: "فشل تحميل جزء")
            }
        }
    } finally {
        pool.shutdownNow()
    }
    out.outputStream().buffered().use { o ->
        iniF?.inputStream()?.use { it.copyTo(o) }
        for (p in parts) p.inputStream().use { it.copyTo(o) }
    }
    return out
}

internal fun MainActivity.dash(u0: String, h: Map<String, String>, work: File, ctl: Ctl, progress: (Int, Int) -> Unit): DashJob {
    Diag.i("تحميل", "dash: بدء تحليل وتنزيل التمثيلات | عمل=" + work.absolutePath + " | الرابط " + u0)
    val base = u0.substringBefore("#tdlrep=")
    val want = u0.substringAfter("#tdlrep=", "")
    val info = parseMpd(base, text(base, h))
    if (info.drm && !prefs().getBoolean("ignoredrm", false)) throw ProtectedException("الفيديو يعلن حماية DRM فلا يمكن تنزيله كملف قابل للتشغيل. اضغط «▶ مشاهدة» لتجربة تشغيله مباشرة بنظام الحماية الرسمي إن توفّر الترخيص. (للتنزيل الخام رغم ذلك فعّل «محاولة التحميل رغم علامة DRM» من الإعدادات)")
    val v = info.video.firstOrNull { it.id == want }
        ?: pickDashVideo(info.video)
    val a = pickDashAudio(info.audio)
    if (v == null && a == null) throw Exception("لا توجد مسارات فيديو أو صوت في الملف")
    val total = (v?.segs?.size ?: 0) + (a?.segs?.size ?: 0)
    val done = AtomicInteger(0)
    // الصورة والصوت معاً بالتوازي حتى تعمل المشاهدة أثناء التنزيل بصوت وصورة
    var vf: File? = null
    var af: File? = null
    if (v != null && a != null) {
        val ex = diagSingle("dashAudio")
        val fut = ex.submit(Callable<File> { dashRep(a, "da", work, h, ctl, done, total, progress) })
        try {
            vf = dashRep(v, "dv", work, h, ctl, done, total, progress)
        } catch (e: Throwable) { Diag.swallow("DashDownload.kt:276 dash", e);
            fut.cancel(true)
            ex.shutdownNow()
            throw e
        }
        try {
            af = fut.get()
        } catch (e: ExecutionException) { Diag.swallow("DashDownload.kt:283 dash", e);
            val c = e.cause
            if (c is CancelledException) throw c
            throw Exception(c?.message ?: "فشل تحميل الصوت")
        } finally {
            ex.shutdownNow()
        }
    } else {
        vf = v?.let { dashRep(it, "dv", work, h, ctl, done, total, progress) }
        af = a?.let { dashRep(it, "da", work, h, ctl, done, total, progress) }
    }
    return DashJob(vf ?: af!!, if (vf != null) af else null, info.drm)
}

// دمج مسار الصورة ومسار الصوت في ملف mp4 واحد: mp4parser أولاً، وMediaMuxer كبديل
internal fun MainActivity.mergeDash(job: DashJob, work: File, name: String): File {
    Diag.i("تحميل", "dash: بدء الدمج | اسم=" + name + " | عمل=" + work.absolutePath)
    val out = File(work, "$name.mp4")
    try {
        val movie = Movie()
        for (t in MovieCreator.build(job.v.absolutePath).tracks) movie.addTrack(t)
        val a = job.a
        if (a != null) {
            for (t in MovieCreator.build(a.absolutePath).tracks) if (t.handler == "soun") movie.addTrack(t)
        }
        if (movie.tracks.isEmpty()) throw Exception("لا توجد مسارات")
        val c = DefaultMp4Builder().build(movie)
        FileOutputStream(out).use { fo -> c.writeContainer(fo.channel) }
        return out
    } catch (e: Throwable) { Diag.swallow("DashDownload.kt:311 mergeDash", e);
        out.delete()
        logErr("دمج", "فشل دمج DASH عبر mp4parser، جاري تجربة MediaMuxer: " + (e.message ?: e.javaClass.simpleName), e)
        val srcs = ArrayList<File>()
        srcs.add(job.v)
        job.a?.let { srcs.add(it) }
        if (remuxMany(srcs, out)) return out
        out.delete()
        throw Exception("فشل دمج الصوت والصورة: " + (e.message ?: e.javaClass.simpleName))
    }
}

internal fun MainActivity.remuxMany(srcs: List<File>, mp4: File): Boolean {
    var mux: MediaMuxer? = null
    var started = false
    try {
        mux = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val exs = ArrayList<MediaExtractor>()
        val maps = ArrayList<HashMap<Int, Int>>()
        var haveV = false
        var haveA = false
        for (s in srcs) {
            val ex = MediaExtractor()
            ex.setDataSource(s.absolutePath)
            exs.add(ex)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && !haveV) {
                    map[i] = mux.addTrack(f); ex.selectTrack(i); haveV = true
                } else if (mime.startsWith("audio/") && !haveA) {
                    map[i] = mux.addTrack(f); ex.selectTrack(i); haveA = true
                }
            }
            maps.add(map)
        }
        if (!haveV && !haveA) return false
        mux.start()
        started = true
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        for (k in exs.indices) {
            val ex = exs[k]
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                val t = maps[k][ex.sampleTrackIndex]
                if (t != null) mux.writeSampleData(t, buf, info)
                ex.advance()
            }
            try { ex.release() } catch (e: Exception) { Diag.swallow("DashDownload.kt:366 remuxMany", e); }
        }
        mux.stop()
        started = false
        return true
    } catch (e: Exception) { Diag.swallow("DashDownload.kt:371 remuxMany", e);
        return false
    } finally {
        try { if (started) mux?.stop() } catch (e: Exception) { Diag.swallow("DashDownload.kt:374 remuxMany", e); }
        try { mux?.release() } catch (e: Exception) { Diag.swallow("DashDownload.kt:375 remuxMany", e); }
    }
}


internal fun MainActivity.be32(b: ByteArray, o: Int): Long =
    ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

internal fun MainActivity.be64(b: ByteArray, o: Int): Long = (be32(b, o) shl 32) or be32(b, o + 4)

internal fun MainActivity.put32(b: ByteArray, o: Int, v: Long) {
    b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
}

internal fun MainActivity.put64(b: ByteArray, o: Int, v: Long) {
    put32(b, o, v shr 32); put32(b, o + 4, v and 0xffffffffL)
}

internal fun MainActivity.rangeBytes(u: String, h: Map<String, String>, a: Long, b: Long): ByteArray {
    val c = conn(u, h, "bytes=$a-$b")
    try {
        if (c.responseCode != 206) throw Exception("الخادم لا يدعم التجزئة")
        return c.inputStream.use { it.readBytes() }
    } finally {
        try { c.disconnect() } catch (e: Throwable) { Diag.swallow("DashDownload.kt:399 rangeBytes", e);}
    }
}

internal fun MainActivity.prepMoov(u: String, h: Map<String, String>, total: Long, work: File, n: Int, chunk: Long) {
    try {
        if (File(work, "moov.bin").exists()) return
        var pos = 0L
        var ftyp: ByteArray? = null
        var mdatStart = -1L
        var moovAt = -1L
        var moovSz = 0L
        var guard = 0
        while (pos < total && guard++ < 64) {
            val hd = rangeBytes(u, h, pos, minOf(pos + 15, total - 1))
            if (hd.size < 8) break
            var sz = be32(hd, 0)
            val type = String(hd, 4, 4, Charsets.ISO_8859_1)
            if (sz == 1L && hd.size >= 16) sz = be64(hd, 8) else if (sz == 0L) sz = total - pos
            if (sz < 8) break
            if (type == "ftyp" && sz < 4096) ftyp = rangeBytes(u, h, pos, pos + sz - 1)
            if (type == "mdat" && mdatStart < 0) mdatStart = pos
            if (type == "moov") {
                moovAt = pos
                moovSz = sz
                break
            }
            pos += sz
        }
        // moov قبل البيانات: بداية الملف تكفي للمشاهدة بدون أي معالجة
        if (moovAt < 0 || mdatStart < 0 || moovAt < mdatStart || moovSz > 80L * 1048576L) return
        val moov = rangeBytes(u, h, moovAt, moovAt + moovSz - 1)
        File(work, "ftyp.bin").writeBytes(ftyp ?: ByteArray(0))
        File(work, "moovmeta.txt").writeText("$mdatStart,$n,$chunk")
        File(work, "moov.bin").writeBytes(moov)
    } catch (e: Throwable) { Diag.swallow("DashDownload.kt:434 prepMoov", e);
    }
}

// يضبط مواضع الأجزاء (stco/co64) داخل moov بعد نقله لبداية الملف
internal fun MainActivity.patchMoov(m: ByteArray, delta: Long) {
    fun walk(start: Int, end: Int) {
        var p = start
        while (p + 8 <= end) {
            val sz = be32(m, p).toInt()
            if (sz < 8 || p + sz > end) break
            when (String(m, p + 4, 4, Charsets.ISO_8859_1)) {
                "trak", "mdia", "minf", "stbl" -> walk(p + 8, p + sz)
                "stco" -> {
                    val cnt = be32(m, p + 12).toInt()
                    for (i in 0 until cnt) {
                        val o = p + 16 + i * 4
                        if (o + 4 > p + sz) break
                        put32(m, o, be32(m, o) + delta)
                    }
                }
                "co64" -> {
                    val cnt = be32(m, p + 12).toInt()
                    for (i in 0 until cnt) {
                        val o = p + 16 + i * 8
                        if (o + 8 > p + sz) break
                        put64(m, o, be64(m, o) + delta)
                    }
                }
            }
            p += sz
        }
    }
    val hl = if (be32(m, 0) == 1L) 16 else 8
    walk(hl, m.size)
}
