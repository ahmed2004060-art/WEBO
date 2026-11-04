package com.webo

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// الشاشة الرئيسية: البحث والمواقع والمفضلة والاختصارات
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)


internal fun MainActivity.newHomeTab() {
    if (prefs().getBoolean("startscreen", true)) newTab("") else newTab(prefs().getString("home", HOME) ?: HOME)
}

internal fun MainActivity.updateStart() {
    val on = cur?.start == true
    startView.visibility = if (on) View.VISIBLE else View.GONE
    if (on) refreshStart()
}

internal fun MainActivity.favFile(h: String): java.io.File {
    val dir = java.io.File(cacheDir, "fav")
    dir.mkdirs()
    return java.io.File(dir, h.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
}

internal fun MainActivity.saveFav(h: String, b: android.graphics.Bitmap) {
    try {
        val f = favFile(h)
        if (f.exists()) {
            val o = android.graphics.BitmapFactory.Options()
            o.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth >= b.width) return
        }
        previewPool.execute {
            try {
                java.io.FileOutputStream(f).use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Throwable) { Diag.swallow("StartPage.kt:54 saveFav", e);
            }
        }
    } catch (e: Throwable) { Diag.swallow("StartPage.kt:57 saveFav", e);
    }
}

// شعار الموقع: الذاكرة، ثم الملف المحفوظ، فيبقى ظاهراً بعد إعادة تشغيل التطبيق
internal fun MainActivity.favOf(u: String): android.graphics.Bitmap? {
    val h = try { Uri.parse(u).host } catch (e: Throwable) { Diag.swallow("StartPage.kt:63 favOf", e); null }
    val hit = (if (h != null) favCache[h] else null) ?: favCache[hostOf(u)]
    if (hit != null) return hit
    val key = h ?: hostOf(u)
    return try {
        val f = favFile(key)
        if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.also { favCache[key] = it } else null
    } catch (e: Throwable) { Diag.swallow("StartPage.kt:70 favOf", e);
        null
    }
}

internal fun MainActivity.candThumb(c: Cand): android.graphics.Bitmap? {
    return try {
        if (c.rw.thumbSet) (c.rw.thumb?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap else null
    } catch (e: Throwable) { Diag.swallow("StartPage.kt:78 candThumb", e);
        null
    }
}

internal fun MainActivity.searchUrl(q: String): String {
    val e = URLEncoder.encode(q, "UTF-8")
    return when (prefs().getString("engine", "google")) {
        "ddg" -> "https://duckduckgo.com/?q=" + e
        "bing" -> "https://www.bing.com/search?q=" + e
        else -> "https://www.google.com/search?q=" + e
    }
}

internal fun MainActivity.buildStart(): View {
    val sv = ScrollView(this)
    sv.setBackgroundColor(cBg)
    sv.isFillViewport = true
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER_HORIZONTAL
    col.setPadding(dp(18), dp(32), dp(18), dp(24))

    val logo = ImageView(this)
    logo.setImageResource(R.drawable.logo)
    col.addView(logo, lp(dp(76), dp(76)))
    val nm = tv("WEBO", 22f, cText, true)
    nm.gravity = Gravity.CENTER
    col.addView(nm, lp(WRAP, WRAP, 0f, 0, 8, 0, 18))

    // مربع البحث: أيقونة بحث ثم الحقل
    val sb = LinearLayout(this)
    sb.orientation = LinearLayout.HORIZONTAL
    sb.gravity = Gravity.CENTER_VERTICAL
    sb.background = bg(cCard, 26, cLine)
    sb.setPadding(dp(14), 0, dp(14), 0)
    val si = ImageView(this)
    si.setImageResource(R.drawable.ic_search)
    si.setColorFilter(cAccent)
    startBox = EditText(this)
    startBox.setTextColor(cText)
    startBox.setHintTextColor(cMuted)
    startBox.hint = "ابحث أو اكتب رابطاً"
    startBox.textSize = 15f
    startBox.setBackgroundColor(Color.TRANSPARENT)
    startBox.setSingleLine()
    startBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
    startBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    val go: () -> Unit = {
        val q = startBox.text.toString().trim()
        if (q.isNotEmpty()) {
            startBox.setText("")
            load(q)
        }
    }
    startBox.setOnEditorActionListener { _, id, ev ->
        if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
            go()
            true
        } else false
    }
    si.setOnClickListener { go() }
    sb.addView(si, lp(dp(24), dp(24)))
    sb.addView(startBox, lp(0, dp(50), 1f, 10, 0, 0, 0))
    col.addView(sb, lp(MATCH, WRAP))

    // أيقونات: المفضلة / الاختصارات / السجل / التحميلات
    val ir = LinearLayout(this)
    ir.orientation = LinearLayout.HORIZONTAL
    ir.gravity = Gravity.CENTER
    fun act(res: Int, label: String, f: () -> Unit) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        val ic = ImageView(this)
        ic.setImageResource(res)
        ic.setColorFilter(cAccent)
        ic.background = bg(cCard, 24, cLine)
        ic.setPadding(dp(12), dp(12), dp(12), dp(12))
        c.addView(ic, lp(dp(48), dp(48)))
        val t = tv(label, 11f, cMuted)
        t.gravity = Gravity.CENTER
        c.addView(t, lp(WRAP, WRAP, 0f, 0, 4, 0, 0))
        c.setOnClickListener { f() }
        ir.addView(c, lp(0, WRAP, 1f))
    }
    act(R.drawable.ic_bookmark, "المفضلة") { showBookmarks() }
    act(R.drawable.ic_add, "الاختصارات") { showShortcuts() }
    act(R.drawable.ic_history, "السجل") { showHistory() }
    col.addView(ir, lp(MATCH, WRAP, 0f, 0, 18, 0, 0))

    // المواقع
    val h1 = LinearLayout(this)
    h1.orientation = LinearLayout.HORIZONTAL
    h1.gravity = Gravity.CENTER_VERTICAL
    h1.addView(tv("المواقع", 15f, cText, true), lp(0, WRAP, 1f))
    col.addView(h1, lp(MATCH, WRAP, 0f, 0, 22, 0, 6))
    startSites = LinearLayout(this)
    startSites.orientation = LinearLayout.VERTICAL
    col.addView(startSites, lp(MATCH, WRAP))

    // المفضلة
    col.addView(tv("المفضلة", 15f, cText, true), lp(MATCH, WRAP, 0f, 0, 22, 0, 6))
    startBm = LinearLayout(this)
    startBm.orientation = LinearLayout.VERTICAL
    col.addView(startBm, lp(MATCH, WRAP))

    sv.addView(col)
    startView = sv
    startView.visibility = View.GONE
    return sv
}

internal fun MainActivity.siteTile(label: String, onClick: () -> Unit, onLong: (() -> Unit)?, glyph: String = "", url: String = ""): View {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.gravity = Gravity.CENTER_HORIZONTAL
    c.setPadding(dp(2), dp(6), dp(2), dp(6))
    val favIc = if (glyph.isEmpty() && url.isNotEmpty()) favOf(url) else null
    if (favIc != null) {
        // أيقونة الموقع الحقيقية
        val iv = ImageView(this)
        iv.setImageBitmap(favIc)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = if (favIc.width >= 64) dp(8) else dp(11)
        iv.setPadding(pad, pad, pad, pad)
        if (favIc.width < 64) fetchFavFor(url)
        iv.background = bg(Color.WHITE, 26)
        iv.clipToOutline = true
        iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        regFavView(url, iv)
        c.addView(iv, lp(dp(52), dp(52)))
    } else if (glyph.isNotEmpty()) {
        val ch = tv(glyph, 20f, cAccent, true)
        ch.gravity = Gravity.CENTER
        ch.background = bg(cAccentSoft, 26)
        c.addView(ch, lp(dp(52), dp(52)))
    } else {
        // الموقع لا يعرض أيقونته: أيقونة التطبيق، ونحاول جلب أيقونة الموقع نفسه
        val iv = ImageView(this)
        iv.setImageResource(R.drawable.logo)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(8), dp(8), dp(8), dp(8))
        iv.background = bg(Color.WHITE, 26)
        iv.clipToOutline = true
        iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        regFavView(url, iv)
        c.addView(iv, lp(dp(52), dp(52)))
        if (url.isNotEmpty()) fetchFavFor(url)
    }
    val t = tv(label, 12f, cText)
    t.gravity = Gravity.CENTER
    t.maxLines = 1
    t.ellipsize = TextUtils.TruncateAt.END
    c.addView(t, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
    c.setOnClickListener { onClick() }
    if (onLong != null) c.setOnLongClickListener {
        onLong()
        true
    }
    return c
}

internal fun MainActivity.dlBmp(u: String): android.graphics.Bitmap? {
    return try {
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", favUa)
        val b = c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        c.disconnect()
        b
    } catch (e: Throwable) { Diag.swallow("StartPage.kt:251 dlBmp", e);
        null
    }
}

// يقرأ وسوم <link rel=...icon> من الصفحة الرئيسية ويرتب الأيقونات من الأكبر إلى الأصغر
internal fun MainActivity.iconLinks(h: String): List<String> {
    val out = ArrayList<Pair<Int, String>>()
    try {
        val c = java.net.URL("https://" + h + "/").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", favUa)
        val base = c.url
        val html = c.inputStream.bufferedReader().use { r ->
            val buf = CharArray(80000)
            val n = r.read(buf)
            if (n > 0) String(buf, 0, n) else ""
        }
        c.disconnect()
        val opt = RegexOption.IGNORE_CASE
        for (m in Regex("<link[^>]+>", opt).findAll(html)) {
            val tag = m.value
            val rel = Regex("rel\\s*=\\s*[\"']([^\"']+)[\"']", opt).find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
            if (!rel.contains("icon")) continue
            val href = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", opt).find(tag)?.groupValues?.get(1) ?: continue
            if (href.endsWith(".svg", true) || href.startsWith("data:")) continue
            val sz = Regex("sizes\\s*=\\s*[\"'](\\d+)x\\d+", opt).find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: (if (rel.contains("apple")) 180 else 0)
            val full = try { java.net.URL(base, href).toString() } catch (e: Throwable) { Diag.swallow("StartPage.kt:281 iconLinks", e); continue }
            out.add(Pair(sz, full))
        }
    } catch (e: Throwable) { Diag.swallow("StartPage.kt:284 iconLinks", e);
    }
    return out.sortedByDescending { it.first }.map { it.second }
}

internal fun MainActivity.regFavView(url: String, iv: ImageView) {
    val h = try { Uri.parse(url).host } catch (e: Throwable) { Diag.swallow("StartPage.kt:290 regFavView", e); null } ?: return
    favViews.add(Pair(h, java.lang.ref.WeakReference(iv)))
}

internal fun MainActivity.applyFav(h: String, bmp: android.graphics.Bitmap) {
    val it = favViews.iterator()
    while (it.hasNext()) {
        val e = it.next()
        val iv = e.second.get()
        if (iv == null) { it.remove(); continue }
        if (e.first != h) continue
        iv.setImageBitmap(bmp)
        val pad = if (bmp.width >= 64) dp(8) else dp(11)
        iv.setPadding(pad, pad, pad, pad)
    }
}

internal fun MainActivity.fetchFavFor(u: String) {
    val h = try { Uri.parse(u).host } catch (e: Throwable) { Diag.swallow("StartPage.kt:308 fetchFavFor", e); null }
    if (h == null) return
    // المواقع التي جُرّبت سابقاً لا يُعاد البحث عن شعارها عند كل فتح
    if (prefs().contains("fav_tried_" + h)) return
    if (favTried.putIfAbsent(h, true) != null) return
    favExec.execute {
        try {
            val cands = ArrayList<String>()
            cands.addAll(iconLinks(h).take(3))
            cands.add("https://" + h + "/apple-touch-icon.png")
            cands.add("https://" + h + "/favicon.ico")
            var best: android.graphics.Bitmap? = null
            for (cu in cands) {
                val b = dlBmp(cu) ?: continue
                if (b.width < 16) continue
                if (best == null || b.width > best.width) best = b
                if (b.width >= 120) break
            }
            val bmp = best
            val old = favCache[h]
            if (bmp != null && (old == null || bmp.width > old.width)) {
                favCache[h] = bmp
                saveFav(h, bmp)
                // تحديث أيقونة هذا الموقع فقط، دون إعادة رسم الشاشة
                runOnUiThread {
                    try { applyFav(h, bmp) } catch (e: Throwable) { Diag.swallow("StartPage.kt:333 fetchFavFor", e); }
                }
            }
        } catch (e: Throwable) { Diag.swallow("StartPage.kt:336 fetchFavFor", e);
        }
        try { prefs().edit().putLong("fav_tried_" + h, System.currentTimeMillis()).apply() } catch (e: Throwable) { Diag.swallow("StartPage.kt:338 fetchFavFor", e); }
    }
}

internal fun MainActivity.tileGrid(parent: LinearLayout, tiles: List<View>) {
    var i = 0
    while (i < tiles.size) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (k in 0 until 4) {
            val v = if (i + k < tiles.size) tiles[i + k] else View(this)
            row.addView(v, lp(0, WRAP, 1f))
        }
        parent.addView(row, lp(MATCH, WRAP))
        i += 4
    }
}

internal fun MainActivity.confirmDelete(name: String, ok: () -> Unit) {
    StyledBuilder(this)
        .setTitle(name)
        .setMessage("حذف هذا العنصر؟")
        .setPositiveButton("حذف") { _, _ -> ok() }
        .setNegativeButton("إلغاء", null)
        .show()
}

internal fun MainActivity.refreshStart() {
    favViews.clear()
    startSites.removeAllViews()
    startBm.removeAllViews()
    val sc = loadShortcuts()
    val tiles = ArrayList<View>()
    for (i in sc.indices) {
        val p = sc[i]
        tiles.add(siteTile(p.first, { load(p.second) }, {
            confirmDelete(p.first) {
                val l = loadShortcuts()
                if (i < l.size) l.removeAt(i)
                saveShortcuts(l)
                refreshStart()
            }
        }, "", p.second))
    }
    tiles.add(siteTile("إضافة", { addShortcutDialog() }, null, "+"))
    tileGrid(startSites, tiles)

    val bm = bookmarkList()
    if (bm.isEmpty()) {
        startBm.addView(tv("لا توجد صفحات محفوظة بعد. افتح صفحة ثم احفظها من «الأدوات».", 12f, cMuted))
    } else {
        val bt = ArrayList<View>()
        for (u in bm) bt.add(siteTile(hostOf(u), { load(u) }, { confirmDelete(hostOf(u)) { removeBookmark(u) } }, "", u))
        tileGrid(startBm, bt)
    }
}

internal fun MainActivity.loadShortcuts(): ArrayList<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val raw = prefs().getString("sc", null)
    if (raw == null) {
        out.add(Pair("جوجل", "https://www.google.com"))
        out.add(Pair("يوتيوب", "https://m.youtube.com"))
        out.add(Pair("ويكيبيديا", "https://ar.wikipedia.org"))
        out.add(Pair("تانجو", "https://www.tango.me"))
        out.add(Pair("X", "https://x.com"))
        return out
    }
    try {
        val a = org.json.JSONArray(raw)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Pair(o.getString("n"), o.getString("u")))
        }
    } catch (e: Throwable) { Diag.swallow("StartPage.kt:412 loadShortcuts", e);
    }
    return out
}

internal fun MainActivity.saveShortcuts(l: List<Pair<String, String>>) {
    val a = org.json.JSONArray()
    for (p in l) {
        val o = org.json.JSONObject()
        o.put("n", p.first)
        o.put("u", p.second)
        a.put(o)
    }
    prefs().edit().putString("sc", a.toString()).apply()
}

internal fun MainActivity.addShortcutDialog(n0: String = "", u0: String = "") {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(20), dp(8), dp(20), dp(0))
    val n = EditText(this)
    n.hint = "اسم الموقع"
    n.setText(n0)
    n.setSingleLine()
    val u = EditText(this)
    u.hint = "https://..."
    u.setText(u0)
    u.setSingleLine()
    u.layoutDirection = View.LAYOUT_DIRECTION_LTR
    u.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    u.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    box.addView(n, lp(MATCH, WRAP))
    box.addView(u, lp(MATCH, WRAP))
    StyledBuilder(this)
        .setTitle("إضافة اختصار")
        .setView(box)
        .setPositiveButton("حفظ") { _, _ ->
            val raw = u.text.toString().trim()
            if (raw.isEmpty()) {
                toast("اكتب الرابط")
            } else {
                val fu = fix(raw)
                val name = n.text.toString().trim().ifEmpty { hostOf(fu) }
                val l = loadShortcuts()
                l.add(Pair(name, fu))
                saveShortcuts(l)
                refreshStart()
                toast("تمت إضافة الاختصار")
            }
        }
        .setNegativeButton("إلغاء", null)
        .show()
}

internal fun MainActivity.showShortcuts() {
    val l = loadShortcuts()
    val b = StyledBuilder(this).setTitle("الاختصارات (" + l.size + ")")
    val scHolder = arrayOfNulls<AlertDialog>(1)
    if (l.isEmpty()) b.setMessage("لا توجد اختصارات") else {
        val rows = l.mapIndexed { i, sc ->
            val r = DlgStyle.siteRow(this, sc.first, hostOf(sc.second), favOf(sc.second)) { scHolder[0]?.dismiss(); load(sc.second) }
            r.setOnLongClickListener {
                scHolder[0]?.dismiss()
                confirmDelete(l[i].first) {
                    val x = loadShortcuts()
                    if (i < x.size) x.removeAt(i)
                    saveShortcuts(x)
                    refreshStart()
                }
                true
            }
            r
        }
        b.setView(DlgStyle.rowsList(this, rows))
    }
    b.setPositiveButton("إضافة") { _, _ -> addShortcutDialog() }
    b.setNegativeButton("رجوع", null)
    scHolder[0] = b.show()
}
