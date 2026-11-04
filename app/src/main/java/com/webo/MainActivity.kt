package com.webo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.ValueCallback
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * MainActivity — نقطة الدخول والحالة فقط.
 * الدوال نُقلت تلقائياً إلى ملفات متخصصة (دوال امتداد على MainActivity):
 *   Alerts.kt          سجل التنبيهات والأخطاء
 *   BrowserChrome.kt   الشريط العلوي والقائمة الجانبية وتبديل الصفحات
 *   ButtonSizes.kt     أحجام الأزرار والأيقونات
 *   Candidates.kt      اكتشاف الروابط وتحليلها وتحويلها إلى مهام
 *   Classification.kt  تصنيف الفيديوهات يدوياً والمستبعدات
 *   DashDownload.kt    تحليل وتنزيل DASH وأدوات moov
 *   DownloadsUi.kt     شاشة التنزيلات وصفوفها وعدّاداتها
 *   FilesIo.kt         حفظ الملفات وفتح التخزين والعارض
 *   FindInPage.kt      البحث داخل الصفحة
 *   HistoryBookmarks.kt سجل التصفح والمفضلة
 *   HlsDownload.kt     تحليل وتنزيل HLS ودمج المقاطع
 *   JsInjection.kt     حقن سكربتات الالتقاط في الصفحات
 *   JsScripts.kt       نصوص JavaScript المحقونة داخل الصفحات
 *   LiveAndPlayback.kt البث المباشر والمعاينة والتشغيل
 *   Models.kt          الكلاسات المساعدة (بيانات الصفوف والمرشحات والروابط)
 *   Network.kt         طلبات الشبكة والفحص والتنزيل المباشر
 *   PageMedia.kt       أزرار الفيديو والصور داخل الصفحة ومعرض الصور
 *   ResponseCache.kt   مخزن الصور والاستجابات الخاص
 *   SettingsPages.kt   صفحات الإعدادات الفرعية
 *   SpeedAndLinks.kt   التحكم بالسرعة وفحص الروابط والدفعات
 *   StartPage.kt       الشاشة الرئيسية: البحث والمواقع والمفضلة والاختصارات
 *   TabSwitcher.kt     شاشة مبدّل التبويبات (البطاقات واللمس والتحديد)
 *   Tabs.kt            إدارة التبويبات والصفحات المغلقة واسترجاعها
 *   TaskPersistence.kt حفظ مهام التنزيل واستعادتها
 *   Thumbnails.kt      الصور المصغّرة للفيديو
 *   ToolsUi.kt         شاشة الأدوات والإجراءات السريعة
 *   UiKit.kt           أدوات الواجهة المشتركة: الأبعاد والألوان والأزرار والصفوف وتنسيق الأحجام
 *   UserScripts.kt     سكربتات JavaScript للمستخدم
 *   WebClient.kt       إعداد WebView وWebViewClient/ChromeClient والصلاحيات وملء الشاشة
 */
class MainActivity : Activity() {

    internal val HOME = "https://www.google.com"
    internal val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    internal val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    internal val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    internal val ui = Handler(Looper.getMainLooper())
    internal val found = LinkedHashSet<String>()
    internal val queue = DiagPool("queue", 2, 2, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>())
    @Volatile internal var limitKb = 0
    internal val thrLock = Any()
    internal var thrNext = 0L
    internal val pages = java.util.concurrent.ConcurrentHashMap<WebView, String>()
    internal val tabs = ArrayList<Tab>()
    internal var cur: Tab? = null
    internal var page = 0
    internal var fileCb: ValueCallback<Array<Uri>>? = null
    internal var geoCb: GeolocationPermissions.Callback? = null
    internal var geoOrigin = ""
    internal var permReq: PermissionRequest? = null
    internal var fullView: View? = null
    internal var fullCb: WebChromeClient.CustomViewCallback? = null
    internal var fullFlags = 0
    internal val foundName = HashMap<String, String>()
    internal val foundMime = HashMap<String, String>()
    internal val sizeIds = LinkedHashMap<String, Pair<String, String>>()
    internal val noIconIds = hashSetOf("txt_pill")
    internal lateinit var barBack: ImageView
    internal lateinit var barFwd: ImageView
    internal lateinit var barTabs: FrameLayout
    internal lateinit var barMenu: ImageView
    internal lateinit var barPlus: ImageView
    internal lateinit var barDl: ImageView
    internal lateinit var barBatch: ImageView
    internal lateinit var barHome: ImageView
    internal lateinit var navOverlay: FrameLayout
    internal lateinit var navPopup: LinearLayout

    @Volatile internal var adBlock = true
    @Volatile internal var curUa = ""

    internal var dark = true
    internal var cBg = 0
    internal var cBar = 0
    internal var cCard = 0
    internal var cText = 0
    internal var cMuted = 0
    internal var cLine = 0
    internal var cAccent = 0
    internal var cAccentSoft = 0

    internal lateinit var root: LinearLayout
    internal lateinit var pageHost: FrameLayout
    internal val pagesList = ArrayList<View>()
    internal val navItems = ArrayList<Pair<ImageView, TextView>>()
    internal lateinit var container: FrameLayout
    internal lateinit var tabsRow: LinearLayout
    internal lateinit var plusChip: TextView
    internal lateinit var urlBox: EditText
    internal lateinit var loadBar: ProgressBar
    internal lateinit var pct: TextView
    internal lateinit var reloadBtn: ImageView
    internal lateinit var list: LinearLayout
    internal lateinit var emptyTv: TextView
    internal lateinit var manualBox: EditText

    internal val alerts = ArrayList<AlertItem>()
    internal lateinit var alertList: LinearLayout
    internal lateinit var alertEmpty: TextView
    internal lateinit var tabBtn: TextView
    internal lateinit var activeBox: LinearLayout
    internal lateinit var activeTitle: TextView
    internal lateinit var badge: TextView
    internal lateinit var dlActions: LinearLayout
    internal lateinit var listTitle: TextView
    internal lateinit var dlScroll: ScrollView
    // 0 = شاشة التحميلات، 1 = شاشة عداد ملتقط الروابط (الملتقطة فقط)
    internal var dlView = 0
    internal var badgeSeen = true
    internal var lastFound = 0
    internal lateinit var badgeBox: LinearLayout
    internal lateinit var badgeNum: TextView
    internal lateinit var tabDl: TextView

    // بدء تحميل كل رابط ملتقط وهيدرزه (لأزرار الفيديو داخل الصفحة)
    internal val foundStart = HashMap<String, () -> Unit>()
    internal val foundHdr = HashMap<String, Map<String, String>>()
    internal val imgPool = DiagPool("imgPool", 2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>())

    internal val segUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val hlsSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val previewPool = DiagPool("previewPool", 1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>())
    internal val cands = LinkedHashMap<String, Cand>()
    internal val considered = HashSet<String>()
    internal val capLog = java.util.ArrayList<String>()

    internal val excluded = java.util.concurrent.ConcurrentHashMap<String, Excl>()

    internal val subUrls = HashSet<String>()
    internal val analyzer = DiagPool("analyzer", 16, 16, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>()).also { it.allowCoreThreadTimeOut(true) }
    internal lateinit var startView: ScrollView
    internal lateinit var startSites: LinearLayout
    internal lateinit var startBm: LinearLayout
    internal lateinit var startBox: EditText
    internal lateinit var findBar: LinearLayout
    internal lateinit var findBox: EditText
    internal lateinit var findCount: TextView

    internal val closed = ArrayList<Closed>()

    internal val adHosts = listOf(
        "applovin", "doubleclick", "googlesyndication", "googleadservices", "adservice.google",
        "admob", "adnxs", "pangle", "unityads", "mopub", "inmobi", "taboola", "outbrain",
        "criteo", "adcolony", "vungle", "chartboost", "ironsrc", "adjust.com", "appsflyer"
    )

    inner class Tab(var web: WebView) {
        var pendingUrl: String? = null
        var lastCrash = 0L
        lateinit var chip: LinearLayout
        lateinit var label: TextView
        lateinit var close: ImageView
        var progress = 0
        var loading = false
        var title = ""
        var restoring = false
        var injected = false
        var lastUrl = ""
        var start = false
        var startBack = false
        var popup = false
        var scanning = false
        var icon: android.graphics.Bitmap? = null
        var thumb: android.graphics.Bitmap? = null
    }

    // ---------- دورة الحياة ----------

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Diag.init(this)
        Diag.tabProvider = {
            val c = cur
            if (c == null) {
                "0/" + tabs.size
            } else {
                (tabs.indexOf(c) + 1).toString() + "/" + tabs.size + " " +
                    (if (c.lastUrl.isNotEmpty()) c.lastUrl else (c.pendingUrl ?: ""))
            }
        }
        DiagWatch.start(this)
        applyPools()
        try { File(cacheDir, "live_preview").deleteRecursively() } catch (e: Throwable) { Diag.swallow("MainActivity.kt:219 MainActivity.onCreate", e);}
        PlayerCore.liveHook = { key, lastSig -> liveSnapshot(key, lastSig) }
        val prevH = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try {
                val line = listOf(
                    System.currentTimeMillis().toString(), "انهيار",
                    "توقف التطبيق بسبب خطأ غير متوقع (" + th.name + ")",
                    android.util.Log.getStackTraceString(e).lines().take(14).joinToString("␤")
                ).joinToString("\t")
                File(filesDir, "alerts.tsv").appendText(line + "\n")
            } catch (x: Throwable) { Diag.swallow("MainActivity.kt:230 MainActivity.onCreate", x);
            }
            prevH?.uncaughtException(th, e)
        }
        dark = prefs().getBoolean("dark", true)
        adBlock = prefs().getBoolean("adblock", true)
        limitKb = prefs().getInt("limitkb", 0)
        diagThread("MainActivity:237") { rcTrim() }.start()
        applyParallel()
        regStatic()
        initColors()
        window.statusBarColor = cBar
        window.navigationBarColor = cBar
        if (!dark) {
            var f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        pageHost = FrameLayout(this)
        pagesList.add(buildBrowser())
        pagesList.add(buildDownloads())
        pagesList.add(buildTools())
        pagesList.add(buildSettings())
        pagesList.add(buildAlerts())
        for (p in pagesList) pageHost.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(pageHost, lp(MATCH, 0, 1f))
        val frame = FrameLayout(this)
        frame.layoutDirection = View.LAYOUT_DIRECTION_LTR
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        navOverlay = FrameLayout(this)
        navOverlay.visibility = View.GONE
        val scrim = View(this)
        scrim.setBackgroundColor(Color.parseColor("#66000000"))
        scrim.setOnClickListener { closeNavMenu() }
        navOverlay.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        navOverlay.addView(buildNav(), navPopupLp())
        frame.addView(navOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(buildSwitcher(), FrameLayout.LayoutParams(MATCH, MATCH))
        val snLp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        snLp.setMargins(dp(12), 0, dp(12), dp(14))
        frame.addView(swSnack, snLp)
        setContentView(frame)
        loadAlerts()
        loadClosed()
        cleanOldWork()
        restoreTasks()

        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        showPage(0)
        var saved = b?.getStringArrayList("urls")
        var st = b?.getBooleanArray("starts")
        var sb = b?.getBooleanArray("startbacks")
        var ci = b?.getInt("cur", 0) ?: 0
        if (saved == null || saved.isEmpty()) {
            // لا توجد حالة مؤقتة (التطبيق أُغلق بالكامل): نستعيد التبويبات المحفوظة في ملف دائم
            val raw = prefs().getString("tabs_urls", null)
            if (!raw.isNullOrEmpty()) {
                saved = ArrayList(raw.split("\n"))
                val sr = (prefs().getString("tabs_starts", "") ?: "").split(",")
                val br = (prefs().getString("tabs_backs", "") ?: "").split(",")
                st = BooleanArray(saved.size) { it < sr.size && sr[it] == "1" }
                sb = BooleanArray(saved.size) { it < br.size && br[it] == "1" }
                ci = prefs().getInt("tabs_cur", 0)
            }
        }
        if (saved != null && saved.isNotEmpty()) {
            restoringTabs = true
            for (u in saved) newTab(u)
            // استعادة حالة واجهة البدء لكل تبويب (مثلاً تبويب فُتحت فوقه واجهة التطبيق)
            for (i in tabs.indices) {
                if (st != null && i < st.size) tabs[i].start = st[i]
                if (sb != null && i < sb.size) tabs[i].startBack = sb[i]
            }
            restoringTabs = false
            if (ci in tabs.indices) switchTo(tabs[ci])
            // العودة لنفس الصفحة التي كان المستخدم فيها (إعدادات/تحميلات/أدوات) بدل المتصفح
            val pg = b?.getInt("page", 0) ?: 0
            if (pg in pagesList.indices) showPage(pg)
            val ss = b?.getString("setsub") ?: ""
            if (ss.isNotEmpty()) openSettingsSub(ss)
        } else {
            newHomeTab()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        val urls = ArrayList<String>()
        for (t in tabs) urls.add(t.web.url ?: "")
        out.putStringArrayList("urls", urls)
        out.putInt("cur", tabs.indexOf(cur))
        out.putInt("page", page)
        out.putString("setsub", setSub)
        out.putBooleanArray("starts", BooleanArray(tabs.size) { tabs[it].start })
        out.putBooleanArray("startbacks", BooleanArray(tabs.size) { tabs[it].startBack })
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush() // يحفظ تسجيل الدخول
        saveTabsPrefs()
    }

    override fun onStop() {
        super.onStop()
        saveTabsPrefs()
    }

    internal var restoringTabs = false

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            for (t in tabs) if (t !== cur) try { t.web.clearCache(false) } catch (e: Throwable) { Diag.swallow("MainActivity.kt:354 MainActivity.onTrimMemory", e);}
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // تحرير ذاكرة التبويبات المخفية عند ضغط الذاكرة
        for (t in tabs) if (t !== cur) try { t.web.freeMemory() } catch (e: Throwable) { Diag.swallow("MainActivity.kt:361 MainActivity.onLowMemory", e);}
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        for (c in swClosed) try { c.t.web.destroy() } catch (e: Throwable) { Diag.swallow("MainActivity.kt:366 MainActivity.onDestroy", e);}
        swClosed.clear()
        for (t in ArrayList(tabs)) {
            try { container.removeView(t.web) } catch (e: Throwable) { Diag.swallow("MainActivity.kt:369 MainActivity.onDestroy", e);}
            try { t.web.destroy() } catch (e: Throwable) { Diag.swallow("MainActivity.kt:370 MainActivity.onDestroy", e);}
        }
        pages.clear()
        try { prober.shutdownNow() } catch (e: Throwable) { Diag.swallow("MainActivity.kt:373 MainActivity.onDestroy", e);}
        super.onDestroy()
    }

    internal var lastBackAt = 0L

    override fun onBackPressed() {
        if (swMenu.visibility == View.VISIBLE) {
            swMenu.visibility = View.GONE
            return
        }
        if (swShown) {
            if (swSelMode) swEndSel() else closeSw()
            return
        }
        if (navOverlay.visibility == View.VISIBLE) {
            closeNavMenu()
            return
        }
        if (fullView != null) {
            hideFull()
            return
        }
        if (page == 3 && setSub.isNotEmpty()) {
            openSettingsSub("")
            return
        }
        if (page != 0) {
            showPage(0)
            return
        }
        if (findBar.visibility == View.VISIBLE) {
            closeFind()
            return
        }
        val t = cur
        if (t != null && backInTab(t)) return
        // نحن في واجهة التطبيق: ضغطتان للخروج حتى لا يخرج التطبيق بالخطأ
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) {
            super.onBackPressed()
        } else {
            lastBackAt = now
            toast("اضغط رجوع مرة أخرى للخروج")
        }
    }

    // ---------- الإعدادات: قائمة رئيسية + صفحات داخلية (كل إعداد في مكان واحد فقط) ----------

    internal lateinit var settingsHost: FrameLayout
    internal var setSub = ""

    internal val favTried = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    internal val favUa = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    // يجلب أكبر أيقونة حقيقية متاحة للموقع، ثم favicon.ico كاحتياط، مرة واحدة لكل موقع
    // طابور واحد: الشعارات تُجلب واحداً بعد الآخر
    internal val favExec = diagSingle("favExec")

    // أيقونات البطاقات الظاهرة حالياً في الرئيسية، لتحديث الواصل منها فقط
    internal val favViews = ArrayList<Pair<String, java.lang.ref.WeakReference<ImageView>>>()

    // ---------- سجل التصفح ----------

    internal val histLock = Any()
    @Volatile internal var lastHist = ""

    internal lateinit var swRoot: FrameLayout
    internal lateinit var swCount: TextView
    internal lateinit var swQuery: EditText
    internal lateinit var swHint: TextView
    internal lateinit var swScroll: ScrollView
    internal lateinit var swList: LinearLayout
    internal lateinit var swSelBar: LinearLayout
    internal lateinit var swDel: TextView
    internal lateinit var swAll: TextView
    internal lateinit var swMenu: LinearLayout
    internal lateinit var swMenuBtn: TextView
    internal lateinit var swSnack: LinearLayout
    internal lateinit var swSnackTxt: TextView
    internal lateinit var swSnackBtn: TextView
    internal var swShown = false
    internal var swSelMode = false
    internal var swDragging = false
    internal val swSel = HashSet<Tab>()
    internal val swClosed = ArrayList<SwClosed>()
    internal val swCells = ArrayList<Pair<View, Int>>()
    internal val swTg = HashMap<Int, View>()
    internal val swHideSnack = Runnable {
        swSnack.visibility = View.GONE
        swFinalize()
    }
    internal val favCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    internal val SW_HINT = "اضغط مطولاً على أي بطاقة ثم اسحبها فوق بطاقة أخرى لتغيير الترتيب"

    // ---------- مخزن الصور الخاص (يحفظ كل صورة وأيقونة على الجهاز بغض النظر عن منع الموقع) ----------

    internal val rcDir: File by lazy { File(cacheDir, "rc").also { it.mkdirs() } }
    internal val rcExt = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif", ".bmp", ".woff", ".woff2", ".ttf")
    internal val rcWrites = AtomicInteger(0)

    // ---------- أزرار الفيديو والصور داخل الصفحة ----------

    inner class Bridge {
        // خطأ كان يُبتلع بصمت داخل catch في سكربتات الصفحات
        @JavascriptInterface
        fun dg(msg: String) {
            Diag.js(msg)
        }

        @JavascriptInterface
        fun video(src: String, page: String) {
            ui.post { handleVideo(src, page) }
        }

        @JavascriptInterface
        fun copyVideo(src: String) {
            ui.post {
                val u = if (src.startsWith("http")) src else bestFor(cur)?.url
                if (u == null) toast("شغّل الفيديو لحظة ثم اضغط مرة أخرى") else copyText(u)
            }
        }

        // رابط فيديو اكتشفته الصفحة بنفسها (عنصر video أو fetch/XHR)
        @JavascriptInterface
        fun found(src: String, page: String) {
            if (!src.startsWith("http")) return
            ui.post { consider(src, hdrFor(src, page), cur?.web) }
        }

        @JavascriptInterface
        fun scanDone(n: Int, steps: Int) {
            ui.post {
                for (t in tabs) t.scanning = false
                capNote("فحص", cur?.lastUrl ?: "", "انتهى فحص الصفحة: " + n + " رابط فيديو محتمل بعد " + steps + " خطوة تمرير")
                w2rescan()
                toast("انتهى فحص الصفحة: وُجد " + n + " رابط محتمل، راجع تبويب التنزيلات")
                updateCounts()
            }
        }

        @JavascriptInterface
        fun image(src: String, page: String, act: String) {
            ui.post { handleImage(src, page, act) }
        }

        @JavascriptInterface
        fun err(msg: String) {
            logErr("فيديو", msg.take(250))
        }

        // ملفات blob التي تنشئها الصفحة (تصل على أجزاء)
        @JavascriptInterface
        fun blobPart(id: String, b64: String) {
            try {
                val f = File(cacheDir, "blob_" + id.filter { it.isLetterOrDigit() })
                FileOutputStream(f, true).use { it.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)) }
            } catch (e: Throwable) { Diag.swallow("MainActivity.kt:531 MainActivity.Bridge.blobPart", e);
                logErr("تحميل", "تعذر استقبال ملف الصفحة: " + (e.message ?: ""))
            }
        }

        @JavascriptInterface
        fun blobEnd(id: String, name: String, mime: String) {
            val f = File(cacheDir, "blob_" + id.filter { it.isLetterOrDigit() })
            Diag.i("تحميل", "blob: اكتمل وصول الأجزاء، بدء الحفظ | id=" + id + " | اسم=" + name + " | mime=" + mime + " | حجم=" + (if (f.exists()) f.length() else -1L))
            diagThread("MainActivity:539") {
                try {
                    if (!f.exists()) throw Exception("لم يصل أي محتوى")
                    val nm = name.ifBlank { "ملف_" + System.currentTimeMillis() }
                    FileUtil.saveToDownloads(this@MainActivity, f.inputStream(), nm, if (mime.isNotBlank()) mime else FileUtil.mimeOf(nm))
                    Diag.i("تحميل", "blob: انتهى الحفظ بنجاح | id=" + id + " | اسم=" + nm)
                    ui.post { toast("تم حفظ " + nm + " في مجلد التحميلات") }
                } catch (e: Throwable) { Diag.swallow("MainActivity.kt:545 MainActivity.Bridge.blobEnd", e);
                    Diag.i("تحميل", "blob: انتهى الحفظ بالفشل | id=" + id)
                    logErr("تحميل", "تعذر حفظ ملف الصفحة: " + (e.message ?: ""))
                    ui.post { toast("تعذر حفظ الملف") }
                } finally {
                    f.delete()
                }
            }.start()
        }
    }

    internal val galPool = diagFixed("galPool", 4)

    // ---------- حفظ مهام التنزيل واستعادتها (كل مهمة مستقلة) ----------

    internal val taskIo = diagSingle("taskIo")

    // صورة كل فيديو تُحفظ مرة واحدة بعد جلبها، فإذا عاد الفيديو للالتقاط (بعد التنقل بين الصفحات) تُستخدم المحفوظة ولا تُطلب من جديد
    internal val videoThumbs = object : LinkedHashMap<String, android.graphics.Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean = size > 64
    }

    // صورة المعاينة التي يوفرها الموقع (poster أو og:image): تُنزَّل مرة واحدة فقط لكل رابط وتُستخدم لكل الفيديوهات التي تشترك فيها، وتتوقف إذا حُذفت كل الفيديوهات التي تنتظرها
    internal val posterFail = HashSet<String>()
    internal val posterWait = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Row>>()
    internal val posterBmp = object : LinkedHashMap<String, android.graphics.Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean = size > 16
    }

    internal val liveRows = java.util.concurrent.ConcurrentHashMap<String, Row>()

    internal val liveB = java.util.concurrent.ConcurrentHashMap<String, LiveB>()

    internal val dashSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ---------- الشبكة ----------

    internal val skipHeaders = setOf(
        "range", "accept-encoding", "host", "connection", "content-length", "if-none-match", "if-modified-since"
    )

    internal val probed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val prober = DiagPool("prober", 8, 8, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>()
    ).also { it.allowCoreThreadTimeOut(true) }
    internal val staticExt = listOf(
        ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".woff", ".woff2", ".ttf",
        ".ico", ".html", ".map"
    )
    internal val audioExt = listOf(".mp3", ".aac", ".m4a", ".ogg", ".oga", ".opus", ".wav", ".flac", ".weba")
    internal val segExt = listOf(".ts", ".m4s")
    internal val videoExt = listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".flv", ".3gp", ".avi", ".wmv", ".ogv")

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 78) {
            val ok = res.isNotEmpty() && res.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            try { geoCb?.invoke(geoOrigin, ok, false) } catch (e: Throwable) { Diag.swallow("MainActivity.kt:602 MainActivity.onRequestPermissionsResult", e);}
            geoCb = null
        } else if (code == 80) {
            val r = permReq
            permReq = null
            if (r != null) {
                val ok = res.isNotEmpty() && res.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
                try { if (ok) r.grant(r.resources) else r.deny() } catch (e: Throwable) { Diag.swallow("MainActivity.kt:609 MainActivity.onRequestPermissionsResult", e);}
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 77) {
            val cb = fileCb
            fileCb = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data))
            return
        }
        if (req == 79) {
            val u = data?.data
            if (res == RESULT_OK && u != null) {
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Throwable) { Diag.swallow("MainActivity.kt:625 MainActivity.onActivityResult", e);}
                openViewer(u)
            }
            return
        }
        super.onActivityResult(req, res, data)
    }

}
