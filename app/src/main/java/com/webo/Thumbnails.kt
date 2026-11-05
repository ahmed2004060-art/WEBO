package com.webo

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import com.webo.MainActivity.Tab

// الصور المصغّرة للفيديو
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.shrinkBmp(b: android.graphics.Bitmap): android.graphics.Bitmap {
    val m = maxOf(b.width, b.height)
    if (m <= 320) return b
    val k = 320f / m
    return android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
}

// الفيديو حُذف من القائمة: لا فائدة من جلب صورته
internal fun MainActivity.gone(r: Row): Boolean = r.removed || r.thumbStop

internal fun MainActivity.setThumb(r: Row, b: android.graphics.Bitmap?) {
    val iv = r.thumb ?: return
    if (b == null) return
    iv.clearColorFilter()
    iv.scaleType = ImageView.ScaleType.CENTER_CROP
    iv.setImageBitmap(b)
    r.thumbSet = true
    if (r.url.isNotEmpty()) synchronized(videoThumbs) { videoThumbs[r.url] = b }
    r.thumbSaved = false
    if (!r.quiet && r.id.isNotEmpty()) saveTask(r)
}

internal fun MainActivity.readLimited(s: java.io.InputStream, max: Int, stop: () -> Boolean = { false }): ByteArray {
    val o = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16384)
    while (o.size() < max) {
        if (stop()) break
        val n = s.read(buf, 0, minOf(buf.size, max - o.size()))
        if (n < 0) break
        o.write(buf, 0, n)
    }
    return o.toByteArray()
}

internal fun MainActivity.thumbFromUrl(r: Row, src: String, h: Map<String, String>) {
    if (gone(r)) return
    if (posterFail.contains(src)) { r.thumbRetry?.invoke(); return }
    val have = posterBmp[src]
    if (have != null) {
        if (!r.thumbSet) setThumb(r, have)
        return
    }
    val waiting = posterWait[src]
    if (waiting != null) {
        r.thumbBusy = true
        waiting.add(r)
        return
    }
    val rows = java.util.concurrent.CopyOnWriteArrayList<Row>()
    rows.add(r)
    r.thumbBusy = true
    posterWait[src] = rows
    val stop: () -> Boolean = { rows.all { gone(it) } }
    try {
        imgPool.execute {
            var sb: android.graphics.Bitmap? = null
            try {
                if (!stop()) {
                    val d: ByteArray? = if (prefs().getBoolean("rescache", true)) {
                        rcServe(src, h, stop)?.data?.use { readLimited(it, 4000000, stop) }
                    } else {
                        val c = conn(src, h)
                        try { c.inputStream.use { readLimited(it, 4000000, stop) } } finally { try { c.disconnect() } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:76 thumbFromUrl", e);} }
                    }
                    if (d != null && !stop()) {
                        val o = android.graphics.BitmapFactory.Options()
                        o.inJustDecodeBounds = true
                        android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, o)
                        var ss = 1
                        while (o.outWidth / ss > 640) ss *= 2
                        val o2 = android.graphics.BitmapFactory.Options()
                        o2.inSampleSize = ss
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, o2)
                        if (bmp != null) sb = shrinkBmp(bmp)
                    }
                }
            } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:90 thumbFromUrl", e);
            }
            val fin = sb
            ui.post {
                if (fin != null) posterBmp[src] = fin
                else if (!stop()) posterFail.add(src)
                val ws = posterWait.remove(src)
                if (ws != null) for (w in ws) {
                    w.thumbBusy = false
                    if (w.thumbSet || gone(w)) continue
                    if (fin != null) setThumb(w, fin) else if (!stop()) w.thumbRetry?.invoke()
                }
            }
        }
    } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:104 thumbFromUrl", e);
        posterWait.remove(src)
        for (w in rows) w.thumbBusy = false
    }
}

// لقطة من الفيديو: فقط بعد فشل المحاولات الثلاث، ولا تؤخذ أبداً إذا كان «مخزن الصور والأيقونات» مطفأً
internal fun MainActivity.frameFallback(rw: Row, vurl: String, h: Map<String, String>) {
    if (rw.thumbSet || gone(rw) || rw.thumbBusy) return
    if (!prefs().getBoolean("rescache", true)) return
    // الفيديو نُزّل فعلاً: تؤخذ اللقطة من الملف المحفوظ على الجهاز دون استهلاك إنترنت
    val fl = rw.finalLoc
    if (fl != null) { thumbFromFile(rw, fl, rw.finalIsUri); return }
    rw.thumbBusy = true
    try {
        analyzer.execute {
            try {
                if (gone(rw)) return@execute
                val r = MediaMetadataRetriever()
                try {
                    val hh = HashMap<String, String>()
                    for ((k, v) in h) if (v.isNotEmpty()) hh[k] = v
                    r.setDataSource(vurl, hh)
                    if (gone(rw)) return@execute
                    val du = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val fr = try { r.getFrameAtTime(if (du > 4000L) 2000000L else 0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:129 frameFallback", e); null }
                    if (fr != null) {
                        val sb = shrinkBmp(fr)
                        ui.post { if (!rw.thumbSet && !gone(rw)) setThumb(rw, sb) }
                    }
                } finally {
                    try { r.release() } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:135 frameFallback", e);}
                }
            } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:137 frameFallback", e);
            } finally {
                rw.thumbBusy = false
            }
        }
    } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:142 frameFallback", e);
        rw.thumbBusy = false
    }
}

// يسأل الصفحة عن صورة المعاينة (تُعاد المحاولة لأن الصفحة قد لا تكون جاهزة وقت الالتقاط)
internal fun MainActivity.findPagePoster(c: Cand, owner: Tab?, h: Map<String, String>, tries: Int) {
    val t = owner ?: return
    if (c.rw.thumbSet || gone(c.rw)) return
    c.rw.thumbTries = maxOf(tries, 0)
    if (tries <= 0) { frameFallback(c.rw, c.url, h); return }
    val js = "(function(){try{var v=document.querySelector('video[poster]');if(v&&v.poster)return v.poster;" +
        "var m=document.querySelector('meta[property=\"og:image\"],meta[name=\"twitter:image\"]');" +
        "return m&&m.content?m.content:''}catch(e){try{TDL.dg('Thumbnails.kt:155 findPagePoster.js '+(e&&e.stack||e))}catch(_q){}return ''}})()"
    try {
        t.web.evaluateJavascript(js) { res ->
            var s = ""
            try {
                val v = org.json.JSONTokener(res ?: "").nextValue()
                if (v is String) s = v
            } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:162 findPagePoster", e);
            }
            if (s.startsWith("http://") || s.startsWith("https://")) {
                if (!c.rw.thumbSet) {
                    c.rw.thumbRetry = { ui.postDelayed({ findPagePoster(c, owner, h, tries - 1) }, 3000) }
                    thumbFromUrl(c.rw, s, h)
                }
            } else {
                ui.postDelayed({ findPagePoster(c, owner, h, tries - 1) }, 3000)
            }
        }
    } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:173 findPagePoster", e);
    }
}

// لقطة من وسائط قابلة للقراءة (ملف محفوظ)؛ لا تُستخرج من محتوى مشفّر
internal fun MainActivity.thumbFromFile(r: Row, loc: String, isUri: Boolean) {
    if (r.thumbSet || gone(r)) return
    // لقطة من الفيديو: لا تؤخذ أبداً إذا كان المخزن مطفأً، ولا قبل انتهاء المحاولات الثلاث (وقتها تتولاها سلسلة المحاولات)
    if (!prefs().getBoolean("rescache", true)) return
    if (r.thumbTries > 0) return
    if (r.thumbBusy) {
        ui.postDelayed({ thumbFromFile(r, loc, isUri) }, 1500)
        return
    }
    r.thumbBusy = true
    try {
        imgPool.execute {
            val mr = MediaMetadataRetriever()
            try {
                if (isUri) mr.setDataSource(this, Uri.parse(loc)) else mr.setDataSource(loc)
                val dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val at = if (dur > 4000L) 2000000L else 0L
                val fr = mr.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (fr != null) {
                    val sb = shrinkBmp(fr)
                    ui.post { if (!r.thumbSet) setThumb(r, sb) }
                }
            } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:200 thumbFromFile", e);
            } finally {
                try { mr.release() } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:202 thumbFromFile", e);}
                r.thumbBusy = false
            }
        }
    } catch (e: Throwable) { Diag.swallow("Thumbnails.kt:206 thumbFromFile", e);
        r.thumbBusy = false
    }
}
