package com.webo

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// نقاط ربط بسيطة: تسجيل النوافذ المفتوحة والرسائل العابرة ليراها قسم الفحص الشامل
object ScanHook {
    val dialogs = ArrayList<AlertDialog>()
    @Volatile var toastSink: ((String) -> Unit)? = null
}

// قسم الفحص الشامل: يعمل كإنسان؛ يفتح كل صفحة ويضغط كل زر ويجرب الوظائف ويحمّل شيئاً بسيطاً من موقع خفيف، ويكتب كل شيء حرفياً
class FullScan(
    private val act: Activity,
    private val root: View,
    private val pageCount: () -> Int,
    private val pageName: (Int) -> String,
    private val showPage: (Int) -> Unit,
    private val openBrowserUrl: (String) -> Unit,
    private val browserProbe: () -> String,
    private val closeMenu: () -> Unit,
    private val dl: (String, String) -> String,
    private val sink: (String) -> Unit
) {
    @Volatile var running = false
        private set
    private var worker: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val skipWords = listOf(
        "حذف", "مسح", "إفراغ", "تفريغ", "إعادة ضبط", "إعادة تعيين", "تسجيل الخروج", "إنهاء التطبيق",
        "الوضع الداكن", "قسم الفحص الشامل"
    )

    fun start() {
        if (running) return
        running = true
        worker = Thread {
            ScanHook.toastSink = { m -> line("ظهرت رسالة على الشاشة: $m") }
            try {
                runAll()
            } catch (e: InterruptedException) {
                line("تم إيقاف الفحص")
            } catch (e: Throwable) {
                line("خطأ غير متوقع في الفحص: " + err(e))
            } finally {
                ScanHook.toastSink = null
                running = false
                line("انتهى الفحص")
                sink("\u0000done")
            }
        }
        worker!!.start()
    }

    fun stop() {
        if (!running) return
        running = false
        line("طُلب إيقاف الفحص...")
        worker?.interrupt()
    }

    // ---------- أدوات مساعدة ----------

    private val liveFile: File by lazy { File(act.getExternalFilesDir(null) ?: act.filesDir, "scan_live.txt") }

    private fun line(s: String) {
        val full = "[" + clock.format(Date()) + "] " + s
        try {
            liveFile.appendText(full + "\n", Charsets.UTF_8)
        } catch (e: Throwable) {
        }
        sink(full)
    }

    private fun err(e: Throwable): String {
        val sb = StringBuilder(e.javaClass.name + ": " + (e.message ?: ""))
        var n = 0
        for (el in e.stackTrace) {
            if (el.className.startsWith("com.webo")) {
                sb.append("\n      في ").append(el.className).append(".").append(el.methodName).append(":").append(el.lineNumber)
                if (++n >= 6) break
            }
        }
        val c = e.cause
        if (c != null && c !== e) sb.append("\n      السبب: ").append(c.javaClass.name).append(": ").append(c.message ?: "")
        return sb.toString()
    }

    private fun <T> onUi(f: () -> T): T? {
        val latch = CountDownLatch(1)
        var r: T? = null
        var ex: Throwable? = null
        ui.post {
            try {
                r = f()
            } catch (e: Throwable) {
                ex = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) throw RuntimeException("انتهت مهلة انتظار الواجهة (15 ثانية) — الواجهة لا تستجيب")
        val e = ex
        if (e != null) throw e
        return r
    }

    private fun pause(ms: Long) {
        if (!running) throw InterruptedException()
        Thread.sleep(ms)
        if (!running) throw InterruptedException()
    }

    private fun texts(v: View, out: MutableList<String>) {
        if (v is EditText) {
            val t = v.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t)
        } else if (v is TextView) {
            val t = v.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t)
        }
        if (v is ViewGroup && v !is WebView) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
    }

    private fun describe(v: View): String {
        val list = ArrayList<String>()
        texts(v, list)
        var s = list.joinToString(" | ")
        if (s.isBlank()) s = v.contentDescription?.toString().orEmpty()
        if (s.isBlank() && v is CompoundButton) {
            val p = v.parent
            val pp = (p as? View)?.parent
            val l2 = ArrayList<String>()
            if (pp is View) texts(pp, l2) else if (p is View) texts(p, l2)
            s = l2.joinToString(" | ")
        }
        if (s.isBlank()) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            s = "زر بلا نص (" + v.javaClass.simpleName + ") عند x=" + loc[0] + " y=" + loc[1]
        }
        return s.take(90)
    }

    private fun inScanPage(v: View): Boolean {
        var p: View? = v
        var n = 0
        while (p != null && n < 40) {
            if (p.tag == "scan_page") return true
            p = p.parent as? View
            n++
        }
        return false
    }

    private fun collect(v: View, path: String, out: MutableList<Pair<View, String>>) {
        if (v.visibility != View.VISIBLE) return
        if (v.tag == "scan_page") return
        if (v.isClickable && v.hasOnClickListeners() && v.isEnabled && v.isShown && v.width > 0 && v.height > 0) out.add(Pair(v, path))
        if (v is ViewGroup && v !is WebView) for (i in 0 until v.childCount) collect(v.getChildAt(i), "$path/$i", out)
    }

    private fun closeDialogs() {
        val open = onUi {
            val res = ArrayList<String>()
            val it = ScanHook.dialogs.iterator()
            while (it.hasNext()) {
                val d = it.next()
                if (d.isShowing) {
                    val l = ArrayList<String>()
                    val dv = d.window?.decorView
                    if (dv != null) texts(dv, l)
                    for (k in 0 until 3) {
                        val b = d.getButton(-1 - k)
                        if (b != null && b.text.isNullOrBlank().not()) l.add("[زر: " + b.text + "]")
                    }
                    res.add(l.joinToString(" | ").take(400))
                    try { d.dismiss() } catch (e: Throwable) {}
                }
                it.remove()
            }
            res
        }
        if (open != null) for (d in open) line("   فُتحت نافذة، محتواها حرفياً: $d — ثم أُغلقت")
    }

    private fun ensureFocus() {
        var i = 0
        while (!act.hasWindowFocus() && i < 8) {
            Thread.sleep(250)
            i++
        }
        if (!act.hasWindowFocus()) {
            line("   خرج التطبيق إلى واجهة خارجية (مشاركة أو اختيار ملف أو غيره) — أعيد التطبيق إلى الأمام")
            onUi {
                val it = Intent(act, act.javaClass)
                it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                act.startActivity(it)
            }
            Thread.sleep(800)
        }
    }

    // ---------- الفحص ----------

    private fun runAll() {
        try { liveFile.writeText("", Charsets.UTF_8) } catch (e: Throwable) {}
        line("بدأ الفحص الشامل")
        line("يُكتب السجل أيضاً سطراً بسطر أثناء الفحص في الملف: " + liveFile.absolutePath)
        line("إصدار أندرويد: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ") — الجهاز: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)

        funcTests()
        pause(300)
        netTest()
        pause(300)
        browserTest()
        pause(300)
        dlTest("https://proof.ovh.net/files/1Mb.dat")
        pause(300)

        val n = pageCount()
        var total = 0
        var okc = 0
        var bad = 0
        var skipped = 0
        for (i in 0 until n) {
            pause(200)
            line("")
            line("===== فتح الصفحة: " + pageName(i) + " =====")
            onUi { closeMenu(); showPage(i) }
            pause(700)
            val visited = HashSet<String>()
            var guard = 0
            while (guard++ < 600) {
                if (!running) throw InterruptedException()
                val list = ArrayList<Pair<View, String>>()
                onUi { collect(root, "r", list); Unit }
                var target: Pair<View, String>? = null
                var key = ""
                var desc = ""
                for (p in list) {
                    val d = onUi { describe(p.first) } ?: continue
                    val k = p.second + "#" + p.first.javaClass.simpleName + "#" + d
                    if (!visited.contains(k)) {
                        target = p
                        key = k
                        desc = d
                        break
                    }
                }
                if (target == null) break
                visited.add(key)
                val v = target.first
                total++
                if (onUi { inScanPage(v) } == true) { skipped++; continue }
                val hit = skipWords.firstOrNull { desc.contains(it) }
                if (hit != null) {
                    skipped++
                    line("تخطي الضغط على «$desc» لأنه يحتوي «$hit» (قد يحذف بياناتك أو يعيد إنشاء التطبيق أو يقطع الفحص)")
                    continue
                }
                val isSwitch = v is CompoundButton
                val reps = if (isSwitch) 2 else 1
                for (r in 1..reps) {
                    try {
                        line("أجرّب الضغط على «$desc»" + (if (reps == 2) " (المرة $r من 2)" else ""))
                        val before = if (isSwitch) onUi { (v as CompoundButton).isChecked } else null
                        onUi { v.performClick() }
                        pause(450)
                        ensureFocus()
                        val after = if (isSwitch) onUi { (v as CompoundButton).isChecked } else null
                        if (isSwitch) line("ضغط على مفتاح «$desc»: كان $before وصار $after" + (if (r == 2) " (إرجاع للحالة الأصلية)" else "")) else line("ضغط على «$desc» → نجح بدون خطأ")
                        okc++
                        closeDialogs()
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Throwable) {
                        bad++
                        line("فشل الضغط على «$desc» → " + err(e))
                        try { closeDialogs() } catch (e2: Throwable) {}
                    }
                }
            }
            if (guard >= 600) line("توقف الفحص في هذه الصفحة عند حد 600 عنصر")
        }
        onUi { closeMenu(); showPage(3) }
        line("")
        line("===== الخلاصة =====")
        line("عدد العناصر التي وُجدت: $total — نجح الضغط: $okc — فشل: $bad — تُخطي: $skipped")
    }

    private fun funcTests() {
        line("")
        line("===== فحص الوظائف =====")
        try {
            val sp = act.getSharedPreferences("scan_test", Context.MODE_PRIVATE)
            val val1 = "v" + System.currentTimeMillis()
            sp.edit().putString("k", val1).commit()
            val back = sp.getString("k", null)
            line("حفظ وقراءة الإعدادات: كتبت «$val1» وقرأت «$back» → " + (if (back == val1) "سليم" else "خلل"))
            sp.edit().clear().commit()
        } catch (e: Throwable) {
            line("فشل فحص الإعدادات: " + err(e))
        }
        try {
            val f = File(act.cacheDir, "scan_test.txt")
            val txt = "فحص-" + System.currentTimeMillis()
            f.writeText(txt)
            val rd = f.readText()
            line("كتابة وقراءة ملف: كتبت «$txt» وقرأت «$rd» → " + (if (rd == txt) "سليم" else "خلل"))
            f.delete()
        } catch (e: Throwable) {
            line("فشل فحص الملفات: " + err(e))
        }
        try {
            val t = "نسخ-" + System.currentTimeMillis()
            val got = onUi {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("scan", t))
                cm.primaryClip?.getItemAt(0)?.text?.toString()
            }
            line("الحافظة: نسخت «$t» وقرأت «$got» → " + (if (got == t) "سليم" else "خلل"))
        } catch (e: Throwable) {
            line("فشل فحص الحافظة: " + err(e))
        }
        try {
            val st = android.os.StatFs(act.filesDir.absolutePath)
            line("المساحة الحرة في مجلد التطبيق: " + (st.availableBytes / 1048576) + " ميغابايت")
        } catch (e: Throwable) {
            line("فشل قراءة المساحة: " + err(e))
        }
    }

    private fun netTest() {
        line("")
        line("===== فحص التحميل من موقع خفيف (https://example.com/) =====")
        val t0 = System.currentTimeMillis()
        try {
            val c = URL("https://example.com/").openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) WeboScan")
            val code = c.responseCode
            line("رمز الاستجابة: $code — النوع: " + c.contentType + " — الحجم المعلن: " + c.contentLength)
            val f = File(act.cacheDir, "scan_download.html")
            var total = 0L
            c.inputStream.use { ins ->
                f.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        total += r
                    }
                }
            }
            val ms = System.currentTimeMillis() - t0
            line("اكتمل التحميل: $total بايت في $ms مللي ثانية → الملف الناتج " + f.length() + " بايت")
            val head = f.readText().take(120).replace("\n", " ")
            line("بداية محتوى الملف حرفياً: $head")
            f.delete()
        } catch (e: Throwable) {
            line("فشل التحميل بعد " + (System.currentTimeMillis() - t0) + " مللي ثانية → " + err(e))
        }
    }

    private fun stOf(s: String, k: String): String = Regex("(?:^|\\|)" + k + "=([^|]*)").find(s)?.groupValues?.get(1) ?: ""
    private fun stateOf(s: String): Int = stOf(s, "state").toIntOrNull() ?: -1
    private fun bytesOf(s: String): Long = stOf(s, "bytes").toLongOrNull() ?: 0L

    private fun dlTest(u: String) {
        line("")
        line("===== فحص التحميل الحقيقي بمحرك التطبيق نفسه من الموقع الخفيف: $u (ملف 1 ميغابايت) =====")
        try {
            onUi { closeMenu(); showPage(1) }
            pause(500)
            line("حذف أي صف قديم لهذا الرابط قبل البدء: " + onUi { dl("remove", u) })
            line("إضافة الرابط إلى التحميلات كأنه التُقط من صفحة: " + onUi { dl("add", u) })
            pause(800)
            line("الحالة بعد الإضافة: " + onUi { dl("status", u) })
            line("أجرّب الضغط على زر «تحميل»")
            line("نتيجة الضغط: " + onUi { dl("start", u) })

            var st = ""
            var waited = 0
            while (waited < 15000) {
                pause(150)
                waited += 150
                st = onUi { dl("status", u) } ?: ""
                val s1 = stateOf(st)
                if (s1 == 1 || s1 == 3 || s1 == 4 || s1 == -1) break
            }
            line("الحالة بعد بدء التحميل بـ ${waited} مللي ثانية: $st")

            if (stateOf(st) == 1) {
                line("أجرّب الضغط على زر «إيقاف مؤقت»")
                line("نتيجة الضغط: " + onUi { dl("pause", u) })
                pause(1500)
                val sp1 = onUi { dl("status", u) } ?: ""
                val b2 = bytesOf(sp1)
                pause(2000)
                val sp2 = onUi { dl("status", u) } ?: ""
                val b3 = bytesOf(sp2)
                line("الحالة أثناء الإيقاف المؤقت: $sp2")
                line("البايتات بعد 1.5 ثانية من الإيقاف: $b2 — وبعد ثانيتين أخريين: $b3 → " +
                    (if (stateOf(sp2) == 2 && b3 == b2) "الإيقاف المؤقت يعمل: ثبتت البايتات" else if (stateOf(sp2) == 3) "اكتمل التحميل قبل أن يسري الإيقاف المؤقت" else "خلل في الإيقاف المؤقت: الحالة ${stateOf(sp2)} والبايتات لم تثبت"))
                line("أجرّب الضغط على زر «استئناف»")
                line("نتيجة الضغط: " + onUi { dl("pause", u) })
                pause(3000)
                val sr = onUi { dl("status", u) } ?: ""
                val b4 = bytesOf(sr)
                line("الحالة بعد الاستئناف بـ 3 ثوان: $sr")
                line("البايتات: عند الإيقاف $b3 — بعد الاستئناف $b4 → " +
                    (if (b4 > b3) "الاستئناف يعمل وزادت البايتات" else if (b4 == b3 && stateOf(sr) == 3) "اكتمل التحميل بعد الاستئناف" else if (b4 < b3) "الاستئناف عاد إلى نقطة أقل (لا يكمل من نفس النقطة)" else "الاستئناف لم يُحرز أي تقدم"))
            } else {
                line("انتهى التحميل أو فشل قبل أن أتمكن من ضغط «إيقاف مؤقت»، فلم تُجرَّب الإيقاف المؤقت والاستئناف في هذا الفحص")
            }

            waited = 0
            while (waited < 60000) {
                st = onUi { dl("status", u) } ?: ""
                val s1 = stateOf(st)
                if (s1 == 3 || s1 == 4 || s1 == -1) break
                pause(500)
                waited += 500
            }
            line("الحالة النهائية: $st")
            when (stateOf(st)) {
                3 -> {
                    val size = stOf(st, "size")
                    line("اكتمل التحميل. مكان الملف المخزَّن: " + stOf(st, "final") + " — حجمه الفعلي: $size بايت — المتوقع 1048576 بايت → " + (if (size == "1048576") "التخزين سليم" else "الحجم لا يطابق المتوقع"))
                }
                4 -> line("التحميل فشل أو لم يكتمل. الرسالة حرفياً: " + stOf(st, "msg"))
                else -> line("لم يكتمل التحميل خلال 60 ثانية")
            }
        } catch (e: InterruptedException) {
            try { onUi { dl("remove", u) } } catch (e2: Throwable) {}
            throw e
        } catch (e: Throwable) {
            line("فشل فحص التحميل: " + err(e))
        }
        try {
            line("إزالة صف الاختبار من التحميلات: " + onUi { dl("remove", u) })
        } catch (e: Throwable) {
            line("فشلت إزالة صف الاختبار: " + err(e))
        }
    }

    private fun browserTest() {
        line("")
        line("===== فحص المتصفح: فتح الموقع الخفيف =====")
        try {
            onUi { closeMenu(); showPage(0); openBrowserUrl("https://example.com/") }
            var waited = 0
            var info = ""
            while (waited < 15000) {
                pause(1000)
                waited += 1000
                info = onUi { browserProbe() } ?: ""
                if (info.endsWith("|100")) break
            }
            line("حالة المتصفح بعد ${waited / 1000} ثانية (العنوان | العنوان النصي | نسبة التحميل): $info")
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Throwable) {
            line("فشل فحص المتصفح: " + err(e))
        }
    }
}
