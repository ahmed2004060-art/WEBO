package com.webo

import android.app.AlertDialog
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.View
import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Track
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.googlecode.mp4parser.authoring.tracks.AACTrackImpl
import com.googlecode.mp4parser.authoring.tracks.AppendTrack
import com.googlecode.mp4parser.authoring.tracks.h264.H264TrackImpl
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// تحليل وتنزيل HLS ودمج المقاطع
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

// قيمة خاصية من سطر وسم HLS (تدعم القيم بين علامتي اقتباس وفيها فواصل)
internal fun MainActivity.hlsAttr(l: String, name: String): String? =
    Regex("(?:^|[:,])" + Regex.escape(name) + "=(\"[^\"]*\"|[^,]*)").find(l)?.groupValues?.get(1)?.trim()?.trim('"')

internal fun MainActivity.codecTag(c: String): String {
    val x = c.lowercase()
    return when {
        x.contains("av01") -> "AV1"
        x.contains("hvc1") || x.contains("hev1") || x.contains("dvh") -> "HEVC"
        x.contains("vp09") || x.contains("vp9") -> "VP9"
        x.contains("avc") -> "H.264"
        else -> ""
    }
}

// يقرأ القائمة الرئيسية: كل المسارات مع الدقة والنطاق والترميز ومجموعة الصوت، بلا تكرار
internal fun MainActivity.parseMaster(base: String, pl: String): List<HlsVar> {
    val out = ArrayList<HlsVar>()
    val ls = pl.lines()
    for (i in ls.indices) {
        val l = ls[i].trim()
        if (!l.startsWith("#EXT-X-STREAM-INF")) continue
        val nx = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
        val bw = hlsAttr(l, "BANDWIDTH")?.toLongOrNull() ?: 0L
        val avg = hlsAttr(l, "AVERAGE-BANDWIDTH")?.toLongOrNull() ?: 0L
        val res = hlsAttr(l, "RESOLUTION") ?: ""
        val w = res.substringBefore('x', "").toIntOrNull() ?: 0
        val ht = res.substringAfter('x', "").toIntOrNull() ?: 0
        val codecs = hlsAttr(l, "CODECS") ?: ""
        val fps = hlsAttr(l, "FRAME-RATE") ?: ""
        val grp = hlsAttr(l, "AUDIO") ?: ""
        val cl = codecs.lowercase()
        val hasVideo = ht > 0 || codecs.isEmpty() || Regex("avc|hvc|hev|av01|vp0?9|dvh").containsMatchIn(cl)
        val url = try { absUrl(base, nx.trim()) } catch (e: Throwable) { Diag.swallow("HlsDownload.kt:64 parseMaster", e); continue }
        out.add(HlsVar(bw, avg, w, ht, codecs, fps, grp, url, hasVideo))
    }
    // نفس الرابط مرتين: نبقي الأعلى نطاقاً
    return out.groupBy { it.url }.map { g -> g.value.maxByOrNull { it.bw }!! }
}

// يستبعد مسارات الصوت فقط إن وُجد مسار فيه صورة، ثم يختار الأعلى (أو الأدنى في وضع التوفير)
internal fun MainActivity.pickHls(vs: List<HlsVar>): HlsVar? {
    val v = vs.filter { it.hasVideo }.ifEmpty { vs }
    return if (lowQ()) v.minByOrNull { it.bw } else v.maxByOrNull { it.bw }
}

// رابط مسار الصوت المنفصل (EXT-X-MEDIA TYPE=AUDIO) لمجموعة الصوت المرتبطة بالمسار المختار
internal fun MainActivity.hlsAudioUrl(base: String, pl: String, group: String): String? {
    if (group.isEmpty()) return null
    var first: String? = null
    var def: String? = null
    for (raw in pl.lines()) {
        val l = raw.trim()
        if (!l.startsWith("#EXT-X-MEDIA:")) continue
        if (hlsAttr(l, "TYPE") != "AUDIO" || hlsAttr(l, "GROUP-ID") != group) continue
        val uri = hlsAttr(l, "URI") ?: continue
        val au = try { absUrl(base, uri) } catch (e: Throwable) { Diag.swallow("HlsDownload.kt:87 hlsAudioUrl", e); continue }
        if (first == null) first = au
        if (def == null && hlsAttr(l, "DEFAULT") == "YES") def = au
    }
    return def ?: first
}

internal fun MainActivity.variants(u: String, h: Map<String, String>): List<Variant> {
    val pl = text(u, h)
    if (!pl.contains("#EXT-X-STREAM-INF")) return emptyList()
    val all = parseMaster(u, pl)
    val vs = all.filter { it.hasVideo }.ifEmpty { all }
    return vs.sortedByDescending { it.bw }.map { x ->
        val bw = if (x.avg > 0) x.avg else x.bw
        val head = if (x.h > 0) x.h.toString() + "p" else "جودة"
        val fr = x.fps.toDoubleOrNull()?.let { if (it > 0) Math.round(it).toString() + "fps" else "" } ?: ""
        val ct = codecTag(x.codecs)
        val label = head + (if (fr.isNotEmpty()) " " + fr else "") + (if (ct.isNotEmpty()) " " + ct else "") + " • " + (bw / 1000) + " kbps"
        Variant(label, x.url, bw, if (x.h > 0) x.h.toString() + "p" else "")
    }
}

internal fun MainActivity.chooseQuality(vs: List<Variant>, u: String, h: Map<String, String>, r: Row) {
    val qLabels = ArrayList<String>()
    val qSizes = ArrayList<String>()
    val dur = cands[u]?.meta?.dur ?: -1.0
    qLabels.add("الأعلى جودة (تلقائي)")
    qSizes.add("")
    for (v in vs) {
        qLabels.add(v.label)
        qSizes.add(if (dur > 0 && v.bw > 0) "≈ " + fmtSize(v.bw * dur / 8.0) else "")
    }
    val qHolder = arrayOfNulls<AlertDialog>(1)
    val qRows = qLabels.indices.map { i ->
        DlgStyle.qualityRow(this, qLabels[i], qSizes[i]) { qHolder[0]?.dismiss(); enqueue(if (i == 0) u else vs[i - 1].url, h, r) }
    }
    qHolder[0] = StyledBuilder(this)
        .setTitle("اختر الجودة")
        .setView(DlgStyle.rowsList(this, qRows))
        .setOnCancelListener {
            r.msg.text = ""
            r.btn.isEnabled = true
            r.btn.alpha = 1f
            r.bar.visibility = View.GONE
            r.started = false
            updateCounts()
        }
        .show()
}

internal fun MainActivity.isHls(u: String): Boolean {
    val l = u.lowercase()
    return l.contains(".m3u8") || l.contains("mpegurl") || hlsSet.contains(u)
}

internal fun MainActivity.makeIv(hex: String?, n: Int): ByteArray {
    if (hex != null) {
        val b = hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return if (b.size > 16) b.copyOfRange(b.size - 16, b.size) else b
    }
    val iv = ByteArray(16)
    for (k in 0..3) iv[15 - k] = (n shr (8 * k)).toByte()
    return iv
}

// 4 + 5 + 6 + 7: اختيار الجودة، قراءة القائمة، التحميل المتوازي، فك التشفير
internal fun MainActivity.hls(u0: String, h: Map<String, String>, work: File, ctl: Ctl, progress: (Int, Int) -> Unit): HlsJob {
    Diag.i("تحميل", "hls: بدء تحليل وتنزيل الأجزاء | عمل=" + work.absolutePath + " | الرابط " + u0)
    var url = u0
    var pl = text(url, h)

    // قائمة رئيسية: اختر المسار (الأعلى جودة أو الأدنى في وضع التوفير، بدون مسارات الصوت فقط) وافتح قائمته
    var audioUrl: String? = null
    if (pl.contains("#EXT-X-STREAM-INF")) {
        val best = pickHls(parseMaster(url, pl)) ?: throw Exception("لم يتم العثور على جودة في القائمة الرئيسية")
        audioUrl = hlsAudioUrl(url, pl, best.audio)
        url = best.url
        pl = text(url, h)
    }

    // قراءة القائمة: كل سطر لا يبدأ بـ # هو جزء، والنسبي يُضاف لعنوان القائمة
    val seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(pl)?.groupValues?.get(1)?.toInt() ?: 0
    var curKey: ByteArray? = null
    var curIv: String? = null
    var initUrl: String? = null
    var initRs = -1L
    var initRe = -1L
    var pendLen = -1L
    var pendOff = -1L
    var lastUri = ""
    var lastEnd = 0L
    val keyCache = HashMap<String, ByteArray>()
    val segs = ArrayList<Seg>()
    for (raw in pl.lines()) {
        val l = raw.trim()
        if (l.isEmpty()) continue
        if (l.startsWith("#EXT-X-KEY")) {
            val method = Regex("METHOD=([A-Z0-9-]+)").find(l)?.groupValues?.get(1) ?: "NONE"
            if (method == "NONE") {
                curKey = null
                curIv = null
            } else if (method == "AES-128") {
                val ku = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                    ?: throw Exception("المفتاح بدون رابط")
                val ka = absUrl(url, ku)
                curKey = keyCache.getOrPut(ka) { retry(3) { bytes(ka, h) } }
                curIv = Regex("IV=0x([0-9A-Fa-f]+)").find(l)?.groupValues?.get(1)
            } else if (prefs().getBoolean("ignoreenc", false)) {
                curKey = null
                curIv = null
            } else {
                throw ProtectedException("نوع تشفير غير مدعوم للتنزيل: $method. اضغط «▶ مشاهدة» لتجربة التشغيل المباشر إن كان الفيديو يدعم ذلك. (للتنزيل الخام فعّل «تحميل خام عند تشفير HLS غير مدعوم» من الإعدادات)")
            }
        } else if (l.startsWith("#EXT-X-MAP")) {
            val mu = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
            if (mu != null) initUrl = absUrl(url, mu)
            val br = hlsAttr(l, "BYTERANGE")
            if (br != null) {
                val ln = br.substringBefore('@').toLongOrNull() ?: -1L
                val of = br.substringAfter('@', "0").toLongOrNull() ?: 0L
                if (ln > 0) { initRs = of; initRe = of + ln - 1 }
            }
        } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
            // الجزء مقطع من ملف أكبر: الطول@الإزاحة، وبدون إزاحة يكمل من نهاية المقطع السابق لنفس الملف
            val v = l.substringAfter(':').trim()
            pendLen = v.substringBefore('@').toLongOrNull() ?: -1L
            pendOff = if (v.contains('@')) (v.substringAfter('@').toLongOrNull() ?: -1L) else -1L
        } else if (!l.startsWith("#")) {
            val i = segs.size
            val su = absUrl(url, l)
            var rs = -1L
            var re = -1L
            if (pendLen > 0) {
                val st = if (pendOff >= 0) pendOff else if (su == lastUri) lastEnd else 0L
                rs = st
                re = st + pendLen - 1
                lastUri = su
                lastEnd = st + pendLen
                pendLen = -1L
                pendOff = -1L
            }
            segs.add(Seg(i, su, curKey, makeIv(curIv, seq + i), rs, re))
        }
    }
    if (segs.isEmpty()) throw Exception("القائمة فاضية")

    // نحمّل ملف التهيئة أولاً (يلزم لمشاهدة الجزء المحمّل قبل اكتمال التحميل)
    val initFile = initUrl?.let { iu ->
        val f = File(work, "init.mp4")
        if (!(f.exists() && f.length() > 0)) {
            val d = retry(3) { if (initRs >= 0) rangeBytes(iu, h, initRs, initRe) else segBytes(iu, h) }
            if (d.isEmpty()) throw Exception("ملف التهيئة فارغ")
            f.writeBytes(d)
        }
        f
    }
    // مسار الصوت المنفصل (إن وُجد) يُحمَّل أولاً في مجلد فرعي، وفشله يُعلَن ولا يُهمَل بصمت
    var audioJob: HlsJob? = null
    var audioDir: File? = null
    val au = audioUrl
    if (au != null && au != url) {
        val ad = File(work, "aud")
        ad.mkdirs()
        try {
            audioJob = hls(au, h, ad, ctl) { _, _ -> }
            audioDir = ad
        } catch (e: CancelledException) { Diag.swallow("HlsDownload.kt:252 hls", e);
            throw e
        } catch (e: ProtectedException) { Diag.swallow("HlsDownload.kt:254 hls", e);
            throw e
        } catch (e: Throwable) { Diag.swallow("HlsDownload.kt:256 hls", e);
            throw Exception("فشل تحميل مسار الصوت المنفصل: " + (e.message ?: e.javaClass.simpleName))
        }
    }
    val ext = segs[0].url.substringBefore('?').substringAfterLast('.').lowercase()
    val mp4Family = initFile != null || ext in setOf("mp4", "m4s", "m4v", "m4a", "cmfv", "cmfa")
    try { if (mp4Family) File(work, "fam_mp4").writeText("1") } catch (e: Throwable) { Diag.swallow("HlsDownload.kt:262 hls", e);}

    // كل جزء يُحفظ في ملف برقمه (part_00000.seg ...) لضمان الترتيب وعدم ملء الذاكرة
    val parts = ArrayList<File>()
    for (s in segs) parts.add(File(work, "part_%05d.seg".format(s.index)))
    val done = AtomicInteger(0)
    val pool = diagFixed("hlsSegments", prefs().getInt("threads", 8).coerceIn(1, 128))
    try {
        val futures = segs.map { s ->
            pool.submit(Callable<Unit> {
                gate(ctl)
                val part = parts[s.index]
                // جزء مكتمل من محاولة سابقة: لا يُعاد تحميله
                if (!(part.exists() && part.length() > 0)) {
                    // الجزء لا يُعدّ مكتملاً إلا إذا وصل بالحجم المتوقع (للمقاطع المحدّدة بالبايت) وغير فارغ
                    var d = retry(5) {
                        gate(ctl)
                        val x = if (s.rs >= 0) rangeBytes(s.url, h, s.rs, s.re) else segBytes(s.url, h)
                        if (x.isEmpty()) throw Exception("جزء فارغ")
                        if (s.rs >= 0 && x.size.toLong() != s.re - s.rs + 1) throw Exception("جزء ناقص")
                        x
                    }
                    ctl.bytes.addAndGet(d.size.toLong())
                    throttle(d.size)
                    if (s.key != null) {
                        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(s.key, "AES"), IvParameterSpec(s.iv))
                        d = c.doFinal(d)
                    }
                    val tmp = File(work, "part_%05d.tmp".format(s.index))
                    tmp.writeBytes(d)
                    if (!tmp.renameTo(part)) {
                        Diag.i("تخزين", "HLS: فشل renameTo " + tmp.absolutePath + " ← " + part.absolutePath + " | مساحة حرة " + (work.usableSpace / 1048576) + " MB")
                        part.writeBytes(d)
                        tmp.delete()
                    }
                }
                progress(done.incrementAndGet(), segs.size)
            })
        }
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) { Diag.swallow("HlsDownload.kt:305 hls", e);
                val c = e.cause
                if (c is CancelledException) throw c
                throw Exception(c?.message ?: "فشل تحميل جزء")
            }
        }
    } finally {
        pool.shutdownNow()
    }

    return HlsJob(parts, initFile, mp4Family, audioJob, audioDir)
}

// دمج مسار الصورة مع مسار الصوت المنفصل إن وُجد (HLS بقوائم صوت مستقلة)
internal fun MainActivity.mergeHls(job: HlsJob, work: File, name: String): File {
    Diag.i("تحميل", "hls: بدء الدمج | اسم=" + name + " | عمل=" + work.absolutePath)
    val aj = job.audio ?: return merge(job, work, name)
    val vOut = merge(HlsJob(job.parts, job.init, job.mp4Family), work, name + "_v")
    val adir = job.audioDir ?: File(work, "aud")
    return try {
        val aOut = merge(aj, adir, "a")
        mergeDash(DashJob(vOut, aOut), work, name)
    } catch (e: Throwable) { Diag.swallow("HlsDownload.kt:326 mergeHls", e);
        logErr("دمج", "تعذر دمج الصوت المنفصل، سيُحفظ الفيديو بدون صوت: " + (e.message ?: e.javaClass.simpleName), e)
        vOut
    }
}

// 8: ترتيب الأجزاء ودمجها في ملف واحد: mp4parser أولاً، وMediaMuxer كبديل (يشمل HEVC)
internal fun MainActivity.merge(job: HlsJob, work: File, name: String): File {
    val out = File(work, "$name.mp4")
    val ini = job.init
    // الملف المتسلسل بترتيب الأجزاء (TS كامل، أو fMP4 مع ملف التهيئة) ليستعمله MediaMuxer عند الحاجة
    val whole: File? = if (!job.mp4Family) File(work, "whole.ts") else if (ini != null) File(work, "whole.mp4") else null
    if (whole != null) {
        whole.outputStream().buffered().use { o ->
            if (ini != null) ini.inputStream().use { it.copyTo(o) }
            for (p in job.parts) p.inputStream().use { it.copyTo(o) }
        }
    }
    try {
        val movie: Movie
        if (!job.mp4Family) {
            // TS: تُفكّ الحزم إلى مسار H.264 ومسار AAC ثم يبنيهما mp4parser
            val v = File(work, "video.h264")
            val a = File(work, "audio.aac")
            val used = demuxTs(job.parts, v, a)
            movie = Movie()
            if (used.first) movie.addTrack(H264TrackImpl(FileDataSourceImpl(v)))
            if (used.second) movie.addTrack(AACTrackImpl(FileDataSourceImpl(a)))
            if (movie.tracks.isEmpty()) throw Exception("لا يوجد فيديو H.264 أو صوت AAC داخل الأجزاء")
        } else if (whole != null) {
            movie = MovieCreator.build(whole.absolutePath)
        } else {
            val video = ArrayList<Track>()
            val audio = ArrayList<Track>()
            for (p in job.parts) {
                for (t in MovieCreator.build(p.absolutePath).tracks) {
                    if (t.handler == "vide") video.add(t) else if (t.handler == "soun") audio.add(t)
                }
            }
            movie = Movie()
            if (video.isNotEmpty()) movie.addTrack(AppendTrack(*video.toTypedArray()))
            if (audio.isNotEmpty()) movie.addTrack(AppendTrack(*audio.toTypedArray()))
        }
        val c = DefaultMp4Builder().build(movie)
        FileOutputStream(out).use { fo -> c.writeContainer(fo.channel) }
        return out
    } catch (e: Throwable) { Diag.swallow("HlsDownload.kt:372 merge", e);
        // بديل: MediaMuxer (يدعم HEVC وأي ترميز يقرأه النظام) على الملف المتسلسل
        out.delete()
        logErr("دمج", "فشل mp4parser، جاري تجربة MediaMuxer: " + (e.message ?: e.javaClass.simpleName), e)
        if (whole != null && remux(whole, out)) return out
        if (whole == null && remuxParts(job.parts, out)) return out
        out.delete()
        throw Exception("فشل دمج الأجزاء: " + (e.message ?: e.javaClass.simpleName))
    }
}

// يحوّل الملف المتسلسل إلى mp4 بدون إعادة ترميز (MediaExtractor + MediaMuxer)
internal fun MainActivity.remux(src: File, mp4: File): Boolean {
    val ex = MediaExtractor()
    var mux: MediaMuxer? = null
    var started = false
    try {
        ex.setDataSource(src.absolutePath)
        mux = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val map = HashMap<Int, Int>()
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                map[i] = mux.addTrack(f)
                ex.selectTrack(i)
            }
        }
        if (map.isEmpty()) return false
        mux.start()
        started = true
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            info.offset = 0
            info.size = n
            info.presentationTimeUs = ex.sampleTime
            info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            val t = map[ex.sampleTrackIndex]
            if (t != null) mux.writeSampleData(t, buf, info)
            ex.advance()
        }
        mux.stop()
        started = false
        return true
    } catch (e: Exception) { Diag.swallow("HlsDownload.kt:419 remux", e);
        return false
    } finally {
        try { ex.release() } catch (e: Exception) { Diag.swallow("HlsDownload.kt:422 remux", e); }
        try { if (started) mux?.stop() } catch (e: Exception) { Diag.swallow("HlsDownload.kt:423 remux", e); }
        try { mux?.release() } catch (e: Exception) { Diag.swallow("HlsDownload.kt:424 remux", e); }
    }
}

// بديل MediaMuxer لأجزاء MP4 المستقلة: يقرأ كل جزء بالترتيب ويكتب عيّناته في ملف واحد مع إزاحة الزمن
internal fun MainActivity.remuxParts(parts: List<File>, mp4: File): Boolean {
    var mux: MediaMuxer? = null
    var started = false
    try {
        mux = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val kindToTrack = HashMap<String, Int>()
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var offset = 0L
        for (pf in parts) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(pf.absolutePath)
                val map = HashMap<Int, Int>()
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    val kind = if (mime.startsWith("video/")) "video" else if (mime.startsWith("audio/")) "audio" else continue
                    if (!started && !kindToTrack.containsKey(kind)) kindToTrack[kind] = mux.addTrack(f)
                    val mt = kindToTrack[kind] ?: continue
                    map[i] = mt
                    ex.selectTrack(i)
                }
                if (!started) {
                    if (kindToTrack.isEmpty()) return false
                    mux.start()
                    started = true
                }
                var maxPts = 0L
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val t = map[ex.sampleTrackIndex]
                    val pts = ex.sampleTime
                    if (t != null) {
                        info.offset = 0
                        info.size = n
                        info.presentationTimeUs = pts + offset
                        info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        mux.writeSampleData(t, buf, info)
                        if (pts > maxPts) maxPts = pts
                    }
                    ex.advance()
                }
                offset += maxPts + 33000L
            } finally {
                try { ex.release() } catch (e: Exception) { Diag.swallow("HlsDownload.kt:475 remuxParts", e); }
            }
        }
        mux.stop()
        started = false
        return true
    } catch (e: Exception) { Diag.swallow("HlsDownload.kt:481 remuxParts", e);
        return false
    } finally {
        try { if (started) mux?.stop() } catch (e: Exception) { Diag.swallow("HlsDownload.kt:484 remuxParts", e); }
        try { mux?.release() } catch (e: Exception) { Diag.swallow("HlsDownload.kt:485 remuxParts", e); }
    }
}

// يفكّ حزم MPEG-TS (188 بايت) بترتيب الأجزاء: PAT ثم PMT ثم PES، ويكتب مسار H.264 ومسار AAC(ADTS)
internal fun MainActivity.demuxTs(parts: List<File>, vFile: File, aFile: File): Pair<Boolean, Boolean> {
    var pmtPid = -1
    var vPid = -1
    var aPid = -1
    var hevc = false
    var vUsed = false
    var aUsed = false
    val vo = vFile.outputStream().buffered()
    val ao = aFile.outputStream().buffered()
    try {
        for (part in parts) {
            val d = part.readBytes()
            var i = 0
            while (i + 188 <= d.size) {
                if (d[i] != 0x47.toByte()) { i++; continue }
                val pid = ((d[i + 1].toInt() and 0x1F) shl 8) or (d[i + 2].toInt() and 0xFF)
                val pusi = (d[i + 1].toInt() and 0x40) != 0
                val afc = (d[i + 3].toInt() shr 4) and 3
                var off = i + 4
                if ((afc and 2) != 0) off += 1 + (d[i + 4].toInt() and 0xFF)
                val end = i + 188
                if ((afc and 1) != 0 && off < end) {
                    try {
                        if (pid == 0 && pusi) {
                            val s0 = off + 1 + (d[off].toInt() and 0xFF)
                            val secLen = ((d[s0 + 1].toInt() and 0x0F) shl 8) or (d[s0 + 2].toInt() and 0xFF)
                            var q = s0 + 8
                            val qend = minOf(s0 + 3 + secLen - 4, end)
                            while (q + 4 <= qend) {
                                val prog = ((d[q].toInt() and 0xFF) shl 8) or (d[q + 1].toInt() and 0xFF)
                                val pp = ((d[q + 2].toInt() and 0x1F) shl 8) or (d[q + 3].toInt() and 0xFF)
                                if (prog != 0) { pmtPid = pp; break }
                                q += 4
                            }
                        } else if (pid == pmtPid && pusi) {
                            val s0 = off + 1 + (d[off].toInt() and 0xFF)
                            val secLen = ((d[s0 + 1].toInt() and 0x0F) shl 8) or (d[s0 + 2].toInt() and 0xFF)
                            val piLen = ((d[s0 + 10].toInt() and 0x0F) shl 8) or (d[s0 + 11].toInt() and 0xFF)
                            var q = s0 + 12 + piLen
                            val qend = minOf(s0 + 3 + secLen - 4, end)
                            while (q + 5 <= qend) {
                                val st = d[q].toInt() and 0xFF
                                val ep = ((d[q + 1].toInt() and 0x1F) shl 8) or (d[q + 2].toInt() and 0xFF)
                                val esLen = ((d[q + 3].toInt() and 0x0F) shl 8) or (d[q + 4].toInt() and 0xFF)
                                if (st == 0x1B && vPid < 0) vPid = ep
                                else if (st == 0x0F && aPid < 0) aPid = ep
                                else if (st == 0x24) hevc = true
                                q += 5 + esLen
                            }
                        } else if (pid == vPid || pid == aPid) {
                            var ps = off
                            if (pusi && ps + 9 <= end && d[ps].toInt() == 0 && d[ps + 1].toInt() == 0 && d[ps + 2].toInt() == 1) {
                                ps += 9 + (d[ps + 8].toInt() and 0xFF)
                            }
                            if (ps < end) {
                                if (pid == vPid) { vo.write(d, ps, end - ps); vUsed = true }
                                else { ao.write(d, ps, end - ps); aUsed = true }
                            }
                        }
                    } catch (e: IndexOutOfBoundsException) { Diag.swallow("HlsDownload.kt:549 demuxTs", e);
                    }
                }
                i += 188
            }
        }
    } finally {
        vo.close()
        ao.close()
    }
    if (hevc && !vUsed) throw Exception("HEVC: يتولاه MediaMuxer")
    return Pair(vUsed, aUsed)
}
