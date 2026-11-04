package com.webo

import android.app.AlertDialog
import java.io.File

// سجل التصفح والمفضلة
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.histFile() = File(filesDir, "history.tsv")

// سجل غير محدود: سطر لكل صفحة في ملف (إضافة في نهاية الملف دون إعادة كتابته)
internal fun MainActivity.addHistory(title: String, url: String) {
    if (!url.startsWith("http")) return
    if (url == lastHist) return
    lastHist = url
    val t = title.replace("\t", " ").replace("\n", " ").replace("\r", " ")
    val line = System.currentTimeMillis().toString() + "\t" + t + "\t" + url + "\n"
    imgPool.execute {
        try {
            synchronized(histLock) { histFile().appendText(line) }
        } catch (e: Throwable) { Diag.swallow("HistoryBookmarks.kt:21 addHistory", e);
        }
    }
}

internal fun MainActivity.clearHistory() {
    lastHist = ""
    imgPool.execute {
        try {
            synchronized(histLock) { histFile().delete() }
        } catch (e: Throwable) { Diag.swallow("HistoryBookmarks.kt:31 clearHistory", e);
        }
    }
}

internal fun MainActivity.showHistory() {
    val urls = ArrayList<String>()
    val labels = ArrayList<String>()
    val hNames = ArrayList<String>()
    try {
        val f = histFile()
        if (f.exists()) {
            val lines = synchronized(histLock) { f.readLines() }
            for (k in lines.indices.reversed()) {
                val p = lines[k].split("\t")
                if (p.size < 3) continue
                val u = p[2]
                val t = p[1].ifBlank { hostOf(u) }
                urls.add(u)
                labels.add(t.take(50) + "\n" + u.take(60))
                hNames.add(t.take(50))
            }
        }
    } catch (e: Throwable) { Diag.swallow("HistoryBookmarks.kt:54 showHistory", e);
    }
    val b = StyledBuilder(this).setTitle("سجل التصفح (" + urls.size + ")")
    val hiHolder = arrayOfNulls<AlertDialog>(1)
    if (urls.isEmpty()) b.setMessage("السجل فارغ") else {
        val rows = urls.mapIndexed { i, hu ->
            DlgStyle.siteRow(this, hNames[i], hu.take(60), favOf(hu)) { hiHolder[0]?.dismiss(); load(hu) }
        }
        b.setView(DlgStyle.rowsList(this, rows))
    }
    if (urls.isNotEmpty()) b.setNeutralButton("مسح السجل") { _, _ ->
        clearHistory()
        toast("تم مسح السجل")
    }
    b.setNegativeButton("رجوع", null)
    hiHolder[0] = b.show()
}


internal fun MainActivity.bookmarkList(): List<String> {
    val raw = prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>()
    return raw.toList().sorted()
}

internal fun MainActivity.addBookmark() {
    val u = cur?.web?.url
    if (u == null) {
        toast("افتح صفحة أولاً ثم احفظها")
        return
    }
    val set = HashSet<String>(prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>())
    set.add(u)
    prefs().edit().putStringSet("bm", set).apply()
    toast("تم الحفظ في المفضلة")
    refreshStart()
}

internal fun MainActivity.removeBookmark(u: String) {
    val set = HashSet<String>(prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>())
    set.remove(u)
    prefs().edit().putStringSet("bm", set).apply()
    refreshStart()
}

internal fun MainActivity.showBookmarks() {
    val items = bookmarkList()
    val b = StyledBuilder(this).setTitle("المفضلة (" + items.size + ")")
    val bmHolder = arrayOfNulls<AlertDialog>(1)
    if (items.isEmpty()) b.setMessage("لا توجد صفحات في المفضلة بعد") else {
        val rows = items.map { u ->
            val r = DlgStyle.siteRow(this, hostOf(u), u, favOf(u)) { bmHolder[0]?.dismiss(); load(u) }
            r.setOnLongClickListener {
                removeBookmark(u)
                bmHolder[0]?.dismiss()
                true
            }
            r
        }
        b.setView(DlgStyle.rowsList(this, rows))
    }
    b.setPositiveButton("حفظ الصفحة الحالية") { _, _ -> addBookmark() }
    if (items.isNotEmpty()) b.setNegativeButton("مسح الكل") { _, _ ->
        prefs().edit().remove("bm").apply()
        refreshStart()
    }
    bmHolder[0] = b.show()
}
