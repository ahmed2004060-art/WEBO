package com.webo

import android.content.Context
import android.os.Build
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// المسجّل: مستقل تماماً عن MainActivity وعن أي ملف آخر في التطبيق.
// - يكتب كل حدث في ملف على الهاتف لحظة وقوعه (كتابة ثم flush فوراً).
// - يحفظ النص الكامل بلا أي اقتطاع، مع الوقت والخيط والتبويب والرابط.
// - لا يحذف أي شيء تلقائياً أبداً. عند امتلاء الملف الحالي يبدأ ملفاً جديداً:
//   diag-0001.log ثم diag-0002.log ...
// - لا يرمي استثناءً أبداً: أي فشل داخلي يذهب إلى logcat فقط.
object Diag {
    // حد الملف الواحد. حدث واحد لا يُقسَّم أبداً حتى لو تجاوز الحد وحده.
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    private val lock = Any()
    private var dir: File? = null
    private var index = 1
    private var out: FileOutputStream? = null
    private var written = 0L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // أحداث وقعت قبل init تُحفظ هنا وتُكتب أول شيء عند التهيئة
    private val early = ArrayList<String>()

    // يضبطه التطبيق: يعيد وصف التبويب الحالي ورابطه ليُكتبا مع كل حدث.
    // يُستدعى من أي خيط، فيجب ألا يلمس أي واجهة.
    @Volatile var tabProvider: (() -> String)? = null

    // يُستدعى في أول onCreate. آمن للتكرار.
    fun init(ctx: Context) {
        var first = false
        synchronized(lock) {
            if (dir == null) {
                try {
                    val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                    var d = File(base, "diag")
                    if (!d.isDirectory && !d.mkdirs()) d = File(ctx.filesDir, "diag").also { it.mkdirs() }
                    dir = d
                    var last = 0
                    d.listFiles()?.forEach { f ->
                        val m = Regex("diag-(\\d+)\\.log").matchEntire(f.name)
                        val n = m?.groupValues?.get(1)?.toIntOrNull()
                        if (n != null && n > last) last = n
                    }
                    index = if (last > 0) last else 1
                    openCurrent()
                    first = true
                } catch (t: Throwable) {
                    android.util.Log.e("Diag", "init failed", t)
                }
            }
        }
        if (!first) return
        installCrashHandler()
        val rt = Runtime.getRuntime()
        val pm = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (t: Throwable) { null }
        i("بدء", "تشغيل التطبيق | الإصدار " + (pm?.versionName ?: "؟") +
            " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
            " | " + Build.MANUFACTURER + " " + Build.MODEL +
            " | ذاكرة التطبيق " + (rt.totalMemory() / 1048576) + "/" + (rt.maxMemory() / 1048576) + " MB" +
            " | مجلد السجل " + (dir?.absolutePath ?: "؟"))
    }

    // حدث عادي
    fun i(kind: String, msg: String) {
        write(kind, msg, null)
    }

    // خطأ بنصه الكامل مع كل سلسلة الأسباب
    fun e(kind: String, where: String, t: Throwable?) {
        write(kind, where, t)
    }

    // خطأ كان يُبتلع بصمت في catch (where = ملف:سطر ثم اسم الدالة)
    fun swallow(where: String, t: Throwable) {
        write("مبتلع", where, t)
    }

    // خطأ جاء من جافاسكربت داخل صفحة ويب (يصل عبر الجسر)
    fun js(msg: String) {
        write("جافاسكربت-مبتلع", msg, null)
    }

    // ملفات السجل بترتيب الإنشاء
    // هل يوجد النص في أي ملف سجل (للفحص الذاتي)؟ الكتابة تُفرَّغ فوراً فلا حاجة لانتظار
    fun contains(marker: String): Boolean {
        for (f in files().reversed()) {
            try {
                if (f.readText(Charsets.UTF_8).contains(marker)) return true
            } catch (t: Throwable) {
                android.util.Log.e("Diag", "contains failed", t)
            }
        }
        return false
    }

    // يقرأ الأحداث من الملفات (الأحدث أولاً) ويقسمها حرفياً بلا أي اقتطاع
    fun readEvents(max: Int): List<DiagEvent> {
        val out = ArrayList<DiagEvent>()
        val re = Regex("^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})  \\[(.*?)\\]  (.*)$")
        for (f in files().reversed()) {
            val lines = try { f.readLines(Charsets.UTF_8) } catch (t: Throwable) { android.util.Log.e("Diag", "readEvents failed", t); continue }
            val evs = ArrayList<DiagEvent>()
            var time = ""
            var kind = ""
            var head = ""
            val body = StringBuilder()
            var open = false
            var boundary = true
            for (l in lines) {
                if (boundary) {
                    val m = re.matchEntire(l)
                    if (m != null) {
                        if (open) evs.add(DiagEvent(time, kind, head, body.toString().trimEnd('\n')))
                        body.setLength(0)
                        time = m.groupValues[1]; kind = m.groupValues[2]; head = m.groupValues[3]; open = true
                        boundary = false
                        continue
                    }
                }
                if (l == "----") {
                    if (open) { evs.add(DiagEvent(time, kind, head, body.toString().trimEnd('\n'))); open = false }
                    body.setLength(0)
                    boundary = true
                    continue
                }
                if (open) body.append(l).append('\n')
            }
            if (open) evs.add(DiagEvent(time, kind, head, body.toString().trimEnd('\n')))
            out.addAll(evs.reversed())
            if (out.size >= max) break
        }
        return if (out.size > max) out.subList(0, max) else out
    }

    // يكتب تقريراً كاملاً: رأس + نص إضافي + كل ملفات السجل من الأقدم للأحدث (نسخ بالتدفق بلا تحميل كل شيء في الذاكرة)
    fun exportTo(dest: File, header: String) {
        dest.outputStream().buffered().use { o ->
            o.write(header.toByteArray(Charsets.UTF_8))
            o.write("\n==== ملفات السجل ====\n".toByteArray(Charsets.UTF_8))
            for (f in files()) {
                o.write(("\n######## " + f.name + " (" + f.length() + " بايت) ########\n").toByteArray(Charsets.UTF_8))
                f.inputStream().use { it.copyTo(o) }
            }
        }
    }

    // يحذف كل ملفات السجل ثم يبدأ ملفاً جديداً فارغاً
    fun clearAll() {
        synchronized(lock) {
            try { out?.close() } catch (t: Throwable) { android.util.Log.e("Diag", "close failed", t) }
            out = null
            dir?.listFiles()?.filter { it.name.startsWith("diag-") }?.forEach { it.delete() }
            index = 1
            written = 0L
            try { openCurrent() } catch (t: Throwable) { android.util.Log.e("Diag", "reopen failed", t) }
        }
    }

    fun dirPath(): String = dir?.absolutePath ?: "؟"

    fun files(): List<File> = synchronized(lock) {
        dir?.listFiles()?.filter { it.name.startsWith("diag-") }?.sortedBy { it.name } ?: emptyList()
    }

    // أي انهيار غير ملتقط في أي خيط يُكتب كاملاً قبل أن تموت العملية، ثم يُمرَّر للمعالج السابق
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            write("انهيار", "توقف التطبيق بسبب خطأ غير ملتقط في الخيط " + th.name, ex)
            prev?.uncaughtException(th, ex)
        }
    }

    private fun openCurrent() {
        val d = dir ?: return
        try { out?.close() } catch (t: Throwable) { android.util.Log.e("Diag", "close failed", t) }
        val f = File(d, "diag-%04d.log".format(index))
        written = if (f.exists()) f.length() else 0L
        out = FileOutputStream(f, true)
    }

    private fun tabText(): String {
        val p = tabProvider ?: return ""
        return try { p() } catch (t: Throwable) { "؟" }
    }

    private fun format(kind: String, msg: String, t: Throwable?): String {
        val sb = StringBuilder()
        sb.append(synchronized(fmt) { fmt.format(Date()) }).append("  [").append(kind).append("]  ")
        val th = Thread.currentThread()
        sb.append("خيط=").append(th.name)
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) sb.append(" (رئيسي)")
        val tab = tabText()
        if (tab.isNotEmpty()) sb.append("  تبويب=").append(tab)
        sb.append('\n').append(msg).append('\n')
        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString()).append('\n')
        }
        sb.append("----\n")
        return sb.toString()
    }

    private fun write(kind: String, msg: String, t: Throwable?) {
        try {
            val text = format(kind, msg, t)
            synchronized(lock) {
                if (out == null) {
                    // قبل التهيئة: لا يضيع شيء
                    early.add(text)
                } else {
                    if (early.isNotEmpty()) {
                        val pending = ArrayList(early)
                        early.clear()
                        for (p in pending) append(p)
                    }
                    append(text)
                }
            }
        } catch (x: Throwable) {
            android.util.Log.e("Diag", "write failed", x)
        }
        if (t != null) android.util.Log.e("Diag", kind + " | " + msg, t)
    }

    // يُستدعى تحت القفل فقط
    private fun append(text: String) {
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (written > 0 && written + bytes.size > MAX_FILE_BYTES) {
                index++
                openCurrent()
            }
            out?.write(bytes)
            out?.flush()
            written += bytes.size
        } catch (x: Throwable) {
            android.util.Log.e("Diag", "append failed", x)
        }
    }
}

// مصنع خيوط يسمّي الخيط باسم المنفّذ ليظهر الاسم في السجل
internal class DiagNamed(private val base: String) : java.util.concurrent.ThreadFactory {
    private val n = java.util.concurrent.atomic.AtomicInteger(0)
    private val def = java.util.concurrent.Executors.defaultThreadFactory()
    override fun newThread(r: Runnable): Thread {
        val t = def.newThread(r)
        t.name = "webo-" + base + "-" + n.incrementAndGet()
        return t
    }
}

// منفّذ مهام يسجّل كل استثناء يخرج من أي مهمة (execute أو submit) قبل أن يموت الخيط بصمت.
// السلوك مطابق للمنفّذات الأصلية: الاستثناء يستمر كما كان، والفرق الوحيد التسجيل وتسمية الخيوط.
class DiagPool(
    private val poolName: String,
    core: Int,
    max: Int,
    keep: Long,
    unit: java.util.concurrent.TimeUnit,
    q: java.util.concurrent.BlockingQueue<Runnable>
) : java.util.concurrent.ThreadPoolExecutor(core, max, keep, unit, q, DiagNamed(poolName)) {

    override fun afterExecute(r: Runnable?, t: Throwable?) {
        super.afterExecute(r, t)
        var err: Throwable? = t
        if (err == null && r is java.util.concurrent.Future<*>) {
            try {
                if (r.isDone && !r.isCancelled) r.get()
            } catch (x: java.util.concurrent.ExecutionException) {
                err = x.cause ?: x
            } catch (x: java.util.concurrent.CancellationException) {
                err = null
            } catch (x: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (err is CancelledException) Diag.i("مهمة", "أُلغيت مهمة بطلب المستخدم داخل المنفّذ " + poolName)
        else if (err != null) Diag.e("مهمة", "استثناء غير ملتقط داخل المنفّذ " + poolName, err)
    }
}

// خيط مسمّى يسجّل أي استثناء غير ملتقط بدل موته بصمت (يُستعمل بدل Thread { } الخام)
internal fun diagThread(name: String, block: () -> Unit): Thread = Thread({
    try {
        block()
    } catch (t: Throwable) {
        Diag.e("خيط", "استثناء غير ملتقط في خيط " + name, t)
        throw t
    }
}, "webo-" + name)

internal fun diagFixed(name: String, n: Int): DiagPool =
    DiagPool(name, n, n, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>())

internal fun diagSingle(name: String): DiagPool = diagFixed(name, 1)

// تتبع دورة حياة التحميل: كل حدث بمعرّفه ورابطه وبايتاته ومجلد عمله
internal fun dlTrace(event: String, r: Row, extra: String = "") {
    Diag.i("تحميل", event + " | id=" + r.id + " | حالة=" + r.state + " | بايتات=" + r.ctl.bytes.get() +
        " | عمل=" + (r.work?.absolutePath ?: "-") + (if (extra.isEmpty()) "" else " | " + extra) + " | الرابط " + r.url)
}

class DiagEvent(val time: String, val kind: String, val head: String, val body: String)

// يعرض المحارف غير المطبوعة بصيغة مقروءة (u0002) دون تغيير النص الأصلي؛ لتظهر في التقرير أسباب الروابط المشوّهة
internal fun diagEsc(s: String): String {
    val sb = StringBuilder()
    for (ch in s) {
        if (ch.isISOControl() || ch == ' ' || ch == '\u00a0') sb.append("\\u").append(String.format("%04x", ch.code))
        else sb.append(ch)
    }
    return sb.toString()
}
