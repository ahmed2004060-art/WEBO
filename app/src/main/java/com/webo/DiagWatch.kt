package com.webo

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

// المراقبات: مستقلة عن MainActivity وتكتب في Diag فقط.
// 1) مراقب تجمّد الواجهة (ANR): خيط يرسل نبضة للخيط الرئيسي كل ثانية.
// 2) مراقب الذاكرة: أحداث النظام + عتبات استهلاك الذاكرة + لقطة دورية.
// 3) مرشّح أخطاء console القادمة من الصفحات (يحمي الخيط الرئيسي من الإغراق).
object DiagWatch {
    private val started = AtomicBoolean(false)
    private const val ANR_FIRST_MS = 1000L
    private const val ANR_SECOND_MS = 1000L
    private const val ANR_AGAIN_MS = 10000L
    private const val MEM_EVERY_MS = 15000L
    private const val BEAT_EVERY_MS = 300000L

    @Volatile private var pong = 0L

    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                Diag.i("ذاكرة-نظام", "onTrimMemory المستوى " + level + " (" + levelName(level) + ") | " + memLine())
            }

            override fun onLowMemory() {
                Diag.i("ذاكرة-نظام", "onLowMemory | " + memLine())
            }

            override fun onConfigurationChanged(c: Configuration) {}
        })
        val main = Handler(Looper.getMainLooper())
        pong = SystemClock.elapsedRealtime()
        val t = Thread({ loop(main) }, "webo-watch")
        t.isDaemon = true
        t.start()
    }

    private fun loop(main: Handler) {
        var lastTick = SystemClock.elapsedRealtime()
        var stallStart = 0L
        var nextStall = ANR_FIRST_MS
        var stackCount = 0
        var lastMem = 0L
        var lastBeat = lastTick
        var level = 0
        while (true) {
            val ping = SystemClock.elapsedRealtime()
            main.post { pong = SystemClock.elapsedRealtime() }
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Diag.e("مراقب", "توقف خيط المراقبة", e)
                return
            }
            val now = SystemClock.elapsedRealtime()
            // فجوة كبيرة بين دورتين = العملية نفسها كانت متوقفة (خلفية/تجميد)، لا تجمّد واجهة
            if (now - lastTick > 3500L) {
                stallStart = 0L
                nextStall = ANR_FIRST_MS
                stackCount = 0
                lastTick = now
                lastMem = now
                continue
            }
            lastTick = now
            if (pong >= ping) {
                if (stallStart != 0L) {
                    Diag.i("تجمّد-انتهى", "عادت الواجهة للاستجابة بعد " + (now - stallStart) + " ms")
                    stallStart = 0L
                    nextStall = ANR_FIRST_MS
                    stackCount = 0
                }
            } else {
                if (stallStart == 0L) stallStart = ping
                val d = now - stallStart
                if (d >= nextStall) {
                    Diag.i("تجمّد-واجهة", "الخيط الرئيسي لا يستجيب منذ " + d + " ms\n" + mainStack())
                    stackCount++
                    nextStall = d + (if (stackCount == 1) ANR_SECOND_MS else ANR_AGAIN_MS)
                }
            }
            if (now - lastMem >= MEM_EVERY_MS) {
                lastMem = now
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                val pct = (used * 100 / rt.maxMemory()).toInt()
                val nl = if (pct >= 95) 3 else if (pct >= 90) 2 else if (pct >= 80) 1 else 0
                if (nl != level) {
                    Diag.i("ذاكرة", (if (nl > level) "ارتفاع" else "انخفاض") + " استهلاك الذاكرة إلى " + pct + "% | " + memLine())
                    level = nl
                }
            }
            if (now - lastBeat >= BEAT_EVERY_MS) {
                lastBeat = now
                Diag.i("نبض", "التطبيق يعمل | " + memLine())
            }
        }
    }

    private fun mainStack(): String {
        val th = Looper.getMainLooper().thread
        val sb = StringBuilder("مكدّس الخيط الرئيسي:\n")
        for (el in th.stackTrace) sb.append("    at ").append(el.toString()).append('\n')
        return sb.toString()
    }

    private fun memLine(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1048576
        return "Java " + used + "/" + (rt.maxMemory() / 1048576) + " MB" +
            " | Native " + (Debug.getNativeHeapAllocatedSize() / 1048576) + " MB" +
            " | خيوط " + Thread.getAllStackTraces().size
    }

    private fun levelName(l: Int): String = when {
        l >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        l >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        l >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        l >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        l >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        l >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        else -> "RUNNING_MODERATE"
    }

    // ---- أخطاء console: تُسجَّل كلها بلا أي حد ----
    fun consoleError(msg: String, src: String, line: Int) {
        Diag.i("جافاسكربت-console", msg + "\nالمصدر: " + src + ":" + line)
    }
}
