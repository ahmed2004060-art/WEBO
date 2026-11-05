package com.webo

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

// شاشة التنزيلات وصفوفها وعدّاداتها
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

// يوقف الخيط أثناء الإيقاف المؤقت ويرمي استثناء عند الإلغاء
internal fun MainActivity.gate(c: Ctl) {
    while (c.paused && !c.cancelled) Thread.sleep(200)
    if (c.cancelled) throw CancelledException()
}

// ينقل صف التحميل إلى قسم "جارٍ التحميل" أثناء النزول ثم يعيده للقائمة
internal fun MainActivity.setActive(r: Row, on: Boolean) {
    (r.view.parent as? ViewGroup)?.removeView(r.view)
    if (r.removed) {
        updateCounts()
        return
    }
    if (on) activeBox.addView(r.view, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
    else list.addView(r.view, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
    updateCounts()
}

internal fun MainActivity.buildDownloads(): View {
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(14), dp(14), dp(14), dp(24))
    val head = LinearLayout(this)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    head.addView(View(this), lp(0, WRAP, 1f))
    head.addView(dlPill("الصور", false) { showImagesSection() }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
    head.addView(dlPill("المحمية", false) { showOfflineManager() }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
    // (١) المخزن بنفس شكل الأزرار المجاورة (إطار بدون تعبئة)
    head.addView(dlPill("المخزن", false) { openStorage() }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
    // (٣) مسح القائمة: إزاحة لليسار بمقدار المسافة بين الأزرار (6dp)
    val clearBtn = dlPill("مسح القائمة", false) { clearFound() }
    head.addView(clearBtn, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
    head.clipChildren = false
    col.clipChildren = false
    col.clipToPadding = false
    col.addView(head, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
    col.addView(tv("شغّل الفيديو داخل المتصفح وستظهر روابطه هنا ثم اضغط تحميل. الملفات تُحفظ في مجلد التحميلات.", 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 0, 0, 10))

    // شريط الأزرار: عداد ملتقط الروابط (مصغّر برقم أحمر يومض) + التحميلات
    dlActions = LinearLayout(this)
    dlActions.orientation = LinearLayout.HORIZONTAL
    dlActions.gravity = Gravity.CENTER_VERTICAL
    badgeBox = LinearLayout(this)
    badgeBox.orientation = LinearLayout.HORIZONTAL
    badgeBox.gravity = Gravity.CENTER
    badgeBox.setPadding(dp(12), 0, dp(8), 0)
    badge = tv("عداد ملتقط الروابط", 12f, Color.WHITE, true)
    badgeNum = tv("0", 12f, Color.WHITE, true)
    badgeNum.visibility = View.GONE
    badgeNum.gravity = Gravity.CENTER
    badgeNum.minWidth = dp(22)
    badgeNum.setPadding(dp(6), dp(1), dp(6), dp(1))
    badgeNum.background = bg(Color.parseColor("#E53935"), 11)
    badgeBox.addView(badge, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
    badgeBox.addView(badgeNum, lp(WRAP, WRAP))
    badgeBox.background = bg(cAccent, 22)
    badgeBox.visibility = View.VISIBLE
    badgeBox.setOnClickListener {
        badgeSeen = true
        setDlView(1)
    }
    // (٤) مسافة قصيرة (6dp) بين عداد ملتقط الروابط وزر التحميلات
    val badgeLp = lp(0, WRAP, 1f)
    badgeLp.marginEnd = dp(6)
    dlActions.addView(badgeBox, badgeLp)
    tabDl = tv("التحميلات", 12f, Color.WHITE, true)
    tabDl.gravity = Gravity.CENTER
    tabDl.setPadding(dp(14), 0, dp(14), 0)
    tabDl.background = bg(cAccent, 22, Color.WHITE)
    tabDl.setOnClickListener { setDlView(0) }
    dlActions.addView(tabDl, lp(0, WRAP, 1f))
    col.addView(dlActions, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
    // (٥) سماكة الزرين = سماكة الأزرار العلوية
    syncDlBtnHeights()

    // شاشة العداد: عدد الملتقطة + زر تحميل الفيديو
    activeTitle = tv("جارٍ التحميل الآن", 16f, cAccent, true)
    col.addView(activeTitle, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
    activeBox = LinearLayout(this)
    activeBox.orientation = LinearLayout.VERTICAL
    col.addView(activeBox, lp(MATCH, WRAP))
    listTitle = tv("قائمة الفيديوهات", 15f, cText, true)
    col.addView(listTitle, lp(MATCH, WRAP, 0f, 0, 6, 0, 6))
    emptyTv = tv("لا توجد روابط ملتقطة بعد", 14f, cMuted)
    emptyTv.gravity = Gravity.CENTER
    emptyTv.setPadding(0, dp(40), 0, dp(40))
    col.addView(emptyTv, lp(MATCH, WRAP))
    list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    col.addView(list, lp(MATCH, WRAP))
    val sv = ScrollView(this)
    sv.setBackgroundColor(cBg)
    sv.addView(col)
    dlScroll = sv
    return sv
}

internal fun MainActivity.allRows(): List<Row> {
    val out = ArrayList<Row>()
    for (box in listOf<LinearLayout>(activeBox, list)) {
        for (i in 0 until box.childCount) (box.getChildAt(i).tag as? Row)?.let { out.add(it) }
    }
    return out
}

internal fun MainActivity.inFilter(r: Row): Boolean = if (dlView == 1) (r.state == 0 && !r.started) else (r.state != 0 || r.started)

internal fun MainActivity.setDlView(v: Int) {
    dlView = v
    updateCounts()
    dlScroll.smoothScrollTo(0, 0)
}

internal fun MainActivity.setState(r: Row, st: Int) {
    val prevSt = r.state
    r.state = st
    dlTrace("تغيّر الحالة " + prevSt + "→" + st + " (" + (when (st) { 1 -> "جارٍ"; 2 -> "متوقف مؤقتاً"; 3 -> "مكتمل"; 4 -> "غير مكتمل"; else -> "ملتقط" }) + ")", r)
    val c = when (st) {
        1 -> cAccent
        2 -> Color.parseColor("#E0A030")
        3 -> Color.parseColor("#3DB36B")
        4 -> Color.parseColor("#E05A5A")
        else -> cMuted
    }
    val tx = when (st) {
        1 -> "جارٍ"
        2 -> "متوقف مؤقتاً"
        3 -> "مكتمل"
        4 -> "غير مكتمل"
        else -> "ملتقط"
    }
    r.del?.visibility = if (st == 1 || st == 2) View.GONE else View.VISIBLE
    r.chip?.let {
        it.text = tx
        it.setTextColor(c)
        it.background = bg(Color.TRANSPARENT, 10, c)
    }
    updateCounts()
    saveTask(r)
}

// حذف تحميل: يوقفه إن كان يعمل ويحذف أجزاءه المؤقتة ويزيله من القائمة (الملف المحفوظ في التحميلات لا يُمس)
internal fun MainActivity.deleteRow(c: Cand) {
    val r = c.rw
    dlTrace("حذف/إلغاء من المستخدم", r)
    r.removed = true
    r.ctl.paused = false
    r.ctl.cancelled = true
    val w = r.work
    if (r.idle) {
        // صف مستعاد لا يعمل فيه تحميل: تُحذف أجزاؤه المؤقتة المحفوظة أيضاً
        val wd = File(getExternalFilesDir(null) ?: filesDir, "work_" + r.id)
        diagThread("DownloadsUi:idleDel") { try { wd.deleteRecursively() } catch (e: Throwable) { Diag.swallow("DownloadsUi.kt idleDel", e) } }.start()
    }
    if (r.state != 1 && r.state != 2 && w != null) {
        diagThread("DownloadsUi:162") { try { w.deleteRecursively() } catch (e: Throwable) { Diag.swallow("DownloadsUi.kt:162 deleteRow", e);} }.start()
        r.work = null
    }
    (r.view.parent as? ViewGroup)?.removeView(r.view)
    forgetTask(r.id)
    found.remove(c.url)
    foundStart.remove(c.url)
    foundHdr.remove(c.url)
    cands.remove(c.url)
    updateCounts()
    toast("تم الحذف")
}

internal fun MainActivity.deleteDialog(c: Cand) {
    val r = c.rw
    val msg = when (r.state) {
        1, 2 -> "سيتوقف التحميل وتُحذف أجزاؤه المؤقتة ويُزال من القائمة."
        3 -> "يُزال من القائمة فقط. الملف المحفوظ يبقى في مجلد التحميلات."
        else -> "يُزال من القائمة مع أي أجزاء مؤقتة محفوظة له."
    }
    StyledBuilder(this)
        .setTitle("حذف التحميل")
        .setMessage(msg)
        .setPositiveButton("حذف") { _, _ -> deleteRow(c) }
        .setNegativeButton("إلغاء", null)
        .show()
}


internal fun MainActivity.startDl(u: String, h: Map<String, String>, r: Row) {
    dlTrace("طلب بدء التحميل من المستخدم", r, "hls=" + isHls(u) + " dash=" + isDash(u) + " رؤوس=" + h.size)
    r.btn.isEnabled = false
    r.btn.alpha = 0.5f
    r.bar.visibility = View.VISIBLE
    r.msg.text = "في الانتظار..."
    if (!isHls(u) && !isDash(u)) {
        enqueue(u, h, r)
        return
    }
    r.msg.text = "جاري قراءة الجودات..."
    prober.execute {
        var vs: List<Variant> = emptyList()
        try {
            vs = if (isDash(u)) dashVariants(u, h) else variants(u, h)
        } catch (e: Exception) { Diag.swallow("DownloadsUi.kt:205 startDl", e);
            logErr("تحميل", "تعذر قراءة الجودات: " + (e.message ?: e.javaClass.simpleName), e)
        }
        ui.post {
            if (vs.size <= 1) enqueue(u, h, r) else chooseQuality(vs, u, h, r)
        }
    }
}


internal fun MainActivity.updateCounts() {
    val n = found.size
    val cap = allRows().count { it.state == 0 && !it.started }
    // الوميض الأحمر عند التقاط فيديو جديد فقط، ويتوقف بفتح العداد
    if (cap > lastFound) badgeSeen = (dlView == 1)
    lastFound = cap
    badgeBox.visibility = View.VISIBLE
    badgeNum.text = cap.toString()
    badgeNum.visibility = if (cap > 0) View.VISIBLE else View.GONE
    if (badgeSeen || cap == 0) { badgeNum.clearAnimation(); badgeNum.alpha = 1f }
    else if (badgeNum.animation == null) {
        val an = android.view.animation.AlphaAnimation(1f, 0.25f)
        an.duration = 600
        an.repeatMode = android.view.animation.Animation.REVERSE
        an.repeatCount = android.view.animation.Animation.INFINITE
        badgeNum.startAnimation(an)
    }
    badgeBox.background = if (dlView == 1) bg(cAccent, 22, Color.WHITE) else bg(cAccent, 22)
    tabDl.background = if (dlView == 0) bg(cAccent, 22, Color.WHITE) else bg(cAccent, 22)
    dlActions.visibility = View.VISIBLE
    val act = activeBox.childCount
    navItems[1].second.text = if (act > 0) "جارٍ ($act)" else if (n > 0) "التنزيلات ($n)" else "التنزيلات"

    val rows = allRows()
    var visAct = 0
    var visList = 0
    for (r in rows) {
        val show = inFilter(r)
        r.view.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { if (r.view.parent === activeBox) visAct++ else visList++ }
    }
    activeTitle.text = "جارٍ التحميل الآن ($visAct)"
    activeTitle.visibility = if (visAct > 0) View.VISIBLE else View.GONE
    listTitle.visibility = if (visList > 0) View.VISIBLE else View.GONE
    val none = visAct + visList == 0
    emptyTv.visibility = if (none && (dlView == 1 || n == 0)) View.VISIBLE else View.GONE
    emptyTv.text = if (dlView == 1) "لا توجد فيديوهات ملتقطة" else "لا توجد روابط ملتقطة بعد"
    if (navItems.size > 4) navItems[4].second.text = if (alerts.isNotEmpty()) "التنبيهات (" + alerts.size + ")" else "التنبيهات"
    alertEmpty.visibility = if (alerts.isNotEmpty()) View.GONE else View.VISIBLE
}

internal fun MainActivity.clearFound() {
    for (cc in cands.values) if (cc.rw.view.parent !== activeBox) cc.rw.thumbStop = true
    for (cc in cands.values) forgetTask(cc.rw.id)
    list.removeAllViews()
    found.clear()
    cands.clear()
    considered.clear()
    subUrls.clear()
    foundStart.clear()
    foundHdr.clear()
    updateCounts()
}

// مشاهدة الفيديو: النهائي إن اكتمل، وإلا الجزء المحمّل حتى الآن
internal fun MainActivity.watchRow(r: Row) {
    val oid = r.offlineId
    if (oid != null) {
        playOffline(oid)
        return
    }
    val su = r.srcUrl
    if (r.protectedStream && r.finalLoc == null && su != null) {
        playStream(su, r.srcHdr ?: emptyMap())
        return
    }
    val fl = r.finalLoc
    if (fl != null) {
        playVideo(fl, r.finalIsUri, r.engine)
        return
    }
    val w = r.work
    if (w == null || !w.exists()) {
        toast("لم يُحمَّل أي جزء بعد")
        return
    }
    if (r.state == 1 || r.state == 2) {
        // تشغيل أثناء التنزيل: المشغّل يتابع الأجزاء الجديدة ويكمل تلقائياً
        playLive(w.absolutePath)
        return
    }
    toast("جاري تجهيز الجزء المحمّل للمشاهدة...")
    try {
        previewPool.execute {
            var f: File? = null
            try {
                f = buildPreview(w)
            } catch (e: Throwable) { Diag.swallow("DownloadsUi.kt:302 watchRow", e);
                logErr("مشاهدة", "تعذر تجهيز المعاينة: " + (e.message ?: e.javaClass.simpleName), e)
            }
            val pf = f
            ui.post {
                if (pf == null) toast("لم يُحمَّل جزء كافٍ للمشاهدة بعد، انتظر قليلاً")
                else playVideo(pf.absolutePath, false)
            }
        }
    } catch (e: Throwable) { Diag.swallow("DownloadsUi.kt:311 watchRow", e);
    }
}
