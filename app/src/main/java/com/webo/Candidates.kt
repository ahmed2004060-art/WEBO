package com.webo

import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import com.webo.MainActivity.Tab
import java.io.File

// اكتشاف الروابط وتحليلها وتحويلها إلى مهام
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.dropCand(c: Cand) {
    c.rw.thumbStop = true
    forgetTask(c.rw.id)
    list.removeView(c.view)
    found.remove(c.url)
    foundStart.remove(c.url)
    foundHdr.remove(c.url)
    cands.remove(c.url)
}

internal fun MainActivity.tabKey(t: Tab?): String = System.identityHashCode(t).toString() + "|"

// فيديوهات التبويب الحالي المسجلة (غير البث المباشر)
internal fun MainActivity.candsOf(t: Tab?): List<Cand> =
    if (t == null) emptyList() else cands.values.filter { it.owner === t && it.meta?.vod != false }

// أفضل مرشح للتبويب: قوائم البث (m3u8/mpd) أولاً ثم الأطول مدة والأكبر حجماً، وعند التساوي الأحدث
internal fun MainActivity.bestFor(t: Tab?): Cand? {
    val l = candsOf(t)
    var best: Cand? = null
    var bs = -1.0
    for (c in l) {
        val m = c.meta
        var sc = 0.0
        if (isHls(c.url) || isDash(c.url)) sc += 1e12
        if (m != null) {
            if (m.dur > 0) sc += m.dur * 1000.0
            if (m.size > 0) sc += m.size / 1024.0
        }
        if (sc >= bs) {
            bs = sc
            best = c
        }
    }
    return best
}

// الانتقال لصفحة أخرى: نمسح روابط هذا التبويب التي لم يبدأ تحميلها حتى لا تختلط فيديوهات الصفحات
internal fun MainActivity.pruneTab(t: Tab) {
    val k = tabKey(t)
    considered.removeAll { it.startsWith(k) }
    for (c in ArrayList(cands.values)) {
        if (c.owner === t && c.rw.btn.isEnabled && c.rw.bar.visibility != View.VISIBLE) dropCand(c)
    }
    updateCounts()
}

internal fun MainActivity.startCand(c: Cand) {
    val go = foundStart[c.url]
    if (go == null) {
        toast("تعذر بدء التحميل")
        return
    }
    go()
    toast("تابع التحميل من تبويب التنزيلات")
}

// prot: 0 عادي، 1 محمي، 2 غير محدد
internal fun MainActivity.setKind(r: Row, prot: Int, fmt: String) {
    val t = r.kindTv ?: return
    val st = when (prot) {
        0 -> "عادي"
        1 -> "محمي"
        else -> "غير محدد"
    }
    val col = when (prot) {
        0 -> Color.parseColor("#3DB36B")
        1 -> Color.parseColor("#E0A030")
        else -> cMuted
    }
    t.text = st + " — " + fmt
    t.setTextColor(col)
    r.kindProt = prot
    r.kindFmt = fmt
    r.engine = engineFor(prot, fmt)
    saveTask(r)
}

internal fun MainActivity.hasAny(s: String, vararg k: String): Boolean {
    for (x in k) if (s.contains(x)) return true
    return false
}

// يحدد الصيغة والحماية من بيانات الملف الفعلية (محتوى القائمة أو أول بايتات الملف) وليس من الامتداد وحده
internal fun MainActivity.sniffKind(u0: String, h: Map<String, String>): Kind {
    val u = u0.substringBefore("#tdlrep=")
    if (isHls(u) || isDash(u)) {
        val p = PlayerCore.probe(u, h, if (isHls(u)) "HLS" else "DASH")
        return Kind(p.prot, if (p.kind == "HLS") "HLS/M3U8" else "DASH/MPD")
    }
    val c = conn(u, h, "bytes=0-262143")
    val ct = (c.contentType ?: "").lowercase()
    val b = try { c.inputStream.use { readLimited(it, 262144) } } finally { try { c.disconnect() } catch (e: Throwable) { Diag.swallow("Candidates.kt:112 sniffKind", e);} }
    if (b.size < 12) return Kind(2, "غير معروف")
    val s = String(b, Charsets.ISO_8859_1)
    val head = s.substring(0, minOf(s.length, 64)).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    if (head.startsWith("#EXTM3U")) {
        val p = PlayerCore.probe(u, h, "HLS")
        return Kind(p.prot, "HLS/M3U8")
    }
    if (head.startsWith("<") && s.contains("<MPD")) {
        val p = PlayerCore.probe(u, h, "DASH")
        return Kind(p.prot, "DASH/MPD")
    }
    if (s.substring(4, 8) == "ftyp") {
        val brand = s.substring(8, 12)
        val fmt = if (brand.startsWith("qt")) "MOV" else "MP4"
        val marked = hasAny(s, "pssh", "cenc", "cbcs", "cbc1", "cens")
        val prot = if (marked) 1 else if (s.contains("moov")) 0 else 2
        return Kind(prot, fmt)
    }
    if (b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()) {
        val fmt = if (s.substring(0, minOf(s.length, 64)).contains("webm")) "WEBM" else "MKV"
        val enc = s.contains("\u006D\u0080")
        return Kind(if (enc) 1 else 0, fmt)
    }
    if (s.startsWith("OggS")) return Kind(0, "OGG")
    if (s.startsWith("RIFF") && s.substring(8, 12) == "AVI ") return Kind(0, "AVI")
    if (s.startsWith("FLV")) return Kind(0, "FLV")
    if (b[0] == 0x47.toByte() && b.size > 376 && b[188] == 0x47.toByte() && b[376] == 0x47.toByte()) return Kind(2, "TS")
    val fmt = when {
        ct.contains("mp4") -> "MP4"
        ct.contains("webm") -> "WEBM"
        ct.contains("matroska") -> "MKV"
        else -> "غير معروف"
    }
    return Kind(2, fmt)
}

internal fun MainActivity.classifyCand(c: Cand, h: Map<String, String>) {
    try {
        analyzer.execute {
            val k = try {
                sniffKind(c.url, h)
            } catch (e: Throwable) { Diag.swallow("Candidates.kt:154 classifyCand", e);
                Kind(2, if (isHls(c.url)) "HLS/M3U8" else if (isDash(c.url)) "DASH/MPD" else "غير معروف")
            }
            ui.post {
                // ما ثبت بعد فشل التحميل أو بعد الحفظ المحمي لا يُستبدل بنتيجة أضعف
                if (cands[c.url] === c && c.rw.kindProt != 1) setKind(c.rw, k.prot, k.fmt)
            }
        }
    } catch (e: Throwable) { Diag.swallow("Candidates.kt:162 classifyCand", e);
    }
}

internal fun MainActivity.refreshInfo(c: Cand) {
    val m = c.meta
    val tags = ArrayList<String>()
    if (m != null) {
        tags.add((if (m.vod) "مسجل" else "بث مباشر") + (if (m.why.startsWith("يدوي")) " (يدوي)" else if (m.conf in 1..99) " (تقديري " + m.conf + "%)" else ""))
        if (m.dur > 0) tags.add(fmtDur(m.dur))
        if (m.res.isNotBlank()) tags.add("الجودة " + m.res)
        if (m.kbps > 0) tags.add(m.kbps.toString() + " kbps")
        if (m.size > 0) tags.add("الحجم " + (if (m.sizeEst) "≈ " else "") + fmtSize(m.size.toDouble()))
    }
    c.info.text = c.hostTxt + (if (tags.isNotEmpty()) "\n" + tags.joinToString(" • ") else "")
    c.title = c.pageTitle + (if (tags.isNotEmpty()) " — " + tags.joinToString(" • ") else "")
    saveTask(c.rw)
}

// جودة وأبعاد الملف المباشر (mp4/webm...) من بيانات الملف نفسه، بعد ظهوره في القائمة
internal fun MainActivity.enrich(c: Cand, h: Map<String, String>) {
    try {
        analyzer.execute {
            try {
                val r = MediaMetadataRetriever()
                try {
                    val hh = HashMap<String, String>()
                    for ((k, v) in h) if (v.isNotEmpty()) hh[k] = v
                    r.setDataSource(c.url, hh)
                    val ht = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val wd = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                    val du = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val side = if (ht > 0) minOf(ht, if (wd > 0) wd else ht).takeIf { wd in 1 until ht } ?: ht else 0
                    ui.post {
                        val m = c.meta
                        if (m != null && cands[c.url] === c) {
                            c.meta = m.copy(
                                res = if (side > 0) side.toString() + "p" else m.res,
                                kbps = if (br > 0) br / 1000 else m.kbps,
                                dur = if (du > 0) du / 1000.0 else m.dur
                            )
                            refreshInfo(c)
                        }
                    }
                } finally {
                    try { r.release() } catch (e: Throwable) { Diag.swallow("Candidates.kt:208 enrich", e);}
                }
            } catch (e: Throwable) { Diag.swallow("Candidates.kt:210 enrich", e);
            }
        }
    } catch (e: Throwable) { Diag.swallow("Candidates.kt:213 enrich", e);
    }
}

// يقرر هل الرابط فيديو حقيقي: يقرأ قائمة m3u8 (مسجل أم مباشر + المدة + الجودة + الحجم التقديري)، ويتحقق من نوع الملفات المباشرة
internal fun MainActivity.consider(u: String, h: Map<String, String>, v: WebView?) {
    val t = if (v != null) hasTab(v) else cur
    val key = tabKey(t) + u
    if (!showKnownSeg() && segUrls.contains(u)) { capNote("أُهمل", u, "جزء من قائمة بث معروفة (يمكنك إظهاره من الإعدادات: إظهار أجزاء القوائم)"); return }
    if (!considered.add(key)) return
    try {
        analyzer.execute {
            var meta: Meta? = null
            var ok = true
            try {
                meta = analyze(u, h, getOvr(u))
                if (meta == null) { ok = false; capNote("أُهمل", u, "ليس فيديو: نوع المحتوى ليس فيديو، أو القائمة فارغة أو غير صالحة") }
            } catch (e: Throwable) { Diag.swallow("Candidates.kt:230 consider", e);
                meta = null // تعذر الفحص: نعرض الرابط كما هو بدل أن نخسره
                capNote("تنبيه", u, "تعذر فحص الرابط (" + (e.message ?: e.javaClass.simpleName) + ") فعُرض كما هو")
                logErr("التقاط", "تعذر فحص الرابط: " + (e.message ?: e.javaClass.simpleName), e)
            }
            val m: Meta? = meta
            val thr = prefs().getInt("uncert", 75)
            val unsure = m != null && thr > 0 && m.conf < thr && !m.why.startsWith("يدوي")
            if (ok && m != null && !m.vod && !prefs().getBoolean("showlive", true) && !unsure) {
                ok = false
                excluded[u] = Excl(u, h, m, t, t?.title ?: "")
                capNote("أُهمل", u, "بث مباشر (" + m.why + "، ثقة " + m.conf + "%). يمكنك إضافته من «الروابط المستبعدة»")
            }
            if (ok && !showKnownSeg() && segUrls.contains(u)) { ok = false; capNote("أُهمل", u, "جزء من بث (segment)") }
            if (ok) capNote("قُبل", u, if (m == null) "قُبل بلا معلومات" else (if (m.vod) "فيديو مسجل" else "بث مباشر") + " (" + m.why + "، ثقة " + m.conf + "%)" + (if (m.dur > 0) " • " + fmtDur(m.dur) else "") + (if (m.res.isNotEmpty()) " • " + m.res else ""))
            if (ok) ui.post {
                if (t == null || tabs.contains(t)) {
                    addFound(u, h, m, t, t?.title ?: "")
                    val c = cands[u]
                    if (c != null && c.meta != null && !isHls(u) && !isDash(u)) enrich(c, h)
                }
                // أي جزء بث (segment) ظهر قبل معرفة قائمته يُحذف
                for (c in ArrayList(cands.values)) {
                    if (!showKnownSeg() && segUrls.contains(c.url) && c.rw.btn.isEnabled && c.rw.bar.visibility != View.VISIBLE) dropCand(c)
                }
                updateCounts()
            }
        }
    } catch (e: Throwable) { Diag.swallow("Candidates.kt:258 consider", e);
    }
}

internal fun MainActivity.analyze(u: String, h: Map<String, String>, force: Boolean? = null): Meta? {
    var hls = isHls(u)
    var dashMode = isDash(u)
    var size = -1L
    var ct = ""
    if (!hls && !dashMode) {
        // نقرأ أول بايت فقط: نوع المحتوى والحجم الكلي
        val c = try { conn(u, h, "bytes=0-0") } catch (e: Exception) { Diag.swallow("Candidates.kt:269 analyze", e); conn(u, h) } // بعض الخوادم ترفض Range: نقرأ الرؤوس بطلب عادي
        try {
            ct = (c.contentType ?: "").lowercase()
            size = if (c.responseCode == 206) {
                (c.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull() ?: -1L
            } else c.contentLengthLong
        } finally {
            try { c.disconnect() } catch (e: Throwable) { Diag.swallow("Candidates.kt:276 analyze", e);}
        }
        if (ct.contains("dash+xml")) dashMode = true
        else if (ct.contains("mpegurl")) hls = true
        else if (ct.startsWith("text/") || ct.contains("json") || ct.contains("html") || ct.contains("javascript") ||
            ct.startsWith("image/") || (ct.startsWith("audio/") && !capAudio()) || ct.contains("xml") || ct.contains("css")) return null
    }
    if (dashMode) {
        val base = u.substringBefore("#tdlrep=")
        val xml = text(base, h)
        if (!xml.contains("<MPD")) return null
        dashSet.add(base)
        val info = parseMpd(base, xml)
        if (info.video.isEmpty() && info.audio.isEmpty()) return null
        for (r in info.video + info.audio) {
            r.init?.let { segUrls.add(it) }
            for (x in r.segs) segUrls.add(x)
            r.single?.let { segUrls.add(it) }
        }
        val bv = info.video.maxByOrNull { it.bw }
        val bwTot = (bv?.bw ?: 0L) + (info.audio.maxByOrNull { it.bw }?.bw ?: 0L)
        val total = if (bwTot > 0 && info.dur > 0) (bwTot * info.dur / 8.0).toLong() else -1L
        var isLive = info.live
        var conf = 100
        var why = ""
        if (force != null) {
            isLive = !force
            why = "يدوي"
        } else if (isLive) {
            // type=dynamic لا يعني بثاً دائماً: نقارن المانيفست بعد انتظار مرتين
            val hasMup = xml.contains("minimumUpdatePeriod")
            var changed = false
            var ended = false
            var failed = false
            try {
                val w = prefs().getInt("livewait", 0).let { if (it > 0) it * 1000L else 5000L }
                val norm = { x: String -> x.replace(Regex("\\s+"), "") }
                val first = norm(xml)
                for (round in 0 until 2) {
                    Thread.sleep(w)
                    val xml2 = text(base, h)
                    if (xml2.contains("type=\"static\"")) { ended = true; break }
                    if (norm(xml2) != first) { changed = true; break }
                }
            } catch (e: InterruptedException) { Diag.swallow("Candidates.kt:320 analyze", e);
                throw e
            } catch (e: Throwable) { Diag.swallow("Candidates.kt:322 analyze", e);
                failed = true
            }
            if (ended) { isLive = false; conf = 100; why = "المانيفست صار static" }
            else if (changed) { isLive = true; conf = 95; why = "المانيفست يتجدد" }
            else if (failed) { isLive = true; conf = 30; why = "تعذر إعادة فحص المانيفست" }
            else { isLive = false; conf = if (hasMup) 60 else 80; why = "المانيفست ثابت" }
            capNote("تحليل", u, "DASH: " + why + " (" + conf + "%)")
        }
        return Meta(!isLive, if (info.dur > 0) info.dur else -1.0, total, true,
            if (bv != null && bv.h > 0) bv.h.toString() + "p" else "", bwTot / 1000, emptyList(), conf, why)
    }
    if (hls) {
        var pl = text(u, h)
        if (!pl.trimStart().startsWith("#EXTM3U")) return null
        hlsSet.add(u)
        var base = u
        var res = ""
        var kbps = 0L
        var bw = 0L
        val subs = ArrayList<String>()
        if (pl.contains("#EXT-X-STREAM-INF")) {
            val vs = variants(u, h)
            if (vs.isEmpty()) return Meta(true, -1.0, -1L, false, "", 0L, subs)
            for (x in vs) { subs.add(x.url); hlsSet.add(x.url) }
            res = vs[0].res
            bw = vs[0].bw
            kbps = bw / 1000
            base = vs[0].url
            pl = text(base, h)
        }
        var vod = pl.contains("#EXT-X-ENDLIST") || pl.contains("#EXT-X-PLAYLIST-TYPE:VOD")
        var dur = 0.0
        var count = 0
        var firstSeg: String? = null
        for (raw in pl.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF:")) dur += l.substring(8).substringBefore(',').trim().toDoubleOrNull() ?: 0.0
            else if (l.startsWith("#EXT-X-MAP")) {
                Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { segUrls.add(absUrl(base, it)) }
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                val su = absUrl(base, l)
                segUrls.add(su)
                if (firstSeg == null) firstSeg = su
                count++
            }
        }
        // الحكم: علامة النهاية قاطعة. وإلا نقارن القائمة مرتين بعد انتظار أطول من target-duration:
        // تجدد = بث مباشر، ثبات = مسجل مع درجة ثقة تُخفض عند القرائن المشبوهة (تسلسل لا يبدأ من 0، نوع EVENT، مدة قصيرة)
        var conf = 100
        var why = ""
        if (force != null) {
            vod = force
            why = "يدوي"
        } else if (vod) {
            why = "علامة النهاية موجودة"
        } else if (count > 0) {
            val td = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(pl)?.groupValues?.get(1)?.toLongOrNull() ?: 4L
            val lw = prefs().getInt("livewait", 0)
            val waitMs = if (lw > 0) lw * 1000L else (td * 1500L).coerceAtLeast(3500L)
            fun sig(x: String): String {
                val sq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(x)?.groupValues?.get(1) ?: ""
                val ls = x.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: ""
                val n = x.lineSequence().count { it.isNotBlank() && !it.trim().startsWith("#") }
                return sq + "|" + ls + "|" + n
            }
            var lastSig = sig(pl)
            var changed = false
            var ended = false
            var failed = false
            try {
                for (round in 0 until 2) {
                    Thread.sleep(waitMs)
                    val p2 = text(base, h)
                    if (p2.contains("#EXT-X-ENDLIST")) { ended = true; break }
                    val s2 = sig(p2)
                    if (s2 != lastSig) { changed = true; break }
                    lastSig = s2
                }
            } catch (e: InterruptedException) { Diag.swallow("Candidates.kt:401 analyze", e);
                throw e
            } catch (e: Throwable) { Diag.swallow("Candidates.kt:403 analyze", e);
                failed = true
            }
            if (ended) { vod = true; conf = 100; why = "ظهرت علامة نهاية" }
            else if (changed) { vod = false; conf = 95; why = "القائمة تتجدد" }
            else if (failed) { vod = false; conf = 30; why = "تعذر إعادة فحص القائمة" }
            else {
                vod = true
                conf = 85
                why = "القائمة ثابتة بلا علامة نهاية"
                val seq0 = (Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(pl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L) == 0L
                if (!seq0) conf -= 20
                if (pl.contains("#EXT-X-PLAYLIST-TYPE:EVENT")) conf -= 15
                if (dur < 30.0) conf -= 10
            }
            capNote("تحليل", u, "HLS: " + (if (vod) "مسجل" else "بث") + " - " + why + " (" + conf + "%)")
        } else {
            vod = false
            conf = 40
            why = "قائمة بلا أجزاء"
        }
        // الحجم: من معدل البت إن عُرف، وإلا من حجم أول جزء × عدد الأجزاء
        var total = -1L
        var est = true
        if (bw > 0 && dur > 0) total = (bw * dur / 8.0).toLong()
        else if (firstSeg != null && count > 0) {
            try {
                val c = conn(firstSeg, h, "bytes=0-0")
                try {
                    val one = if (c.responseCode == 206) {
                        (c.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull() ?: -1L
                    } else c.contentLengthLong
                    if (one > 0) total = one * count
                    if (one > 0 && dur > 0) kbps = (one * count * 8.0 / dur / 1000.0).toLong()
                } finally {
                    try { c.disconnect() } catch (e: Throwable) { Diag.swallow("Candidates.kt:438 analyze", e);}
                }
            } catch (e: Throwable) { Diag.swallow("Candidates.kt:440 analyze", e);
            }
        }
        return Meta(vod, if (dur > 0) dur else -1.0, total, est, res, kbps, subs, conf, why)
    }
    // ملف فيديو مباشر (mp4 / webm / mov / mkv ...): الحجم الفعلي من الخادم
    return Meta(force != false, -1.0, size, false, "", 0L, emptyList(),
        if (force != null) 100 else if (size <= 0) 70 else 100,
        if (force != null) "يدوي" else if (size <= 0) "حجم غير معروف: قد يكون بثاً" else "")
}

internal fun MainActivity.addManual() {
    val u = manualBox.text.toString().trim()
    if (!(u.startsWith("http://") || u.startsWith("https://"))) {
        toast("أدخل رابطاً صحيحاً يبدأ بـ http")
        return
    }
    val h = HashMap<String, String>()
    h["User-Agent"] = curUa
    h["Referer"] = cur?.web?.url ?: ""
    h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
    addFound(u, h, null, cur, "")
    manualBox.setText("")
    showPage(1)
}

internal fun MainActivity.addFound(u: String, h: Map<String, String>, meta: Meta? = null, owner: Tab? = null, pageTitle: String = "", restore: TaskRec? = null) {
    if (found.contains(u) || subUrls.contains(u) || (!showKnownSeg() && segUrls.contains(u))) return
    // ملف رئيسي (master): نحذف أي جودة فرعية ظهرت قبله لنفس الفيديو ولم يبدأ تحميلها
    if (meta != null && meta.subs.isNotEmpty()) {
        subUrls.addAll(meta.subs)
        for (su in meta.subs) {
            val c = cands[su] ?: continue
            if (c.rw.btn.isEnabled && c.rw.bar.visibility != View.VISIBLE) dropCand(c)
        }
    }
    found.add(u)

    val row = LinearLayout(this)
    row.orientation = LinearLayout.VERTICAL
    row.background = bg(cCard, 14, cLine)
    row.setPadding(dp(12), dp(12), dp(12), dp(12))

    val name = u.substringAfterLast('/').substringBefore('?').take(60)
    val shownName = if (pageTitle.isNotBlank()) pageTitle.take(70) else if (name.isBlank()) "فيديو" else name
    val title = tv(shownName, 14f, cText, true)
    val host = tv("", 12f, cMuted)
    val msg = tv("", 12f, cMuted)
    // شريط تقدم بشكل سلايدر (للعرض فقط، لا يُسحب باللمس)
    val bar = android.widget.SeekBar(this)
    bar.max = 100
    bar.progressTintList = ColorStateList.valueOf(cAccent)
    bar.progressBackgroundTintList = ColorStateList.valueOf(cLine)
    bar.thumbTintList = ColorStateList.valueOf(cAccent)
    bar.setPadding(dp(10), 0, dp(10), 0)
    bar.setOnTouchListener { _, _ -> true }
    bar.visibility = View.GONE

    val btn = dlPill("تحميل", true) { }
    val ctl = Ctl()
    val pauseB = dlPill("إيقاف مؤقت", false) { }
    val cancelB = dlPill("إلغاء", false) { }
    pauseB.visibility = View.GONE
    cancelB.visibility = View.GONE
    pauseB.setOnClickListener {
        ctl.paused = !ctl.paused
        Diag.i("تحميل", (if (ctl.paused) "إيقاف مؤقت" else "استئناف") + " | id=" + TaskStore.id(u) + " | بايتات=" + ctl.bytes.get() + " | الرابط " + u)
        pauseB.text = if (ctl.paused) "استئناف" else "إيقاف مؤقت"
        msg.text = if (ctl.paused) "متوقف مؤقتاً" else "جاري التحميل..."
        cands[u]?.rw?.let { setState(it, if (ctl.paused) 2 else 1) }
    }
    cancelB.setOnClickListener {
        // إلغاء التحميل = إيقاف العملية الجارية وحذفها من القائمة
        cands[u]?.let { deleteRow(it) }
    }
    val rw = Row(bar, msg, btn, pauseB, cancelB, ctl, row)
    rw.url = u
    rw.id = TaskStore.id(u)
    rw.pageUrl = restore?.pageUrl ?: (try { owner?.web?.url ?: "" } catch (e: Throwable) { Diag.swallow("Candidates.kt:517 addFound", e); "" })
    if (!rw.pageUrl.startsWith("http://") && !rw.pageUrl.startsWith("https://")) rw.pageUrl = ""
    rw.quiet = restore != null
    val go: () -> Unit = {
        if (!btn.isEnabled) toast("قيد التحميل أو تم تحميله")
        else {
            rw.started = true
            startDl(u, h, rw)
            if (dlView == 1) setDlView(0) else updateCounts()
        }
    }
    btn.setOnClickListener { go() }
    foundStart[u] = go
    foundHdr[u] = h
    val cand = Cand(u, owner, meta, shownName, rw, row, host, Uri.parse(u).host ?: "", shownName)
    cands[u] = cand
    refreshInfo(cand)
    rw.work = null
    val copy = dlPill("نسخ الرابط", false) { copyText(u) }
    val watchB = dlPill("▶ مشاهدة", false) { watchRow(rw) }
    watchB.visibility = View.GONE
    rw.watch = watchB
    val delB = dlPill("إلغاء", false) { cands[u]?.let { deleteDialog(it) } }
    rw.del = delB
    val classB = dlPill("تصنيف", false) { cands[u]?.let { classifyDialog(it) } }

    // السطر الأول: اسم الفيديو وحالته
    val trow = LinearLayout(this)
    trow.orientation = LinearLayout.HORIZONTAL
    trow.gravity = Gravity.CENTER_VERTICAL
    val chip = tv("ملتقط", 11f, cMuted, true)
    chip.setPadding(dp(10), dp(3), dp(10), dp(3))
    chip.background = bg(Color.TRANSPARENT, 10, cMuted)
    rw.chip = chip
    trow.addView(title, lp(0, WRAP, 1f, 0, 0, 8, 0))
    trow.addView(chip, lp(WRAP, WRAP))

    // أزرار التحميل (أساسية) ثم أزرار الأدوات (ثانوية)
    val btns = LinearLayout(this)
    btns.orientation = LinearLayout.HORIZONTAL
    btns.addView(btn, lp(0, MATCH, 1f, 3, 0, 3, 0))
    btns.addView(pauseB, lp(0, MATCH, 1f, 3, 0, 3, 0))
    btns.addView(cancelB, lp(0, MATCH, 1f, 3, 0, 3, 0))
    val btns2 = LinearLayout(this)
    btns2.orientation = LinearLayout.HORIZONTAL
    btns2.addView(watchB, lp(0, MATCH, 1f, 3, 0, 3, 0))
    btns2.addView(copy, lp(0, MATCH, 1f, 3, 0, 3, 0))
    btns2.addView(classB, lp(0, MATCH, 1f, 3, 0, 3, 0))
    btns2.addView(delB, lp(0, MATCH, 1f, 3, 0, 3, 0))

    // الصورة المصغّرة (أيقونة افتراضية حتى تتوفر صورة) وبجانبها الاسم والمصدر وحالة الحماية والصيغة
    val thumb = ImageView(this)
    thumb.setImageResource(R.drawable.ic_image)
    thumb.setColorFilter(cMuted)
    thumb.scaleType = ImageView.ScaleType.CENTER
    thumb.background = bg(cBg, 10, cLine)
    thumb.clipToOutline = true
    rw.thumb = thumb
    val kindTv = tv("جارٍ الفحص...", 11f, cMuted, true)
    rw.kindTv = kindTv
    val infoCol = LinearLayout(this)
    infoCol.orientation = LinearLayout.VERTICAL
    infoCol.addView(trow)
    infoCol.addView(host)
    infoCol.addView(kindTv, lp(WRAP, WRAP, 0f, 0, 2, 0, 0))
    val topRow = LinearLayout(this)
    topRow.orientation = LinearLayout.HORIZONTAL
    topRow.addView(thumb, lp(dp(96), dp(60), 0f, 0, 0, 10, 0))
    topRow.addView(infoCol, lp(0, WRAP, 1f))
    row.addView(topRow)
    row.addView(bar, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
    row.addView(msg, lp(MATCH, WRAP, 0f, 0, 2, 0, 0))
    row.addView(btns, lp(MATCH, WRAP, 0f, -3, 8, -3, 0))
    row.addView(btns2, lp(MATCH, WRAP, 0f, -3, 6, -3, 0))
    row.tag = rw
    list.addView(row, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
    updateCounts()
    if (restore == null) {
        classifyCand(cand, h)
        val kept = synchronized(videoThumbs) { videoThumbs[u] }
        if (kept != null) setThumb(rw, kept) else findPagePoster(cand, owner, h, 3)
    }
}

// الحفظ الرسمي لفيديو DASH المحمي بنظام Widevine دون اتصال. يرمي ProtectedException بشرح السبب إن لم يكن ممكناً
internal fun MainActivity.offlineProtected(u: String, h: Map<String, String>, r: Row, ctl: Ctl, progress: (Int, Int) -> Unit, pe: ProtectedException): OfflineDrm.Rec {
    val base = u.substringBefore("#tdlrep=")
    val pr = PlayerCore.probe(base, h, "DASH")
    if (pr.prot != 1 || !pr.widevine) {
        throw ProtectedException("هذا الفيديو محمي بنظام غير Widevine، والحفظ دون اتصال غير مدعوم له. اضغط «▶ مشاهدة» لتجربة التشغيل المباشر.")
    }
    val lu = pr.licenseUrl
    if (lu == null) {
        throw ProtectedException("هذا الفيديو محمي بنظام Widevine لكن المنصة لا تعلن رابط الترخيص داخل الملف، فلا يمكن طلب ترخيص للحفظ دون اتصال. القرار للمنصة وترخيصها. جرّب «▶ مشاهدة» للتشغيل المباشر.")
    }
    val nm = foundName[u]?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val title = if (nm.isNullOrBlank()) "tango_" + System.currentTimeMillis() else nm
    try {
        return OfflineDrm.download(
            this, base, h, lu, lowQ(), title,
            { pc, b ->
                ctl.bytes.set(b)
                progress(pc, 100)
            },
            { s -> ui.post { r.msg.text = s } },
            { ctl.cancelled },
            { ctl.paused }
        )
    } catch (e: java.util.concurrent.CancellationException) { Diag.swallow("Candidates.kt:625 offlineProtected", e);
        throw CancelledException()
    } catch (e: ProtectedException) { Diag.swallow("Candidates.kt:627 offlineProtected", e);
        throw e
    } catch (e: Throwable) { Diag.swallow("Candidates.kt:629 offlineProtected", e);
        throw ProtectedException((e.message ?: e.javaClass.simpleName) + "\nيمكنك تجربة «▶ مشاهدة» للتشغيل المباشر.")
    }
}

internal fun MainActivity.enqueue(u: String, h: Map<String, String>, r: Row) {
    if (r.removed) return
    val ctl = r.ctl
    ctl.cancelled = false
    ctl.paused = false
    r.pause.text = "إيقاف مؤقت"
    r.pause.visibility = View.VISIBLE
    r.cancel.visibility = View.VISIBLE
    ctl.bytes.set(0)
    setActive(r, true)
    setState(r, 1)
    val t0 = System.currentTimeMillis()
    dlTrace("بدء الطلب ودخول الطابور", r, "نوع=" + (if (isHls(u)) "HLS" else if (isDash(u)) "DASH" else "مباشر") + " رؤوس=" + h.size)
    var lastT = t0
    var lastB = 0L
    var speed = 0.0
    var lastSave = 0L
    val progress: (Int, Int) -> Unit = { done, total ->
        ui.post {
            r.bar.max = if (total > 0) total else 100
            r.bar.progress = done
            val tn = System.currentTimeMillis()
            if (tn - lastSave > 3000) {
                lastSave = tn
                saveTask(r)
            }
            if (!ctl.paused && !ctl.cancelled) {
                val now = System.currentTimeMillis()
                val b = ctl.bytes.get()
                if (now - lastT >= 800) {
                    val inst = (b - lastB) * 1000.0 / (now - lastT)
                    speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                    lastT = now
                    lastB = b
                }
                val frac = if (total > 0) done.toDouble() / total else 0.0
                val el = (now - t0) / 1000
                val pc = (frac * 100).toInt()
                val sb = StringBuilder()
                if (total > 0) sb.append(pc).append("%  •  ")
                sb.append(fmtSize(b.toDouble()))
                if (speed > 0) sb.append("  •  ").append(fmtSize(speed)).append("/ث")
                sb.append("\n").append("مضى ").append(fmtTime(el))
                if (frac > 0.02) sb.append("  •  متبقي ").append(fmtTime((el * (1 - frac) / frac).toLong()))
                r.msg.text = sb.toString()
            }
        }
    }
    queue.execute {
        // مجلد ثابت لكل رابط: عند الفشل تبقى الأجزاء المكتملة ويكمل من حيث توقف
        val work = File(getExternalFilesDir(null) ?: filesDir, "work_" + TaskStore.id(u))
        var keep = false
        r.work = work
        liveRows[work.absolutePath] = r
        dlTrace("بدأ التنفيذ الفعلي على خيط الطابور", r, "منتظر=" + (System.currentTimeMillis() - t0) + "ms")
        ui.post { r.watch?.visibility = View.VISIBLE }
        try {
            work.mkdirs()
            val name = "tango_" + System.currentTimeMillis()
            var result: String
            val saved: Pair<String, String>
            var offRec: OfflineDrm.Rec? = null
            var rawDrm = false
            if (isHls(u)) {
                val job = hls(u, h, work, ctl, progress)
                ui.post { r.msg.text = "جاري دمج الأجزاء..." }
                val out = mergeHls(job, work, name)
                saved = saveToDownloadsEx(out, "video/mp4")
            } else if (isDash(u)) {
                var job: DashJob? = null
                try {
                    job = dash(u, h, work, ctl, progress)
                } catch (pe: ProtectedException) { Diag.swallow("Candidates.kt:704 enqueue", pe);
                    // محمي: نحاول الحفظ الرسمي دون اتصال (ترخيص من المنصة + أجزاء مشفّرة)، وإلا يُشرح السبب
                    offRec = offlineProtected(u, h, r, ctl, progress, pe)
                }
                if (job != null) {
                    rawDrm = job.drm
                    ui.post { r.msg.text = "جاري دمج الصوت والصورة..." }
                    val out = mergeDash(job, work, name)
                    saved = saveToDownloadsEx(out, "video/mp4")
                } else {
                    saved = Pair("", "")
                }
            } else {
                val nm = foundName[u]?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val mp4 = File(work, if (nm.isNullOrBlank()) "$name.mp4" else nm)
                direct(u, h, mp4, work, ctl, progress)
                saved = saveToDownloadsEx(mp4, foundMime[u] ?: "video/mp4")
            }
            val orr = offRec
            dlTrace("اكتمل التحميل بنجاح", r, "مدة=" + (System.currentTimeMillis() - t0) + "ms محمي=" + (orr != null) + " وجهة=" + (saved.second.ifEmpty { "-" }))
            if (orr != null) {
                // محتوى محمي محفوظ دون اتصال: لا ملف نهائي عادي، التشغيل عبر مشغّل التطبيق فقط
                r.offlineId = orr.id
                ui.post {
                    setKind(r, 1, "DASH/MPD")
                    r.msg.text = OfflineDrm.savedNote()
                    r.bar.progress = r.bar.max
                    r.watch?.visibility = View.VISIBLE
                    if (!r.removed) setState(r, 3)
                }
            } else {
                result = saved.first
                r.finalLoc = saved.second
                r.finalIsUri = saved.second.startsWith("content:")
                if (!rawDrm) thumbFromFile(r, saved.second, r.finalIsUri)
                ui.post {
                    if (rawDrm) setKind(r, 1, "DASH/MPD")
                    r.msg.text = "تم: $result" + (if (rawDrm) "\nتنبيه: الفيديو معلن عنه كمحمي، فالبيانات المحفوظة مشفّرة غالباً ولا تعمل كفيديو عادي. استخدم «المحمية» للحفظ الرسمي دون اتصال إن سمحت المنصة." else "")
                    r.bar.progress = r.bar.max
                    r.watch?.visibility = if (rawDrm) View.GONE else View.VISIBLE
                    if (!r.removed) setState(r, 3)
                }
            }
        } catch (e: CancelledException) { Diag.swallow("Candidates.kt:746 enqueue", e);
            dlTrace("انتهى بالإلغاء", r, "مدة=" + (System.currentTimeMillis() - t0) + "ms")
            ui.post {
                r.msg.text = "أُلغي التحميل"
                r.btn.isEnabled = true
                r.btn.alpha = 1f
                r.bar.visibility = View.GONE
                if (!r.removed) setState(r, 4)
            }
        } catch (e: Throwable) { Diag.swallow("Candidates.kt:754 enqueue", e);
            keep = true
            dlTrace("انتهى بالفشل", r, "مدة=" + (System.currentTimeMillis() - t0) + "ms سبب=" + e.javaClass.simpleName + ": " + (e.message ?: ""))
            logErr("تحميل", "فشل تحميل " + u.substringAfterLast('/').substringBefore('?').take(40) + ": " + (e.message ?: e.javaClass.simpleName), e)
            Diag.i("تحميل", "الرابط الكامل للتحميل الفاشل: " + u)
            val prot = e is ProtectedException
            if (prot) {
                ui.post { setKind(r, 1, if (isDash(u)) "DASH/MPD" else "HLS/M3U8") }
                r.protectedStream = true
                r.srcUrl = u
                r.srcHdr = h
            }
            ui.post {
                r.msg.text = if (prot) (e.message ?: "") else "فشل: " + (e.message ?: e.javaClass.simpleName) + " (اضغط تحميل للإكمال من حيث توقف)"
                r.btn.isEnabled = true
                r.btn.alpha = 1f
                if (prot) r.watch?.visibility = View.VISIBLE
                if (!r.removed) setState(r, 4)
            }
        } finally {
            dlTrace("إغلاق المهمة (finally)", r, "keep=" + keep + " مدة=" + (System.currentTimeMillis() - t0) + "ms")
            if (!keep) {
                // يمنح المشغّل فرصة للانتقال إلى الملف النهائي قبل حذف مجلد العمل
                if (r.finalLoc == null) liveRows.remove(work.absolutePath)
                work.deleteRecursively()
            }
            if (!keep) r.work = null
            ui.post {
                if (r.finalLoc == null && r.work == null) r.watch?.visibility = View.GONE
                r.pause.visibility = View.GONE
                r.cancel.visibility = View.GONE
                setActive(r, false)
            }
        }
    }
}
