package com.webo

import android.app.AlertDialog
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.WebSettings
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.webo.MainActivity.Tab
import java.io.File

// إدارة التبويبات والصفحات المغلقة واسترجاعها
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

// حفظ التبويبات المفتوحة في ملف دائم: تبقى بعد إغلاق التطبيق ولا تُحذف إلا عندما يغلقها المستخدم بنفسه
internal fun MainActivity.saveTabsPrefs() {
    if (restoringTabs) return
    try {
        if (tabs.isEmpty()) {
            prefs().edit().remove("tabs_urls").remove("tabs_starts").remove("tabs_backs").remove("tabs_cur").apply()
            return
        }
        prefs().edit()
            .putString("tabs_urls", tabs.joinToString("\n") { (it.web.url ?: "").replace("\n", "") })
            .putString("tabs_starts", tabs.joinToString(",") { if (it.start) "1" else "0" })
            .putString("tabs_backs", tabs.joinToString(",") { if (it.startBack) "1" else "0" })
            .putInt("tabs_cur", tabs.indexOf(cur).coerceAtLeast(0))
            .apply()
    } catch (e: Throwable) { Diag.swallow("Tabs.kt:32 saveTabsPrefs", e);}
}

// الرجوع داخل التبويب: صفحة سابقة، ثم إغلاق تبويب منبثق، ثم واجهة التطبيق. يعيد false إذا لم يبقَ شيء للرجوع إليه
internal fun MainActivity.backInTab(t: Tab): Boolean {
    if (t.start) {
        // الواجهة ظاهرة: إن فتحناها من زر «الرئيسية» نعود للصفحة التي كانت خلفها
        if (t.startBack && t.web.url != null) {
            t.start = false
            t.startBack = false
            updateStart()
            return true
        }
        return false
    }
    if (t.web.canGoBack()) {
        t.web.goBack()
        return true
    }
    // تبويب فتحته صفحة أخرى: نغلقه ونعود لما قبله
    if (t.popup && tabs.size > 1) {
        closeTab(t)
        return true
    }
    // لا توجد صفحة سابقة: نعرض واجهة التطبيق بدل الخروج
    t.start = true
    t.startBack = false
    pruneTab(t)
    hideKb()
    updateStart()
    return true
}

internal fun MainActivity.buildChip(t: Tab) {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.HORIZONTAL
    c.gravity = Gravity.CENTER_VERTICAL
    c.setPadding(dp(12), dp(4), dp(4), dp(4))
    val l = tv("", 12f, cText)
    l.maxLines = 1
    l.ellipsize = TextUtils.TruncateAt.END
    l.maxWidth = dp(110)
    val x = ImageView(this)
    x.setImageResource(R.drawable.ic_close)
    x.setColorFilter(cMuted)
    x.setPadding(dp(5), dp(5), dp(5), dp(5))
    x.setOnClickListener { closeTab(t) }
    c.addView(l, lp(WRAP, WRAP))
    c.addView(x, lp(dp(24), dp(24)))
    c.setOnClickListener {
        switchTo(t)
        showPage(0)
    }
    c.layoutParams = lp(WRAP, WRAP, 0f, 3, 3, 3, 3)
    t.chip = c
    t.label = l
    t.close = x
}

internal fun MainActivity.newTab(url: String, popup: Boolean = false) {
    val t = Tab(makeWeb())
    t.popup = popup
    buildChip(t)
    if (url.isBlank()) t.start = true
    tabs.add(t)
    container.addView(t.web, FrameLayout.LayoutParams(MATCH, MATCH))
    tabsRow.addView(t.chip, tabsRow.childCount - 1)
    relabel()
    switchTo(t)
    showPage(0)
    if (url.isNotBlank()) t.web.loadUrl(fix(url))
    saveTabsPrefs()
}

internal fun MainActivity.removeTab(t: Tab) {
    snapshot(t)
    pruneTab(t)
    tabs.remove(t)
    container.removeView(t.web)
    tabsRow.removeView(t.chip)
    pages.remove(t.web)
    try { t.web.destroy() } catch (e: Throwable) { Diag.swallow("Tabs.kt:111 removeTab", e);}
}

internal fun MainActivity.closeTab(t: Tab) {
    if (tabs.size <= 1) {
        toast("هذا آخر تبويب")
        return
    }
    val i = tabs.indexOf(t)
    val wasCur = cur === t
    removeTab(t)
    relabel()
    if (wasCur) switchTo(tabs[if (i > 0) i - 1 else 0])
    saveTabsPrefs()
}

internal fun MainActivity.moveTab(from: Int, to: Int) {
    if (from == to || from !in tabs.indices) return
    val t = tabs.removeAt(from)
    val ti = to.coerceIn(0, tabs.size)
    tabs.add(ti, t)
    tabsRow.removeView(t.chip)
    tabsRow.addView(t.chip, ti)
    relabel()
}

internal fun MainActivity.closeOthers() {
    val keep = cur ?: return
    for (t in ArrayList(tabs)) if (t !== keep) removeTab(t)
    relabel()
    switchTo(keep)
    toast("تم إغلاق باقي التبويبات")
    saveTabsPrefs()
}

internal fun MainActivity.relabel() {
    tabBtn.text = if (tabs.size > 99) "99+" else "" + tabs.size
    tabBtn.textSize = if (tabs.size > 99) 8f else if (tabs.size > 9) 10f else 12f
    for (i in tabs.indices) {
        val t = tabs[i]
        t.label.text = if (t.title.isNotBlank()) t.title else "تبويب " + (i + 1)
        t.close.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
    }
}

internal fun MainActivity.switchTo(t: Tab) {
    val old = cur
    if (old != null && old !== t && tabs.contains(old)) { swCapture(old); pruneTab(old) }
    cur = t
    for (x in tabs) {
        x.web.visibility = if (x === t) View.VISIBLE else View.GONE
        if (prefs().getBoolean("pausebg", true) && x !== t) x.web.onPause() else x.web.onResume()
        x.chip.background = bg(if (x === t) cAccentSoft else cCard, 16, if (x === t) cAccent else cLine)
    }
    val pu = t.pendingUrl
    if (pu != null) {
        t.pendingUrl = null
        t.web.loadUrl(pu)
    }
    urlBox.setText(t.web.url ?: pu ?: "")
    if (findBar.visibility == View.VISIBLE) closeFind()
    updateStart()
    updateLoadUi()
    updateCounts()
    // الصفحة التي صرت فيها: يبدأ الالتقاط عليها الآن
    if (old !== t && !t.start && !t.web.url.isNullOrEmpty()) ui.post { if (cur === t) { w2rescan(); captureImagesAuto(t) } }
}

internal fun MainActivity.fix(u: String): String {
    val s = u.trim()
    if (s.startsWith("http://") || s.startsWith("https://")) return s
    if (s.contains(" ") || !s.contains(".")) return searchUrl(s)
    return "https://$s"
}

internal fun MainActivity.load(u: String) {
    hideKb()
    // من الشاشة الرئيسية: يُفتح الموقع في تبويب جديد منفصل وتبقى الرئيسية تبويباً مستقلاً
    if (cur?.start == true && u.isNotBlank()) {
        newTab(u)
        return
    }
    cur?.start = false
    cur?.startBack = false
    updateStart()
    cur?.web?.loadUrl(fix(u))
    showPage(0)
}

internal fun MainActivity.stateFile(id: String) = File(filesDir, "closed_$id.bin")

internal fun MainActivity.loadClosed() {
    val s = prefs().getString("closed", "") ?: ""
    for (l in s.split("\n")) {
        val p = l.split("\t")
        if (p.size >= 3 && stateFile(p[0]).exists()) closed.add(Closed(p[0], p[1], p[2]))
    }
}

internal fun MainActivity.saveClosedPrefs() {
    prefs().edit().putString("closed", closed.joinToString("\n") { it.id + "\t" + it.title + "\t" + it.url }).apply()
}

internal fun MainActivity.snapshot(t: Tab) {
    try {
        val url = t.web.url ?: return
        if (!url.startsWith("http")) return
        val b = Bundle()
        if (t.web.saveState(b) == null) return
        val id = System.currentTimeMillis().toString()
        val p = android.os.Parcel.obtain()
        try {
            p.writeBundle(b)
            stateFile(id).writeBytes(p.marshall())
        } finally {
            p.recycle()
        }
        for (x in ArrayList(closed)) if (x.url == url) {
            stateFile(x.id).delete()
            closed.remove(x)
        }
        val title = (if (t.title.isNotBlank()) t.title else url).replace("\t", " ").replace("\n", " ")
        closed.add(0, Closed(id, title, url))
        while (closed.size > 15) {
            val x = closed.removeAt(closed.size - 1)
            stateFile(x.id).delete()
        }
        saveClosedPrefs()
    } catch (e: Throwable) { Diag.swallow("Tabs.kt:237 snapshot", e);
        logErr("كاش", "تعذر حفظ حالة الصفحة المغلقة", e)
    }
}

internal fun MainActivity.reopen(c: Closed) {
    try {
        val d = stateFile(c.id).readBytes()
        var b: Bundle? = null
        val p = android.os.Parcel.obtain()
        try {
            p.unmarshall(d, 0, d.size)
            p.setDataPosition(0)
            b = p.readBundle(javaClass.classLoader)
        } finally {
            p.recycle()
        }
        newTab("")
        val t = cur ?: return
        t.restoring = true
        t.web.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        if (b == null || t.web.restoreState(b) == null) t.web.loadUrl(c.url)
    } catch (e: Throwable) { Diag.swallow("Tabs.kt:259 reopen", e);
        logErr("كاش", "تعذر استرجاع الصفحة، فُتحت من جديد", e)
        newTab(c.url)
    }
}

internal fun MainActivity.showClosed() {
    if (closed.isEmpty()) {
        toast("لا توجد صفحات مغلقة محفوظة")
        return
    }
    val clHolder = arrayOfNulls<AlertDialog>(1)
    val rows = closed.mapIndexed { i, cp ->
        DlgStyle.siteRow(this, (if (cp.title.isNotBlank()) cp.title else cp.url).take(60), hostOf(cp.url), favOf(cp.url)) { clHolder[0]?.dismiss(); reopen(closed[i]) }
    }
    clHolder[0] = StyledBuilder(this)
        .setTitle("الصفحات المغلقة مؤخراً")
        .setView(DlgStyle.rowsList(this, rows))
        .setNegativeButton("مسح الكل") { _, _ ->
            for (x in closed) stateFile(x.id).delete()
            closed.clear()
            saveClosedPrefs()
        }
        .show()
}
