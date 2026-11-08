package com.webo

import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.webo.MainActivity.Tab
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

// أزرار الفيديو والصور داخل الصفحة ومعرض الصور
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.hdrFor(u: String, page: String): Map<String, String> {
    val h = HashMap<String, String>()
    h["User-Agent"] = curUa
    h["Referer"] = page
    h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
    return h
}

internal fun MainActivity.handleVideo(src: String, page: String) {
    // فيديو blob: لا يُحمَّل مباشرة، فنستعمل آخر رابط التقطه التطبيق
    val u = if (src.startsWith("http")) src else bestFor(cur)?.url
    if (u == null) {
        // لا رابط ملتقط بعد: نمسح ما حمّلته الصفحة ثم نعيد المحاولة مرة واحدة
        val w = cur?.web
        if (w != null && !src.startsWith("http")) {
            w.evaluateJavascript(RESCAN_JS, null)
            ui.postDelayed({
                val u2 = bestFor(cur)?.url
                if (u2 == null) {
                    if (src.startsWith("blob:")) fetchBlob(src, "video_" + System.currentTimeMillis() + ".mp4", "video/mp4")
                    else toast("شغّل الفيديو لحظة ثم اضغط مرة أخرى")
                } else {
                    addFound(u2, foundHdr[u2] ?: hdrFor(u2, page), null, cur, cur?.title ?: "")
                    foundStart[u2]?.invoke()
                    toast("تابع التحميل من تبويب التنزيلات")
                }
            }, 1800)
        } else toast("شغّل الفيديو لحظة ثم اضغط مرة أخرى")
        return
    }
    addFound(u, foundHdr[u] ?: hdrFor(u, page), null, cur, cur?.title ?: "")
    foundStart[u]?.invoke()
    toast("تابع التحميل من تبويب التنزيلات")
}

internal fun MainActivity.imgExt(ct: String, src: String): String {
    if (ct.contains("png")) return "png"
    if (ct.contains("webp")) return "webp"
    if (ct.contains("gif")) return "gif"
    if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg"
    val e = src.substringBefore('?').substringAfterLast('.', "").lowercase()
    return if (Regex("[a-z0-9]{2,4}").matches(e)) e else "jpg"
}

// ضغطة مطولة على صورة: نسخ الرابط / مشاركته / تحميل الصورة
internal fun MainActivity.onWebLongPress(w: WebView): Boolean {
    val hit = w.hitTestResult
    val t = hit.type
    if (t != WebView.HitTestResult.IMAGE_TYPE && t != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
    val src = hit.extra ?: return false
    if (src.isBlank()) return false
    val pg = w.url ?: ""
    StyledBuilder(this)
        .setTitle("الصورة")
        .setItems(arrayOf("نسخ الرابط", "مشاركة الرابط", "تحميل الصورة")) { _, i ->
            when (i) {
                0 -> handleImage(src, pg, "copy")
                1 -> {
                    if (src.startsWith("http")) {
                        val sh = Intent(Intent.ACTION_SEND)
                        sh.type = "text/plain"
                        sh.putExtra(Intent.EXTRA_TEXT, src)
                        startActivity(Intent.createChooser(sh, "مشاركة الرابط"))
                    } else toast("لا يوجد رابط يمكن مشاركته لهذه الصورة")
                }
                else -> handleImage(src, pg, "dl")
            }
        }
        .show()
    return true
}

internal fun MainActivity.handleImage(src: String, page: String, act: String) {
    if (act == "copy") {
        if (src.isBlank()) toast("لا يوجد رابط للصورة") else copyText(src)
        return
    }
    if (!src.startsWith("http")) {
        toast("هذه الصورة لا يمكن تحميلها مباشرة، جرّب نسخ الرابط")
        return
    }
    val h = hdrFor(src, page)
    toast("جاري تحميل الصورة...")
    imgPool.execute {
        try {
            val c = conn(src, h)
            val ext = imgExt((c.contentType ?: "").lowercase(), src)
            val f = File(cacheDir, "tango_img_" + System.currentTimeMillis() + "." + ext)
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val mime = if (ext == "jpg") "image/jpeg" else "image/" + ext
            val r = saveToDownloads(f, mime)
            ui.post { toast("تم حفظ الصورة: " + r) }
        } catch (e: Throwable) { Diag.swallow("PageMedia.kt:115 handleImage", e);
            logErr("صورة", "فشل تحميل الصورة: " + (e.message ?: e.javaClass.simpleName), e)
            ui.post { toast("فشل تحميل الصورة، راجع التنبيهات") }
        }
    }
}

internal fun MainActivity.saveImageSync(src: String, page: String, onProgress: ((Long, Long) -> Unit)? = null): String {
    val loc = rcLocal(src) ?: throw Exception("الصورة غير ملتقطة")
    val ext0 = imgExt(loc.second.lowercase(), src)
    val f0 = File(cacheDir, "tango_img_" + System.nanoTime() + "." + ext0)
    loc.first.copyTo(f0, true)
    onProgress?.invoke(f0.length(), f0.length())
    return saveToDownloads(f0, if (ext0 == "jpg") "image/jpeg" else "image/" + ext0)
}

internal fun MainActivity.showImagesSection() {
    val t = cur
    val page = t?.web?.url ?: ""
    val pre = t?.capImgs
    // الصور ملتقطة تلقائياً: الزر يعرضها فقط، وتفتح الصفحة دائماً حتى لو لم تُلتقط صور
    openImagesDialog(pre ?: emptyList(), page)
}

// يبدأ التقاط صور الصفحة التي أقف فيها تلقائياً عند فتحها، ويحذفها pruneTab عند الخروج
internal fun MainActivity.captureImagesAuto(t: Tab) {
    val key = t.lastUrl
    if (t.capSched == key) return
    t.capSched = key
    collectMerge(t, key)
}

// ظهور صورة جديدة في الصفحة التي أقف فيها: يُحدَّث الالتقاط
internal fun MainActivity.imagesChanged(t: Tab) {
    if (cur !== t || t.capSched.isEmpty() || t.capSched != t.lastUrl) return
    val key = t.lastUrl
    t.capRun?.let { ui.removeCallbacks(it) }
    val run = Runnable { collectMerge(t, key) }
    t.capRun = run
    ui.postDelayed(run, 1000L)
}

internal fun MainActivity.collectMerge(t: Tab, key: String) {
    if (!tabs.contains(t) || cur !== t || t.lastUrl != key || t.capSched != key) return
    collectImages(t) { cap ->
        if (cap != null && cap.isNotEmpty() && tabs.contains(t) && cur === t && t.lastUrl == key && t.capSched == key) {
            val old = t.capImgs
            if (old == null) t.capImgs = cap
            else {
                val seen = HashSet<String>()
                val m = ArrayList<GImg>()
                for (g in old) if (seen.add(g.u)) m.add(g)
                for (g in cap) if (seen.add(g.u)) m.add(g)
                t.capImgs = m
            }
            t.capImgs?.let { imgLive?.invoke(it) }
        }
    }
}

internal fun MainActivity.collectImages(t: Tab, done: (List<GImg>?) -> Unit) {
    val js = "(function(){try{var o=[],s={};function add(u,w,h){if(!u||s[u])return;if(u.indexOf('http')!==0)return;s[u]=1;o.push([u,w|0,h|0]);}" +
        "var im=document.images;for(var i=0;i<im.length;i++){var e=im[i];var u=e.currentSrc||e.src||e.getAttribute('data-src')||e.getAttribute('data-original')||'';" +
        "if(u&&u.indexOf('http')!==0){try{u=new URL(u,location.href).href}catch(x){try{TDL.dg('PageMedia.kt:141 showImagesSection.js '+(x&&x.stack||x))}catch(_q){}}}" +
        "add(u,e.naturalWidth||e.width,e.naturalHeight||e.height);}" +
        "var all=document.querySelectorAll('*');var n=all.length;for(var j=0;j<n;j++){var b=getComputedStyle(all[j]).backgroundImage;" +
        "if(b&&b.indexOf('url(')>=0){var m=/url\\([\"']?([^\"')]+)/.exec(b);if(m)add(m[1],all[j].clientWidth,all[j].clientHeight);}}" +
        "return JSON.stringify(o)}catch(x){try{TDL.dg('PageMedia.kt:145 showImagesSection '+(x&&x.stack||x))}catch(_q){}return '[]'}})()"
    try {
        t.web.evaluateJavascript(js) { res ->
            val list = ArrayList<GImg>()
            try {
                val v = org.json.JSONTokener(res ?: "").nextValue()
                if (v is String) {
                    val a = org.json.JSONArray(v)
                    for (i in 0 until a.length()) {
                        val x = a.getJSONArray(i)
                        list.add(GImg(x.getString(0), x.getInt(1), x.getInt(2)))
                    }
                }
            } catch (e: Throwable) { Diag.swallow("PageMedia.kt collectImages", e);
            }
            galPool.execute {
                val cap = ArrayList<GImg>()
                for (g in list) if (rcLocal(g.u) != null) cap.add(g)
                ui.post { done(cap) }
            }
        }
    } catch (e: Throwable) { Diag.swallow("PageMedia.kt collectImages", e);
        done(null)
    }
}

internal fun MainActivity.openImagesDialog(all0: List<GImg>, page: String) {
    val all = ArrayList<GImg>(all0)
    val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
    val root = LinearLayout(this)
    root.orientation = LinearLayout.VERTICAL
    root.setBackgroundColor(cBg)
    root.setPadding(dp(12), dp(24), dp(12), dp(12))
    val title = tv("", 16f, cText, true)
    root.addView(title, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))

    fun badgeBtn(label: String, on: Boolean): Triple<LinearLayout, TextView, TextView> {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = android.view.Gravity.CENTER
        box.setPadding(dp(12), 0, dp(12), 0)
        box.minimumHeight = dp(46)
        val t = tv(label, 13f, if (on) Color.WHITE else cAccent, true)
        val n = tv("0", 12f, Color.WHITE, true)
        n.visibility = View.GONE
        n.gravity = android.view.Gravity.CENTER
        n.minWidth = dp(22)
        n.setPadding(dp(6), dp(1), dp(6), dp(1))
        n.background = bg(Color.parseColor("#E53935"), 11)
        box.addView(t, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        box.addView(n, lp(WRAP, WRAP))
        box.background = if (on) bg(cAccent, 18) else bg(Color.TRANSPARENT, 18, cAccent)
        return Triple(box, t, n)
    }
    fun blinkNum(n: TextView, seen: Boolean, count: Int) {
        n.text = count.toString()
        n.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (seen || count == 0) { n.clearAnimation(); n.alpha = 1f }
        else if (n.animation == null) {
            val an = android.view.animation.AlphaAnimation(1f, 0.25f)
            an.duration = 600
            an.repeatMode = android.view.animation.Animation.REVERSE
            an.repeatCount = android.view.animation.Animation.INFINITE
            n.startAnimation(an)
        }
    }
    val (cntW, cntB, cntN) = badgeBtn("عداد التقاط الصور", true)
    cntB.maxLines = 1
    cntB.ellipsize = TextUtils.TruncateAt.END
    cntW.setPadding(dp(10), 0, dp(10), 0)
    // النص وحده يضيق (بثلاث نقاط) ويبقى الرقم بمقاسه؛ ولا يتمدد النص فيبقى الاثنان متلاصقين في الوسط
    cntW.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
        val reserve = if (cntN.visibility == View.VISIBLE) cntN.width + dp(6) else 0
        val avail = ((r - l) - cntW.paddingLeft - cntW.paddingRight - reserve).coerceAtLeast(dp(20))
        if (cntB.maxWidth != avail) cntW.post { cntB.maxWidth = avail }
    }
    val dlsB = pill("التحميلات", false) { }.also { it.height = dp(46) }
    val closeB = pill("إغلاق", false) { dlg.dismiss() }.also { it.height = dp(46) }
    val nav = LinearLayout(this)
    nav.orientation = LinearLayout.HORIZONTAL
    nav.addView(cntW, lp(0, WRAP, 1.6f, 0, 0, 6, 0))
    nav.addView(dlsB, lp(0, WRAP, 1f, 0, 0, 6, 0))
    nav.addView(closeB, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
    root.addView(nav, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))

    // صفحة عداد التقاط الصور
    val cntBox = LinearLayout(this)
    cntBox.orientation = LinearLayout.VERTICAL
    root.addView(cntBox, lp(MATCH, 0, 1f))
    // صفحة التحميلات
    val dlsBox = ScrollView(this)
    val dlsList = LinearLayout(this)
    dlsList.orientation = LinearLayout.VERTICAL
    dlsBox.addView(dlsList, FrameLayout.LayoutParams(MATCH, WRAP))
    dlsBox.visibility = View.GONE
    root.addView(dlsBox, lp(MATCH, 0, 1f))

    val (smallW, smallB, smallN2) = badgeBtn("إظهار الصور الصغيرة", false)
    val allB = pill("تحديد الكل", false) { }.also { it.height = dp(46) }
    val bar1 = LinearLayout(this)
    bar1.orientation = LinearLayout.HORIZONTAL
    bar1.addView(smallW, lp(0, WRAP, 1f, 0, 0, 6, 0))
    bar1.addView(allB, lp(0, WRAP, 1f, 0, 0, 6, 0))
    cntBox.addView(bar1, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))

    val dlB = pill("⬇ تحميل المحدد", true) { }.also { it.height = dp(46) }
    cntBox.addView(dlB, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))

    val sv = ScrollView(this)
    val grid = LinearLayout(this)
    grid.orientation = LinearLayout.VERTICAL
    sv.addView(grid, FrameLayout.LayoutParams(MATCH, WRAP))
    cntBox.addView(sv, lp(MATCH, 0, 1f))

    val side = (resources.displayMetrics.widthPixels - dp(24) - dp(12)) / 3
    var showSmall = false
    var cntSeen = true
    var smallSeen = true
    var lastCnt = 0
    var lastSmall = 0
    var shown = 0

    fun isSmall(g: GImg): Boolean = maxOf(g.w, g.h) in 1..149
    // صفحة العداد: الصور التي لم يُضغط تحميلها بعد
    fun capList(): List<GImg> = all.filter { !it.started }
    fun visible(): List<GImg> = if (showSmall) capList() else capList().filter { !isSmall(it) }

    fun paint(g: GImg) {
        val m = g.mark ?: return
        m.text = if (g.sel) "✓" else ""
        m.background = bg(if (g.sel) cAccent else Color.argb(120, 0, 0, 0), 14, Color.WHITE)
        g.cell?.alpha = if (g.sel) 0.75f else 1f
    }

    fun updateTitle() {
        val vis = visible()
        val cap = capList()
        val hidden = cap.size - vis.size
        title.text = if (shown == 1) "تحميلات الصور: " + all.count { it.started }
            else ""
        title.visibility = if (title.text.isEmpty()) View.GONE else View.VISIBLE
        cntB.text = "عداد التقاط الصور"
        if (vis.size > lastCnt) cntSeen = false
        lastCnt = vis.size
        blinkNum(cntN, cntSeen, vis.size)
        val smallN = cap.count { isSmall(it) }
        smallB.text = if (showSmall) "إخفاء الصور الصغيرة" else "إظهار الصور الصغيرة"
        if (smallN > lastSmall) smallSeen = showSmall
        if (showSmall) smallSeen = true
        lastSmall = smallN
        blinkNum(smallN2, smallSeen, smallN)
        allB.text = if (vis.isNotEmpty() && vis.all { it.sel }) "إلغاء التحديد" else "تحديد الكل"
    }

    fun loadThumb(g: GImg) {
        if (g.loaded) return
        g.loaded = true
        galPool.execute {
            try {
                val loc = rcLocal(g.u) ?: return@execute
                val d = loc.first.inputStream().use { readLimited(it, 8000000) }
                val o = android.graphics.BitmapFactory.Options()
                o.inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, o)
                var ss = 1
                while (o.outWidth / ss > 320) ss *= 2
                val o2 = android.graphics.BitmapFactory.Options()
                o2.inSampleSize = ss
                val bmp = android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, o2)
                if (bmp != null) {
                    ui.post {
                        val iv = g.iv
                        if (iv != null && g.loaded) {
                            iv.clearColorFilter()
                            iv.scaleType = ImageView.ScaleType.CENTER_CROP
                            iv.setImageBitmap(bmp)
                        }
                    }
                }
            } catch (e: Throwable) { Diag.swallow("PageMedia.kt:275 loadThumb", e);
            }
        }
    }

    fun makeCell(g: GImg): FrameLayout {
        val c = FrameLayout(this)
        c.background = bg(cCard, 8, cLine)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER
        iv.setImageResource(R.drawable.ic_image)
        iv.setColorFilter(cMuted)
        c.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        val mk = tv("", 16f, Color.WHITE, true)
        mk.gravity = Gravity.CENTER
        val mp = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.START)
        mp.setMargins(dp(4), dp(4), 0, 0)
        c.addView(mk, mp)
        if (g.w > 0 && g.h > 0) {
            val sz = tv(g.w.toString() + "×" + g.h, 10f, Color.WHITE)
            sz.setPadding(dp(4), dp(1), dp(4), dp(1))
            sz.background = bg(Color.argb(140, 0, 0, 0), 6)
            val sp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
            sp.setMargins(0, 0, dp(4), dp(4))
            c.addView(sz, sp)
        }
        g.cell = c
        c.tag = g
        g.iv = iv
        g.mark = mk
        c.setOnClickListener {
            g.sel = !g.sel
            paint(g)
            updateTitle()
        }
        paint(g)
        return c
    }

    // يفرغ معاينة الصورة من الذاكرة (يبقى تحديدها كما هو)
    fun unloadThumb(g: GImg) {
        if (!g.loaded) return
        g.loaded = false
        val iv = g.iv ?: return
        iv.setImageResource(R.drawable.ic_image)
        iv.setColorFilter(cMuted)
        iv.scaleType = ImageView.ScaleType.CENTER
    }

    // تُحمَّل المعاينات للصفوف القريبة من الشاشة فقط، وتُفرَّغ البعيدة، فتبقى الذاكرة قليلة مهما كثرت الصور
    fun loadVisible() {
        val top = sv.scrollY - sv.height
        val bottom = sv.scrollY + sv.height * 2
        for (ri in 0 until grid.childCount) {
            val r = grid.getChildAt(ri) as? ViewGroup ?: continue
            val far = r.bottom < top || r.top > bottom
            for (ci in 0 until r.childCount) {
                val g = r.getChildAt(ci).tag as? GImg ?: continue
                if (far) unloadThumb(g) else loadThumb(g)
            }
        }
    }

    fun refresh() {
        for (g in all) (g.cell?.parent as? ViewGroup)?.removeView(g.cell)
        grid.removeAllViews()
        val vis = visible()
        for (g in all) if (!vis.contains(g)) unloadThumb(g)
        var i = 0
        while (i < vis.size) {
            val rowL = LinearLayout(this)
            rowL.orientation = LinearLayout.HORIZONTAL
            for (k in i until minOf(i + 3, vis.size)) {
                val g = vis[k]
                val c = g.cell ?: makeCell(g)
                c.setOnClickListener {
                    g.sel = !g.sel
                    paint(g)
                    updateTitle()
                }
                rowL.addView(c, lp(side, side, 0f, 0, 0, 4, 4))
            }
            grid.addView(rowL, lp(MATCH, WRAP))
            i += 3
        }
        updateTitle()
        grid.post { loadVisible() }
    }

    fun showView(v: Int) {
        shown = v
        cntBox.visibility = if (v == 0) View.VISIBLE else View.GONE
        dlsBox.visibility = if (v == 1) View.VISIBLE else View.GONE
        cntW.background = if (v == 0) bg(cAccent, 18) else bg(Color.TRANSPARENT, 18, cAccent)
        cntB.setTextColor(if (v == 0) Color.WHITE else cAccent)
        styleChip(dlsB, v == 1)
        updateTitle()
        if (v == 0) grid.post { loadVisible() }
    }

    // صف صورة في صفحة التحميلات: يبقى فيها سواء جارٍ أو مكتمل أو فشل
    fun addRow(g: GImg) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = bg(cCard, 12)
        r.setPadding(dp(8), dp(8), dp(8), dp(8))
        val th = ImageView(this)
        val bmp = (g.iv?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (bmp != null) {
            th.scaleType = ImageView.ScaleType.CENTER_CROP
            th.setImageBitmap(bmp)
        } else {
            th.scaleType = ImageView.ScaleType.CENTER
            th.setImageResource(R.drawable.ic_image)
            th.setColorFilter(cMuted)
        }
        r.addView(th, lp(dp(48), dp(48)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val nm = tv(g.u.substringBefore('?').substringBefore('#').substringAfterLast('/').ifEmpty { "image" }, 13f, cText)
        nm.maxLines = 1
        nm.ellipsize = TextUtils.TruncateAt.END
        nm.textDirection = View.TEXT_DIRECTION_LTR
        col.addView(nm, lp(MATCH, WRAP))
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.isIndeterminate = true
        col.addView(pb, lp(MATCH, dp(4), 0f, 0, 6, 0, 0))
        r.addView(col, lp(0, WRAP, 1f, 10, 0, 10, 0))
        val chip = tv("جارٍ", 11f, cAccent, true)
        r.addView(chip, lp(WRAP, WRAP))
        g.chip = chip
        g.pbar = pb
        dlsList.addView(r, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
    }

    // نسبة التقدم الحقيقية (إن عُرف حجم الملف) وإلا يبقى الشريط متحركاً
    fun progressImg(g: GImg, done: Long, total: Long) {
        if (g.state != 1 || total <= 0) return
        val pc = (done * 100 / total).toInt().coerceIn(0, 100)
        g.pbar?.let { it.isIndeterminate = false; it.max = 100; it.progress = pc }
        g.chip?.text = "جارٍ " + pc + "%"
    }

    fun finishImg(g: GImg, st: Int) {
        g.state = st
        g.pbar?.visibility = View.GONE
        g.chip?.text = if (st == 2) "مكتمل 100%" else "فشل"
        g.chip?.setTextColor(if (st == 2) Color.parseColor("#3DB36B") else Color.parseColor("#E05A5A"))
    }

    cntW.setOnClickListener { cntSeen = true; showView(0) }
    dlsB.setOnClickListener { showView(1) }

    sv.viewTreeObserver.addOnScrollChangedListener { loadVisible() }
    smallW.setOnClickListener {
        showSmall = !showSmall
        refresh()
    }
    allB.setOnClickListener {
        val vis = visible()
        val allSel = vis.isNotEmpty() && vis.all { it.sel }
        for (g in vis) {
            g.sel = !allSel
            paint(g)
        }
        updateTitle()
    }
    dlB.setOnClickListener {
        val sel = all.filter { it.sel && !it.started }
        if (sel.isEmpty()) {
            toast("اختر صوراً أولاً")
        } else {
            toast("جاري تحميل " + sel.size + " صورة...")
            val ok = java.util.concurrent.atomic.AtomicInteger(0)
            val bad = java.util.concurrent.atomic.AtomicInteger(0)
            for (g in sel) {
                g.sel = false
                g.started = true
                g.state = 1
                addRow(g)
            }
            // يُغلق عداد التقاط الصور ويُفتح التحميلات تلقائياً وتظهر فيها الصور
            showView(1)
            refresh()
            for (g in sel) {
                galPool.execute {
                    try {
                        saveImageSync(g.u, page) { done, total ->
                            ui.post { progressImg(g, done, total) }
                        }
                        ok.incrementAndGet()
                        ui.post { finishImg(g, 2) }
                    } catch (e: Throwable) { Diag.swallow("PageMedia.kt:462 openImagesDialog", e);
                        bad.incrementAndGet()
                        ui.post { finishImg(g, 3) }
                        logErr("صورة", "فشل تحميل الصورة: " + (e.message ?: e.javaClass.simpleName), e)
                    }
                    if (ok.get() + bad.get() == sel.size) {
                        ui.post { toast("تم حفظ " + ok.get() + " صورة" + (if (bad.get() > 0) " وفشلت " + bad.get() else "")) }
                    }
                }
            }
        }
    }

    imgLive = { fresh ->
        val have = HashSet<String>()
        for (g in all) have.add(g.u)
        var added = false
        for (g in fresh) if (have.add(g.u)) { all.add(g); added = true }
        if (added) {
            val y = sv.scrollY
            refresh()
            sv.post { sv.scrollTo(0, y) }
        }
    }
    dlg.setOnDismissListener { imgLive = null }
    // إعادة فتح النافذة: تعود صور التحميلات السابقة إلى صفحة التحميلات بحالتها
    for (g in all) if (g.started) {
        addRow(g)
        if (g.state == 2 || g.state == 3) finishImg(g, g.state)
    }
    refresh()
    showView(0)
    dlg.setContentView(root)
    dlg.show()
}
