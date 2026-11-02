package com.webo.lab

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
import android.widget.ListView
import android.widget.ScrollView
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.SeekBar
import android.widget.Spinner
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

// قسم الفحص الشامل: يعمل كإنسان؛ يفتح كل صفحة ويضغط كل زر ويفتح كل نافذة ويضغط كل أزرارها وعناصرها، ويجرب الوظائف ويحمّل شيئاً بسيطاً من موقع خفيف، ويكتب كل شيء حرفياً
// لا يكتب شيئاً في الحافظة: أي ضغطة تنسخ شيئاً تُسجَّل ثم تُعاد الحافظة كما كانت. وأي إعداد يتغير أثناء الفحص يُسجَّل ثم يُرجَع.
class FullScan(
    private val act: Activity,
    private val root: View,
    private val pageCount: () -> Int,
    private val pageName: (Int) -> String,
    private val showPage: (Int) -> Unit,
    private val openBrowserUrl: (String) -> Unit,
    private val browserProbe: () -> String,
    private val closeMenu: () -> Unit,
    private val curPage: () -> Int,
    private val pageCtx: () -> String,
    private val resetPage: (Int) -> Unit,
    private val webView: () -> WebView?,
    private val pressBack: () -> Unit,
    private val dl: (String, String) -> String,
    private val sink: (String) -> Unit,
    private val virtualSiteBase: String = ""
) {
    @Volatile var running = false
        private set
    private var worker: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun scanUrl(path: String, fallback: String): String =
        if (virtualSiteBase.startsWith("http://127.0.0.1:")) virtualSiteBase.trimEnd('/') + "/" + path.trimStart('/') else fallback

    // ما يُتخطى عمداً لأن ضغطه يحذف بيانات المستخدم أو يعيد إنشاء التطبيق أو يقطع الفحص؛ يُذكر كل واحد منها في الخلاصة مع سببه
    private val skipWords = listOf(
        "حذف", "مسح", "إفراغ", "تفريغ", "إعادة ضبط", "إعادة تعيين", "تسجيل الخروج", "إنهاء التطبيق",
        "الوضع الداكن", "قسم الفحص الشامل"
    )

    // إحصاءات الخلاصة
    private var total = 0
    private var okc = 0
    private var bad = 0
    private var skipped = 0
    private var longTried = 0
    private var dialogsOpened = 0
    private var dialogItems = 0
    private var fieldsTried = 0
    private var clipFixes = 0
    private val skippedList = ArrayList<String>()
    private val failedList = ArrayList<String>()
    private val disabledList = LinkedHashSet<String>()
    private val perPage = LinkedHashMap<String, IntArray>() // 0 وُجد، 1 نجح، 2 فشل، 3 تُخطي

    private var prevHandler: Thread.UncaughtExceptionHandler? = null
    private var clipBase: ClipData? = null
    private var clipBaseText = ""
    private var prefBase: Map<String, Any?> = emptyMap()

    fun start() {
        if (running) return
        running = true
        worker = Thread {
            ScanHook.toastSink = { m -> line("ظهرت رسالة على الشاشة: $m") }
            prevHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { th, e ->
                try { liveFile.appendText("[" + clock.format(Date()) + "] انهيار التطبيق أثناء الفحص (" + th.name + "): " + err(e) + "\n", Charsets.UTF_8) } catch (x: Throwable) {}
                prevHandler?.uncaughtException(th, e)
            }
            try {
                snapshotState()
                runAll()
            } catch (e: InterruptedException) {
                line("تم إيقاف الفحص")
            } catch (e: Throwable) {
                line("خطأ غير متوقع في الفحص: " + err(e))
            } finally {
                try { Thread.interrupted() } catch (e: Throwable) {}
                try { printSummary() } catch (e: Throwable) { line("تعذر كتابة الخلاصة: " + err(e)) }
                try { restoreState() } catch (e: Throwable) { line("تعذرت استعادة الحالة: " + err(e)) }
                try { Thread.setDefaultUncaughtExceptionHandler(prevHandler) } catch (e: Throwable) {}
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
            if (el.className.startsWith("com.webo.lab")) {
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
            if (t.isNotEmpty()) out.add(t) else if (!v.hint.isNullOrBlank()) out.add("(حقل فارغ، تلميحه: " + v.hint + ")")
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

    private fun testable(v: View): Boolean =
        v !is EditText && ((v.isClickable && v.hasOnClickListeners()) || v is CompoundButton || v is Spinner)

    private fun collect(v: View, path: String, out: MutableList<Pair<View, String>>) {
        if (v.visibility != View.VISIBLE) return
        if (v.tag == "scan_page") return
        if (testable(v) && v.isShown && v.width > 0 && v.height > 0) {
            if (v.isEnabled) out.add(Pair(v, path)) else disabledList.add(describe(v))
        }
        if (v is ViewGroup && v !is WebView) for (i in 0 until v.childCount) collect(v.getChildAt(i), "$path/$i", out)
    }

    private fun skipHit(desc: String): String? = skipWords.firstOrNull { desc.contains(it) }

    // أزرار الرجوع والإغلاق تُؤجَّل لآخر الصفحة حتى لا نخرج من الصفحة الداخلية قبل فحص ما فيها
    private fun isBackLike(desc: String, v: View, ctx: String): Boolean {
        if (desc.contains("إغلاق") || desc.contains("رجوع")) return true
        return v is android.widget.ImageView && desc.startsWith("زر بلا نص") && !ctx.contains(";sub=;")
    }

    private fun pageLabelOf(ctx: String): String {
        val n = Regex("p(\\d+)").find(ctx)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val sub = Regex(";sub=([^;]*)").find(ctx)?.groupValues?.get(1).orEmpty()
        val menu = ctx.endsWith("menu=1")
        return (if (n >= 0) pageName(n) else "؟") + (if (sub.isNotEmpty()) " ← قسم داخلي «$sub»" else "") + (if (menu) " (القائمة الجانبية مفتوحة)" else "")
    }

    // ---------- الحافظة والإعدادات: حفظ ثم استعادة ----------

    private fun clipText(): String = try {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip
        if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(act)?.toString().orEmpty() else ""
    } catch (e: Throwable) { "" }

    private fun doClipRestore() {
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val b = clipBase
            if (b != null) cm.setPrimaryClip(b)
            else if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (e: Throwable) {
        }
    }

    private fun clipGuard() {
        val now = onUi { clipText() } ?: return
        if (now != clipBaseText) {
            clipFixes++
            line("   هذه الضغطة كتبت في الحافظة: «" + now.take(150) + "» — أعدتُ الحافظة كما كانت قبل الفحص حتى لا تتغير نسختك")
            onUi { doClipRestore() }
        }
    }

    private fun snapshotState() {
        onUi {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipBase = try { cm.primaryClip } catch (e: Throwable) { null }
            clipBaseText = clipText()
        }
        prefBase = HashMap(act.getSharedPreferences("tdl", Context.MODE_PRIVATE).all)
    }

    private fun restoreState() {
        ui.post { doClipRestore() }
        val sp = act.getSharedPreferences("tdl", Context.MODE_PRIVATE)
        val now = HashMap(sp.all)
        val ed = sp.edit()
        var n = 0
        fun put(k: String, v: Any?) {
            when (v) {
                is Boolean -> ed.putBoolean(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Float -> ed.putFloat(k, v)
                is String -> ed.putString(k, v)
                is Set<*> -> ed.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }
        for ((k, v) in now) {
            if (!prefBase.containsKey(k)) {
                line("الفحص أضاف الإعداد «$k» = «$v» — أُزيل")
                ed.remove(k)
                n++
            } else if (prefBase[k] != v) {
                line("الفحص غيّر الإعداد «$k» من «" + prefBase[k] + "» إلى «$v» — أُعيد لقيمته الأصلية")
                put(k, prefBase[k])
                n++
            }
        }
        for ((k, v) in prefBase) if (!now.containsKey(k)) {
            line("الفحص أزال الإعداد «$k» — أُعيد")
            put(k, v)
            n++
        }
        ed.commit()
        line(if (n == 0) "الإعدادات: لم يبقَ أي تغيير بعد الفحص" else "الإعدادات: أُعيد $n إعداداً لقيمته قبل الفحص (إن لاحظت أن شيئاً لم يرجع في الشاشة فأعد فتح التطبيق)")
    }

    // ---------- النوافذ ----------

    private fun topDialog(): AlertDialog? {
        var last: AlertDialog? = null
        val it = ScanHook.dialogs.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (d.isShowing) last = d else it.remove()
        }
        return last
    }

    private fun closeDialogs() {
        onUi {
            val it = ScanHook.dialogs.iterator()
            while (it.hasNext()) {
                val d = it.next()
                try { if (d.isShowing) d.dismiss() } catch (e: Throwable) {}
                it.remove()
            }
        }
    }

    private class Tgt(val v: View?, val lv: ListView?, val pos: Int, val key: String, val desc: String)

    private fun dialogTitle(dv: View): String {
        val l = ArrayList<String>()
        texts(dv, l)
        return (l.firstOrNull() ?: "").take(60)
    }

    private fun dialogTargets(d: AlertDialog): List<Tgt> {
        val res = ArrayList<Tgt>()
        val dv = d.window?.decorView ?: return res
        val title = dialogTitle(dv)
        val lv = try { d.listView } catch (e: Throwable) { null }
        val ad = lv?.adapter
        if (lv != null && ad != null) {
            for (p in 0 until ad.count) {
                val label = try { ad.getItem(p)?.toString().orEmpty() } catch (e: Throwable) { "" }
                res.add(Tgt(null, lv, p, "$title#L$p#$label", if (label.isBlank()) "عنصر رقم ${p + 1} في القائمة" else label))
            }
        }
        val list = ArrayList<Pair<View, String>>()
        collect(dv, "d", list)
        for ((v, path) in list) {
            val ds = describe(v)
            res.add(Tgt(v, null, -1, "$title#$path#" + v.javaClass.simpleName + "#$ds", ds))
        }
        return res
    }

    // يفتح النافذة الظاهرة ويضغط كل زر وكل عنصر فيها، وإذا أُغلقت بعد ضغطة فيُعيد فتحها ليكمل الباقي
    private fun exploreDialog(reopen: (() -> Unit)?, depth: Int) {
        val visited = HashSet<String>()
        var reopens = 0
        var pending = true
        var logged = false
        var lastDlg: AlertDialog? = null
        var guard = 0
        while (guard++ < 100000) {
            if (!running) throw InterruptedException()
            val d = onUi { topDialog() }
            if (d == null) {
                if (reopen == null || !pending || reopens >= 100000) break
                reopens++
                onUi { reopen() }
                pause(450)
                if (onUi { topDialog() } == null) break
                continue
            }
            if (!logged) {
                logged = true
                dialogsOpened++
                val content = onUi {
                    val l = ArrayList<String>()
                    val dv = d.window?.decorView
                    if (dv != null) texts(dv, l)
                    l.joinToString(" | ").take(500)
                }
                line("   فُتحت نافذة (عمق $depth)، محتواها حرفياً: $content")
            }
            if (lastDlg !== d) {
                lastDlg = d
                val typed = onUi {
                    val res = ArrayList<String>()
                    val dv = d.window?.decorView
                    fun walk(v: View) {
                        if (v.visibility != View.VISIBLE) return
                        if (v is EditText && v.isShown && v.isEnabled) {
                            val old = v.text?.toString().orEmpty()
                            v.setText("1")
                            res.add("حقل «" + (v.hint?.toString() ?: "") + "» كان «" + old + "» كتبت فيه «1» وقرأت «" + v.text + "»")
                        }
                        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                    }
                    if (dv != null) walk(dv)
                    res
                }
                if (typed != null) for (t in typed) line("   داخل النافذة: $t")
            }
            val tg = onUi { dialogTargets(d) } ?: break
            val next = tg.firstOrNull { !visited.contains(it.key) }
            if (next == null) {
                pending = false
                break
            }
            visited.add(next.key)
            pending = tg.any { !visited.contains(it.key) }
            val hit = skipHit(next.desc)
            if (hit != null) {
                skipped++
                skippedList.add("داخل نافذة: «" + next.desc + "» — يحتوي «$hit»")
                line("   داخل النافذة: تخطي «" + next.desc + "» لأنه يحتوي «$hit»")
                continue
            }
            dialogItems++
            total++
            try {
                line("   داخل النافذة: أجرّب الضغط على «" + next.desc + "»")
                onUi {
                    val lv = next.lv
                    if (lv != null) {
                        val ch = lv.getChildAt(next.pos - lv.firstVisiblePosition) ?: lv
                        lv.performItemClick(ch, next.pos, lv.adapter.getItemId(next.pos))
                    } else next.v!!.performClick()
                }
                pause(450)
                ensureFocus()
                line("   داخل النافذة: ضغط على «" + next.desc + "» → نجح بدون خطأ")
                okc++
                clipGuard()
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Throwable) {
                bad++
                failedList.add("داخل نافذة: «" + next.desc + "»")
                line("   داخل النافذة: فشل الضغط على «" + next.desc + "» → " + err(e))
            }
            val d2 = onUi { topDialog() }
            if (d2 != null && d2 !== d && depth < 40) {
                line("   الضغط فتح نافذة أخرى داخلها")
                exploreDialog(null, depth + 1)
            }
        }
        closeDialogs()
    }

    private fun ensureFocus() {
        if (onUi { topDialog() } != null) return
        var i = 0
        while (!act.hasWindowFocus() && i < 8) {
            Thread.sleep(250)
            i++
        }
        if (!act.hasWindowFocus() && onUi { topDialog() } == null) {
            line("   خرج التطبيق إلى واجهة خارجية (مشاركة أو اختيار ملف أو غيره) — أعيد التطبيق إلى الأمام")
            onUi {
                val it = Intent(act, act.javaClass)
                it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                act.startActivity(it)
            }
            Thread.sleep(800)
        }
    }

    // ---------- حقول الكتابة والمنزلقات ----------

    private fun fieldsPass() {
        val edits = onUi {
            val out = ArrayList<EditText>()
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE || v.tag == "scan_page") return
                if (v is EditText && v.isShown && v.isEnabled && v.width > 0) out.add(v)
                if (v is ViewGroup && v !is WebView) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            out
        } ?: return
        for (e in edits) {
            if (!running) throw InterruptedException()
            try {
                val name = onUi { (e.hint?.toString() ?: "").ifBlank { "حقل بلا تلميح" } } ?: "حقل"
                val old = onUi { e.text?.toString().orEmpty() } ?: ""
                val test = "اختبار الفحص 123"
                fieldsTried++
                onUi { e.setText(test) }
                pause(250)
                val back = onUi { e.text?.toString().orEmpty() }
                line("أكتب في الحقل «$name» النص «$test» وأقرأ منه «$back» → " + (if (back == test) "سليم" else "خلل: لم يُحفظ النص كما كُتب"))
                onUi { e.setText(old) }
                line("   أعدتُ نص الحقل «$name» كما كان")
            } catch (x: InterruptedException) {
                throw x
            } catch (x: Throwable) {
                failedList.add("حقل كتابة")
                line("فشل فحص حقل كتابة → " + err(x))
            }
        }
        val bars = onUi {
            val out = ArrayList<SeekBar>()
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE || v.tag == "scan_page") return
                if (v is SeekBar && v.isShown && v.isEnabled && v.width > 0) out.add(v)
                if (v is ViewGroup && v !is WebView) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            out
        } ?: return
        for (s in bars) {
            if (!running) throw InterruptedException()
            try {
                val old = onUi { s.progress } ?: 0
                val mx = onUi { s.max } ?: 0
                fieldsTried++
                onUi { s.progress = mx / 2 }
                pause(250)
                val mid = onUi { s.progress }
                line("منزلق: كان $old والأقصى $mx، حرّكته للمنتصف فصار $mid")
                onUi { s.progress = old }
                line("   أعدتُ المنزلق إلى $old")
            } catch (x: InterruptedException) {
                throw x
            } catch (x: Throwable) {
                failedList.add("منزلق")
                line("فشل فحص منزلق → " + err(x))
            }
        }
    }

    // ---------- الفحص ----------

    private class Entry(val v: View, val path: String, val desc: String)

    private fun runAll() {
        try { liveFile.writeText("", Charsets.UTF_8) } catch (e: Throwable) {}
        line("بدأ الفحص الشامل")
        line("يُكتب السجل أيضاً سطراً بسطر أثناء الفحص في الملف: " + liveFile.absolutePath)
        line("إصدار أندرويد: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ") — الجهاز: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        line("الحافظة محفوظة قبل البدء وستُعاد كما هي، والإعدادات محفوظة وأي تغيير سيُرجَع")

        funcTests()
        pause(300)
        netTest()
        pause(300)
        browserTest()
        pause(300)
        webTest()
        pause(300)
        captureTest()
        pause(300)
        dlTest(scanUrl("downloads/fullscan.bin", "https://proof.ovh.net/files/1Mb.dat"))
        pause(300)

        val n = pageCount()
        val visited = HashSet<String>()
        val openers = HashSet<String>()
        val pathHits = HashMap<String, Int>()
        for (i in 0 until n) {
            pause(200)
            line("")
            line("===== فتح الصفحة: " + pageName(i) + " =====")
            var round = 0
            while (round++ < 100000) {
                var fresh = 0
                onUi { resetPage(i) }
                pause(700)
                var guard = 0
                var lastCtx = onUi { pageCtx() } ?: ""
                while (true) {
                    if (!running) throw InterruptedException()
                    val ctx = onUi { pageCtx() } ?: ""
                    if (ctx != lastCtx) {
                        line("   ← صرتُ الآن في: " + pageLabelOf(ctx))
                        lastCtx = ctx
                    }
                    val items = onUi {
                        val l = ArrayList<Pair<View, String>>()
                        collect(root, "r", l)
                        l.map { Entry(it.first, it.second, describe(it.first)) }
                    } ?: break
                    fun keyOf(e: Entry) = ctx + "|" + e.path + "#" + e.v.javaClass.simpleName + "#" + e.desc
                    val fresh1 = items.filter { !visited.contains(keyOf(it)) }
                    if (fresh1.isEmpty()) break
                    val target = fresh1.firstOrNull { !isBackLike(it.desc, it.v, ctx) } ?: fresh1.first()
                    val key = keyOf(target)
                    visited.add(key)
                    // حماية من الدوران إلى الأبد: عنصر يتغير نصه بعد كل ضغطة (نسبة تحميل، عدّاد) يُضغط 3 مرات فقط ثم يُسجَّل
                    val slot = ctx + "|" + target.path + "#" + target.v.javaClass.simpleName
                    val hitsNow = (pathHits[slot] ?: 0) + 1
                    pathHits[slot] = hitsNow
                    if (hitsNow > 3) {
                        skipped++
                        skippedList.add("«" + target.desc + "» نصه يتغير باستمرار وقد ضُغط 3 مرات")
                        line("توقفت عن تكرار الضغط على «" + target.desc + "» لأن نصه يتغير بعد كل ضغطة وقد جُرّب 3 مرات")
                        continue
                    }
                    val v = target.v
                    val desc = target.desc
                    val pk = pageLabelOf(ctx)
                    val st = perPage.getOrPut(pk) { IntArray(4) }
                    total++
                    st[0]++
                    if (onUi { inScanPage(v) } == true) { skipped++; st[3]++; continue }
                    val hit = skipHit(desc)
                    if (hit != null) {
                        skipped++
                        st[3]++
                        skippedList.add("$pk: «$desc» — يحتوي «$hit»")
                        line("تخطي الضغط على «$desc» لأنه يحتوي «$hit» (قد يحذف بياناتك أو يعيد إنشاء التطبيق أو يقطع الفحص)")
                        continue
                    }
                    val isBack = isBackLike(desc, v, ctx)
                    if (!openers.contains(key)) fresh++
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
                            if (isSwitch) {
                                if (before == after) {
                                    bad++
                                    st[2]++
                                    failedList.add("$pk: مفتاح «$desc» لم يتغير بعد الضغط")
                                    line("ضغط على مفتاح «$desc»: كان $before وبقي $after → خلل: المفتاح لم يتغير")
                                } else {
                                    okc++
                                    st[1]++
                                    line("ضغط على مفتاح «$desc»: كان $before وصار $after" + (if (r == 2) " (إرجاع للحالة الأصلية)" else ""))
                                }
                            } else {
                                okc++
                                st[1]++
                                line("ضغط على «$desc» → نجح بدون خطأ")
                            }
                            clipGuard()
                            val ctx2 = onUi { pageCtx() } ?: ""
                            if (ctx2 != ctx && !isSwitch) openers.add(key)
                            if (onUi { topDialog() } != null) exploreDialog({ v.performClick() }, 0)
                        } catch (e: InterruptedException) {
                            throw e
                        } catch (e: Throwable) {
                            bad++
                            st[2]++
                            failedList.add("$pk: «$desc» — " + (e.message ?: e.javaClass.simpleName))
                            line("فشل الضغط على «$desc» → " + err(e))
                            try { closeDialogs() } catch (e2: Throwable) {}
                        }
                    }
                    // الضغط المطوّل
                    if (v.isLongClickable && !isSwitch && !isBack) {
                        try {
                            longTried++
                            line("أجرّب الضغط المطوّل على «$desc»")
                            onUi { v.performLongClick() }
                            pause(450)
                            ensureFocus()
                            line("ضغط مطوّل على «$desc» → نجح بدون خطأ")
                            clipGuard()
                            if (onUi { topDialog() } != null) exploreDialog({ v.performLongClick() }, 0)
                        } catch (e: InterruptedException) {
                            throw e
                        } catch (e: Throwable) {
                            bad++
                            failedList.add("$pk: ضغط مطوّل على «$desc» — " + (e.message ?: e.javaClass.simpleName))
                            line("فشل الضغط المطوّل على «$desc» → " + err(e))
                            try { closeDialogs() } catch (e2: Throwable) {}
                        }
                    }
                }
                try { fieldsPass() } catch (e: InterruptedException) { throw e } catch (e: Throwable) { line("فشل فحص الحقول: " + err(e)) }
                try { gesturesPass() } catch (e: InterruptedException) { throw e } catch (e: Throwable) { line("فشل فحص التمرير والسحب: " + err(e)) }
                if (openers.isEmpty() || (round > 1 && fresh == 0)) break
                if (round == 1 && fresh == 0) break
                // أعد الضغط على الأزرار التي تنقل إلى صفحة أخرى لعل خلفها عناصر لم تُفحص بعد
                visited.removeAll(openers)
                line("   جولة إضافية ${round + 1} في هذه الصفحة لفحص ما لم يُفحص بعد")
            }
        }
        auditVisited = HashSet(visited)
        try { audit() } catch (e: InterruptedException) { throw e } catch (e: Throwable) { line("فشل التدقيق: " + err(e)) }
        backTest()
        onUi { closeMenu(); showPage(3) }
    }

    // زر الرجوع في الهاتف: مرة واحدة في كل حالة (الحالة بعد كل ضغطة تُسجَّل)
    private fun backTest() {
        line("")
        line("===== فحص زر الرجوع في الهاتف =====")
        for (i in 0 until pageCount()) {
            pause(2300)
            try {
                onUi { resetPage(i) }
                pause(500)
                val b = onUi { pageCtx() } ?: ""
                line("أضغط زر الرجوع وأنا في: " + pageLabelOf(b))
                onUi { pressBack() }
                pause(600)
                ensureFocus()
                val a = onUi { pageCtx() } ?: ""
                line("   بعد الرجوع صرت في: " + pageLabelOf(a) + " — ظهرت نافذة: " + (onUi { topDialog() != null }))
                closeDialogs()
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Throwable) {
                bad++
                failedList.add("زر الرجوع في صفحة " + pageName(i))
                line("فشل فحص زر الرجوع → " + err(e))
            }
        }
    }

    // تمرير كل قائمة إلى آخرها وأولها، ثم سحب بإصبع حقيقي في الاتجاهات الأربعة فوق الصفحة
    private fun gesturesPass() {
        val scrollers = onUi {
            val out = ArrayList<View>()
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE || v.tag == "scan_page" || !v.isShown) return
                if (v is ScrollView || v is ListView || v is WebView) out.add(v)
                if (v is ViewGroup && v !is WebView) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            out
        } ?: return
        for (v in scrollers) {
            if (!running) throw InterruptedException()
            try {
                val nm = v.javaClass.simpleName
                line("أمرّر $nm إلى آخره ثم إلى أوله")
                val y1 = onUi {
                    when (v) {
                        is ScrollView -> { v.fullScroll(View.FOCUS_DOWN); v.scrollY }
                        is WebView -> { v.pageDown(true); v.scrollY }
                        is ListView -> { v.smoothScrollToPosition(v.count); v.firstVisiblePosition }
                        else -> 0
                    }
                }
                pause(500)
                val y2 = onUi {
                    when (v) {
                        is ScrollView -> { v.fullScroll(View.FOCUS_UP); v.scrollY }
                        is WebView -> { v.pageUp(true); v.scrollY }
                        is ListView -> { v.smoothScrollToPosition(0); v.firstVisiblePosition }
                        else -> 0
                    }
                }
                pause(500)
                line("   موضع التمرير بعد النزول: $y1 — بعد الصعود: $y2")
                fieldsTried++
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Throwable) {
                bad++
                failedList.add("تمرير")
                line("فشل التمرير → " + err(e))
            }
        }
        val dv = act.window.decorView
        val w = dv.width
        val h = dv.height
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val moves = listOf(
            Triple("لأعلى", floatArrayOf(cx, cy + h / 4f, cx, cy - h / 4f), 0),
            Triple("لأسفل", floatArrayOf(cx, cy - h / 4f, cx, cy + h / 4f), 0),
            Triple("لليسار", floatArrayOf(cx + w / 3f, cy, cx - w / 3f, cy), 0),
            Triple("لليمين", floatArrayOf(cx - w / 3f, cy, cx + w / 3f, cy), 0)
        )
        for ((nm, c, _) in moves) {
            if (!running) throw InterruptedException()
            try {
                val before = onUi { pageCtx() } ?: ""
                line("أسحب بإصبعي $nm وسط الشاشة")
                val t0 = SystemClock.uptimeMillis()
                onUi { dv.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, c[0], c[1], 0)) }
                for (k in 1..8) {
                    val f = k / 8f
                    val x = c[0] + (c[2] - c[0]) * f
                    val y = c[1] + (c[3] - c[1]) * f
                    val tm = SystemClock.uptimeMillis()
                    onUi { dv.dispatchTouchEvent(MotionEvent.obtain(t0, tm, MotionEvent.ACTION_MOVE, x, y, 0)) }
                    Thread.sleep(25)
                }
                val t1 = SystemClock.uptimeMillis()
                onUi { dv.dispatchTouchEvent(MotionEvent.obtain(t0, t1, MotionEvent.ACTION_UP, c[2], c[3], 0)) }
                pause(500)
                ensureFocus()
                val after = onUi { pageCtx() } ?: ""
                line("   السحب $nm انتهى بدون خطأ" + (if (after != before) " — وانتقل التطبيق إلى: " + pageLabelOf(after) else " — بقي في نفس المكان"))
                fieldsTried++
                if (onUi { topDialog() } != null) exploreDialog(null, 0)
                if (after != before) onUi { resetPage(Regex("p(\\d+)").find(before)?.groupValues?.get(1)?.toIntOrNull() ?: 0) }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Throwable) {
                bad++
                failedList.add("سحب $nm")
                line("فشل السحب $nm → " + err(e))
            }
        }
    }

    private fun evalJs(w: WebView, js: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        ui.post {
            try {
                w.evaluateJavascript(js) { r -> out = r ?: ""; latch.countDown() }
            } catch (e: Throwable) {
                out = "خطأ: " + (e.message ?: e.javaClass.name)
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) return "انتهت مهلة تنفيذ الجافاسكربت"
        return out.removeSurrounding("\"").replace("\\n", "\n").replace("\\u003C", "<").replace("\\\"", "\"")
    }

    // الضغط على كل عنصر داخل صفحة الموقع الخفيف نفسها (روابط وأزرار وحقول) كما يفعل الإنسان
    private fun webTest() {
        line("")
        line("===== فحص الضغط داخل صفحة الاختبار المحلية/الاحتياطية =====")
        val base = scanUrl("", "https://example.com/")
        try {
            onUi { closeMenu(); showPage(0); openBrowserUrl(base) }
            var waited = 0
            while (waited < 15000) {
                pause(1000)
                waited += 1000
                if ((onUi { browserProbe() } ?: "").endsWith("|100")) break
            }
            val w = onUi { webView() }
            if (w == null) {
                line("لا يوجد تبويب متصفح مفتوح لفحصه")
                return
            }
            val sel = "a,button,input,select,textarea,[onclick],[role=button]"
            val list = evalJs(w, "(function(){var r=[];var els=document.querySelectorAll('$sel');for(var i=0;i<els.length;i++){var e=els[i];r.push(i+'|'+e.tagName+'|'+((e.innerText||e.value||e.name||'')+'').trim().slice(0,80)+'|'+(e.getAttribute('href')||''));}return r.join('\\n');})()")
            val rows = list.split("\n").filter { it.isNotBlank() }
            line("عدد العناصر القابلة للضغط في الصفحة: " + rows.size)
            for (r in rows) line("   عنصر: $r")
            for (r in rows) {
                if (!running) throw InterruptedException()
                val idx = r.substringBefore("|").toIntOrNull() ?: continue
                val tag = r.split("|").getOrNull(1) ?: ""
                total++
                try {
                    line("أجرّب الضغط داخل الصفحة على: $r")
                    val res = if (tag == "INPUT" || tag == "TEXTAREA") evalJs(w, "(function(){var e=document.querySelectorAll('$sel')[$idx];var o=e.value;e.focus();e.value='اختبار';var n=e.value;e.value=o;return 'كان ['+o+'] كتبت [اختبار] وقرأت ['+n+']';})()")
                    else evalJs(w, "(function(){var e=document.querySelectorAll('$sel')[$idx];e.click();return 'تم الضغط';})()")
                    pause(2500)
                    val info = onUi { browserProbe() } ?: ""
                    line("   النتيجة: $res — حالة المتصفح بعد الضغط: $info")
                    okc++
                    if (!info.startsWith(base)) {
                        line("   انتقل المتصفح إلى صفحة أخرى — أعود إلى الصفحة الخفيفة")
                        onUi { openBrowserUrl(base) }
                        pause(3500)
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) {
                    bad++
                    failedList.add("داخل صفحة الموقع: $r")
                    line("   فشل الضغط داخل الصفحة → " + err(e))
                }
            }
            val sc = evalJs(w, "(function(){window.scrollTo(0,document.body.scrollHeight);var a=window.scrollY;window.scrollTo(0,0);return 'نزلت إلى '+a+' ثم عدت إلى '+window.scrollY;})()")
            line("تمرير الصفحة نفسها: $sc")
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Throwable) {
            line("فشل فحص داخل الصفحة: " + err(e))
        }
    }

    private fun keyFor(ctx: String, e: Entry) = ctx + "|" + e.path + "#" + e.v.javaClass.simpleName + "#" + e.desc

    private var auditVisited: Set<String> = emptySet()

    // تدقيق ذاتي: يعيد فتح كل صفحة ويعدّ كل عنصر ظاهر لم يُضغط
    private fun audit() {
        line("")
        line("===== تدقيق التغطية: عناصر ظاهرة لم تُضغط =====")
        var left = 0
        for (i in 0 until pageCount()) {
            pause(200)
            onUi { resetPage(i) }
            pause(600)
            val ctx = onUi { pageCtx() } ?: ""
            val items = onUi {
                val l = ArrayList<Pair<View, String>>()
                collect(root, "r", l)
                l.map { Entry(it.first, it.second, describe(it.first)) }
            } ?: continue
            val miss = items.filter { !auditVisited.contains(keyFor(ctx, it)) && !it.desc.startsWith("زر بلا نص") }
            if (miss.isEmpty()) line(pageName(i) + ": كل العناصر الظاهرة ضُغطت أو سُجّل سبب تخطيها")
            else for (m in miss) { left++; line(pageName(i) + ": ظاهر ولم يُضغط: " + m.desc + " (" + m.v.javaClass.simpleName + ")") }
        }
        line("مجموع العناصر الظاهرة التي لم تُضغط: $left")
    }

    // التقاط فيديو حقيقي: يفتح رابط فيديو صغيراً في المتصفح وينتظر أن يلتقطه التطبيق في قائمة التحميلات
    private fun captureTest() {
        val u = scanUrl("assets/sample.mp4", "https://www.w3schools.com/html/mov_bbb.mp4")
        line("")
        line("===== فحص التقاط الفيديو: فتح فيديو الاختبار ($u) =====")
        try {
            onUi { dl("remove", u) }
            onUi { closeMenu(); showPage(0); openBrowserUrl(u) }
            var waited = 0
            var st = ""
            while (waited < 30000) {
                pause(1000)
                waited += 1000
                st = onUi { dl("status", u) } ?: ""
                if (st.startsWith("state=")) break
            }
            if (st.startsWith("state=")) line("التقط التطبيق الفيديو بعد ${waited / 1000} ثانية: $st")
            else line("لم يلتقط التطبيق الفيديو خلال 30 ثانية. آخر رد: $st")
            line("إزالة صف الاختبار: " + onUi { dl("remove", u) })
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Throwable) {
            line("فشل فحص التقاط الفيديو: " + err(e))
        }
    }

    private fun printSummary() {
        line("")
        line("===== الخلاصة =====")
        line("عدد العناصر التي وُجدت: $total — نجح الضغط: $okc — فشل: $bad — تُخطي: $skipped")
        line("نوافذ فُتحت وفُحصت: $dialogsOpened — عناصر ضُغطت داخل النوافذ: $dialogItems — ضغطات مطوّلة: $longTried — حقول ومنزلقات: $fieldsTried — مرات أعدتُ فيها الحافظة: $clipFixes")
        for ((k, a) in perPage) line("   $k: وُجد ${a[0]} — نجح ${a[1]} — فشل ${a[2]} — تُخطي ${a[3]}")
        if (failedList.isNotEmpty()) {
            line("الذي فشل حرفياً:")
            for (f in failedList) line("   - $f")
        }
        if (skippedList.isNotEmpty()) {
            line("الذي لم أضغطه ولماذا (حتى لا يحذف بياناتك أو يقطع الفحص):")
            for (f in skippedList) line("   - $f")
        }
        if (disabledList.isNotEmpty()) {
            line("عناصر ظاهرة لكنها معطّلة فلم يمكن ضغطها:")
            for (f in disabledList) line("   - $f")
        }
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
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            line("الحافظة: الخدمة " + (if (cm != null) "متوفرة" else "غير متوفرة") + " — لا أكتب فيها في هذه المرحلة حتى لا يتغير ما نسختَه أنت؛ أزرار النسخ داخل التطبيق تُجرَّب أثناء الضغط على الأزرار وتُعاد الحافظة بعد كل ضغطة")
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
        line("===== فحص التحميل من موقع الاختبار المحلي/الاحتياطي =====")
        val t0 = System.currentTimeMillis()
        try {
            val c = URL(scanUrl("", "https://example.com/")).openConnection() as HttpURLConnection
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
        line("===== فحص المتصفح: فتح موقع الاختبار المحلي/الاحتياطي =====")
        try {
            onUi { closeMenu(); showPage(0); openBrowserUrl(scanUrl("", "https://example.com/")) }
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
