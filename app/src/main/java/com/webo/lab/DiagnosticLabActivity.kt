package com.webo.lab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real interactive terminal and autonomous in-app test center. */
class DiagnosticLabActivity : Activity() {
    companion object {
        const val EXTRA_RESTART_MARKER = "webo.lab.RESTART_MARKER"
        const val EXTRA_RESTART_SCHEDULED_AT = "webo.lab.RESTART_SCHEDULED_AT"
        private const val REQUEST_FULL_SCAN = 74121
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "webo-lab-test-runner").apply { isDaemon = true } }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var server: VirtualSiteServer
    private lateinit var runner: DiagnosticTestRunner
    private lateinit var console: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var preview: WebView
    private lateinit var previewFrame: FrameLayout
    private lateinit var root: LinearLayout
    @Volatile private var currentTest = false
    @Volatile private var stopRequested = false
    @Volatile private var restartAfterFullScan = false
    private val relaunchedAfterRestart by lazy { intent.getBooleanExtra(EXTRA_RESTART_MARKER, false) }
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticEngine.initialize(applicationContext)
        val started = SystemClock.elapsedRealtime()
        server = VirtualSiteServer(applicationContext)
        runner = DiagnosticTestRunner(applicationContext, server)
        server.start()
        buildUi()
        DiagnosticEngine.record("INFO", "diagnostic_lab", "on_create", "OK", "Lab screen created", SystemClock.elapsedRealtime() - started)
        appendLine("WEBO AUTONOMOUS TESTING LAB v1.0", "#78E39D")
        appendLine("Test engine initialized. Local virtual sites are running.")
        appendLine("Type 'help' to list commands.\n")
        if (intent.getBooleanExtra(EXTRA_RESTART_MARKER, false)) {
            val scheduled = intent.getLongExtra(EXTRA_RESTART_SCHEDULED_AT, 0L)
            appendLine("[PASS] Process restart returned to WEBO Lab. Delay=${(System.currentTimeMillis() - scheduled).coerceAtLeast(0L)}ms", "#78E39D")
            DiagnosticEngine.record("INFO", "lifecycle", "process_restart", "PASS", "Lab relaunched after process termination")
        }
        command("status", false)
        if (!relaunchedAfterRestart) ui.postDelayed({ if (!isFinishing && !currentTest) command("test all", false) }, 800L)
        else appendLine("[INFO] استئناف بعد اختبار إعادة تشغيل العملية؛ لن أعيد سلسلة الاختبارات تلقائياً.")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(if (w == -3) ViewGroup.LayoutParams.MATCH_PARENT else w, if (h == -3) ViewGroup.LayoutParams.WRAP_CONTENT else h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    }

    private fun buildUi() {
        val dark = getSharedPreferences("tdl", MODE_PRIVATE).getBoolean("dark", true)
        val bg = if (dark) Color.rgb(15, 19, 24) else Color.rgb(243, 245, 248)
        val card = if (dark) Color.rgb(30, 37, 46) else Color.WHITE
        val text = if (dark) Color.rgb(236, 239, 244) else Color.rgb(27, 31, 36)
        val muted = if (dark) Color.rgb(147, 160, 177) else Color.rgb(91, 100, 112)
        val accent = if (dark) Color.rgb(91, 155, 255) else Color.rgb(11, 107, 203)
        window.statusBarColor = if (dark) Color.rgb(23, 28, 35) else Color.WHITE
        window.navigationBarColor = window.statusBarColor
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(if (dark) Color.rgb(23, 28, 35) else Color.WHITE) }
        val heading = TextView(this).apply { text = "مختبر WEBO"; textSize = 19f; setTextColor(text); setTypeface(null, android.graphics.Typeface.BOLD) }
        val sub = TextView(this).apply { this.text = "Terminal تفاعلية • فحص وتحكم آلي"; textSize = 11f; setTextColor(muted) }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(heading); addView(sub) }
        header.addView(titleColumn, lp(0, -3, 1f))
        status = TextView(this).apply { text = "● ONLINE"; textSize = 12f; setTextColor(Color.rgb(64, 190, 112)); setTypeface(null, android.graphics.Typeface.BOLD) }
        header.addView(status)
        root.addView(header)

        val scroller = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(18)) }
        val intro = TextView(this).apply {
            this.text = "المحرك يختبر وظائف فعلية داخل التطبيق، ويستخدم مواقع محلية وملفات صورة وفيديو وHLS وDASH. السجلات محفوظة محلياً ويمكن تصديرها."
            textSize = 12f; setTextColor(muted); setPadding(dp(4), dp(3), dp(4), dp(10))
        }
        body.addView(intro)

        val terminalCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(Color.rgb(7, 11, 16)) }
        val terminalHead = TextView(this).apply { this.text = "● LIVE TERMINAL     ${server.baseUrl}"; textSize = 11f; setTextColor(Color.rgb(102, 210, 144)); typeface = android.graphics.Typeface.MONOSPACE }
        terminalCard.addView(terminalHead, lp(-3, -3, 0f, 0, 0, 0, 8))
        console = TextView(this).apply { textSize = 11f; setTextColor(Color.rgb(220, 230, 236)); typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true); setText("WEBO> ") }
        terminalScroll = ScrollView(this).apply { isFillViewport = false; addView(console) }
        terminalCard.addView(terminalScroll, lp(-3, dp(300)))
        body.addView(terminalCard, lp(-3, -3, 0f, 0, 0, 0, 10))

        val commandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "اكتب أمراً مثل: test all"; textSize = 13f; setTextColor(text); setHintTextColor(muted)
            setSingleLine(true); typeface = android.graphics.Typeface.MONOSPACE; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { submitCommand(); true } else false }
        }
        commandRow.addView(input, lp(0, dp(48), 1f))
        val run = Button(this).apply { this.text = "تنفيذ"; setTextColor(Color.WHITE); setBackgroundColor(accent); setOnClickListener { submitCommand() } }
        commandRow.addView(run, lp(dp(88), dp(46), 0f, 6, 0, 0, 0))
        body.addView(commandRow, lp(-3, -3, 0f, 0, 0, 0, 8))

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(actionButton("فحص الكل", accent) { command("test all") }, lp(0, dp(44), 1f, 0, 0, 4, 0))
        quick.addView(actionButton("فحص واجهة التطبيق", Color.rgb(45, 140, 95)) { command("test full") }, lp(0, dp(44), 1f, 4, 0, 0, 0))
        body.addView(quick, lp(-3, -3, 0f, 0, 0, 0, 8))

        val quick2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick2.addView(actionButton("الموقع الوهمي", Color.rgb(65, 105, 150)) { command("site") }, lp(0, dp(42), 1f, 0, 0, 4, 0))
        quick2.addView(actionButton("التقرير", Color.rgb(85, 90, 105)) { command("report") }, lp(0, dp(42), 1f, 4, 0, 4, 0))
        quick2.addView(actionButton("إيقاف", Color.rgb(160, 74, 74)) { command("stop") }, lp(0, dp(42), 1f, 4, 0, 0, 0))
        body.addView(quick2, lp(-3, -3, 0f, 0, 0, 0, 10))

        val previewTitle = TextView(this).apply { this.text = "معاينة الموقع التجريبي"; textSize = 14f; setTextColor(text); setTypeface(null, android.graphics.Typeface.BOLD) }
        body.addView(previewTitle, lp(-3, -3, 0f, 0, 0, 0, 6))
        previewFrame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        preview = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    DiagnosticEngine.record("INFO", "virtual_browser", "page_finished", "OK", "Loaded local test page ${url.substringAfterLast('/')}")
                }
            }
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        body.addView(previewFrame, lp(-3, dp(170), 0f, 0, 0, 0, 10))
        val siteButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, path) in listOf("الرئيسية" to "/", "الصور" to "/gallery", "الفيديو" to "/video", "HLS" to "/hls", "DASH" to "/dash")) {
            siteButtons.addView(actionButton(label, if (dark) Color.rgb(52, 66, 84) else Color.rgb(226, 235, 245)) { loadVirtualPage(path) }, lp(0, dp(38), 1f, 2, 0, 2, 0))
        }
        body.addView(siteButtons, lp(-3, -3, 0f, 0, 0, 6, 5))
        val extraSites = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, path) in listOf("الأخبار" to "/site/news", "المتجر" to "/site/shop", "اجتماعي" to "/site/social", "القراءة" to "/site/reader")) {
            extraSites.addView(actionButton(label, if (dark) Color.rgb(43, 76, 69) else Color.rgb(65, 125, 94)) { loadVirtualPage(path) }, lp(0, dp(38), 1f, 2, 0, 2, 0))
        }
        body.addView(extraSites, lp(-3, -3, 0f, 0, 0, 0, 12))

        val info = TextView(this).apply {
            this.text = "الأوامر: help • test all • test full • test browser • test media • test hls • test dash • test downloads • test fullscan-download • test storage • test icons • logs • report • restart app"
            textSize = 11f; setTextColor(muted)
        }
        body.addView(info, lp(-3, -3))
        scroller.addView(body)
        root.addView(scroller, lp(-3, 0, 1f))
        setContentView(root)
        loadVirtualPage("/")
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 10f; isAllCaps = false; setOnClickListener { onClick() }
        setBackgroundColor(color); setTextColor(if (color == Color.WHITE) Color.BLACK else Color.WHITE)
        minimumWidth = 0; minimumHeight = 0; stateListAnimator = null
    }

    private fun loadVirtualPage(path: String) {
        val url = server.url(path)
        preview.loadUrl(url)
        appendLine("[SITE] $url", "#79B8FF")
    }

    private fun appendLine(message: String, color: String? = null) {
        runOnUiThread {
            val buffer = android.text.SpannableStringBuilder(console.text)
            val start = buffer.length
            buffer.append(message).append('\n')
            if (!color.isNullOrBlank() && message.isNotEmpty()) {
                try { buffer.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(color)), start, start + message.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Throwable) {}
            }
            if (buffer.length > 180_000) {
                val cut = buffer.length - 140_000
                val nl = buffer.indexOf("\n", cut)
                if (nl >= 0) buffer.delete(0, nl + 1) else buffer.delete(0, cut)
            }
            console.text = buffer
            terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun submitCommand() {
        val text = input.text.toString().trim()
        input.setText("")
        if (text.isNotEmpty()) command(text)
    }

    private fun command(raw: String, echo: Boolean = true) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        if (echo) appendLine("WEBO> $cmd", "#78E39D")
        when (cmd.lowercase()) {
            "help" -> showHelp()
            "status" -> showStatus()
            "site", "open site" -> loadVirtualPage("/")
            "site news" -> loadVirtualPage("/site/news")
            "site shop" -> loadVirtualPage("/site/shop")
            "site social" -> loadVirtualPage("/site/social")
            "site reader" -> loadVirtualPage("/site/reader")
            "site gallery" -> loadVirtualPage("/gallery")
            "site video" -> loadVirtualPage("/video")
            "site hls" -> loadVirtualPage("/hls")
            "site dash" -> loadVirtualPage("/dash")
            "test all", "testall" -> runAllTests()
            "test full", "test ui", "full scan" -> launchFullAppScan()
            "test browser" -> runActivityTest("browser")
            "test interactions", "test sites interactions" -> runActivityTest("interactions")
            "test media", "test player" -> runActivityTest("media")
            "test hls playback", "test hls player" -> runActivityTest("hls")
            "test dash playback", "test dash player" -> runActivityTest("dash")
            "test storage" -> runNamedTest("storage")
            "test database", "test db" -> runNamedTest("database")
            "test virtual", "test sites" -> runNamedTest("virtual")
            "test http", "test network" -> runNamedTest("http")
            "test hls" -> runNamedTest("hls")
            "test dash" -> runNamedTest("dash")
            "test downloads", "test download" -> runNamedTest("downloads")
            "test icons", "test icon" -> runNamedTest("icons")
            "test fullscan-download" -> runNamedTest("fullscan-download")
            "test lifecycle" -> runNamedTest("lifecycle")
            "restart app", "restart" -> restartApplication()
            "logs", "logs 50", "show logs" -> showLogs(50)
            "logs 200" -> showLogs(200)
            "report", "generate report" -> exportReport()
            "clear" -> clearLogs()
            "stop", "cancel" -> stopTests()
            else -> appendLine("[ERROR] أمر غير معروف. اكتب help.", "#FF7777")
        }
    }

    private fun showHelp() {
        appendLine("COMMANDS", "#78E39D")
        listOf(
            "test all — تشغيل الاختبارات المحلية كلها", "test full — تشغيل فحص واجهات WEBO الفعلي الموجود في التطبيق",
            "test browser — اختبار WebView عبر الموقع المحلي", "test interactions — تجربة أزرار المتجر والنشر في المواقع الوهمية", "test media — اختبار MP4 باستخدام Media3",
            "test hls playback — اختبار تشغيل HLS بمحرك Media3", "test dash playback — اختبار تشغيل DASH بمحرك Media3",
            "test storage / test database — اختبار التخزين وقاعدة التشخيص", "test virtual / test http — اختبار المواقع واستجابات HTTP",
            "test hls / test dash — اختبار القوائم والمقاطع", "test downloads — اختبار نقل ملف وحفظه والتحقق من SHA-256",
            "test icons — اختبار حفظ واسترجاع أيقونة الموقع", "test lifecycle — اختبار حفظ حالة دورة الحياة", "test fullscan-download — اختبار ملف 1 MiB المخصص لفحص الاستئناف",
            "site [news|shop|social|reader|gallery|video|hls|dash] — فتح موقع اختبار", "logs [50|200] — عرض السجل", "report — تصدير تقرير ZIP", "clear — مسح السجل", "stop — إيقاف سلسلة الاختبارات",
            "restart app — إنهاء عملية التطبيق وجدولة إعادة فتح المختبر"
        ).forEach { appendLine("  $it") }
    }

    private fun showStatus() {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val max = runtime.maxMemory() / (1024 * 1024)
        appendLine("STATUS", "#78E39D")
        appendLine("package=${packageName} | sdk=${android.os.Build.VERSION.SDK_INT} | version=${packageManager.getPackageInfo(packageName, 0).versionName}")
        appendLine("server=${if (server.isRunning()) server.baseUrl else "STOPPED"} | requests=${server.stats().values.sumOf { it.count }}")
        appendLine("events=${DiagnosticEngine.count(this)} | RAM=${used}MB/${max}MB | tests=${if (currentTest) "RUNNING" else "IDLE"}")
    }

    private fun runAllTests() {
        if (currentTest) { appendLine("[WARN] هناك اختبار يعمل بالفعل.", "#FFCC66"); return }
        currentTest = true; stopRequested = false
        val names = listOf("storage", "database", "virtual", "http", "hls", "dash", "downloads", "fullscan-download", "icons", "lifecycle")
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            appendLine("[START] Running ${names.size + 5} automated probes", "#79B8FF")
            var pass = 0; var fail = 0
            for (name in names) {
                if (stopRequested) break
                val result = runner.run(name)
                if (result.passed) pass++ else fail++
                appendResult(result)
            }
            if (!stopRequested) {
                val browser = testBrowser()
                if (browser.passed) pass++ else fail++
                appendResult(browser)
                val interactions = testSiteInteractions()
                if (interactions.passed) pass++ else fail++
                appendResult(interactions)
                for (mediaName in listOf("mp4", "hls", "dash")) {
                    if (stopRequested) break
                    val media = testMedia(mediaName)
                    if (media.passed) pass++ else fail++
                    appendResult(media)
                }
            }
            val duration = SystemClock.elapsedRealtime() - started
            DiagnosticEngine.record(if (fail == 0) "INFO" else "WARN", "test_runner", "test_all", if (fail == 0) "PASS" else "PARTIAL", "passed=$pass failed=$fail stopped=$stopRequested", duration)
            appendLine("[SUMMARY] local probes: passed=$pass failed=$fail duration=${duration}ms", if (fail == 0) "#78E39D" else "#FFCC66")
            currentTest = false
            if (!stopRequested) ui.post { restartAfterFullScan = true; launchFullAppScan() }
        }
    }

    private fun runNamedTest(name: String) {
        if (currentTest) { appendLine("[WARN] أوقف أو انتظر الاختبار الحالي أولاً.", "#FFCC66"); return }
        currentTest = true
        worker.execute { appendResult(runner.run(name)); currentTest = false }
    }

    private fun runActivityTest(name: String) {
        if (currentTest) { appendLine("[WARN] هناك اختبار يعمل بالفعل.", "#FFCC66"); return }
        currentTest = true
        worker.execute {
            val result = when (name) {
                "browser" -> testBrowser()
                "interactions" -> testSiteInteractions()
                "hls" -> testMedia("hls")
                "dash" -> testMedia("dash")
                else -> testMedia("mp4")
            }
            appendResult(result); currentTest = false
        }
    }

    private fun appendResult(result: LabTestResult) {
        val color = if (result.passed) "#78E39D" else "#FF7777"
        appendLine("[${if (result.passed) "PASS" else "FAIL"}] ${result.name} — ${result.durationMs}ms", color)
        appendLine("        ${result.detail.replace("\n", "\n        ")}")
    }

    private fun testBrowser(): LabTestResult {
        val started = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<String>("timeout")
        val correlation = DiagnosticEngine.newCorrelationId()
        runOnUiThread {
            try {
                preview.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript("document.title + '|' + (document.getElementById('ready')?.textContent || '')") { value ->
                            outcome.set(value ?: "empty")
                            latch.countDown()
                        }
                    }
                    override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) { outcome.set("WebView error ${error.errorCode}: ${error.description}"); latch.countDown() }
                    }
                }
                preview.loadUrl(server.url("/"))
            } catch (e: Throwable) { outcome.set(e.message ?: e.javaClass.simpleName); latch.countDown() }
        }
        val ok = latch.await(12, TimeUnit.SECONDS)
        val text = outcome.get()
        val passed = ok && text.contains("WEBO Virtual Test Site") && text.contains("VIRTUAL_SITE_READY")
        val duration = SystemClock.elapsedRealtime() - started
        DiagnosticEngine.record(if (passed) "INFO" else "ERROR", "test_runner", "browser", if (passed) "PASS" else "FAIL", text, duration, correlation)
        return LabTestResult("browser", passed, duration, "WebView title/DOM=${text.take(180)}")
    }

    private fun evaluateVirtualPage(path: String, script: String): Pair<Boolean, String> {
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<String>("timeout")
        runOnUiThread {
            try {
                preview.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(script) { value -> outcome.set(value ?: "null"); latch.countDown() }
                    }
                    override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) { outcome.set("WebView error ${error.errorCode}: ${error.description}"); latch.countDown() }
                    }
                }
                preview.loadUrl(server.url(path))
            } catch (e: Throwable) { outcome.set(e.message ?: e.javaClass.simpleName); latch.countDown() }
        }
        return Pair(latch.await(10, TimeUnit.SECONDS), outcome.get())
    }

    private fun testSiteInteractions(): LabTestResult {
        val started = SystemClock.elapsedRealtime()
        val correlation = DiagnosticEngine.newCorrelationId()
        return try {
            val shop = evaluateVirtualPage("/site/shop", "(function(){document.querySelector('button').click();return document.getElementById('cart').textContent})()")
            val shopOk = shop.first && shop.second.contains("ADDED")
            require(shopOk) { "Virtual shop button failed: ${shop.second}" }
            val social = evaluateVirtualPage("/site/social", "(function(){document.getElementById('post').value='WEBO_TEST_POST';document.querySelector('button').click();return document.getElementById('result').textContent})()")
            val socialOk = social.first && social.second.contains("WEBO_TEST_POST")
            require(socialOk) { "Virtual social-post interaction failed: ${social.second}" }
            val duration = SystemClock.elapsedRealtime() - started
            DiagnosticEngine.record("INFO", "test_runner", "virtual_site_interactions", "PASS", "shop=ADDED social=WEBO_TEST_POST", duration, correlation)
            LabTestResult("virtual_site_interactions", true, duration, "Shop button and social form executed inside WebView")
        } catch (e: Throwable) {
            val duration = SystemClock.elapsedRealtime() - started
            DiagnosticEngine.record("ERROR", "test_runner", "virtual_site_interactions", "FAIL", e.message ?: e.javaClass.simpleName, duration, correlation, e)
            LabTestResult("virtual_site_interactions", false, duration, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun testMedia(format: String = "mp4"): LabTestResult {
        val started = SystemClock.elapsedRealtime()
        val mediaPath = when (format) { "hls" -> "/assets/sample.m3u8"; "dash" -> "/assets/sample.mpd"; else -> "/assets/sample.mp4" }
        val latch = CountDownLatch(1)
        val detail = AtomicReference("timeout waiting for Media3")
        val passed = java.util.concurrent.atomic.AtomicBoolean(false)
        runOnUiThread {
            try {
                try { player?.release() } catch (_: Throwable) {}
                val p = ExoPlayer.Builder(this).build()
                player = p
                var readyAt = 0L
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && readyAt == 0L) {
                            readyAt = SystemClock.elapsedRealtime()
                            p.playWhenReady = true
                            ui.postDelayed({
                                if (player === p) {
                                    val pos = p.currentPosition
                                    val err = p.playerError
                                    passed.set(err == null && (pos > 0L || p.playbackState == Player.STATE_ENDED))
                                    detail.set(if (passed.get()) "Media3 reached READY and playback advanced to ${pos}ms" else "Media3 state=${p.playbackState}, position=${pos}ms, error=${err?.message}")
                                    try { p.release() } catch (_: Throwable) {}
                                    player = null
                                    latch.countDown()
                                }
                            }, 450L)
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        detail.set("Media3 error ${error.errorCodeName}: ${error.message}")
                        passed.set(false)
                        try { p.release() } catch (_: Throwable) {}
                        player = null
                        latch.countDown()
                    }
                })
                p.setMediaItem(MediaItem.fromUri(server.url(mediaPath)))
                p.prepare()
            } catch (e: Throwable) { detail.set(e.message ?: e.javaClass.simpleName); latch.countDown() }
        }
        val finished = latch.await(12, TimeUnit.SECONDS)
        val duration = SystemClock.elapsedRealtime() - started
        val ok = finished && passed.get()
        if (!finished) runOnUiThread { try { player?.release() } catch (_: Throwable) {}; player = null }
        DiagnosticEngine.record(if (ok) "INFO" else "ERROR", "test_runner", "media3_$format", if (ok) "PASS" else "FAIL", detail.get(), duration)
        return LabTestResult("media3_$format", ok, duration, "source=$mediaPath; ${detail.get()}")
    }

    private fun launchFullAppScan() {
        if (currentTest) { appendLine("[WARN] أوقف الاختبارات الحالية أولاً.", "#FFCC66"); return }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("WEBO_LAB_AUTO_SCAN", true)
            .putExtra("WEBO_LAB_TEST_BASE", server.baseUrl)
        DiagnosticEngine.record("INFO", "test_runner", "full_ui_scan_launch", "START", "Launching original in-app FullScan against the actual WEBO UI")
        startActivityForResult(intent, REQUEST_FULL_SCAN)
    }

    private fun restartApplication() {
        appendLine("[WARN] سيُنهى process الحالي وتُجدول إعادة فتح المختبر بعد ثانيتين ونصف.", "#FFCC66")
        appendLine("[INFO] هذه عملية إعادة تشغيل حقيقية وليست Activity.recreate().")
        Thread { AppRestartController.restartIntoLab(applicationContext) }.start()
    }

    private fun showLogs(limit: Int) {
        worker.execute {
            val events = DiagnosticEngine.recent(this, limit).asReversed()
            appendLine("--- LAST ${events.size} EVENTS ---", "#79B8FF")
            for (e in events) {
                val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(e.wallTime))
                val color = when (e.level) { "ERROR", "FATAL" -> "#FF7777"; "WARN" -> "#FFCC66"; else -> null }
                appendLine("$stamp ${e.level} ${e.component}.${e.operation} [${e.result}] ${e.detail}", color)
            }
        }
    }

    private fun exportReport() {
        worker.execute {
            try {
                val file = DiagnosticEngine.exportReport(this)
                val saved = FileUtil.saveToDownloads(this, file.inputStream(), file.name, "application/zip")
                appendLine("[REPORT] Saved to Downloads: $saved", "#78E39D")
            } catch (e: Throwable) { appendLine("[ERROR] Report export failed: ${e.message}", "#FF7777") }
        }
    }

    private fun clearLogs() {
        DiagnosticEngine.clear(this)
        console.text = "WEBO> logs cleared\n"
        appendLine("[OK] Diagnostic event history cleared.", "#78E39D")
    }

    private fun stopTests() {
        stopRequested = true
        appendLine("[WARN] Stop requested; current blocking probe will finish or time out.", "#FFCC66")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FULL_SCAN) {
            DiagnosticEngine.record(if (resultCode == RESULT_OK) "INFO" else "WARN", "test_runner", "full_ui_scan_return", if (resultCode == RESULT_OK) "PASS" else "UNKNOWN", "Returned from actual UI scan")
            appendLine("[FULL SCAN] عاد الفحص من واجهة التطبيق. استخدم logs لعرض جميع الأحداث.", if (resultCode == RESULT_OK) "#78E39D" else "#FFCC66")
            val file = File(getExternalFilesDir(null) ?: filesDir, "scan_live.txt")
            if (file.isFile) {
                val tail = file.readLines().takeLast(12)
                tail.forEach { appendLine(it) }
            }
            if (restartAfterFullScan && resultCode == RESULT_OK) {
                restartAfterFullScan = false
                appendLine("[LIFECYCLE] اكتمال فحص الواجهة؛ سأختبر الآن إنهاء العملية وإعادة تشغيل المختبر.", "#79B8FF")
                ui.postDelayed({ restartApplication() }, 900L)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DiagnosticEngine.record("INFO", "lifecycle", "lab_on_resume", "OK")
    }

    override fun onPause() {
        DiagnosticEngine.record("INFO", "lifecycle", "lab_on_pause", "OK")
        super.onPause()
    }

    override fun onDestroy() {
        try { player?.release() } catch (_: Throwable) {}
        player = null
        try { preview.stopLoading(); preview.destroy() } catch (_: Throwable) {}
        try { server.stop() } catch (_: Throwable) {}
        try { worker.shutdownNow() } catch (_: Throwable) {}
        DiagnosticEngine.record("INFO", "lifecycle", "lab_on_destroy", "OK", "Lab activity destroyed")
        super.onDestroy()
    }
}
