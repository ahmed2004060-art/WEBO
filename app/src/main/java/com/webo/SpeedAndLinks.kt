package com.webo

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// التحكم بالسرعة وفحص الروابط والدفعات
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.showSpeed() {
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setBackgroundColor(cCard)
    col.setPadding(dp(20), dp(12), dp(20), dp(8))
    col.addView(tv("حد السرعة الكلي (كيلوبايت في الثانية)", 13f, cText, true))
    val noLim = android.widget.CheckBox(this)
    DlgStyle.styleCheck(this, noLim)
    noLim.text = "بلا حد (أقصى سرعة ممكنة)"
    noLim.isChecked = prefs().getInt("limitkb", 0) == 0
    col.addView(noLim)
    val lim = EditText(this)
    lim.inputType = InputType.TYPE_CLASS_NUMBER
    lim.setText("" + prefs().getInt("limitkb", 0).let { if (it > 0) it else 1024 })
    lim.visibility = if (noLim.isChecked) View.GONE else View.VISIBLE
    noLim.setOnCheckedChangeListener { _, on -> lim.visibility = if (on) View.GONE else View.VISIBLE }
    lim.setTextColor(cText)
    lim.background = bg(cBg, 10, cLine)
    lim.setPadding(dp(12), dp(8), dp(12), dp(8))
    lim.layoutDirection = View.LAYOUT_DIRECTION_LTR
    col.addView(lim, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
    col.addView(tv("مثال: 1024 = 1 م.ب/ث ، 10240 = 10 م.ب/ث", 11f, cMuted))
    val sT = labeledSeek(col, "اتصالات الملف الواحد", 1, 128, prefs().getInt("threads", 8).coerceIn(1, 128), { "" + it }, { })
    val sP = labeledSeek(col, "تحميلات متزامنة", 1, 32, prefs().getInt("parallel", 3).coerceIn(1, 32), { "" + it }, { })
    col.addView(tv("الاتصالات تُقلَّل تلقائياً للملفات الصغيرة حتى لا تُهدر.", 11f, cMuted), lp(MATCH, WRAP, 0f, 0, 10, 0, 0))
    val sv = ScrollView(this)
    sv.addView(col)
    StyledBuilder(this).setTitle("التحكم بالسرعة").setView(sv)
        .setPositiveButton("حفظ") { _, _ ->
            val v = if (noLim.isChecked) 0 else (lim.text.toString().trim().toLongOrNull() ?: 1024L).coerceIn(1L, 10000000L).toInt()
            prefs().edit().putInt("limitkb", v).putInt("threads", sT.progress + 1).putInt("parallel", sP.progress + 1).apply()
            limitKb = v
            applyParallel()
            toast("تم الحفظ")
        }
        .setNeutralButton("سرعة فائقة") { _, _ ->
            prefs().edit().putInt("limitkb", 0).putInt("threads", 64).putInt("parallel", 6).apply()
            limitKb = 0
            applyParallel()
            toast("تم تفعيل السرعة الفائقة بلا حد")
        }
        .setNegativeButton("إلغاء", null).show()
}

// قياس السرعة الفعلية بعدة اتصالات متوازية ثم اقتراح عدد الاتصالات الأنسب
internal fun MainActivity.speedTest() {
    toast("جاري قياس السرعة لمدة 6 ثوانٍ...")
    diagThread("SpeedAndLinks:64") {
        val total = java.util.concurrent.atomic.AtomicLong(0)
        val t0 = System.nanoTime()
        val end = t0 + 6000000000L
        val ex = diagFixed("speedTest", 4)
        val fs = ArrayList<java.util.concurrent.Future<*>>()
        for (k in 0 until 4) {
            fs.add(ex.submit(Runnable {
                try {
                    val c = URL("https://speed.cloudflare.com/__down?bytes=100000000").openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 15000
                    c.inputStream.use { i ->
                        val b = ByteArray(65536)
                        while (System.nanoTime() < end) {
                            val n = i.read(b)
                            if (n < 0) break
                            total.addAndGet(n.toLong())
                        }
                    }
                    c.disconnect()
                } catch (e: Throwable) { Diag.swallow("SpeedAndLinks.kt:85 speedTest", e);
                }
            }))
        }
        for (f in fs) try { f.get() } catch (e: Throwable) { Diag.swallow("SpeedAndLinks.kt:89 speedTest", e);}
        ex.shutdownNow()
        val sec = (System.nanoTime() - t0) / 1e9
        val bps = if (sec > 0) total.get() / sec else 0.0
        val rec = when {
            bps < 100 * 1024 -> 0
            bps < 256 * 1024 -> 2
            bps < 1024 * 1024 -> 4
            bps < 4 * 1024 * 1024 -> 8
            bps < 12 * 1024 * 1024 -> 16
            else -> 32
        }
        ui.post {
            if (rec == 0) {
                StyledBuilder(this).setTitle("نتيجة القياس")
                    .setMessage("لم أتمكن من القياس (الشبكة بطيئة جداً أو منقطعة). التطبيق يعمل مع الشبكات الضعيفة بمهل طويلة وإعادة محاولة تلقائية.")
                    .setPositiveButton("حسناً", null).show()
            } else {
                val mb = String.format(java.util.Locale.US, "%.2f", bps / 1048576.0)
                val mbit = String.format(java.util.Locale.US, "%.1f", bps * 8 / 1000000.0)
                StyledBuilder(this).setTitle("نتيجة القياس")
                    .setView(DlgStyle.kvText(this, "السرعة: " + mb + " م.ب/ث (" + mbit + " ميغابت/ث)\nعدد الاتصالات المقترح: " + rec))
                    .setPositiveButton("تطبيق المقترح") { _, _ ->
                        prefs().edit().putInt("threads", rec).apply()
                        toast("تم ضبط الاتصالات على " + rec)
                    }
                    .setNegativeButton("إغلاق", null).show()
            }
        }
    }.start()
}

// فحص رابط: الحالة والنوع والحجم ودعم التجزئة والتحويلات
internal fun MainActivity.inspectLink() {
    val et = EditText(this)
    et.setTextColor(cText)
    et.setHintTextColor(cMuted)
    et.hint = "https://..."
    et.layoutDirection = View.LAYOUT_DIRECTION_LTR
    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    val c = clipText()
    if (c.startsWith("http")) et.setText(c)
    val box = LinearLayout(this)
    box.setBackgroundColor(cCard)
    box.setPadding(dp(18), dp(10), dp(18), dp(10))
    box.addView(et, lp(MATCH, WRAP))
    StyledBuilder(this).setTitle("فحص رابط").setView(box)
        .setPositiveButton("فحص") { _, _ ->
            val u = et.text.toString().trim()
            if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                toast("أدخل رابطاً صحيحاً")
                return@setPositiveButton
            }
            toast("جاري الفحص...")
            diagThread("SpeedAndLinks:143") {
                var msg = ""
                try {
                    val h = HashMap<String, String>()
                    h["User-Agent"] = curUa
                    h["Referer"] = cur?.web?.url ?: ""
                    h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
                    val cn = conn(u, h, "bytes=0-0")
                    val code = cn.responseCode
                    val ct = cn.contentType ?: "غير معروف"
                    val total = if (code == 206) (cn.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull() ?: -1L else cn.contentLengthLong
                    val ranges = code == 206 || (cn.getHeaderField("Accept-Ranges") ?: "").contains("bytes")
                    val fin = cn.url.toString()
                    cn.disconnect()
                    msg = "الحالة: " + code + "\nالنوع: " + ct + "\nالحجم: " + (if (total > 0) FileUtil.fmtSize(total) else "غير معروف") +
                        "\nالتحميل المتجزئ السريع: " + (if (ranges) "مدعوم" else "غير مدعوم") +
                        (if (fin != u) "\nالرابط النهائي بعد التحويل:\n" + fin else "")
                } catch (e: Throwable) { Diag.swallow("SpeedAndLinks.kt:160 inspectLink", e);
                    msg = "تعذر الفحص: " + (e.message ?: e.javaClass.simpleName)
                }
                val m = msg
                ui.post {
                    StyledBuilder(this).setTitle("نتيجة الفحص").setView(DlgStyle.kvText(this, m))
                        .setPositiveButton("إضافة للتحميلات") { _, _ ->
                            val h = HashMap<String, String>()
                            h["User-Agent"] = curUa
                            h["Referer"] = cur?.web?.url ?: ""
                            h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
                            addFound(u, h, null, cur, "")
                            showPage(1)
                        }
                        .setNegativeButton("إغلاق", null).show()
                }
            }.start()
        }
        .setNegativeButton("إلغاء", null).show()
}

// لصق عدة روابط دفعة واحدة وتحميلها كلها
internal fun MainActivity.batchLinks() {
    val et = EditText(this)
    et.setTextColor(cText)
    et.setHintTextColor(cMuted)
    et.hint = "ألصق الروابط هنا، رابط في كل سطر"
    et.layoutDirection = View.LAYOUT_DIRECTION_LTR
    et.gravity = Gravity.TOP or Gravity.START
    et.minLines = 6
    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
    val c = clipText()
    if (c.contains("http")) et.setText(c)
    val box = LinearLayout(this)
    box.setBackgroundColor(cCard)
    box.setPadding(dp(18), dp(10), dp(18), dp(10))
    box.addView(et, lp(MATCH, WRAP))
    StyledBuilder(this).setTitle("تحميل دفعة روابط").setView(box)
        .setPositiveButton("بدء التحميل") { _, _ ->
            val urls = Regex("https?://[^\\s\"'<>]+").findAll(et.text.toString()).map { it.value }.distinct().toList()
            if (urls.isEmpty()) {
                toast("لم أجد روابط")
                return@setPositiveButton
            }
            for (u in urls) {
                val h = HashMap<String, String>()
                h["User-Agent"] = curUa
                h["Referer"] = cur?.web?.url ?: ""
                h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
                addFound(u, h, null, cur, "")
                foundStart[u]?.invoke()
            }
            showPage(1)
            toast("أُضيف " + urls.size + " رابط")
        }
        .setNegativeButton("إلغاء", null).show()
}
