package com.webo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مستكشف الاختبار الآلي لتطبيق Webo.
 *
 * يحل محل تطبيق "webo-tester" المنفصل (الذي كان يعتمد على AccessibilityService
 * للتحكم في Webo من خارجه). هذا الاختبار يعمل كـ instrumentation test رسمي من
 * Google ضمن مشروع Webo نفسه، فلا يحتاج أي إذن حساس (لا Accessibility، لا
 * REQUEST_INSTALL_PACKAGES)، وبالتالي لا يصنَّفه Google Play Protect كتطبيق ضار.
 *
 * يُشغَّل عبر: ./gradlew connectedDebugAndroidTest
 * (أو تلقائياً من GitHub Actions بعد كل بناء — انظر .github/workflows/build.yml)
 *
 * لا يعتمد على أي كود داخلي في Webo (لا SelfTest ولا غيره): يكتشف واجهة
 * Webo بالكامل من الشاشة فقط، تماماً كما كان يفعل المختبِر القديم.
 */
class WeboExplorerTest {

    companion object {
        const val TARGET = "com.webo"
        const val MAX_STEPS = 1200
        const val MAX_MINUTES = 25
        const val STAGNATION = 150
        const val PER_SCREEN = 30
    }

    private val urls = listOf(
        "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4",
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",
        "https://example.com",
        "بحث تجريبي",
        ""
    )

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var shotsDir: File
    private lateinit var logW: FileWriter
    private val events = ArrayList<String>()
    private val screens = LinkedHashMap<String, Int>()
    private val tried = HashSet<String>()
    private val perScreen = HashMap<String, Int>()
    private var steps = 0
    private var shots = 0
    private var crashes = 0
    private var anrs = 0
    private var sinceNew = 0
    private var startT = 0L

    @Test
    fun exploreWebo() {
        val inst = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(inst)
        context = inst.targetContext

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // مجلد خاص بتطبيق Webo نفسه (App-specific external storage) — لا يحتاج أي
        // إذن تخزين على أي إصدار أندرويد (بعكس مجلد Downloads العام).
        val base = File(context.getExternalFilesDir(null), "webo-test-report")
        dir = File(base, ts)
        shotsDir = File(dir, "shots")
        shotsDir.mkdirs()
        logW = FileWriter(File(dir, "log.jsonl"))

        startT = now()
        log("START", "بدء اختبار Webo عبر UI Automator — SDK ${android.os.Build.VERSION.SDK_INT}")

        launch()
        device.waitForIdle(3000)

        var exhausted = 0
        while (true) {
            if (steps >= MAX_STEPS) { log("END", "بلغ الحد الأقصى للخطوات"); break }
            if (now() - startT > MAX_MINUTES * 60_000L) { log("END", "انتهت المدة المحددة"); break }
            if (sinceNew >= STAGNATION) { log("END", "لم تظهر شاشة جديدة لفترة طويلة"); break }

            if (handleSystemDialogsIfAny()) continue

            val pkg = device.currentPackageName
            if (pkg != TARGET) {
                log("OFFAPP", "خرج التطبيق إلى: $pkg")
                device.pressBack()
                device.waitForIdle(1000)
                if (device.currentPackageName != TARGET) relaunch("بعد الخروج من Webo")
                continue
            }

            val els = collect()
            val sig = sigOf(els)
            if (!screens.containsKey(sig)) {
                screens[sig] = screens.size + 1
                sinceNew = 0
                val s = shot()
                log("SCREEN", "شاشة جديدة #${screens.size}: ${els.size} عنصر تفاعلي", s)
            }

            val next = pickNext(sig, els)
            if (next == null) {
                exhausted++
                if (exhausted >= 3) { log("END", "اكتمل استكشاف كل ما أمكن الوصول إليه"); break }
                device.pressBack()
                device.waitForIdle(800)
                continue
            }
            exhausted = 0
            doAction(sig, next)
        }

        log("SUMMARY", "خطوات=$steps شاشات=${screens.size} انهيارات=$crashes عدم استجابة=$anrs لقطات=$shots")
        logW.flush()
        logW.close()
        writeReport()
    }

    // ---------------------------------------------------------------- basics

    private fun now() = System.currentTimeMillis()

    private fun log(kind: String, msg: String, shot: String? = null) {
        events.add("[$kind] $msg")
        try {
            val o = JSONObject()
            o.put("t", now()); o.put("kind", kind); o.put("msg", msg); o.put("shot", shot ?: "")
            logW.write(o.toString() + "\n")
            logW.flush()
        } catch (e: Exception) {
        }
    }

    private fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(TARGET)
            ?: throw IllegalStateException("Webo غير مثبّت على جهاز/محاكي الاختبار")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(TARGET).depth(0)), 8000)
    }

    private fun relaunch(reason: String) {
        log("RELAUNCH", reason)
        launch()
        device.waitForIdle(2000)
    }

    private fun shot(): String? {
        if (shots >= 200) return null
        return try {
            shots++
            val f = File(shotsDir, "s$shots.png")
            val bmp: Bitmap? = device.takeScreenshot(f)
            if (bmp != null) "shots/s$shots.png" else null
        } catch (e: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- system dialogs

    /** يتعامل مع نوافذ النظام (أذونات، تجمّد، انهيار). يرجع true لو تعامل مع شيء ويجب إعادة المحاولة. */
    private fun handleSystemDialogsIfAny(): Boolean {
        val pkg = device.currentPackageName ?: return false
        if (pkg == "com.android.permissioncontroller" || pkg == "com.google.android.permissioncontroller") {
            // لا نضغط تلقائياً على أذونات النظام أثناء الاختبار الآلي في CI؛
            // نمنحها عبر `pm grant` قبل بدء الاختبار (انظر ملف الـ workflow).
            // لو ظهرت رغم ذلك، نسجّلها فقط وننتظر دون ضغط تلقائي.
            log("PERM", "نافذة أذونات ظهرت ولم تُمنح مسبقاً — سجِّل وتابع")
            device.pressBack()
            device.waitForIdle(500)
            return true
        }
        val stopped = device.findObject(By.textContains("has stopped"))
            ?: device.findObject(By.textContains("keeps stopping"))
            ?: device.findObject(By.textContains("توقف"))
        if (stopped != null) {
            crashes++
            val s = shot()
            log("CRASH", "انهيار: " + stopped.text, s)
            val ok = device.findObject(By.text("Close app")) ?: device.findObject(By.text("OK"))
                ?: device.findObject(By.text("إغلاق التطبيق"))
            ok?.click() ?: device.pressBack()
            device.waitForIdle(800)
            return true
        }
        val notResp = device.findObject(By.textContains("isn't responding"))
            ?: device.findObject(By.textContains("لا يستجيب"))
        if (notResp != null) {
            anrs++
            val s = shot()
            log("ANR", "عدم استجابة", s)
            val wait = device.findObject(By.text("Wait")) ?: device.findObject(By.text("انتظار"))
            wait?.click() ?: device.pressBack()
            device.waitForIdle(1500)
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- element collection

    private class El(val node: UiObject2, val key: String, val modes: List<String>)

    private fun collect(): List<El> {
        val out = ArrayList<El>()
        val seen = HashSet<String>()
        fun add(nodes: List<UiObject2>, mode: String) {
            for (n in nodes) {
                try {
                    val b = n.visibleBounds ?: continue
                    if (b.width() <= 0 || b.height() <= 0) continue
                    val txt = (n.text ?: "").take(30)
                    val desc = (n.contentDescription ?: "").take(30)
                    val res = n.resourceName ?: ""
                    val key = "${n.className}|$res|$txt|$desc|${b.left / 24},${b.top / 24}"
                    if (!seen.add("$key#$mode")) continue
                    val existing = out.firstOrNull { it.key == key }
                    if (existing != null) {
                        out[out.indexOf(existing)] = El(n, key, existing.modes + mode)
                    } else {
                        out.add(El(n, key, listOf(mode)))
                    }
                } catch (e: Exception) {
                }
            }
        }
        try { add(device.findObjects(By.clazz("android.widget.EditText")), "T") } catch (e: Exception) {}
        try { add(device.findObjects(By.clickable(true)), "C") } catch (e: Exception) {}
        try { add(device.findObjects(By.scrollable(true)), "S") } catch (e: Exception) {}
        try { add(device.findObjects(By.longClickable(true)), "L") } catch (e: Exception) {}
        return out
    }

    private fun sigOf(els: List<El>): String {
        val b = StringBuilder(device.currentPackageName ?: "")
        for (e in els) b.append('|').append(e.key)
        return b.toString().hashCode().toString(16) + "-" + els.size
    }

    private class Task(val el: El, val mode: String, val id: String)

    private fun pickNext(sig: String, els: List<El>): Task? {
        if ((perScreen[sig] ?: 0) >= PER_SCREEN) return null
        for (e in els) {
            for (m in e.modes) {
                val id = "$sig#${e.key}#$m"
                if (id !in tried) return Task(e, m, id)
            }
        }
        return null
    }

    private fun doAction(sig: String, t: Task) {
        tried.add(t.id)
        perScreen[sig] = (perScreen[sig] ?: 0) + 1
        steps++
        sinceNew++
        val el = t.el
        var what = ""
        try {
            when (t.mode) {
                "C" -> { what = "نقر"; el.node.click() }
                "L" -> { what = "ضغط مطوّل"; el.node.longClick() }
                "S" -> {
                    what = "تمرير"
                    el.node.scroll(Direction.DOWN, 0.7f)
                    device.waitForIdle(300)
                    el.node.scroll(Direction.UP, 0.7f)
                }
                "T" -> {
                    val txt = urls[steps % urls.size]
                    what = "كتابة «${txt.take(30)}»"
                    el.node.click()
                    el.node.text = txt
                }
            }
        } catch (e: Exception) {
            log("ERR", "استثناء أثناء $what على ${el.key}: $e")
        }
        device.waitForIdle(1200)
        log("ACT", "$what على «${el.key.take(60)}»")
    }

    // ---------------------------------------------------------------- report

    private fun writeReport() {
        try {
            val html = StringBuilder()
            html.append("<html dir='rtl'><head><meta charset='utf-8'><title>تقرير اختبار Webo</title></head><body style='font-family:sans-serif;padding:16px'>")
            html.append("<h2>تقرير اختبار Webo (UI Automator)</h2>")
            html.append("<p>خطوات: $steps — شاشات مكتشفة: ${screens.size} — انهيارات: $crashes — عدم استجابة: $anrs — لقطات: $shots</p>")
            html.append("<h3>السجل الكامل</h3><pre style='white-space:pre-wrap'>")
            for (e in events) html.append(e.replace("<", "&lt;")).append("\n")
            html.append("</pre></body></html>")
            File(dir, "report.html").writeText(html.toString())
        } catch (e: Exception) {
        }
    }

    // دالة مساعدة محجوزة لفحص رابط اختبار مباشرة عبر HTTP لو احتجنا ذلك لاحقاً
    private fun checkUrl(u: String): Int {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            val code = c.responseCode
            c.disconnect()
            code
        } catch (e: Exception) {
            -1
        }
    }
}
