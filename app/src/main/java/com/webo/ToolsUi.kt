package com.webo

import android.app.DownloadManager
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

// شاشة الأدوات والإجراءات السريعة
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.buildTools(): View {
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(14), dp(14), dp(14), dp(24))
    col.addView(tv("الأدوات", 20f, cText, true), lp(MATCH, WRAP, 0f, 0, 0, 0, 12))

    val card = LinearLayout(this)
    card.orientation = LinearLayout.VERTICAL
    card.background = bg(cCard, 14, cLine)
    card.setPadding(dp(12), dp(12), dp(12), dp(12))
    card.addView(tv("إضافة رابط يدوياً (m3u8 / mp4 / أي ملف)", 14f, cText, true))
    manualBox = EditText(this)
    manualBox.setTextColor(cText)
    manualBox.setHintTextColor(cMuted)
    manualBox.hint = "https://..."
    manualBox.textSize = 13f
    manualBox.background = bg(cBg, 10, cLine)
    manualBox.setPadding(dp(12), dp(8), dp(12), dp(8))
    manualBox.layoutDirection = View.LAYOUT_DIRECTION_LTR
    manualBox.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    manualBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    card.addView(manualBox, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
    card.addView(pill("إضافة للتحميلات", true) { addManual() }.also { it.height = dp(46) }, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
    col.addView(card, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))

    section(col, "التصفح")
    grid(col, listOf(
        toolCard(R.drawable.ic_search, "بحث بالصفحة") { openFind() },
        toolCard(R.drawable.ic_paste, "فتح من الحافظة") { openFromClip() },
        toolCard(R.drawable.ic_web, "تشغيل ملف html") { openHtmlFile() }
    ))

    section(col, "الصفحة الحالية")
    grid(col, listOf(
        toolCard(R.drawable.ic_link, "نسخ الرابط") {
            val u = cur?.web?.url
            if (u != null) copyText(u) else toast("افتح صفحة أولاً")
        },
        toolCard(R.drawable.ic_share, "مشاركة") {
            val u = cur?.web?.url
            if (u != null) {
                val i = Intent(Intent.ACTION_SEND)
                i.type = "text/plain"
                i.putExtra(Intent.EXTRA_TEXT, u)
                startActivity(Intent.createChooser(i, "مشاركة"))
            } else toast("افتح صفحة أولاً")
        },
        toolCard(R.drawable.ic_bookmark, "إضافة للمفضلة") { addBookmark() },
        toolCard(R.drawable.ic_pin, "إضافة اختصار") {
            val c = cur
            val u = c?.web?.url
            if (c == null || u == null) toast("افتح صفحة أولاً") else addShortcutDialog(c.title, u)
        },
        toolCard(R.drawable.ic_code, "سكربتات الصفحة") { showScripts() }
    ))

    section(col, "السجل")
    grid(col, listOf(
        toolCard(R.drawable.ic_undo, "السجل") { showClosed() }
    ))

    section(col, "التحميلات والملفات")
    grid(col, listOf(
        toolCard(R.drawable.ic_batch, "تحميل دفعة روابط") { batchLinks() },
        toolCard(R.drawable.ic_copy, "نسخ روابط الفيديو") { copyFound() },
        toolCard(R.drawable.ic_send, "مشاركة الروابط") { shareFound() },
        toolCard(R.drawable.ic_folder, "مجلد التحميلات") {
            try {
                val i = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e: Exception) { Diag.swallow("ToolsUi.kt:87 buildTools", e);
                toast("تعذر فتح مجلد التحميلات")
            }
        }
    ))

    section(col, "التقاط الفيديو")
    grid(col, listOf(
        toolCard(R.drawable.ic_search_link, "فحص رابط") { inspectLink() },
        toolCard(R.drawable.ic_block, "الروابط المستبعدة") { excludedDialog() }
    ))

    val sv = ScrollView(this)
    sv.setBackgroundColor(cBg)
    sv.addView(col)
    return sv
}

internal fun MainActivity.downloadFromClip() {
    val u = clipText()
    if (!(u.startsWith("http://") || u.startsWith("https://"))) {
        toast("لا يوجد رابط في الحافظة")
        return
    }
    manualBox.setText(u)
    addManual()
}

internal fun MainActivity.openFromClip() {
    val u = clipText()
    if (u.isBlank()) {
        toast("الحافظة فارغة")
        return
    }
    newTab(u)
}

internal fun MainActivity.copyFound() {
    if (found.isEmpty()) toast("لا توجد روابط ملتقطة") else copyText(found.joinToString("\n"))
}

internal fun MainActivity.shareFound() {
    if (found.isEmpty()) {
        toast("لا توجد روابط ملتقطة")
        return
    }
    val i = Intent(Intent.ACTION_SEND)
    i.type = "text/plain"
    i.putExtra(Intent.EXTRA_TEXT, found.joinToString("\n"))
    startActivity(Intent.createChooser(i, "مشاركة الروابط"))
}

internal fun MainActivity.copyTabs() {
    val l = tabs.mapNotNull { it.web.url }.filter { it.startsWith("http") }
    if (l.isEmpty()) toast("لا توجد روابط") else copyText(l.joinToString("\n"))
}

internal fun MainActivity.freeMem() {
    for (t in tabs) if (t !== cur) try { t.web.clearCache(false) } catch (e: Throwable) { Diag.swallow("ToolsUi.kt:146 freeMem", e);}
    System.gc()
    toast("تم تفريغ الذاكرة")
}

internal fun MainActivity.perfReport() {
    val rt = Runtime.getRuntime()
    val used = (rt.totalMemory() - rt.freeMemory()) / 1048576
    val mx = rt.maxMemory() / 1048576
    val lim = prefs().getInt("limitkb", 0)
    val msg = "التبويبات المفتوحة: " + tabs.size +
        "\nتحميلات جارية: " + activeBox.childCount +
        "\nروابط ملتقطة: " + found.size +
        "\nمخزن الصور: " + rcSizeMb() + " م.ب" +
        "\nذاكرة التطبيق: " + used + " / " + mx + " م.ب" +
        "\nحد السرعة: " + (if (lim > 0) "" + lim + " ك.ب/ث" else "بلا حد") +
        "\nالتحميلات المتزامنة: " + prefs().getInt("parallel", 3) +
        "\nالأجزاء المتوازية: " + prefs().getInt("threads", 8)
    StyledBuilder(this).setTitle("تقرير الأداء").setView(DlgStyle.kvText(this, msg))
        .setPositiveButton("تفريغ الذاكرة") { _, _ -> freeMem() }
        .setNegativeButton("رجوع", null).show()
}

internal fun MainActivity.applyParallel() {
    val n = prefs().getInt("parallel", 3).coerceIn(1, 32)
    try {
        if (n > queue.maximumPoolSize) {
            queue.maximumPoolSize = n
            queue.corePoolSize = n
        } else {
            queue.corePoolSize = n
            queue.maximumPoolSize = n
        }
    } catch (e: Throwable) { Diag.swallow("ToolsUi.kt:179 applyParallel", e);
    }
}

// يحدّ سرعة التحميل الكلية (ك.ب/ث) بتأخير الخيط بما يناسب حجم ما قُرئ
internal fun MainActivity.throttle(n: Int) {
    val lim = limitKb
    if (lim <= 0 || n <= 0) return
    val ms: Long
    synchronized(thrLock) {
        val now = System.nanoTime()
        if (thrNext < now) thrNext = now
        thrNext += n * 1000000000L / (lim * 1024L)
        ms = (thrNext - now) / 1000000L
    }
    if (ms > 0) try { Thread.sleep(minOf(ms, 2000L)) } catch (e: InterruptedException) { Diag.swallow("ToolsUi.kt:194 throttle", e);}
}
