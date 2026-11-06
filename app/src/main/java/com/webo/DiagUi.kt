package com.webo

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

// صفحة «السجل والفحص الذاتي» داخل الإعدادات:
// - عرض السجل الكامل بلا اقتطاع، مع فلتر حسب النوع، وتفاصيل كل حدث حرفياً (اضغط الحدث لفتحه كاملاً، واضغط مطولاً لنسخه)
// - نسخ التقرير، ومشاركته كملف، وزر تشغيل الفحص الذاتي
private const val DIAG_PAGE = 100
private const val DIAG_LOAD_MAX = 5000
private const val DIAG_COPY_MAX = 200000

private fun diagIsError(kind: String): Boolean =
    kind == "انهيار" || kind == "مبتلع" || kind == "جافاسكربت-مبتلع" || kind.startsWith("جافاسكربت") || kind == "ANR" || kind == "فشل"

internal fun MainActivity.diagReportHeader(): String {
    val pm = try { packageManager.getPackageInfo(packageName, 0) } catch (e: Throwable) { Diag.swallow("DiagUi.kt diagReportHeader", e); null }
    val rt = Runtime.getRuntime()
    val sb = StringBuilder()
    sb.append("تقرير WEBO\n")
    sb.append("الوقت: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append('\n')
    sb.append("الإصدار: ").append(pm?.versionName ?: "؟").append('\n')
    sb.append("الجهاز: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" | Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
    sb.append("الذاكرة: ").append(rt.totalMemory() / 1048576).append('/').append(rt.maxMemory() / 1048576).append(" MB\n")
    sb.append("مجلد السجل: ").append(Diag.dirPath()).append('\n')
    if (SelfCheck.lastReport.isNotEmpty()) {
        sb.append("\n==== نتيجة آخر فحص ذاتي ====\n").append(SelfCheck.lastReport)
    } else {
        sb.append("\n(لم يُشغَّل الفحص الذاتي في هذه الجلسة)\n")
    }
    return sb.toString()
}

// يصدّر التقرير الكامل (كل ملفات السجل) كملف في مجلد التحميلات ثم يفتح المشاركة
internal fun MainActivity.shareDiagReport() {
    toast("جاري تجهيز التقرير...")
    diagThread("DiagUi.share") {
        try {
            val f = File(cacheDir, "webo-report-" + System.currentTimeMillis() + ".txt")
            Diag.exportTo(f, diagReportHeader())
            val size = f.length()
            val res = saveToDownloadsEx(f, "text/plain")
            Diag.i("سجل", "صُدّر التقرير | " + res.first + " | " + size + " بايت")
            ui.post {
                toast("حُفظ التقرير في " + res.first)
                if (res.second.startsWith("content:")) {
                    try {
                        val i = Intent(Intent.ACTION_SEND)
                        i.type = "text/plain"
                        i.putExtra(Intent.EXTRA_STREAM, Uri.parse(res.second))
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(i, "مشاركة تقرير WEBO"))
                    } catch (e: Throwable) {
                        Diag.e("سجل", "تعذر فتح المشاركة", e)
                        toast("تعذر فتح المشاركة، الملف في مجلد التحميلات")
                    }
                }
            }
        } catch (e: Throwable) {
            Diag.e("سجل", "تعذر تصدير التقرير", e)
            ui.post { toast("تعذر تصدير التقرير: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }.start()
}

internal fun MainActivity.buildSetDiag(col: LinearLayout) {
    val files = Diag.files()
    val total = files.sumOf { it.length() }
    val info = settingsCard(col)
    info.addView(tv("السجل مكتوب على الهاتف لحظة بلحظة، بلا اقتطاع ولا حذف تلقائي. عند امتلاء الملف يبدأ ملف جديد.", 12f, cMuted))
    info.addView(tv("المجلد: " + Diag.dirPath(), 11f, cMuted), lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
    val stat = tv("الملفات: " + files.size + " | الحجم: " + fmtSize(total.toDouble()), 12f, cText, true)
    info.addView(stat, lp(MATCH, WRAP, 0f, 0, 4, 0, 0))

    // ---------- الفحص الذاتي ----------
    section(col, "الفحص الذاتي")
    val chkCard = settingsCard(col)
    val chkSummary = tv(if (SelfCheck.lastResults.isEmpty()) "لم يُشغَّل بعد" else "آخر فحص: نجح " + SelfCheck.lastResults.count { it.ok } + " من " + SelfCheck.lastResults.size, 13f, cText, true)
    chkCard.addView(chkSummary)
    val chkList = LinearLayout(this)
    chkList.orientation = LinearLayout.VERTICAL
    chkCard.addView(chkList, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))

    fun addResult(r: CheckResult) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(6), dp(8), dp(6))
        val col0 = if (r.ok) Color.parseColor("#3DB36B") else Color.parseColor("#E05A5A")
        box.background = bg(Color.TRANSPARENT, 8, col0)
        val head = tv((if (r.ok) "✔ " else "✘ ") + r.group + " › " + r.name + "  (" + r.ms + "ms)", 12f, col0, true)
        box.addView(head)
        val det = tv(r.detail, 11f, if (r.ok) cMuted else cText)
        det.setTextIsSelectable(true)
        run {
            val sp = android.text.SpannableString(r.detail)
            val rx = Regex("مفقودة=[0-9]+")
            for (m in rx.findAll(r.detail)) {
                sp.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#E05A5A")), m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            det.text = sp
        }
        box.addView(det, lp(MATCH, WRAP, 0f, 0, 2, 0, 0))
        chkList.addView(box, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
    }
    for (r in SelfCheck.lastResults) addResult(r)

    var refresh: () -> Unit = {}
    info.addView(dlPill("حذف السجل", false) {
        StyledBuilder(this).setTitle("حذف السجل")
            .setMessage("سيتم حذف كل أحداث السجل نهائياً. لا يمكن التراجع.")
            .setPositiveButton("حذف") { _, _ ->
                Diag.clearAll()
                refresh()
                toast("تم حذف السجل")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
    val runBtn = dlPill("تشغيل الفحص الذاتي", true) {
        val sc = SelfCheck(this)
        chkList.removeAllViews()
        chkSummary.text = "جارٍ الفحص..."
        val started = sc.runAll(
            { r -> ui.post { addResult(r); chkSummary.text = "جارٍ الفحص... (" + chkList.childCount + ")" } },
            { rs, _ -> ui.post { chkSummary.text = "انتهى: نجح " + rs.count { it.ok } + " من " + rs.size + (if (rs.any { !it.ok }) "  — الفاشل بالأحمر مع الاستثناء الكامل" else ""); refresh() } }
        )
        if (!started) toast("الفحص يعمل الآن")
    }
    chkCard.addView(runBtn, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))

    // ---------- السجل ----------
    section(col, "السجل")
    val actions = LinearLayout(this)
    actions.orientation = LinearLayout.HORIZONTAL
    col.addView(actions, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
    val chips = HorizontalScrollView(this)
    chips.isHorizontalScrollBarEnabled = false
    val chipRow = LinearLayout(this)
    chipRow.orientation = LinearLayout.HORIZONTAL
    chips.addView(chipRow)
    col.addView(chips, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
    val listBox = LinearLayout(this)
    listBox.orientation = LinearLayout.VERTICAL
    col.addView(listBox, lp(MATCH, WRAP))
    val moreBtn = dlPill("عرض المزيد", false) { }
    moreBtn.visibility = View.GONE
    col.addView(moreBtn, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))

    var all: List<DiagEvent> = emptyList()
    var filter: String? = null
    var shown = 0

    fun visible(): List<DiagEvent> = if (filter == null) all else all.filter { it.kind == filter }

    fun eventView(e: DiagEvent): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10), dp(8), dp(10), dp(8))
        val err = diagIsError(e.kind)
        box.background = bg(cCard, 10, if (err) Color.parseColor("#E05A5A") else cLine)
        val h = tv(e.time + "  [" + e.kind + "]", 11f, if (err) Color.parseColor("#E05A5A") else cAccent, true)
        box.addView(h)
        val hd = tv(e.head, 10f, cMuted)
        box.addView(hd)
        val firstLine = e.body.lineSequence().firstOrNull() ?: ""
        val body = tv(e.body, 12f, cText)
        body.maxLines = 3
        body.ellipsize = android.text.TextUtils.TruncateAt.END
        box.addView(body, lp(MATCH, WRAP, 0f, 0, 3, 0, 0))
        var open = false
        val longBody = e.body.length > 220 || e.body.count { it == '\n' } >= 3
        box.setOnClickListener {
            open = !open
            if (open) { body.maxLines = Int.MAX_VALUE; body.ellipsize = null; body.setTextIsSelectable(true) }
            else { body.setTextIsSelectable(false); body.maxLines = 3; body.ellipsize = android.text.TextUtils.TruncateAt.END }
        }
        box.setOnLongClickListener {
            copyText(e.time + "  [" + e.kind + "]  " + e.head + "\n" + e.body)
            true
        }
        if (!longBody && firstLine == e.body) body.maxLines = Int.MAX_VALUE
        return box
    }

    fun renderMore() {
        val v = visible()
        val end = minOf(v.size, shown + DIAG_PAGE)
        for (k in shown until end) listBox.addView(eventView(v[k]), lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
        shown = end
        moreBtn.visibility = if (shown < v.size) View.VISIBLE else View.GONE
        moreBtn.text = "عرض المزيد (" + (v.size - shown) + " متبقٍّ)"
    }

    fun renderAll() {
        listBox.removeAllViews()
        shown = 0
        if (visible().isEmpty()) {
            listBox.addView(tv("لا توجد أحداث", 13f, cMuted), lp(MATCH, WRAP, 0f, 0, 10, 0, 10))
            moreBtn.visibility = View.GONE
        } else renderMore()
    }

    fun renderChips() {
        chipRow.removeAllViews()
        val counts = LinkedHashMap<String, Int>()
        for (e in all) counts[e.kind] = (counts[e.kind] ?: 0) + 1
        fun chip(label: String, key: String?) {
            val t = tv(label, 12f, cAccent, true)
            t.setPadding(dp(12), 0, dp(12), 0)
            t.height = dp(46)
            t.gravity = Gravity.CENTER
            styleChip(t, filter == key)
            t.setOnClickListener { filter = key; renderChips(); renderAll() }
            chipRow.addView(t, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        }
        chip("الكل (" + all.size + ")", null)
        for ((k, n) in counts.entries.sortedByDescending { it.value }) chip(k + " (" + n + ")", k)
    }

    refresh = {
        val fs = Diag.files()
        stat.text = "الملفات: " + fs.size + " | الحجم: " + fmtSize(fs.sumOf { it.length() }.toDouble())
        diagThread("DiagUi.load") {
            val ev = try { Diag.readEvents(DIAG_LOAD_MAX) } catch (e: Throwable) { Diag.e("سجل", "تعذر قراءة السجل", e); emptyList() }
            ui.post {
                all = ev
                if (filter != null && all.none { it.kind == filter }) filter = null
                renderChips()
                renderAll()
            }
        }.start()
    }

    actions.addView(dlPill("تحديث", false) { refresh() }, lp(0, WRAP, 1f, 0, 0, 4, 0))
    actions.addView(dlPill("نسخ", false) {
        val v = visible()
        val sb = StringBuilder()
        for (e in v) {
            val one = e.time + "  [" + e.kind + "]  " + e.head + "\n" + e.body + "\n----\n"
            if (sb.length + one.length > DIAG_COPY_MAX) break
            sb.append(one)
        }
        val cut = sb.length < v.sumOf { it.body.length + 60 }
        copyText(sb.toString())
        if (cut) toast("نُسخ الأحدث فقط (حد الحافظة). للتقرير الكامل استخدم «مشاركة كملف»")
    }, lp(0, WRAP, 1f, 0, 0, 4, 0))
    actions.addView(dlPill("مشاركة كملف", true) { shareDiagReport() }, lp(0, WRAP, 1f, 0, 0, 4, 0))
    actions.addView(dlPill("حذف السجل", false) { }, lp(0, WRAP, 1f, 0, 0, 0, 0))

    refresh()
}
