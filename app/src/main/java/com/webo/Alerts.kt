package com.webo

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

// سجل التنبيهات والأخطاء
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.capNote(kind: String, u: String, why: String) {
    val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  [" + kind + "]  " + why + "\n" + u.take(220)
    synchronized(capLog) { capLog.add(line); val lc = prefs().getInt("logcap", 0); while (lc > 0 && capLog.size > lc) capLog.removeAt(0) }
}

internal fun MainActivity.showCapLog() {
    val all = synchronized(capLog) { if (capLog.isEmpty()) "لا توجد سجلات بعد. افتح صفحة فيها فيديوهات ثم عد هنا." else capLog.asReversed().joinToString("\n\n") }
    val ls = prefs().getInt("logshow", 0)
    val txt = if (ls > 0) all.split("\n\n").take(ls).joinToString("\n\n") else all
    val capEmpty = synchronized(capLog) { capLog.isEmpty() }
    val capB = StyledBuilder(this).setTitle("سجل الالتقاط (لماذا ظهر الرابط أو أُهمل)")
    if (capEmpty) capB.setMessage(txt) else capB.setView(DlgStyle.logCards(this, txt.split("\n\n")))
    capB
        .setPositiveButton("نسخ") { _, _ -> copyText(all) }
        .setNeutralButton("مسح") { _, _ -> synchronized(capLog) { capLog.clear() } }
        .setNegativeButton("إغلاق", null).show()
}


internal fun MainActivity.buildAlerts(): View {
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(14), dp(14), dp(14), dp(24))
    val head = LinearLayout(this)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    head.addView(tv("تنبيهات التطبيق", 20f, cText, true), lp(0, WRAP, 1f))
    head.addView(dlPill("نسخ الكل", false) {
        copyText(alerts.joinToString("\n\n") { it.kind + " | " + it.msg + "\n" + it.detail })
    }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
    head.addView(dlPill("مسح", false) { clearAlerts() }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
    col.addView(head, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
    col.addView(tv("أي خطأ في التحميل أو الدمج أو الصفحات أو التطبيق يظهر هنا. اضغط على التنبيه لعرض التفاصيل.", 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 0, 0, 12))
    alertEmpty = tv("لا توجد أخطاء", 14f, cMuted)
    alertEmpty.gravity = Gravity.CENTER
    alertEmpty.setPadding(0, dp(40), 0, dp(40))
    col.addView(alertEmpty, lp(MATCH, WRAP))
    alertList = LinearLayout(this)
    alertList.orientation = LinearLayout.VERTICAL
    col.addView(alertList, lp(MATCH, WRAP))
    val sv = ScrollView(this)
    sv.setBackgroundColor(cBg)
    sv.addView(col)
    return sv
}

// يمكن استدعاؤها من أي خيط
internal fun MainActivity.logErr(kind: String, msg: String, t: Throwable? = null) {
    Diag.e("تنبيه-" + kind, msg, t)
    val detail = if (t != null) android.util.Log.getStackTraceString(t).lines().take(14).joinToString("\n") else ""
    val a = AlertItem(System.currentTimeMillis(), kind, msg, detail)
    ui.post { addAlert(a, true) }
}

internal fun MainActivity.addAlert(a: AlertItem, persist: Boolean) {
    alerts.add(0, a)
    if (alerts.size > 200) {
        alerts.removeAt(alerts.size - 1)
        if (alertList.childCount > 0) alertList.removeViewAt(alertList.childCount - 1)
    }
    val row = LinearLayout(this)
    row.orientation = LinearLayout.VERTICAL
    row.background = bg(cCard, 14, cLine)
    row.setPadding(dp(12), dp(12), dp(12), dp(12))
    val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(a.time))
    row.addView(tv(a.kind + "  •  " + time, 12f, cMuted))
    row.addView(tv(a.msg, 14f, cText, true))
    if (a.detail.isNotBlank()) {
        val d = tv(a.detail, 11f, cMuted)
        d.layoutDirection = View.LAYOUT_DIRECTION_LTR
        d.visibility = View.GONE
        row.addView(d, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
        row.setOnClickListener { d.visibility = if (d.visibility == View.GONE) View.VISIBLE else View.GONE }
    }
    row.addView(dlPill("نسخ", false) { copyText(a.kind + " | " + a.msg + "\n" + a.detail) }, lp(WRAP, WRAP, 0f, 0, 8, 0, 0))
    alertList.addView(row, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
    if (persist) persistAlert(a)
    updateCounts()
}

internal fun MainActivity.persistAlert(a: AlertItem) {
    try {
        val f = File(filesDir, "alerts.tsv")
        if (f.length() > 200000) f.delete()
        val line = listOf(a.time.toString(), a.kind, a.msg, a.detail).joinToString("\t") { it.replace("\t", " ").replace("\n", "␤") }
        f.appendText(line + "\n")
    } catch (e: Exception) { Diag.swallow("Alerts.kt:98 persistAlert", e);
    }
}

internal fun MainActivity.loadAlerts() {
    try {
        val f = File(filesDir, "alerts.tsv")
        if (!f.exists()) return
        for (l in f.readLines().takeLast(100)) {
            val p = l.split("\t")
            if (p.size < 4) continue
            addAlert(AlertItem(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3].replace("␤", "\n")), false)
        }
    } catch (e: Exception) { Diag.swallow("Alerts.kt:111 loadAlerts", e);
    }
}

internal fun MainActivity.clearAlerts() {
    alerts.clear()
    alertList.removeAllViews()
    try { File(filesDir, "alerts.tsv").delete() } catch (e: Exception) { Diag.swallow("Alerts.kt:118 clearAlerts", e); }
    updateCounts()
}


// يحذف مجلدات التحميل غير المكتملة الأقدم من 3 أيام
internal fun MainActivity.cleanOldWork() {
    try {
        val base = getExternalFilesDir(null) ?: filesDir
        val lim = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        base.listFiles()?.forEach {
            if (it.isDirectory && it.name.startsWith("work_") && it.lastModified() < lim) it.deleteRecursively()
        }
    } catch (e: Exception) { Diag.swallow("Alerts.kt:131 cleanOldWork", e);
    }
}
