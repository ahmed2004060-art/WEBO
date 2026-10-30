package com.webo

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import android.webkit.URLUtil
import android.widget.SeekBar
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Track
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.googlecode.mp4parser.authoring.tracks.AACTrackImpl
import com.googlecode.mp4parser.authoring.tracks.AppendTrack
import com.googlecode.mp4parser.authoring.tracks.h264.H264TrackImpl
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    private val HOME = "https://www.google.com"
    private val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ui = Handler(Looper.getMainLooper())
    private val found = LinkedHashSet<String>()
    private val queue = java.util.concurrent.ThreadPoolExecutor(2, 2, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>())
    @Volatile private var limitKb = 0
    private val thrLock = Any()
    private var thrNext = 0L
    private val pages = java.util.concurrent.ConcurrentHashMap<WebView, String>()
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private var page = 0
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var geoCb: GeolocationPermissions.Callback? = null
    private var geoOrigin = ""
    private var permReq: PermissionRequest? = null
    private var fullView: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var fullFlags = 0
    private val foundName = HashMap<String, String>()
    private val foundMime = HashMap<String, String>()
    private val sizeIds = LinkedHashMap<String, Pair<String, String>>()
    private val noIconIds = hashSetOf("txt_pill")
    private lateinit var barBack: ImageView
    private lateinit var barFwd: ImageView
    private lateinit var barTabs: FrameLayout
    private lateinit var barMenu: ImageView
    private lateinit var barPlus: ImageView
    private lateinit var barDl: ImageView
    private lateinit var barBatch: ImageView
    private lateinit var barHome: ImageView
    private lateinit var navOverlay: FrameLayout
    private lateinit var navPopup: LinearLayout

    @Volatile private var adBlock = true
    @Volatile private var curUa = ""

    private var dark = true
    private var cBg = 0
    private var cBar = 0
    private var cCard = 0
    private var cText = 0
    private var cMuted = 0
    private var cLine = 0
    private var cAccent = 0
    private var cAccentSoft = 0

    private lateinit var root: LinearLayout
    private lateinit var pageHost: FrameLayout
    private val pagesList = ArrayList<View>()
    private val navItems = ArrayList<Pair<ImageView, TextView>>()
    private lateinit var container: FrameLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var plusChip: TextView
    private lateinit var urlBox: EditText
    private lateinit var loadBar: ProgressBar
    private lateinit var pct: TextView
    private lateinit var reloadBtn: ImageView
    private lateinit var list: LinearLayout
    private lateinit var emptyTv: TextView
    private lateinit var manualBox: EditText

    // تنبيهات التطبيق
    private class AlertItem(val time: Long, val kind: String, val msg: String, val detail: String)
    private val alerts = ArrayList<AlertItem>()
    private lateinit var alertList: LinearLayout
    private lateinit var alertEmpty: TextView
    private lateinit var tabBtn: TextView
    private lateinit var activeBox: LinearLayout
    private lateinit var activeTitle: TextView
    private lateinit var badge: TextView
    private lateinit var dlActions: LinearLayout
    private lateinit var listTitle: TextView
    private lateinit var dlScroll: ScrollView
    // 0 = شاشة التحميلات، 1 = شاشة عداد ملتقط الروابط (الملتقطة فقط)
    private var dlView = 0
    private var badgeSeen = true
    private var lastFound = 0
    private lateinit var badgeBox: LinearLayout
    private lateinit var badgeNum: TextView
    private lateinit var tabDl: TextView

    // بدء تحميل كل رابط ملتقط وهيدرزه (لأزرار الفيديو داخل الصفحة)
    private val foundStart = HashMap<String, () -> Unit>()
    private val foundHdr = HashMap<String, Map<String, String>>()
    private val imgPool = Executors.newFixedThreadPool(2)
    private class Variant(val label: String, val url: String, val bw: Long = 0L, val res: String = "")

    // معلومات الفيديو الملتقط: مسجل (VOD) أم بث مباشر، المدة، الحجم، أعلى جودة، وروابط الجودات الفرعية
    private data class Meta(val vod: Boolean, val dur: Double, val size: Long, val sizeEst: Boolean, val res: String, val kbps: Long, val subs: List<String>, val conf: Int = 100, val why: String = "")
    private class Cand(val url: String, val owner: Tab?, var meta: Meta?, var title: String, val rw: Row, val view: View, val info: TextView, val hostTxt: String, val pageTitle: String)
    private val segUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val hlsSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val previewPool = Executors.newSingleThreadExecutor()
    private val cands = LinkedHashMap<String, Cand>()
    private val considered = HashSet<String>()
    private val capLog = java.util.ArrayList<String>()

    // ---- تصنيف مسجل/مباشر اليدوي ----
    private class Excl(val u: String, val h: Map<String, String>, val meta: Meta?, val owner: Tab?, val title: String)
    private val excluded = java.util.concurrent.ConcurrentHashMap<String, Excl>()

    private fun ovrKey(u: String): String { val x = Uri.parse(u); return (x.host ?: "") + (x.path ?: "") }

    // true = مسجل، false = بث مباشر، null = لا تصنيف يدوي (الأولوية للرابط ثم للموقع)
    private fun getOvr(u: String): Boolean? {
        val set = prefs().getStringSet("ovr", emptySet()) ?: emptySet()
        val k = ovrKey(u)
        val host = Uri.parse(u).host ?: ""
        if (set.contains("vod|" + k)) return true
        if (set.contains("live|" + k)) return false
        if (set.contains("hvod|" + host)) return true
        if (set.contains("hlive|" + host)) return false
        return null
    }

    // scope: "url" لهذا الرابط، "host" لكل روابط الموقع. vod = null يزيل التصنيف اليدوي
    private fun setOvr(u: String, scope: String, vod: Boolean?) {
        val set = HashSet(prefs().getStringSet("ovr", emptySet()) ?: emptySet())
        val k = ovrKey(u)
        val host = Uri.parse(u).host ?: ""
        if (vod == null) {
            set.remove("vod|" + k); set.remove("live|" + k); set.remove("hvod|" + host); set.remove("hlive|" + host)
        } else if (scope == "host") {
            set.remove("hvod|" + host); set.remove("hlive|" + host)
            set.add((if (vod) "hvod|" else "hlive|") + host)
        } else {
            set.remove("vod|" + k); set.remove("live|" + k)
            set.add((if (vod) "vod|" else "live|") + k)
        }
        prefs().edit().putStringSet("ovr", set).apply()
    }

    private fun applyVerdict(c: Cand, vod: Boolean) {
        val m = c.meta ?: Meta(vod, -1.0, -1L, false, "", 0L, emptyList())
        c.meta = m.copy(vod = vod, conf = 100, why = "يدوي")
        refreshInfo(c)
        if (!vod && !prefs().getBoolean("showlive", true)) {
            excluded[c.url] = Excl(c.url, foundHdr[c.url] ?: emptyMap(), c.meta, c.owner, c.pageTitle)
            dropCand(c)
            updateCounts()
        }
    }

    private fun classifyDialog(c: Cand) {
        val labels = arrayOf("مسجل (هذا الرابط)", "بث مباشر (هذا الرابط)", "كل روابط هذا الموقع مسجلة", "كل روابط هذا الموقع بث مباشر", "إزالة التصنيف اليدوي")
        val icons = intArrayOf(R.drawable.ic_video, R.drawable.ic_live, R.drawable.ic_web, R.drawable.ic_web, R.drawable.ic_refresh)
        fun apply(i: Int) {
            val u = c.url
            when (i) {
                0 -> { setOvr(u, "url", true); applyVerdict(c, true) }
                1 -> { setOvr(u, "url", false); applyVerdict(c, false) }
                2 -> {
                    setOvr(u, "host", true)
                    for (x in ArrayList(cands.values)) if (x.hostTxt == c.hostTxt) applyVerdict(x, true)
                }
                3 -> {
                    setOvr(u, "host", false)
                    for (x in ArrayList(cands.values)) if (x.hostTxt == c.hostTxt) applyVerdict(x, false)
                }
                else -> { setOvr(u, "url", null); toast("أُزيل التصنيف اليدوي، يُحكم على الرابط تلقائياً عند التقاطه ثانية") }
            }
            capNote("يدوي", u, "صُنّف: " + labels[i])
        }
        var dlg: AlertDialog? = null
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(6), dp(12), dp(6))
        for (i in labels.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(12), dp(8), dp(12))
            val ic = ImageView(this)
            ic.setImageResource(icons[i])
            ic.setColorFilter(cAccent)
            row.addView(ic, lp(dp(24), dp(24), 0f, 0, 0, 12, 0))
            row.addView(tv(labels[i], 14f, cText, true), lp(0, WRAP, 1f))
            row.setOnClickListener { dlg?.dismiss(); apply(i) }
            col.addView(row, lp(MATCH, WRAP))
        }
        dlg = StyledBuilder(this).setTitle("تصنيف الفيديو").setView(col).setNegativeButton("إغلاق", null).show()
    }

    // الروابط التي استبعدها التطبيق لأنها بدت بثاً مباشراً: تظهر هنا لتصحح الحكم يدوياً
    private fun excludedDialog() {
        val items = ArrayList(excluded.values).reversed()
        if (items.isEmpty()) { toast("لا توجد روابط مستبعدة"); return }
        val exHolder = arrayOfNulls<AlertDialog>(1)
        fun pick(i: Int) {
            val e = items[i]
            val acts = arrayOf("اعتباره مسجلاً وإضافته", "اعتبار كل روابط هذا الموقع مسجلة", "نسخ الرابط")
            StyledBuilder(this).setTitle("ماذا تريد؟").setItems(acts) { _, k ->
                if (k == 2) { copyText(e.u); return@setItems }
                setOvr(e.u, if (k == 1) "host" else "url", true)
                val targets = if (k == 1) items.filter { (Uri.parse(it.u).host ?: "") == (Uri.parse(e.u).host ?: "") } else listOf(e)
                for (x in targets) {
                    excluded.remove(x.u)
                    val base = x.meta ?: Meta(true, -1.0, -1L, false, "", 0L, emptyList())
                    addFound(x.u, x.h, base.copy(vod = true, conf = 100, why = "يدوي"), x.owner, x.title)
                }
                updateCounts()
                toast("أُضيف إلى التنزيلات")
            }.show()
        }
        val exRows = items.mapIndexed { i, ex ->
            DlgStyle.thumbRow(
                this,
                if (ex.title.isNotBlank()) ex.title.take(40) else "فيديو",
                (Uri.parse(ex.u).host ?: "") + (ex.meta?.let { m -> " • " + (if (m.vod) "مسجل" else "بث") + " " + m.conf + "% • " + m.why } ?: ""),
                null
            ) { exHolder[0]?.dismiss(); pick(i) }
        }
        exHolder[0] = StyledBuilder(this).setTitle("الروابط المستبعدة (" + items.size + ")")
            .setView(DlgStyle.rowsList(this, exRows))
            .setNegativeButton("إغلاق", null).show()
    }
    private fun capNote(kind: String, u: String, why: String) {
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  [" + kind + "]  " + why + "\n" + u.take(220)
        synchronized(capLog) { capLog.add(line); val lc = prefs().getInt("logcap", 0); while (lc > 0 && capLog.size > lc) capLog.removeAt(0) }
    }
    private fun showCapLog() {
        val all = synchronized(capLog) { if (capLog.isEmpty()) "لا توجد سجلات بعد. افتح صفحة فيها فيديوهات ثم عد هنا." else capLog.asReversed().joinToString("\n\n") }
        val ls = prefs().getInt("logshow", 0)
        val txt = if (ls > 0) all.split("\n\n").take(ls).joinToString("\n\n") else all
        val capEmpty = synchronized(capLog) { capLog.isEmpty() }
        val capB = StyledBuilder(this).setTitle("سجل الالتقاط (لماذا ظهر الرابط أو أُهمل)")
        if (capEmpty) capB.setMessage(txt) else capB.setView(DlgStyle.logCards(this, txt.split("\n\n")))
        capB
            .setPositiveButton("نسخ") { _, _ -> copyText(all) }
            .setNeutralButton("مسح") { _, _ -> synchronized(capLog) { capLog.clear() } }
            .setNegativeButton("إغلاق", null).show()
    }
    private val subUrls = HashSet<String>()
    private val analyzer = java.util.concurrent.ThreadPoolExecutor(16, 16, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>()).also { it.allowCoreThreadTimeOut(true) }
    private lateinit var startView: ScrollView
    private lateinit var startSites: LinearLayout
    private lateinit var startBm: LinearLayout
    private lateinit var startBox: EditText
    private lateinit var findBar: LinearLayout
    private lateinit var findBox: EditText
    private lateinit var findCount: TextView

    private class Ctl {
        @Volatile var cancelled = false
        @Volatile var paused = false
        val bytes = java.util.concurrent.atomic.AtomicLong(0)
    }
    private class CancelledException : Exception("أُلغي التحميل")
    private class ProtectedException(m: String) : Exception(m)
    private class Row(val bar: ProgressBar, val msg: TextView, val btn: View, val pause: TextView, val cancel: TextView, val ctl: Ctl, val view: View) {
        var work: File? = null
        var finalLoc: String? = null
        var finalIsUri = false
        var watch: View? = null
        var del: View? = null
        // 0 ملتقط، 1 جارٍ، 2 متوقف مؤقتاً، 3 مكتمل، 4 غير مكتمل (أُلغي أو فشل)
        var state = 0
        // ضُغط عليه تحميل من صفحة العداد وما زال في الانتظار/اختيار الجودة
        var started = false
        var removed = false
        var chip: TextView? = null
        // فيديو محمي رُفض تحميله: زر المشاهدة يجرّب تشغيله مباشرة بالمشغّل المدمج
        var protectedStream = false
        var srcUrl: String? = null
        var srcHdr: Map<String, String>? = null
        // معرّف العنصر المحمي المحفوظ للتشغيل دون اتصال (إن وُجد)
        var offlineId: String? = null
        // الصورة المصغّرة وحالة الحماية والصيغة المعروضة بجانب العنصر
        var thumb: ImageView? = null
        var thumbSet = false
        @Volatile var thumbBusy = false
        @Volatile var thumbStop = false
        var thumbRetry: (() -> Unit)? = null
        // المحاولات المتبقية لجلب صورة المعاينة؛ لا تُؤخذ لقطة من الفيديو إلا بعد أن تصل إلى صفر
        @Volatile var thumbTries = 0
        var kindTv: TextView? = null
        var kindProt = 2
        var kindFmt = ""
        // هوية المهمة (من الرابط كاملاً) وبياناتها المستقلة المحفوظة
        var url = ""
        var id = ""
        var created = System.currentTimeMillis()
        var engine = 0
        var quiet = false
        // عنوان الصفحة التي التُقط منها الفيديو، لإعادة فتحها والالتقاط من جديد عند انتهاء صلاحية الرابط
        var pageUrl = ""
    }

    // يوقف الخيط أثناء الإيقاف المؤقت ويرمي استثناء عند الإلغاء
    private fun gate(c: Ctl) {
        while (c.paused && !c.cancelled) Thread.sleep(200)
        if (c.cancelled) throw CancelledException()
    }

    private fun fmtSize(b: Double): String = when {
        b >= 1048576.0 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024.0 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> b.toLong().toString() + " B"
    }

    private fun fmtTime(sec: Long): String {
        val m = sec / 60
        val x = sec % 60
        return if (m >= 60) String.format(java.util.Locale.US, "%d:%02d:%02d", m / 60, m % 60, x)
        else String.format(java.util.Locale.US, "%02d:%02d", m, x)
    }

    // ينقل صف التحميل إلى قسم "جارٍ التحميل" أثناء النزول ثم يعيده للقائمة
    private fun setActive(r: Row, on: Boolean) {
        (r.view.parent as? ViewGroup)?.removeView(r.view)
        if (r.removed) {
            updateCounts()
            return
        }
        if (on) activeBox.addView(r.view, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        else list.addView(r.view, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        updateCounts()
    }

    private fun saver() = prefs().getBoolean("saver", false)
    private fun lowQ() = saver() || prefs().getString("quality", "high") == "low"
    private fun pageCache() = prefs().getBoolean("cachepages", true)
    private fun baseCache() = if (saver() || pageCache()) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT

    // تحديث حقيقي من الإنترنت (يتجاهل النسخة المخزنة مرة واحدة ثم يعود للوضع المعتاد بعد انتهاء التحميل)
    private fun hardReload(t: Tab) {
        t.restoring = true
        t.web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        t.web.reload()
    }

    // الصفحات المغلقة مؤخراً (حالة محفوظة على القرص)
    private class Closed(val id: String, val title: String, val url: String)
    private val closed = ArrayList<Closed>()

    private val adHosts = listOf(
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

    // ---------- أدوات مساعدة للواجهة ----------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun lp(w: Int, h: Int, wt: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, wt)
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    private fun bg(color: Int, r: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun tv(t: String, size: Float = 15f, color: Int = cText, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    private fun styleChip(t: TextView, on: Boolean) {
        t.setTextColor(if (on) Color.WHITE else cAccent)
        t.background = if (on) bg(cAccent, 18) else bg(Color.TRANSPARENT, 18, cAccent)
    }

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit): TextView {
        val t = tv(text, 13f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.tag = "pill"
        applyPillSize(t)
        styleChip(t, filled)
        t.setOnClickListener { onClick() }
        return t
    }

    // أزرار تبويب التنزيلات: مطابقة لأزرار معاينة html (ارتفاع 46، نصف قطر 22، حشو أفقي 14، خط 13، سطر واحد)
    private fun dlPill(text: String, filled: Boolean, onClick: () -> Unit): TextView {
        val t = pill(text, filled, onClick)
        t.tag = "dlpill"
        t.textSize = 13f
        t.setPadding(dp(14), 0, dp(14), 0)
        t.setSingleLine(true)
        t.gravity = Gravity.CENTER
        t.height = dp(46)
        t.background = if (filled) bg(cAccent, 22) else bg(Color.TRANSPARENT, 22, cAccent)
        return t
    }

    // حجم الأزرار النصية (تحميل، نسخ الرابط، إلغاء...) يتبع شريط «الأزرار النصية» في الأحجام
    private fun applyPillSize(t: TextView) {
        val p = pctB("txt_pill")
        t.textSize = (13f * p / 100f).coerceIn(7f, 30f)
        t.setPadding(dp(14 * p / 100), dp(8 * p / 100), dp(14 * p / 100), dp(8 * p / 100))
    }

    // سماكة زر «عداد ملتقط الروابط» و«التحميلات» = سماكة الأزرار العلوية (تتبع حجم الأزرار النصية)
    private fun syncDlBtnHeights() {
        try {
            val h = dp(46)
            for (v in listOf<View>(badgeBox, tabDl)) {
                val p = v.layoutParams
                p.height = h
                v.layoutParams = p
            }
        } catch (e: Throwable) {
        }
    }

    private fun restylePills(v: View) {
        if (v is TextView && v.tag == "pill") applyPillSize(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) restylePills(v.getChildAt(i))
    }

    private fun iconBtn(res: Int, tint: Int = cText, onClick: () -> Unit): ImageView {
        val i = ImageView(this)
        i.setImageResource(res)
        i.setColorFilter(tint)
        i.setPadding(dp(8), dp(8), dp(8), dp(8))
        i.setOnClickListener { onClick() }
        return i
    }

    private fun toast(s: String) {
        DlgStyle.toast(this, s, false)
    }

    private fun copyText(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", s))
        toast("تم النسخ")
    }

    private fun hideKb() {
        val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        im.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun initColors() {
        if (dark) {
            cBg = Color.parseColor("#0F1318")
            cBar = Color.parseColor("#171C23")
            cCard = Color.parseColor("#1E252E")
            cText = Color.parseColor("#ECEFF4")
            cMuted = Color.parseColor("#93A0B1")
            cLine = Color.parseColor("#2A323D")
            cAccent = Color.parseColor("#5B9BFF")
            cAccentSoft = Color.parseColor("#27415F")
        } else {
            cBg = Color.parseColor("#F3F5F8")
            cBar = Color.parseColor("#FFFFFF")
            cCard = Color.parseColor("#FFFFFF")
            cText = Color.parseColor("#1B1F24")
            cMuted = Color.parseColor("#5B6470")
            cLine = Color.parseColor("#D8DCE2")
            cAccent = Color.parseColor("#0B6BCB")
            cAccentSoft = Color.parseColor("#D6E6FA")
        }
    }

    private fun prefs() = getSharedPreferences("tdl", Context.MODE_PRIVATE)

    // ---------- دورة الحياة ----------

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        applyPools()
        try { File(cacheDir, "live_preview").deleteRecursively() } catch (e: Throwable) {}
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
            } catch (x: Throwable) {
            }
            prevH?.uncaughtException(th, e)
        }
        dark = prefs().getBoolean("dark", true)
        adBlock = prefs().getBoolean("adblock", true)
        limitKb = prefs().getInt("limitkb", 0)
        Thread { rcTrim() }.start()
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

    private var restoringTabs = false

    // حفظ التبويبات المفتوحة في ملف دائم: تبقى بعد إغلاق التطبيق ولا تُحذف إلا عندما يغلقها المستخدم بنفسه
    private fun saveTabsPrefs() {
        if (restoringTabs) return
        try {
            if (tabs.isEmpty()) {
                prefs().edit().remove("tabs_urls").remove("tabs_starts").remove("tabs_backs").remove("tabs_cur").apply()
                return
            }
            prefs().edit()
                .putString("tabs_urls", tabs.joinToString("\n") { (it.web.url ?: "").replace("\n", "") })
                .putString("tabs_starts", tabs.joinToString(",") { if (it.start) "1" else "0" })
                .putString("tabs_backs", tabs.joinToString(",") { if (it.startBack) "1" else "0" })
                .putInt("tabs_cur", tabs.indexOf(cur).coerceAtLeast(0))
                .apply()
        } catch (e: Throwable) {}
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            for (t in tabs) if (t !== cur) try { t.web.clearCache(false) } catch (e: Throwable) {}
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // تحرير ذاكرة التبويبات المخفية عند ضغط الذاكرة
        for (t in tabs) if (t !== cur) try { t.web.freeMemory() } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        for (c in swClosed) try { c.t.web.destroy() } catch (e: Throwable) {}
        swClosed.clear()
        for (t in ArrayList(tabs)) {
            try { container.removeView(t.web) } catch (e: Throwable) {}
            try { t.web.destroy() } catch (e: Throwable) {}
        }
        pages.clear()
        try { prober.shutdownNow() } catch (e: Throwable) {}
        super.onDestroy()
    }

    private var lastBackAt = 0L

    // الرجوع داخل التبويب: صفحة سابقة، ثم إغلاق تبويب منبثق، ثم واجهة التطبيق. يعيد false إذا لم يبقَ شيء للرجوع إليه
    private fun backInTab(t: Tab): Boolean {
        if (t.start) {
            // الواجهة ظاهرة: إن فتحناها من زر «الرئيسية» نعود للصفحة التي كانت خلفها
            if (t.startBack && t.web.url != null) {
                t.start = false
                t.startBack = false
                updateStart()
                return true
            }
            return false
        }
        if (t.web.canGoBack()) {
            t.web.goBack()
            return true
        }
        // تبويب فتحته صفحة أخرى: نغلقه ونعود لما قبله
        if (t.popup && tabs.size > 1) {
            closeTab(t)
            return true
        }
        // لا توجد صفحة سابقة: نعرض واجهة التطبيق بدل الخروج
        t.start = true
        t.startBack = false
        hideKb()
        updateStart()
        return true
    }

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

    // ---------- البناء: القائمة الجانبية والصفحات ----------

    private fun navPopupLp(): FrameLayout.LayoutParams {
        val p = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.LEFT)
        p.topMargin = dp(52)
        p.leftMargin = dp(8)
        return p
    }

    private fun openNavMenu() {
        hideKb()
        navOverlay.visibility = View.VISIBLE
    }

    private fun closeNavMenu() {
        navOverlay.visibility = View.GONE
    }

    // تحديث الصفحة، أو إيقاف تحميلها إن كانت تُحمَّل الآن
    private fun reloadOrStop() {
        val t = cur
        if (t != null) {
            if (t.loading && t.progress < 100) {
                t.web.stopLoading()
                t.loading = false
                updateLoadUi()
            } else {
                hardReload(t)
            }
        }
    }

    // القائمة الجانبية الصغيرة: أيقونات مع أسمائها، تظهر تحت زر الثلاث نقاط
    private fun buildNav(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.background = bg(cBar, 16, cLine)
        col.setPadding(dp(6), dp(6), dp(6), dp(6))
        col.elevation = dp(8).toFloat()
        // زر × لإغلاق القائمة
        val cl = FrameLayout(this)
        cl.minimumWidth = dp(76)
        val cim = ImageView(this)
        cim.setImageResource(R.drawable.ic_close)
        cim.setColorFilter(cMuted)
        cim.setPadding(dp(8), dp(8), dp(8), dp(8))
        cim.contentDescription = "إغلاق"
        cl.addView(cim, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        cl.setOnClickListener { closeNavMenu() }
        col.addView(cl, lp(MATCH, dp(40)))
        val defs = listOf(
            Triple(R.drawable.ic_home, "المتصفح", 0),
            Triple(R.drawable.ic_download, "التنزيلات", 1),
            Triple(R.drawable.ic_tools, "الأدوات", 2),
            Triple(R.drawable.ic_settings, "الإعدادات", 3),
            Triple(R.drawable.ic_alert, "التنبيهات", 4)
        )
        for ((ic, label, idx) in defs) {
            val id = "nav_" + idx
            val pb = pctB(id)
            val pi = pctI(id)
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.minimumWidth = dp(76)
            item.minimumHeight = dp(54 * pb / 100)
            item.setPadding(dp(4), dp(7 * pb / 100), dp(4), dp(7 * pb / 100))
            val im = ImageView(this)
            im.setImageResource(ic)
            val t = tv(label, (11f * pb / 100f).coerceIn(7f, 16f), cMuted)
            t.gravity = Gravity.CENTER
            val isz = dp(24 * pi / 100)
            item.addView(im, lp(isz, isz))
            item.addView(t, lp(WRAP, WRAP, 0f, 0, 2, 0, 0))
            item.setOnClickListener {
                closeNavMenu()
                if (idx == 3 && page == 3 && setSub.isNotEmpty()) openSettingsSub("") else showPage(idx)
            }
            // «المتصفح» غير ظاهر في القائمة لأن زر الرئيسية في الشريط العلوي، لكنه يبقى في القائمة الداخلية لتلوين الصفحة الحالية
            if (idx != 0) col.addView(item, lp(MATCH, WRAP))
            navItems.add(Pair(im, t))
        }
        // تحديث الصفحة
        val rf = LinearLayout(this)
        rf.orientation = LinearLayout.VERTICAL
        rf.gravity = Gravity.CENTER
        rf.minimumWidth = dp(76)
        rf.minimumHeight = dp(54)
        rf.setPadding(dp(4), dp(7), dp(4), dp(7))
        val rim = ImageView(this)
        rim.setImageResource(R.drawable.ic_refresh)
        rim.setColorFilter(cMuted)
        val rt = tv("تحديث الصفحة", 11f, cMuted)
        rt.gravity = Gravity.CENTER
        rf.addView(rim, lp(dp(24), dp(24)))
        rf.addView(rt, lp(WRAP, WRAP, 0f, 0, 2, 0, 0))
        rf.setOnClickListener {
            closeNavMenu()
            showPage(0)
            reloadOrStop()
        }
        col.addView(rf, lp(MATCH, WRAP))
        navPopup = col
        return col
    }

    private fun showPage(i: Int) {
        page = i
        hideKb()
        for (k in pagesList.indices) pagesList[k].visibility = if (k == i) View.VISIBLE else View.GONE
        for (k in navItems.indices) {
            val c = if (k == i) cAccent else cMuted
            navItems[k].first.setColorFilter(c)
            navItems[k].second.setTextColor(c)
        }
        updateCounts()
    }

    private fun buildBrowser(): View {
        val frame = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        // شريط العنوان
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(cBar)
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
        // الرجوع والتقدم والتحديث لم تعد في الشريط (الرجوع بزر الهاتف، والتحديث من القائمة)؛ تبقى مُنشأة غير ظاهرة لأن بقية الكود يشير إليها
        barBack = iconBtn(R.drawable.ic_back) { cur?.let { backInTab(it) } }
        barFwd = iconBtn(R.drawable.ic_forward) { cur?.web?.let { if (it.canGoForward()) it.goForward() } }
        barMenu = iconBtn(R.drawable.ic_more) { openNavMenu() }
        barMenu.contentDescription = "القائمة"
        bar.addView(barMenu, lp(dp(38), dp(38)))

        urlBox = EditText(this)
        urlBox.setTextColor(cText)
        urlBox.setHintTextColor(cMuted)
        urlBox.hint = "ابحث أو اكتب رابطاً"
        urlBox.textSize = 11f
        urlBox.background = bg(cCard, 20, cLine)
        urlBox.setPadding(dp(8), dp(8), dp(8), dp(8))
        urlBox.layoutDirection = View.LAYOUT_DIRECTION_LTR
        urlBox.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        urlBox.setSelectAllOnFocus(true)
        urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBox.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                load(urlBox.text.toString())
                true
            } else false
        }
        bar.addView(urlBox, lp(0, dp(40), 1f, 4, 0, 4, 0))

        // زر التبويبات بجانب الرابط (يعرض العدد ويفتح قائمة التبويبات)
        tabBtn = tv("1", 12f, cText, true)
        tabBtn.gravity = Gravity.CENTER
        tabBtn.includeFontPadding = false
        tabBtn.setPadding(0, 0, 0, 0)
        tabBtn.maxLines = 1
        tabBtn.layoutDirection = View.LAYOUT_DIRECTION_LTR
        tabBtn.contentDescription = "التبويبات"
        // مربع بحواف مستديرة بنفس لون وسماكة أيقونات الشريط (2dp) بدل المربع الصغير الغريب
        val tabFrame = FrameLayout(this)
        val tabShape = bg(Color.TRANSPARENT, 6, 0)
        tabShape.setStroke(dp(2), cText)
        tabBtn.background = tabShape
        tabFrame.addView(tabBtn, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        tabFrame.setPadding(dp(8), dp(8), dp(8), dp(8))
        val rv = android.util.TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rv, true)) {
            tabFrame.foreground = resources.getDrawable(rv.resourceId, theme)
        }
        tabFrame.isClickable = true
        tabFrame.setOnClickListener { openSw() }
        barTabs = tabFrame
        bar.addView(tabFrame, 1, lp(dp(38), dp(38), 0f, 0, 0, 0, 0))
        barPlus = iconBtn(R.drawable.ic_add) { newHomeTab() }
        barPlus.contentDescription = "تبويب جديد"
        bar.addView(barPlus, 2, lp(dp(38), dp(38)))

        pct = tv("", 11f, cAccent, true)
        pct.visibility = View.GONE
        bar.addView(pct, lp(WRAP, WRAP, 0f, 0, 0, 2, 0))

        reloadBtn = iconBtn(R.drawable.ic_refresh) { reloadOrStop() }
        barDl = iconBtn(R.drawable.ic_download, cAccent) { showPage(1) }
        barDl.contentDescription = "تحميل الفيديو"
        bar.addView(barDl, lp(dp(38), dp(38)))
        barBatch = iconBtn(R.drawable.ic_batch) { capturePage() }
        barBatch.contentDescription = "التقاط كل فيديوهات الصفحة"
        bar.addView(barBatch, lp(dp(38), dp(38)))
        barHome = iconBtn(R.drawable.ic_home_solid) {
            // ينتقل إلى تبويب الرئيسية الموجود (أو ينشئه) ويبقى فيه دون المساس بتبويب الموقع
            val home = tabs.firstOrNull { it.start }
            if (home != null) {
                home.startBack = false
                if (home !== cur) switchTo(home) else updateStart()
                showPage(0)
            } else {
                newTab("")
            }
        }
        barHome.contentDescription = "الرئيسية"
        bar.addView(barHome, lp(dp(38), dp(38)))
        col.addView(bar, lp(MATCH, WRAP))

        // شريط تقدّم تحميل الصفحة
        loadBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        loadBar.max = 100
        loadBar.progressTintList = ColorStateList.valueOf(cAccent)
        loadBar.progressBackgroundTintList = ColorStateList.valueOf(cLine)
        loadBar.visibility = View.INVISIBLE
        col.addView(loadBar, lp(MATCH, dp(4)))
        col.addView(buildFindBar(), lp(MATCH, WRAP))

        // التبويبات
        val tabsScroll = HorizontalScrollView(this)
        tabsScroll.setBackgroundColor(cBar)
        tabsScroll.isHorizontalScrollBarEnabled = false
        tabsRow = LinearLayout(this)
        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.setPadding(dp(4), dp(2), dp(4), dp(4))
        plusChip = tv("+", 16f, cAccent, true)
        plusChip.gravity = Gravity.CENTER
        plusChip.background = bg(cCard, 16, cLine)
        plusChip.setPadding(dp(14), dp(4), dp(14), dp(4))
        plusChip.setOnClickListener { newHomeTab() }
        tabsRow.addView(plusChip, lp(WRAP, WRAP, 0f, 3, 3, 3, 3))
        tabsScroll.addView(tabsRow)
        // شريط التبويبات القديم لم يعد يُعرض؛ التبويبات من الزر بجانب الرابط

        // منطقة الصفحة: تملأ كل المساحة المتبقية
        container = FrameLayout(this)
        val area = FrameLayout(this)
        area.addView(container, FrameLayout.LayoutParams(MATCH, MATCH))
        area.addView(buildStart(), FrameLayout.LayoutParams(MATCH, MATCH))
        col.addView(area, lp(MATCH, 0, 1f))

        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        applyBarSizes()
        return frame
    }

    private fun buildDownloads(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(14), dp(14), dp(24))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(View(this), lp(0, WRAP, 1f))
        head.addView(dlPill("الصور", false) { showImagesSection() }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        head.addView(dlPill("المحمية", false) { showOfflineManager() }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        // (١) المخزن بنفس شكل الأزرار المجاورة (إطار بدون تعبئة)
        head.addView(dlPill("المخزن", false) { openStorage() }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        // (٣) مسح القائمة: إزاحة لليسار بمقدار المسافة بين الأزرار (6dp)
        val clearBtn = dlPill("مسح القائمة", false) { clearFound() }
        head.addView(clearBtn, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        head.clipChildren = false
        col.clipChildren = false
        col.clipToPadding = false
        col.addView(head, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
        col.addView(tv("شغّل الفيديو داخل المتصفح وستظهر روابطه هنا ثم اضغط تحميل. الملفات تُحفظ في مجلد التحميلات.", 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 0, 0, 10))

        // شريط الأزرار: عداد ملتقط الروابط (مصغّر برقم أحمر يومض) + التحميلات
        dlActions = LinearLayout(this)
        dlActions.orientation = LinearLayout.HORIZONTAL
        dlActions.gravity = Gravity.CENTER_VERTICAL
        badgeBox = LinearLayout(this)
        badgeBox.orientation = LinearLayout.HORIZONTAL
        badgeBox.gravity = Gravity.CENTER
        badgeBox.setPadding(dp(12), 0, dp(8), 0)
        badge = tv("عداد ملتقط الروابط", 12f, Color.WHITE, true)
        badgeNum = tv("0", 12f, Color.WHITE, true)
        badgeNum.visibility = View.GONE
        badgeNum.gravity = Gravity.CENTER
        badgeNum.minWidth = dp(22)
        badgeNum.setPadding(dp(6), dp(1), dp(6), dp(1))
        badgeNum.background = bg(Color.parseColor("#E53935"), 11)
        badgeBox.addView(badge, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        badgeBox.addView(badgeNum, lp(WRAP, WRAP))
        badgeBox.background = bg(cAccent, 22)
        badgeBox.visibility = View.VISIBLE
        badgeBox.setOnClickListener {
            badgeSeen = true
            setDlView(1)
        }
        // (٤) مسافة قصيرة (6dp) بين عداد ملتقط الروابط وزر التحميلات
        val badgeLp = lp(0, WRAP, 1f)
        badgeLp.marginEnd = dp(6)
        dlActions.addView(badgeBox, badgeLp)
        tabDl = tv("التحميلات", 12f, Color.WHITE, true)
        tabDl.gravity = Gravity.CENTER
        tabDl.setPadding(dp(14), 0, dp(14), 0)
        tabDl.background = bg(cAccent, 22, Color.WHITE)
        tabDl.setOnClickListener { setDlView(0) }
        dlActions.addView(tabDl, lp(0, WRAP, 1f))
        col.addView(dlActions, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        // (٥) سماكة الزرين = سماكة الأزرار العلوية
        syncDlBtnHeights()

        // شاشة العداد: عدد الملتقطة + زر تحميل الفيديو
        activeTitle = tv("جارٍ التحميل الآن", 16f, cAccent, true)
        col.addView(activeTitle, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
        activeBox = LinearLayout(this)
        activeBox.orientation = LinearLayout.VERTICAL
        col.addView(activeBox, lp(MATCH, WRAP))
        listTitle = tv("قائمة الفيديوهات", 15f, cText, true)
        col.addView(listTitle, lp(MATCH, WRAP, 0f, 0, 6, 0, 6))
        emptyTv = tv("لا توجد روابط ملتقطة بعد", 14f, cMuted)
        emptyTv.gravity = Gravity.CENTER
        emptyTv.setPadding(0, dp(40), 0, dp(40))
        col.addView(emptyTv, lp(MATCH, WRAP))
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, lp(MATCH, WRAP))
        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.addView(col)
        dlScroll = sv
        return sv
    }

    private fun allRows(): List<Row> {
        val out = ArrayList<Row>()
        for (box in listOf<LinearLayout>(activeBox, list)) {
            for (i in 0 until box.childCount) (box.getChildAt(i).tag as? Row)?.let { out.add(it) }
        }
        return out
    }

    private fun inFilter(r: Row): Boolean = if (dlView == 1) (r.state == 0 && !r.started) else (r.state != 0 || r.started)

    private fun setDlView(v: Int) {
        dlView = v
        updateCounts()
        dlScroll.smoothScrollTo(0, 0)
    }

    private fun setState(r: Row, st: Int) {
        r.state = st
        val c = when (st) {
            1 -> cAccent
            2 -> Color.parseColor("#E0A030")
            3 -> Color.parseColor("#3DB36B")
            4 -> Color.parseColor("#E05A5A")
            else -> cMuted
        }
        val tx = when (st) {
            1 -> "جارٍ"
            2 -> "متوقف مؤقتاً"
            3 -> "مكتمل"
            4 -> "غير مكتمل"
            else -> "ملتقط"
        }
        r.del?.visibility = if (st == 1 || st == 2) View.GONE else View.VISIBLE
        r.chip?.let {
            it.text = tx
            it.setTextColor(c)
            it.background = bg(Color.TRANSPARENT, 10, c)
        }
        updateCounts()
        saveTask(r)
    }

    // حذف تحميل: يوقفه إن كان يعمل ويحذف أجزاءه المؤقتة ويزيله من القائمة (الملف المحفوظ في التحميلات لا يُمس)
    private fun deleteRow(c: Cand) {
        val r = c.rw
        r.removed = true
        r.ctl.paused = false
        r.ctl.cancelled = true
        val w = r.work
        if (r.state != 1 && r.state != 2 && w != null) {
            Thread { try { w.deleteRecursively() } catch (e: Throwable) {} }.start()
            r.work = null
        }
        (r.view.parent as? ViewGroup)?.removeView(r.view)
        forgetTask(r.id)
        found.remove(c.url)
        foundStart.remove(c.url)
        foundHdr.remove(c.url)
        cands.remove(c.url)
        updateCounts()
        toast("تم الحذف")
    }

    private fun deleteDialog(c: Cand) {
        val r = c.rw
        val msg = when (r.state) {
            1, 2 -> "سيتوقف التحميل وتُحذف أجزاؤه المؤقتة ويُزال من القائمة."
            3 -> "يُزال من القائمة فقط. الملف المحفوظ يبقى في مجلد التحميلات."
            else -> "يُزال من القائمة مع أي أجزاء مؤقتة محفوظة له."
        }
        StyledBuilder(this)
            .setTitle("حذف التحميل")
            .setMessage(msg)
            .setPositiveButton("حذف") { _, _ -> deleteRow(c) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun toolCard(res: Int, label: String, onClick: () -> Unit): LinearLayout {
        val id = "tool_" + label
        sizeIds[id] = Pair("بطاقات الأدوات", label)
        val pb = pctB(id)
        val pi = pctI(id)
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER
        c.background = bg(cCard, 14, cLine)
        c.minimumHeight = dp(92 * pb / 100)
        c.setPadding(dp(6), dp(12 * pb / 100), dp(6), dp(12 * pb / 100))
        val i = ImageView(this)
        i.setImageResource(res)
        i.setColorFilter(cAccent)
        val isz = dp(28 * pi / 100)
        c.addView(i, lp(isz, isz))
        val t = tv(label, (12f * pb / 100f).coerceIn(7f, 24f), cText)
        t.gravity = Gravity.CENTER
        c.addView(t, lp(WRAP, WRAP, 0f, 0, 6, 0, 0))
        c.setOnClickListener { onClick() }
        return c
    }

    private fun grid(parent: LinearLayout, cards: List<View>) {
        val cols = prefs().getInt("toolcols", 3).coerceIn(2, 4)
        var i = 0
        while (i < cards.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until cols) {
                val v = cards.getOrNull(i + k)
                if (v != null) row.addView(v, lp(0, WRAP, 1f, 3, 3, 3, 3))
                else row.addView(View(this), lp(0, 1, 1f, 3, 3, 3, 3))
            }
            parent.addView(row, lp(MATCH, WRAP))
            i += cols
        }
    }

    private fun buildTools(): View {
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
            toolCard(R.drawable.ic_undo, "المغلقة مؤخراً") { showClosed() },
            toolCard(R.drawable.ic_list, "السجل") { copyTabs() }
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
                } catch (e: Exception) {
                    toast("تعذر فتح مجلد التحميلات")
                }
            }
        ))

        section(col, "التقاط الفيديو")
        grid(col, listOf(
            toolCard(R.drawable.ic_search_link, "فحص رابط") { inspectLink() },
            toolCard(R.drawable.ic_history, "سجل الالتقاط") { showCapLog() },
            toolCard(R.drawable.ic_block, "الروابط المستبعدة") { excludedDialog() }
        ))

        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.addView(col)
        return sv
    }

    private fun settingsCard(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = bg(cCard, 14, cLine)
        c.setPadding(dp(14), dp(10), dp(14), dp(10))
        parent.addView(c, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        return c
    }

    private fun switchRow(parent: LinearLayout, title: String, sub: String, key: String, def: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.addView(tv(title, 15f, cText, true))
        txt.addView(tv(sub, 12f, cMuted))
        val sw = Switch(this)
        DlgStyle.styleSwitch(this, sw)
        sw.isChecked = prefs().getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, on ->
            prefs().edit().putBoolean(key, on).apply()
            onChange(on)
        }
        row.addView(txt, lp(0, WRAP, 1f))
        row.addView(sw)
        parent.addView(row, lp(MATCH, WRAP))
    }

    private fun applyWebSettings(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.blockNetworkImage = saver()
        s.cacheMode = baseCache()
        s.mediaPlaybackRequiresUserGesture = !(prefs().getBoolean("autoplay", true) && !saver())
        val desk = prefs().getBoolean("desktop", false)
        s.userAgentString = if (desk) DESKTOP_UA else ""
        s.useWideViewPort = true
        s.loadWithOverviewMode = desk
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.textZoom = prefs().getInt("textzoom", 100)
        s.databaseEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.setGeolocationEnabled(true)
        s.setSupportZoom(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        curUa = s.userAgentString ?: ""
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView(tv(title, 13f, cAccent, true), lp(MATCH, WRAP, 0f, 4, 10, 0, 6))
    }

    private fun actionRow(parent: LinearLayout, title: String, sub: String, btn: String, onClick: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.addView(tv(title, 15f, cText, true))
        txt.addView(tv(sub, 12f, cMuted))
        row.addView(txt, lp(0, WRAP, 1f))
        row.addView(pill(btn, false) { onClick() })
        parent.addView(row, lp(MATCH, WRAP))
    }


    // ---------- الإعدادات: قائمة رئيسية + صفحات داخلية (كل إعداد في مكان واحد فقط) ----------

    private lateinit var settingsHost: FrameLayout
    private var setSub = ""

    private fun buildSettings(): View {
        settingsHost = FrameLayout(this)
        settingsHost.setBackgroundColor(cBg)
        openSettingsSub("")
        return settingsHost
    }

    private fun openSettingsSub(id: String) {
        setSub = id
        hideKb()
        settingsHost.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(14), dp(14), dp(24))
        if (id.isEmpty()) {
            col.addView(tv("الإعدادات", 20f, cText, true), lp(MATCH, WRAP, 0f, 0, 0, 0, 2))
            col.addView(tv("اختر قسماً لعرض إعداداته", 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
            buildSettingsHub(col)
        } else {
            val titles = mapOf(
                "look" to "المظهر والواجهة", "browse" to "التصفح والبحث", "capture" to "التقاط الفيديو",
                "net" to "التحميل والشبكة", "perf" to "الأداء والتخزين", "privacy" to "الخصوصية والبيانات", "about" to "حول التطبيق"
            )
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.addView(iconBtn(R.drawable.ic_back, cText) { openSettingsSub("") }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
            head.addView(tv(titles[id] ?: "الإعدادات", 20f, cText, true), lp(0, WRAP, 1f))
            col.addView(head, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
            when (id) {
                "look" -> buildSetLook(col)
                "browse" -> buildSetBrowse(col)
                "capture" -> buildSetCapture(col)
                "net" -> buildSetNet(col)
                "perf" -> buildSetPerf(col)
                "privacy" -> buildSetPrivacy(col)
                "about" -> buildSetAbout(col)
            }
        }
        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.addView(col)
        settingsHost.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun hubRow(parent: LinearLayout, icon: Int, title: String, sub: String, id: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.background = bg(cCard, 14, cLine)
        val ic = ImageView(this)
        ic.setImageResource(icon)
        ic.setColorFilter(cAccent)
        row.addView(ic, lp(dp(26), dp(26), 0f, 12, 0, 0, 0))
        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.addView(tv(title, 15f, cText, true))
        txt.addView(tv(sub, 12f, cMuted))
        row.addView(txt, lp(0, WRAP, 1f))
        val ch = ImageView(this)
        ch.setImageResource(R.drawable.ic_forward)
        ch.setColorFilter(cMuted)
        row.addView(ch, lp(dp(20), dp(20)))
        row.setOnClickListener { openSettingsSub(id) }
        parent.addView(row, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
    }

    private fun buildSettingsHub(col: LinearLayout) {
        section(col, "التطبيق")
        hubRow(col, R.drawable.ic_resize, "المظهر والواجهة", "الوضع الداكن وأحجام الأزرار والأيقونات", "look")
        hubRow(col, R.drawable.ic_web, "التصفح والبحث", "الشاشة الرئيسية ومحرك البحث وحظر الإعلانات", "browse")
        section(col, "الفيديو والتحميل")
        hubRow(col, R.drawable.ic_batch, "التقاط الفيديو", "أزرار الفيديو وخطوات الالتقاط وما يظهر في القائمة", "capture")
        hubRow(col, R.drawable.ic_download, "التحميل والشبكة", "السرعة والاتصالات والمهل والرؤوس والجودة", "net")
        section(col, "النظام")
        hubRow(col, R.drawable.ic_speed, "الأداء والتخزين", "توفير البيانات وحفظ الصفحات والذاكرة", "perf")
        hubRow(col, R.drawable.ic_cookie, "الخصوصية والبيانات", "مسح السجل والكاش والكوكيز", "privacy")
        hubRow(col, R.drawable.ic_alert, "حول التطبيق", "الإصدار والوصف", "about")
    }

    private fun buildSetLook(col: LinearLayout) {
        section(col, "المظهر")
        val c = settingsCard(col)
        switchRow(c, "الوضع الداكن", "يتبدل المظهر فوراً مع بقاء التبويبات وهذه الصفحة كما هي", "dark", true) { recreate() }
        section(col, "الأحجام")
        val c2 = settingsCard(col)
        actionRow(c2, "أحجام الأزرار والأيقونات", "تحكم حر بحجم كل زر وكل أيقونة وعدد أعمدة الأدوات", "فتح") { showSizes() }
    }

    private fun buildSetBrowse(col: LinearLayout) {
        section(col, "الشاشة الرئيسية والبحث")
        val c0 = settingsCard(col)
        switchRow(c0, "البدء بالشاشة الرئيسية", "تظهر شاشة البحث والمواقع عند بدء التطبيق وعند فتح تبويب جديد", "startscreen", true) { }

        val eBox = LinearLayout(this)
        eBox.orientation = LinearLayout.VERTICAL
        eBox.setPadding(0, dp(8), 0, dp(8))
        eBox.addView(tv("محرك البحث", 15f, cText, true))
        eBox.addView(tv("يُستخدم عند كتابة كلمات بدل رابط", 12f, cMuted))
        val eRow = LinearLayout(this)
        eRow.orientation = LinearLayout.HORIZONTAL
        val eg = pill("جوجل", true) { }
        val ed = pill("DuckDuckGo", false) { }
        val eb = pill("Bing", false) { }
        fun paintE() {
            val e = prefs().getString("engine", "google")
            styleChip(eg, e == "google")
            styleChip(ed, e == "ddg")
            styleChip(eb, e == "bing")
        }
        eg.setOnClickListener { prefs().edit().putString("engine", "google").apply(); paintE() }
        ed.setOnClickListener { prefs().edit().putString("engine", "ddg").apply(); paintE() }
        eb.setOnClickListener { prefs().edit().putString("engine", "bing").apply(); paintE() }
        paintE()
        eRow.addView(eg, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        eRow.addView(ed, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        eRow.addView(eb)
        eBox.addView(eRow, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        c0.addView(eBox, lp(MATCH, WRAP))

        val homeBox = EditText(this)
        homeBox.setText(prefs().getString("home", HOME))
        homeBox.setTextColor(cText)
        homeBox.textSize = 13f
        homeBox.background = bg(cBg, 10, cLine)
        homeBox.setPadding(dp(12), dp(8), dp(12), dp(8))
        homeBox.layoutDirection = View.LAYOUT_DIRECTION_LTR
        homeBox.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        homeBox.setSingleLine()
        c0.addView(tv("صفحة البدء (عند إيقاف الشاشة الرئيسية)", 15f, cText, true), lp(MATCH, WRAP, 0f, 0, 8, 0, 4))
        c0.addView(homeBox, lp(MATCH, WRAP))
        c0.addView(pill("حفظ صفحة البدء", false) {
            val v = homeBox.text.toString().trim()
            prefs().edit().putString("home", if (v.isEmpty()) HOME else fix(v)).apply()
            toast("تم الحفظ")
        }, lp(MATCH, WRAP, 0f, 0, 8, 0, 8))

        section(col, "الصفحات")
        val c1 = settingsCard(col)
        switchRow(c1, "حظر الإعلانات", "يمنع الطلبات المعروفة لشبكات الإعلانات", "adblock", true) { adBlock = it }
        switchRow(c1, "وضع سطح المكتب", "يفتح المواقع بنسختها الكاملة", "desktop", false) {
            for (t in tabs) applyWebSettings(t.web)
            cur?.let { hardReload(it) }
        }
        switchRow(c1, "تشغيل الفيديو تلقائياً", "تشغيل الفيديو بدون لمس الشاشة لالتقاط رابطه", "autoplay", true) {
            for (t in tabs) applyWebSettings(t.web)
        }
        choiceRow(c1, "حجم الخط في الصفحات", "يُطبّق على كل التبويبات", "textzoom", arrayOf("80%", "100%", "120%", "150%"), intArrayOf(80, 100, 120, 150), 100) {
            for (t in tabs) applyWebSettings(t.web)
        }
    }

    private fun buildSetCapture(col: LinearLayout) {
        section(col, "الالتقاط")
        val g1 = settingsCard(col)
        switchRow(g1, "أزرار التحميل على الفيديو", "أزرار تحميل ونسخ وشريط تقديم فوق كل فيديو (تُطبّق عند فتح الصفحة). الصور: اضغط مطولاً على الصورة", "overlay", true) { }
        numRow(g1, "أقصى خطوات التمرير في الالتقاط", "عدد خطوات التمرير الأعلى. الصفر: يستمر حتى نهاية القائمة", "scanmax", 0)
        numRow(g1, "خطوات الثبات قبل إنهاء الالتقاط", "كم خطوة تبقى فيها الصفحة بلا جديد قبل الإنهاء. زدها للمواقع البطيئة", "scanstill", 6, "")
        numRow(g1, "زمن كل خطوة تمرير (ملّي ثانية)", "الانتظار بعد كل تمريرة ليحمّل الموقع المزيد. زدها للمواقع البطيئة", "scandelay", 750, "")
        switchRow(g1, "تشغيل الفيديوهات أثناء التقاط الصفحة", "عند تفعيله يشغّل التطبيق كل فيديو في الصفحة بصوته الطبيعي أثناء الالتقاط، ولا يوقفه أبداً، فيبقى يعمل حتى توقفه أنت أو تخرج من الصفحة. الأصل: مغلق، والالتقاط يعمل بدونه", "scanplay", false) { }
        switchRow(g1, "قراءة البث المستمر (event-stream)", "الأصل: مفعّل. يقرأ البث المستمر بحثاً عن روابط الفيديو. أوقفه فقط إن لاحظت بطئاً أو استهلاكاً للذاكرة (وحد قراءة الردود أعلاه يتحكم بالحجم)", "scanstream", true) { }
        numRow(g1, "أقصى عدد أزرار تحميل فوق الفيديوهات في الصفحة", "عدد عناصر الفيديو التي يضع التطبيق عليها أزرار التحميل. الصفر: بلا حد (يُطبّق عند فتح الصفحة)", "maxoverlay", 0)

        section(col, "ما يظهر في القائمة")
        val g2 = settingsCard(col)
        numRow(g2, "حد الثقة في الحكم (%)", "أي رابط تقل ثقة الحكم عليه عن هذه القيمة يظهر دائماً بوسم «تقديري» لتصنفه أنت يدوياً. الصفر: يُعتمد الحكم دائماً", "uncert", 75, "بدون وسم «تقديري»")
        numRow(g2, "انتظار فحص البث المباشر (ثوانٍ)", "المدة بين فحصي القائمة للتمييز بين المباشر والمسجل. الصفر: تلقائي حسب القائمة", "livewait", 0, "تلقائي")
        switchRow(g2, "التقاط الملفات الصوتية", "mp3 و aac و m4a و ogg و opus و wav و flac تظهر في قائمة الروابط مثل الفيديو", "capaudio", true) { }
        switchRow(g2, "التقاط ملفات ts و m4s المستقلة", "أي ملف ts أو m4s ليس جزءاً من قائمة بث معروفة يُلتقط ويظهر", "capseg", true) { }
        switchRow(g2, "إظهار أجزاء القوائم المعروفة", "الأصل: مغلق حتى لا تمتلئ القائمة بمئات الأجزاء، فالفيديو الكامل يظهر عبر ملف m3u8 أو mpd. فعّله لترى كل جزء بنفسه", "showseg", false) { }
        switchRow(g2, "إظهار البث المباشر", "الأصل: مفعّل، فيظهر المسجل والمباشر. أوقفه لإخفاء البث المباشر (ما أُخفي يبقى في «الروابط المستبعدة»)", "showlive", true) { }

        section(col, "سجل الالتقاط")
        val g3 = settingsCard(col)
        numRow(g3, "أقصى أسطر سجل الالتقاط المحفوظة", "الصفر: يحتفظ بكل الأسطر حتى تغلق التطبيق", "logcap", 0)
        numRow(g3, "أسطر السجل المعروضة في النافذة", "الصفر: يعرض الكل. زر النسخ ينسخ الكل دائماً", "logshow", 0, "الكل")
    }

    private fun buildSetNet(col: LinearLayout) {
        section(col, "التحميل")
        val g3 = settingsCard(col)
        switchRow(g3, "محاولة التحميل رغم علامة DRM", "الأصل: مغلق. فك تشفير DRM غير ممكن، فالمحاولة تحفظ بيانات مشفّرة غالباً لا تعمل. الحفظ الرسمي دون اتصال (ترخيص المنصة) يعمل تلقائياً دون هذا الخيار ويظهر في «المحمية»", "ignoredrm", false) { }
        switchRow(g3, "تحميل خام عند تشفير HLS غير مدعوم", "التطبيق يفك AES-128 فقط. عند أي تشفير آخر مثل SAMPLE-AES يرفض افتراضياً. تفعيله يحمّل الأجزاء كما هي بدون فك", "ignoreenc", false) { }
        val cnt = tv("" + prefs().getInt("threads", 8), 16f, cText, true)
        cnt.gravity = Gravity.CENTER
        cnt.minWidth = dp(34)
        fun setThr(n: Int) {
            val v = n.coerceIn(1, 128)
            prefs().edit().putInt("threads", v).apply()
            cnt.text = "" + v
        }
        val thrRow = LinearLayout(this)
        thrRow.orientation = LinearLayout.HORIZONTAL
        thrRow.gravity = Gravity.CENTER_VERTICAL
        thrRow.setPadding(0, dp(8), 0, dp(8))
        val thrTxt = LinearLayout(this)
        thrTxt.orientation = LinearLayout.VERTICAL
        thrTxt.addView(tv("عدد الأجزاء المتوازية", 15f, cText, true))
        thrTxt.addView(tv("عدد اتصالات التحميل المتوازية للملف الواحد (1 - 32)", 12f, cMuted))
        thrRow.addView(thrTxt, lp(0, WRAP, 1f))
        thrRow.addView(pill("+", false) { setThr(prefs().getInt("threads", 8) + 1) })
        thrRow.addView(cnt)
        thrRow.addView(pill("−", false) { setThr(prefs().getInt("threads", 8) - 1) })
        g3.addView(thrRow, lp(MATCH, WRAP))
        val qRow = LinearLayout(this)
        qRow.orientation = LinearLayout.HORIZONTAL
        qRow.gravity = Gravity.CENTER_VERTICAL
        qRow.setPadding(0, dp(8), 0, dp(8))
        val qTxt = LinearLayout(this)
        qTxt.orientation = LinearLayout.VERTICAL
        qTxt.addView(tv("جودة التحميل", 15f, cText, true))
        qTxt.addView(tv("الجودة المختارة من القائمة الرئيسية", 12f, cMuted))
        val hi = pill("الأعلى", true) { }
        val lo = pill("الأدنى", false) { }
        fun paintQ() {
            val q = prefs().getString("quality", "high")
            styleChip(hi, q == "high")
            styleChip(lo, q == "low")
        }
        hi.setOnClickListener { prefs().edit().putString("quality", "high").apply(); paintQ() }
        lo.setOnClickListener { prefs().edit().putString("quality", "low").apply(); paintQ() }
        paintQ()
        qRow.addView(qTxt, lp(0, WRAP, 1f))
        qRow.addView(hi, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        qRow.addView(lo)
        g3.addView(qRow, lp(MATCH, WRAP))
        actionRow(g3, "التحكم بالسرعة", "حد السرعة الحر وعدد الاتصالات وعدد التحميلات المتزامنة (بلا قيود)", "ضبط") { showSpeed() }
        actionRow(g3, "قياس سرعة الشبكة", "يقيس سرعتك الفعلية ويقترح أفضل عدد اتصالات", "قياس") { speedTest() }

        section(col, "الاتصال")
        val g1 = settingsCard(col)
        numRow(g1, "أقصى عدد تحويلات الروابط (redirects)", "الصفر: بلا حد", "redirects", 30)
        numRow(g1, "مهلة الاتصال (ثوانٍ)", "الصفر: ينتظر بلا مهلة. زدها للخوادم البطيئة", "conntimeout", 45, "بلا مهلة")
        numRow(g1, "مهلة القراءة (ثوانٍ)", "الصفر: ينتظر بلا مهلة. زدها للخوادم البطيئة", "readtimeout", 120, "بلا مهلة")
        actionRow(g1, "رؤوس HTTP مخصصة", "سطر لكل رأس بصيغة Name: value (مثل Referer أو User-Agent أو Cookie). تُضاف لكل طلب فحص وتحميل وتتغلب على القيم التلقائية، وتحل مشكلة المواقع التي ترفض التحميل", "تعديل") {
            val et = EditText(this)
            et.setText(prefs().getString("customhdr", ""))
            et.hint = "Referer: https://example.com/"
            et.minLines = 4
            et.gravity = Gravity.TOP
            et.layoutDirection = View.LAYOUT_DIRECTION_LTR
            StyledBuilder(this).setTitle("رؤوس HTTP مخصصة").setView(et)
                .setPositiveButton("حفظ") { _, _ -> prefs().edit().putString("customhdr", et.text.toString()).apply(); toast("تم الحفظ") }
                .setNegativeButton("إلغاء", null).show()
        }

        section(col, "التحليل والفحص")
        val g2 = settingsCard(col)
        numRow(g2, "خيوط تحليل الروابط", "عدد الخيوط التي تحلل الروابط معاً", "anthreads", 16, "") { applyPools() }
        numRow(g2, "خيوط فحص الشبكة", "عدد الخيوط التي تفحص طلبات الصفحة معاً", "prthreads", 8, "") { applyPools() }
        numRow(g2, "حد قراءة الردود غير النصية (كيلوبايت)", "يمنع قراءة ملفات ثنائية ضخمة بحثاً عن روابط. الصفر: بلا حد", "readcap", 0)
    }

    private fun buildSetPerf(col: LinearLayout) {
        section(col, "البيانات والصفحات")
        val c1 = settingsCard(col)
        switchRow(c1, "توفير البيانات", "يمنع تحميل الصور، يفضّل الكاش، يوقف التشغيل التلقائي، ويختار أقل جودة عند التحميل (أعد تحميل الصفحة)", "saver", false) {
            for (t in tabs) applyWebSettings(t.web)
            toast("أعد تحميل الصفحة ليسري التغيير")
        }
        choiceRow(c1, "مهلة تحميل الصفحة", "يوقف الصفحة البطيئة تلقائياً بعد هذه المدة", "pgtimeout", arrayOf("بلا مهلة", "15 ثانية", "30 ثانية", "60 ثانية"), intArrayOf(0, 15, 30, 60), 0)

        // حفظ الصفحات (مستقل تماماً عن زر توفير البيانات)
        section(col, "حفظ الصفحات والصور")
        val cp = settingsCard(col)
        switchRow(cp, "حفظ الصفحات على الجهاز", "تُحفظ الصفحة وبياناتها بعد تحميلها وتُفتح من الجهاز عند الرجوع إليها أو بعد إغلاق التطبيق. زر التحديث يجلب نسخة جديدة. لا علاقة له بزر توفير البيانات", "cachepages", true) {
            for (t in tabs) applyWebSettings(t.web)
        }
        switchRow(cp, "مخزن الصور والأيقونات", "يحفظ كل صورة وأيقونة تظهر في الصفحات على الجهاز حتى لو منع الموقع التخزين، فلا تُحمَّل من جديد في كل مرة (حتى بعد إغلاق التطبيق)", "rescache", true) { }
        choiceRow(cp, "حجم المخزن الأقصى", "الأصل: بلا حد. إن حددت حجماً فعند الامتلاء تُحذف الأقدم استخداماً أولاً", "rcmb", arrayOf("بلا حد", "500 م.ب", "1000 م.ب", "3000 م.ب"), intArrayOf(0, 500, 1000, 3000), 0) { Thread { rcTrim() }.start() }
        actionRow(cp, "مسح مخزن الصور", "يحذف الصور المحفوظة فقط", "مسح") { rcClear() }

        section(col, "السرعة والذاكرة")
        val c3 = settingsCard(col)
        switchRow(c3, "إيقاف التبويبات الخلفية", "يوقف نشاط التبويبات المخفية لتسريع التبويب الحالي وتوفير الذاكرة والبطارية", "pausebg", true) {
            cur?.let { switchTo(it) }
        }
        actionRow(c3, "تقرير الأداء", "التبويبات والذاكرة والتحميلات", "عرض") { perfReport() }
        actionRow(c3, "تفريغ الذاكرة", "يمسح ذاكرة التبويبات الخلفية", "تفريغ") { freeMem() }
    }

    private fun buildSetPrivacy(col: LinearLayout) {
        section(col, "مسح البيانات")
        val c5 = settingsCard(col)
        actionRow(c5, "سجل التصفح", "حذف كل الصفحات المسجلة", "مسح") {
            clearHistory()
            toast("تم مسح السجل")
        }
        actionRow(c5, "الكاش", "حذف الملفات المؤقتة للصفحات", "مسح") {
            for (t in tabs) {
                t.web.clearCache(true)
                t.web.clearHistory()
            }
            toast("تم مسح الكاش")
        }
        actionRow(c5, "الكوكيز", "سيُطلب تسجيل الدخول من جديد", "مسح") {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            toast("تم مسح الكوكيز")
        }
    }

    private fun buildSetAbout(col: LinearLayout) {
        val c4 = settingsCard(col)
        c4.gravity = Gravity.CENTER_HORIZONTAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo)
        c4.addView(logo, lp(dp(72), dp(72), 0f, 0, 8, 0, 8))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        val name = tv("WEBO", 18f, cText, true)
        name.gravity = Gravity.CENTER
        c4.addView(name)
        val vt = tv("الإصدار $ver", 12f, cMuted)
        vt.gravity = Gravity.CENTER
        c4.addView(vt)
        val desc = tv("متصفح يلتقط روابط الفيديو (m3u8 / mp4) ويحمّلها ويدمج أجزاءها في ملف واحد داخل مجلد التحميلات.", 12f, cMuted)
        desc.gravity = Gravity.CENTER
        c4.addView(desc, lp(MATCH, WRAP, 0f, 0, 6, 0, 6))
    }

    // ---------- الشاشة الرئيسية (بحث + مواقع + مفضلة + اختصارات) ----------

    private fun newHomeTab() {
        if (prefs().getBoolean("startscreen", true)) newTab("") else newTab(prefs().getString("home", HOME) ?: HOME)
    }

    private fun updateStart() {
        val on = cur?.start == true
        startView.visibility = if (on) View.VISIBLE else View.GONE
        if (on) refreshStart()
    }

    private fun favFile(h: String): java.io.File {
        val dir = java.io.File(cacheDir, "fav")
        dir.mkdirs()
        return java.io.File(dir, h.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
    }

    private fun saveFav(h: String, b: android.graphics.Bitmap) {
        try {
            val f = favFile(h)
            if (f.exists()) {
                val o = android.graphics.BitmapFactory.Options()
                o.inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                if (o.outWidth >= b.width) return
            }
            previewPool.execute {
                try {
                    java.io.FileOutputStream(f).use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } catch (e: Throwable) {
                }
            }
        } catch (e: Throwable) {
        }
    }

    // شعار الموقع: الذاكرة، ثم الملف المحفوظ، فيبقى ظاهراً بعد إعادة تشغيل التطبيق
    private fun favOf(u: String): android.graphics.Bitmap? {
        val h = try { Uri.parse(u).host } catch (e: Throwable) { null }
        val hit = (if (h != null) favCache[h] else null) ?: favCache[hostOf(u)]
        if (hit != null) return hit
        val key = h ?: hostOf(u)
        return try {
            val f = favFile(key)
            if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.also { favCache[key] = it } else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun candThumb(c: Cand): android.graphics.Bitmap? {
        return try {
            if (c.rw.thumbSet) (c.rw.thumb?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun hostOf(u: String): String = Uri.parse(u).host?.removePrefix("www.") ?: u.take(20)

    private fun searchUrl(q: String): String {
        val e = URLEncoder.encode(q, "UTF-8")
        return when (prefs().getString("engine", "google")) {
            "ddg" -> "https://duckduckgo.com/?q=" + e
            "bing" -> "https://www.bing.com/search?q=" + e
            else -> "https://www.google.com/search?q=" + e
        }
    }

    private fun buildStart(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(18), dp(32), dp(18), dp(24))

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo)
        col.addView(logo, lp(dp(76), dp(76)))
        val nm = tv("WEBO", 22f, cText, true)
        nm.gravity = Gravity.CENTER
        col.addView(nm, lp(WRAP, WRAP, 0f, 0, 8, 0, 18))

        // مربع البحث: أيقونة بحث ثم الحقل
        val sb = LinearLayout(this)
        sb.orientation = LinearLayout.HORIZONTAL
        sb.gravity = Gravity.CENTER_VERTICAL
        sb.background = bg(cCard, 26, cLine)
        sb.setPadding(dp(14), 0, dp(14), 0)
        val si = ImageView(this)
        si.setImageResource(R.drawable.ic_search)
        si.setColorFilter(cAccent)
        startBox = EditText(this)
        startBox.setTextColor(cText)
        startBox.setHintTextColor(cMuted)
        startBox.hint = "ابحث أو اكتب رابطاً"
        startBox.textSize = 15f
        startBox.setBackgroundColor(Color.TRANSPARENT)
        startBox.setSingleLine()
        startBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        startBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val go: () -> Unit = {
            val q = startBox.text.toString().trim()
            if (q.isNotEmpty()) {
                startBox.setText("")
                load(q)
            }
        }
        startBox.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                go()
                true
            } else false
        }
        si.setOnClickListener { go() }
        sb.addView(si, lp(dp(24), dp(24)))
        sb.addView(startBox, lp(0, dp(50), 1f, 10, 0, 0, 0))
        col.addView(sb, lp(MATCH, WRAP))

        // أيقونات: المفضلة / الاختصارات / السجل / التحميلات
        val ir = LinearLayout(this)
        ir.orientation = LinearLayout.HORIZONTAL
        ir.gravity = Gravity.CENTER
        fun act(res: Int, label: String, f: () -> Unit) {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER_HORIZONTAL
            val ic = ImageView(this)
            ic.setImageResource(res)
            ic.setColorFilter(cAccent)
            ic.background = bg(cCard, 24, cLine)
            ic.setPadding(dp(12), dp(12), dp(12), dp(12))
            c.addView(ic, lp(dp(48), dp(48)))
            val t = tv(label, 11f, cMuted)
            t.gravity = Gravity.CENTER
            c.addView(t, lp(WRAP, WRAP, 0f, 0, 4, 0, 0))
            c.setOnClickListener { f() }
            ir.addView(c, lp(0, WRAP, 1f))
        }
        act(R.drawable.ic_bookmark, "المفضلة") { showBookmarks() }
        act(R.drawable.ic_add, "الاختصارات") { showShortcuts() }
        act(R.drawable.ic_history, "السجل") { showHistory() }
        col.addView(ir, lp(MATCH, WRAP, 0f, 0, 18, 0, 0))

        // المواقع
        val h1 = LinearLayout(this)
        h1.orientation = LinearLayout.HORIZONTAL
        h1.gravity = Gravity.CENTER_VERTICAL
        h1.addView(tv("المواقع", 15f, cText, true), lp(0, WRAP, 1f))
        col.addView(h1, lp(MATCH, WRAP, 0f, 0, 22, 0, 6))
        startSites = LinearLayout(this)
        startSites.orientation = LinearLayout.VERTICAL
        col.addView(startSites, lp(MATCH, WRAP))

        // المفضلة
        col.addView(tv("المفضلة", 15f, cText, true), lp(MATCH, WRAP, 0f, 0, 22, 0, 6))
        startBm = LinearLayout(this)
        startBm.orientation = LinearLayout.VERTICAL
        col.addView(startBm, lp(MATCH, WRAP))

        sv.addView(col)
        startView = sv
        startView.visibility = View.GONE
        return sv
    }

    private fun siteTile(label: String, onClick: () -> Unit, onLong: (() -> Unit)?, glyph: String = "", url: String = ""): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(2), dp(6), dp(2), dp(6))
        val favIc = if (glyph.isEmpty() && url.isNotEmpty()) favOf(url) else null
        if (favIc != null) {
            // أيقونة الموقع الحقيقية
            val iv = ImageView(this)
            iv.setImageBitmap(favIc)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = if (favIc.width >= 64) dp(8) else dp(11)
            iv.setPadding(pad, pad, pad, pad)
            if (favIc.width < 64) fetchFavFor(url)
            iv.background = bg(Color.WHITE, 26)
            iv.clipToOutline = true
            iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            regFavView(url, iv)
            c.addView(iv, lp(dp(52), dp(52)))
        } else if (glyph.isNotEmpty()) {
            val ch = tv(glyph, 20f, cAccent, true)
            ch.gravity = Gravity.CENTER
            ch.background = bg(cAccentSoft, 26)
            c.addView(ch, lp(dp(52), dp(52)))
        } else {
            // الموقع لا يعرض أيقونته: أيقونة التطبيق، ونحاول جلب أيقونة الموقع نفسه
            val iv = ImageView(this)
            iv.setImageResource(R.drawable.logo)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(8), dp(8), dp(8), dp(8))
            iv.background = bg(Color.WHITE, 26)
            iv.clipToOutline = true
            iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            regFavView(url, iv)
            c.addView(iv, lp(dp(52), dp(52)))
            if (url.isNotEmpty()) fetchFavFor(url)
        }
        val t = tv(label, 12f, cText)
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        c.addView(t, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
        c.setOnClickListener { onClick() }
        if (onLong != null) c.setOnLongClickListener {
            onLong()
            true
        }
        return c
    }

    private val favTried = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private val favUa = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private fun dlBmp(u: String): android.graphics.Bitmap? {
        return try {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", favUa)
            val b = c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            c.disconnect()
            b
        } catch (e: Throwable) {
            null
        }
    }

    // يقرأ وسوم <link rel=...icon> من الصفحة الرئيسية ويرتب الأيقونات من الأكبر إلى الأصغر
    private fun iconLinks(h: String): List<String> {
        val out = ArrayList<Pair<Int, String>>()
        try {
            val c = java.net.URL("https://" + h + "/").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", favUa)
            val base = c.url
            val html = c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(80000)
                val n = r.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
            c.disconnect()
            val opt = RegexOption.IGNORE_CASE
            for (m in Regex("<link[^>]+>", opt).findAll(html)) {
                val tag = m.value
                val rel = Regex("rel\\s*=\\s*[\"']([^\"']+)[\"']", opt).find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
                if (!rel.contains("icon")) continue
                val href = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", opt).find(tag)?.groupValues?.get(1) ?: continue
                if (href.endsWith(".svg", true) || href.startsWith("data:")) continue
                val sz = Regex("sizes\\s*=\\s*[\"'](\\d+)x\\d+", opt).find(tag)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (if (rel.contains("apple")) 180 else 0)
                val full = try { java.net.URL(base, href).toString() } catch (e: Throwable) { continue }
                out.add(Pair(sz, full))
            }
        } catch (e: Throwable) {
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    // يجلب أكبر أيقونة حقيقية متاحة للموقع، ثم favicon.ico كاحتياط، مرة واحدة لكل موقع
    // طابور واحد: الشعارات تُجلب واحداً بعد الآخر
    private val favExec = Executors.newSingleThreadExecutor()

    // أيقونات البطاقات الظاهرة حالياً في الرئيسية، لتحديث الواصل منها فقط
    private val favViews = ArrayList<Pair<String, java.lang.ref.WeakReference<ImageView>>>()

    private fun regFavView(url: String, iv: ImageView) {
        val h = try { Uri.parse(url).host } catch (e: Throwable) { null } ?: return
        favViews.add(Pair(h, java.lang.ref.WeakReference(iv)))
    }

    private fun applyFav(h: String, bmp: android.graphics.Bitmap) {
        val it = favViews.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val iv = e.second.get()
            if (iv == null) { it.remove(); continue }
            if (e.first != h) continue
            iv.setImageBitmap(bmp)
            val pad = if (bmp.width >= 64) dp(8) else dp(11)
            iv.setPadding(pad, pad, pad, pad)
        }
    }

    private fun fetchFavFor(u: String) {
        val h = try { Uri.parse(u).host } catch (e: Throwable) { null }
        if (h == null) return
        // المواقع التي جُرّبت سابقاً لا يُعاد البحث عن شعارها عند كل فتح
        if (prefs().contains("fav_tried_" + h)) return
        if (favTried.putIfAbsent(h, true) != null) return
        favExec.execute {
            try {
                val cands = ArrayList<String>()
                cands.addAll(iconLinks(h).take(3))
                cands.add("https://" + h + "/apple-touch-icon.png")
                cands.add("https://" + h + "/favicon.ico")
                var best: android.graphics.Bitmap? = null
                for (cu in cands) {
                    val b = dlBmp(cu) ?: continue
                    if (b.width < 16) continue
                    if (best == null || b.width > best.width) best = b
                    if (b.width >= 120) break
                }
                val bmp = best
                val old = favCache[h]
                if (bmp != null && (old == null || bmp.width > old.width)) {
                    favCache[h] = bmp
                    saveFav(h, bmp)
                    // تحديث أيقونة هذا الموقع فقط، دون إعادة رسم الشاشة
                    runOnUiThread {
                        try { applyFav(h, bmp) } catch (e: Throwable) { }
                    }
                }
            } catch (e: Throwable) {
            }
            try { prefs().edit().putLong("fav_tried_" + h, System.currentTimeMillis()).apply() } catch (e: Throwable) { }
        }
    }

    private fun tileGrid(parent: LinearLayout, tiles: List<View>) {
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until 4) {
                val v = if (i + k < tiles.size) tiles[i + k] else View(this)
                row.addView(v, lp(0, WRAP, 1f))
            }
            parent.addView(row, lp(MATCH, WRAP))
            i += 4
        }
    }

    private fun confirmDelete(name: String, ok: () -> Unit) {
        StyledBuilder(this)
            .setTitle(name)
            .setMessage("حذف هذا العنصر؟")
            .setPositiveButton("حذف") { _, _ -> ok() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun refreshStart() {
        favViews.clear()
        startSites.removeAllViews()
        startBm.removeAllViews()
        val sc = loadShortcuts()
        val tiles = ArrayList<View>()
        for (i in sc.indices) {
            val p = sc[i]
            tiles.add(siteTile(p.first, { load(p.second) }, {
                confirmDelete(p.first) {
                    val l = loadShortcuts()
                    if (i < l.size) l.removeAt(i)
                    saveShortcuts(l)
                    refreshStart()
                }
            }, "", p.second))
        }
        tiles.add(siteTile("إضافة", { addShortcutDialog() }, null, "+"))
        tileGrid(startSites, tiles)

        val bm = bookmarkList()
        if (bm.isEmpty()) {
            startBm.addView(tv("لا توجد صفحات محفوظة بعد. افتح صفحة ثم احفظها من «الأدوات».", 12f, cMuted))
        } else {
            val bt = ArrayList<View>()
            for (u in bm) bt.add(siteTile(hostOf(u), { load(u) }, { confirmDelete(hostOf(u)) { removeBookmark(u) } }, "", u))
            tileGrid(startBm, bt)
        }
    }

    private fun loadShortcuts(): ArrayList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val raw = prefs().getString("sc", null)
        if (raw == null) {
            out.add(Pair("جوجل", "https://www.google.com"))
            out.add(Pair("يوتيوب", "https://m.youtube.com"))
            out.add(Pair("ويكيبيديا", "https://ar.wikipedia.org"))
            out.add(Pair("تانجو", "https://www.tango.me"))
            out.add(Pair("X", "https://x.com"))
            return out
        }
        try {
            val a = org.json.JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Pair(o.getString("n"), o.getString("u")))
            }
        } catch (e: Throwable) {
        }
        return out
    }

    private fun saveShortcuts(l: List<Pair<String, String>>) {
        val a = org.json.JSONArray()
        for (p in l) {
            val o = org.json.JSONObject()
            o.put("n", p.first)
            o.put("u", p.second)
            a.put(o)
        }
        prefs().edit().putString("sc", a.toString()).apply()
    }

    private fun addShortcutDialog(n0: String = "", u0: String = "") {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(0))
        val n = EditText(this)
        n.hint = "اسم الموقع"
        n.setText(n0)
        n.setSingleLine()
        val u = EditText(this)
        u.hint = "https://..."
        u.setText(u0)
        u.setSingleLine()
        u.layoutDirection = View.LAYOUT_DIRECTION_LTR
        u.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        u.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        box.addView(n, lp(MATCH, WRAP))
        box.addView(u, lp(MATCH, WRAP))
        StyledBuilder(this)
            .setTitle("إضافة اختصار")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val raw = u.text.toString().trim()
                if (raw.isEmpty()) {
                    toast("اكتب الرابط")
                } else {
                    val fu = fix(raw)
                    val name = n.text.toString().trim().ifEmpty { hostOf(fu) }
                    val l = loadShortcuts()
                    l.add(Pair(name, fu))
                    saveShortcuts(l)
                    refreshStart()
                    toast("تمت إضافة الاختصار")
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showShortcuts() {
        val l = loadShortcuts()
        val b = StyledBuilder(this).setTitle("الاختصارات (" + l.size + ")")
        val scHolder = arrayOfNulls<AlertDialog>(1)
        if (l.isEmpty()) b.setMessage("لا توجد اختصارات") else {
            val rows = l.mapIndexed { i, sc ->
                val r = DlgStyle.siteRow(this, sc.first, hostOf(sc.second), favOf(sc.second)) { scHolder[0]?.dismiss(); load(sc.second) }
                r.setOnLongClickListener {
                    scHolder[0]?.dismiss()
                    confirmDelete(l[i].first) {
                        val x = loadShortcuts()
                        if (i < x.size) x.removeAt(i)
                        saveShortcuts(x)
                        refreshStart()
                    }
                    true
                }
                r
            }
            b.setView(DlgStyle.rowsList(this, rows))
        }
        b.setPositiveButton("إضافة") { _, _ -> addShortcutDialog() }
        b.setNegativeButton("رجوع", null)
        scHolder[0] = b.show()
    }

    // ---------- سجل التصفح ----------

    private val histLock = Any()
    @Volatile private var lastHist = ""

    private fun histFile() = File(filesDir, "history.tsv")

    // سجل غير محدود: سطر لكل صفحة في ملف (إضافة في نهاية الملف دون إعادة كتابته)
    private fun addHistory(title: String, url: String) {
        if (!url.startsWith("http")) return
        if (url == lastHist) return
        lastHist = url
        val t = title.replace("\t", " ").replace("\n", " ").replace("\r", " ")
        val line = System.currentTimeMillis().toString() + "\t" + t + "\t" + url + "\n"
        imgPool.execute {
            try {
                synchronized(histLock) { histFile().appendText(line) }
            } catch (e: Throwable) {
            }
        }
    }

    private fun clearHistory() {
        lastHist = ""
        imgPool.execute {
            try {
                synchronized(histLock) { histFile().delete() }
            } catch (e: Throwable) {
            }
        }
    }

    private fun showHistory() {
        val urls = ArrayList<String>()
        val labels = ArrayList<String>()
        val hNames = ArrayList<String>()
        try {
            val f = histFile()
            if (f.exists()) {
                val lines = synchronized(histLock) { f.readLines() }
                for (k in lines.indices.reversed()) {
                    val p = lines[k].split("\t")
                    if (p.size < 3) continue
                    val u = p[2]
                    val t = p[1].ifBlank { hostOf(u) }
                    urls.add(u)
                    labels.add(t.take(50) + "\n" + u.take(60))
                    hNames.add(t.take(50))
                }
            }
        } catch (e: Throwable) {
        }
        val b = StyledBuilder(this).setTitle("سجل التصفح (" + urls.size + ")")
        val hiHolder = arrayOfNulls<AlertDialog>(1)
        if (urls.isEmpty()) b.setMessage("السجل فارغ") else {
            val rows = urls.mapIndexed { i, hu ->
                DlgStyle.siteRow(this, hNames[i], hu.take(60), favOf(hu)) { hiHolder[0]?.dismiss(); load(hu) }
            }
            b.setView(DlgStyle.rowsList(this, rows))
        }
        if (urls.isNotEmpty()) b.setNeutralButton("مسح السجل") { _, _ ->
            clearHistory()
            toast("تم مسح السجل")
        }
        b.setNegativeButton("رجوع", null)
        hiHolder[0] = b.show()
    }

    // ---------- البحث داخل الصفحة ----------

    private fun buildFindBar(): View {
        findBar = LinearLayout(this)
        findBar.orientation = LinearLayout.HORIZONTAL
        findBar.gravity = Gravity.CENTER_VERTICAL
        findBar.setBackgroundColor(cBar)
        findBar.setPadding(dp(8), dp(4), dp(8), dp(4))
        findBar.visibility = View.GONE
        findBox = EditText(this)
        findBox.setTextColor(cText)
        findBox.setHintTextColor(cMuted)
        findBox.hint = "بحث داخل الصفحة"
        findBox.textSize = 14f
        findBox.setSingleLine()
        findBox.background = bg(cCard, 18, cLine)
        findBox.setPadding(dp(12), dp(6), dp(12), dp(6))
        findBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        findBox.setOnEditorActionListener { _, _, _ ->
            cur?.web?.findNext(true)
            true
        }
        findBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                val w = cur?.web
                if (q.isEmpty()) {
                    w?.clearMatches()
                    findCount.text = ""
                } else w?.findAllAsync(q)
            }
        })
        findCount = tv("", 12f, cMuted)
        findCount.minWidth = dp(34)
        findCount.gravity = Gravity.CENTER
        val up = pill("▲", false) { cur?.web?.findNext(false) }
        val dn = pill("▼", false) { cur?.web?.findNext(true) }
        up.setPadding(dp(10), dp(6), dp(10), dp(6))
        dn.setPadding(dp(10), dp(6), dp(10), dp(6))
        findBar.addView(findBox, lp(0, WRAP, 1f))
        findBar.addView(findCount)
        findBar.addView(up, lp(WRAP, WRAP, 0f, 2, 0, 2, 0))
        findBar.addView(dn, lp(WRAP, WRAP, 0f, 2, 0, 2, 0))
        findBar.addView(iconBtn(R.drawable.ic_close) { closeFind() }, lp(dp(36), dp(36)))
        return findBar
    }

    private fun openFind() {
        showPage(0)
        findBar.visibility = View.VISIBLE
        findBox.requestFocus()
        findBox.postDelayed({
            val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            im.showSoftInput(findBox, 0)
        }, 150)
    }

    private fun closeFind() {
        findBox.setText("")
        findCount.text = ""
        cur?.web?.clearMatches()
        findBar.visibility = View.GONE
        hideKb()
    }

    // ---------- المتصفح والتبويبات ----------

    private fun hasTab(v: WebView): Tab? = tabs.firstOrNull { it.web === v }

    private fun updateLoadUi() {
        val t = cur ?: return
        if (t.loading && t.progress < 100) {
            loadBar.visibility = View.VISIBLE
            loadBar.progress = maxOf(t.progress, 5)
            pct.text = "" + t.progress + "%"
            pct.visibility = View.VISIBLE
            reloadBtn.setImageResource(R.drawable.ic_close)
        } else {
            loadBar.progress = 100
            pct.visibility = View.GONE
            reloadBtn.setImageResource(R.drawable.ic_refresh)
            ui.postDelayed({
                val c = cur
                if (c == null || !c.loading || c.progress >= 100) loadBar.visibility = View.INVISIBLE
            }, 600)
        }
    }

    private fun makeWeb(): WebView {
        val w = WebView(this)
        applyWebSettings(w)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "TDL")
        // حقن مبكر في كل الإطارات (بما فيها iframe) قبل أي سكربت للصفحة، فلا يفوتنا fetch/XHR أثناء التحميل
        try {
            if (prefs().getBoolean("overlay", true) &&
                androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, hookJs(), setOf("*"))
            }
        } catch (e: Throwable) {
            logErr("التقاط", "تعذر تفعيل الحقن المبكر: " + (e.message ?: e.javaClass.simpleName), e)
        }
        w.setOnLongClickListener { v -> onWebLongPress(v as WebView) }
        w.setFindListener { active, count, _ ->
            if (cur?.web === w) findCount.text = if (count > 0) "" + (active + 1) + "/" + count else "0"
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                val t = hasTab(v) ?: return
                t.progress = p
                if (p >= 20 && !t.injected) {
                    t.injected = true
                    if (prefs().getBoolean("overlay", true)) v.evaluateJavascript(overlayJs(), null)
                }
                if (cur === t) updateLoadUi()
            }

            override fun onReceivedIcon(v: WebView, icon: android.graphics.Bitmap?) {
                val t = hasTab(v) ?: return
                if (icon == null) return
                t.icon = icon
                val h = try { Uri.parse(v.url ?: "").host } catch (e: Throwable) { null }
                if (h != null) {
                    val old = favCache[h]
                    if (old == null || icon.width >= old.width) {
                        favCache[h] = icon
                        saveFav(h, icon)
                    }
                }
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                val t = hasTab(v) ?: return
                t.title = title ?: ""
                relabel()
            }

            // رفع الملفات من المواقع (الصور والمستندات والكاميرا)
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(p.createIntent(), 77)
                    true
                } catch (e: Throwable) {
                    fileCb = null
                    toast("تعذر فتح منتقي الملفات")
                    false
                }
            }

            // الكاميرا والميكروفون للمواقع
            override fun onPermissionRequest(r: PermissionRequest) {
                ui.post { askWebPermission(r) }
            }

            // الموقع الجغرافي للمواقع
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    geoCb = cb
                    geoOrigin = origin
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 78)
                } else cb.invoke(origin, true, false)
            }

            // الفيديو بملء الشاشة
            override fun onShowCustomView(view: View, cb: WebChromeClient.CustomViewCallback) {
                showFull(view, cb)
            }

            override fun onHideCustomView() {
                hideFull()
            }

            // النوافذ المنبثقة (window.open) تُفتح في تبويب جديد
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val msg = resultMsg ?: return false
                val tr = msg.obj as? WebView.WebViewTransport ?: return false
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(x: WebView, r: WebResourceRequest): Boolean {
                        val u = r.url.toString()
                        ui.post { newTab(u, true) }
                        try { x.destroy() } catch (e: Throwable) {}
                        return true
                    }
                }
                tr.webView = tmp
                msg.sendToTarget()
                return true
            }
        }
        // تحميل أي ملف من أي موقع (zip و apk و pdf وغيرها) بمحرك التطبيق السريع
        w.setDownloadListener { url, ua, cd, mime, len -> ui.post { onWebDownload(url, ua, cd, mime, len) } }
        w.webViewClient = object : WebViewClient() {
            // يعمل على خيط الشبكة: لا نلمس WebView هنا، كل شيء من نفس الطلب
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                return try {
                    intercept(v, r)
                } catch (e: Throwable) {
                    null // أي خطأ هنا كان يوقف التطبيق كله؛ نترك الطلب يمر طبيعياً
                }
            }

            private fun intercept(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                val low = u.lowercase()
                val h = HashMap<String, String>()
                h["User-Agent"] = hdr(r.requestHeaders, "User-Agent") ?: curUa
                h["Referer"] = hdr(r.requestHeaders, "Referer") ?: pages[v] ?: ""
                h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: hdr(r.requestHeaders, "Cookie") ?: ""
                val origin = hdr(r.requestHeaders, "Origin")
                if (origin != null) h["Origin"] = origin
                val accept = (hdr(r.requestHeaders, "Accept") ?: "").lowercase()
                val pathOnly = low.substringBefore('?').substringBefore('#')
                if (low.contains(".m3u8") || low.contains("mpegurl") || accept.contains("mpegurl") ||
                    accept.contains("dash+xml") || pathOnly.endsWith(".mpd") ||
                    videoExt.any { pathOnly.endsWith(it) } || low.contains(".mp4") ||
                    (capAudio() && audioExt.any { pathOnly.endsWith(it) }) || (capSeg() && segExt.any { pathOnly.endsWith(it) })) {
                    ui.post { consider(u, h, v) }
                    return null
                }
                if (adBlock) {
                    val host = r.url.host?.lowercase() ?: ""
                    for (a in adHosts) {
                        if (host.contains(a)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                }
                if (rcWanted(r, low, accept)) {
                    val rs = rcServe(u, h)
                    if (rs != null) return rs
                }
                // نوع المحتوى mpegurl: فحص رأس الرد للطلبات التي لا تبدو ملفات ثابتة
                if (r.method == "GET" && !r.isForMainFrame && (r.url.scheme == "http" || r.url.scheme == "https") &&
                    staticExt.none { low.substringBefore('?').endsWith(it) }) {
                    probe(u, h, v)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = (r.url.scheme ?: "").lowercase()
                if (s == "http" || s == "https" || s == "about" || s == "file" || s == "content" || s == "data" || s == "blob" || s == "javascript") return false
                try {
                    val i = if (s == "intent") Intent.parseUri(r.url.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, r.url)
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    startActivity(i)
                } catch (e: Throwable) {
                }
                return true
            }

            override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) {
                pages[v] = url
                val t = hasTab(v) ?: return
                t.lastUrl = url.substringBefore('#')
                t.scanning = false
                pruneTab(t)
                if (t.start) {
                    t.start = false
                    if (cur === t) updateStart()
                }
                t.startBack = false
                t.loading = true
                t.injected = false
                t.progress = 5
                if (prefs().getBoolean("overlay", true)) v.evaluateJavascript(hookJs(), null)
                val pto = prefs().getInt("pgtimeout", 0)
                if (pto > 0) {
                    val key = url.substringBefore('#')
                    ui.postDelayed({
                        if (tabs.contains(t) && t.loading && t.lastUrl == key && t.progress < 100) {
                            try { t.web.stopLoading() } catch (e: Throwable) {}
                            t.loading = false
                            if (cur === t) {
                                updateLoadUi()
                                toast("تم إيقاف تحميل الصفحة لتجاوز المهلة")
                            }
                        }
                    }, pto * 1000L)
                }
                if (cur === t) {
                    urlBox.setText(url)
                    updateLoadUi()
                }
            }

            // صفحات تغيّر عنوانها بدون إعادة تحميل (SPA): نمسح روابط الفيديو غير المبدوءة للصفحة السابقة
            override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) {
                val t = hasTab(v) ?: return
                val nu = url.substringBefore('#')
                if (!isReload && t.lastUrl.isNotEmpty() && nu != t.lastUrl) {
                    t.lastUrl = nu
                    if (!t.scanning) pruneTab(t)
                } else if (t.lastUrl.isEmpty()) {
                    t.lastUrl = nu
                }
            }

            override fun onPageFinished(v: WebView, url: String) {
                val t = hasTab(v) ?: return
                t.loading = false
                t.progress = 100
                t.title = v.title ?: t.title
                addHistory(t.title, url)
                relabel()
                if (cur === t) updateLoadUi()
                CookieManager.getInstance().flush()
                if (t.restoring) {
                    t.restoring = false
                    v.settings.cacheMode = baseCache()
                }
                if (prefs().getBoolean("overlay", true)) v.evaluateJavascript(overlayJs(), null)
                runScripts(v, url)
            }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) {
                    val t = hasTab(v)
                    if (t != null) {
                        t.loading = false
                        if (cur === t) updateLoadUi()
                    }
                    toast("تعذر تحميل الصفحة: " + e.description)
                    logErr("صفحة", "تعذر تحميل " + r.url + ": " + e.description)
                }
            }

            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, resp: WebResourceResponse) {
                if (r.isForMainFrame && resp.statusCode >= 400) logErr("صفحة", "خطأ HTTP " + resp.statusCode + " في " + r.url)
            }

            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                val t = hasTab(v)
                val u = pages[v] ?: HOME
                logErr("WebView", "تعطلت عملية عرض الصفحة " + (if (d.didCrash()) "(انهيار)" else "(نفاد الذاكرة)") + ": " + u)
                if (t != null) ui.post { recoverTab(t, v, u) }
                return true
            }
        }
        return w
    }

    // استبدال WebView المنهار داخل نفس التبويب بدل إغلاق التبويبات (كل التبويبات تشترك في عملية عرض واحدة)
    private fun recoverTab(t: Tab, dead: WebView, url: String) {
        if (t.web !== dead || !tabs.contains(t)) return
        val wasCur = cur === t
        val idx = container.indexOfChild(dead)
        pages.remove(dead)
        try { container.removeView(dead) } catch (e: Throwable) {}
        try { dead.destroy() } catch (e: Throwable) {}
        val nw = makeWeb()
        t.web = nw
        t.loading = false
        t.progress = 0
        t.injected = false
        container.addView(nw, if (idx >= 0) idx else container.childCount, FrameLayout.LayoutParams(MATCH, MATCH))
        nw.visibility = if (wasCur) View.VISIBLE else View.GONE
        val now = System.currentTimeMillis()
        val loop = now - t.lastCrash < 4000 // انهيار متكرر: لا نعيد التحميل تلقائياً لتجنب حلقة انهيار
        t.lastCrash = now
        if (url.startsWith("http") && !loop) {
            if (wasCur) nw.loadUrl(url) else t.pendingUrl = url
        } else {
            toast("تعذر عرض الصفحة بسبب نقص الذاكرة. أغلق بعض التبويبات وأعد المحاولة")
        }
        if (wasCur) {
            urlBox.setText(url)
            updateLoadUi()
        }
        relabel()
    }

    private fun buildChip(t: Tab) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(12), dp(4), dp(4), dp(4))
        val l = tv("", 12f, cText)
        l.maxLines = 1
        l.ellipsize = TextUtils.TruncateAt.END
        l.maxWidth = dp(110)
        val x = ImageView(this)
        x.setImageResource(R.drawable.ic_close)
        x.setColorFilter(cMuted)
        x.setPadding(dp(5), dp(5), dp(5), dp(5))
        x.setOnClickListener { closeTab(t) }
        c.addView(l, lp(WRAP, WRAP))
        c.addView(x, lp(dp(24), dp(24)))
        c.setOnClickListener {
            switchTo(t)
            showPage(0)
        }
        c.layoutParams = lp(WRAP, WRAP, 0f, 3, 3, 3, 3)
        t.chip = c
        t.label = l
        t.close = x
    }

    private fun newTab(url: String, popup: Boolean = false) {
        val t = Tab(makeWeb())
        t.popup = popup
        buildChip(t)
        if (url.isBlank()) t.start = true
        tabs.add(t)
        container.addView(t.web, FrameLayout.LayoutParams(MATCH, MATCH))
        tabsRow.addView(t.chip, tabsRow.childCount - 1)
        relabel()
        switchTo(t)
        showPage(0)
        if (url.isNotBlank()) t.web.loadUrl(fix(url))
        saveTabsPrefs()
    }

    private fun removeTab(t: Tab) {
        snapshot(t)
        tabs.remove(t)
        container.removeView(t.web)
        tabsRow.removeView(t.chip)
        pages.remove(t.web)
        try { t.web.destroy() } catch (e: Throwable) {}
    }

    private fun closeTab(t: Tab) {
        if (tabs.size <= 1) {
            toast("هذا آخر تبويب")
            return
        }
        val i = tabs.indexOf(t)
        val wasCur = cur === t
        removeTab(t)
        relabel()
        if (wasCur) switchTo(tabs[if (i > 0) i - 1 else 0])
        saveTabsPrefs()
    }

    private fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices) return
        val t = tabs.removeAt(from)
        val ti = to.coerceIn(0, tabs.size)
        tabs.add(ti, t)
        tabsRow.removeView(t.chip)
        tabsRow.addView(t.chip, ti)
        relabel()
    }

    // ================= شاشة التبويبات (النمط الجديد) =================

    private class SwClosed(val t: Tab, val i: Int)

    private lateinit var swRoot: FrameLayout
    private lateinit var swCount: TextView
    private lateinit var swQuery: EditText
    private lateinit var swHint: TextView
    private lateinit var swScroll: ScrollView
    private lateinit var swList: LinearLayout
    private lateinit var swSelBar: LinearLayout
    private lateinit var swDel: TextView
    private lateinit var swAll: TextView
    private lateinit var swMenu: LinearLayout
    private lateinit var swMenuBtn: TextView
    private lateinit var swSnack: LinearLayout
    private lateinit var swSnackTxt: TextView
    private lateinit var swSnackBtn: TextView
    private var swShown = false
    private var swSelMode = false
    private var swDragging = false
    private val swSel = HashSet<Tab>()
    private val swClosed = ArrayList<SwClosed>()
    private val swCells = ArrayList<Pair<View, Int>>()
    private val swTg = HashMap<Int, View>()
    private val swHideSnack = Runnable {
        swSnack.visibility = View.GONE
        swFinalize()
    }
    private val favCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val SW_HINT = "اضغط مطولاً على أي بطاقة ثم اسحبها فوق بطاقة أخرى لتغيير الترتيب"

    private fun swC2() = Color.parseColor(if (dark) "#2B2E33" else "#E6EAF0")
    private fun swCs() = Color.parseColor(if (dark) "#A8C7FA" else "#CFE0FB")
    private fun swOns() = Color.parseColor("#0B1B33")
    private fun swOnAcc() = Color.parseColor(if (dark) "#0B1B33" else "#FFFFFF")
    private fun swAcc() = Color.parseColor(if (dark) "#5B9BFF" else "#2F6FE0")

    private fun nAr(n: Int): String {
        val sb = StringBuilder()
        for (ch in n.toString()) sb.append(if (ch in '0'..'9') ('٠' + (ch - '0')) else ch)
        return sb.toString()
    }

    private fun swGd(color: Int, r: Int, sw: Int = 0, sc: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (sw > 0) g.setStroke(dp(sw), sc)
        return g
    }

    private fun swOval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    private fun swPress(normal: android.graphics.drawable.Drawable, pressed: Int, r: Int, oval: Boolean = false): android.graphics.drawable.StateListDrawable {
        val s = android.graphics.drawable.StateListDrawable()
        val p = GradientDrawable()
        p.setColor(pressed)
        if (oval) p.shape = GradientDrawable.OVAL else p.cornerRadius = dp(r).toFloat()
        s.addState(intArrayOf(android.R.attr.state_pressed), p)
        s.addState(intArrayOf(), normal)
        return s
    }

    private fun swHit(v: View, x: Float, y: Float): Boolean {
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return x >= l[0] && x <= l[0] + v.width && y >= l[1] && y <= l[1] + v.height
    }

    private fun buildSwitcher(): View {
        swRoot = object : FrameLayout(this@MainActivity) {
            override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (!swShown) return false
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && swMenu.visibility == View.VISIBLE) {
                    if (!swHit(swMenu, ev.rawX, ev.rawY) && !swHit(swMenuBtn, ev.rawX, ev.rawY)) swMenu.visibility = View.GONE
                }
                return super.dispatchTouchEvent(ev)
            }
        }
        swRoot.layoutDirection = View.LAYOUT_DIRECTION_RTL
        swRoot.setBackgroundColor(cBg)
        swRoot.isClickable = true
        swRoot.visibility = View.GONE
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        swRoot.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // الشريط العلوي: + ، العدّاد ، ⋮
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(12), dp(10), dp(12), dp(10))
        val plus = tv("+", 28f, swOnAcc())
        plus.gravity = Gravity.CENTER
        plus.includeFontPadding = false
        plus.background = swPress(swGd(swAcc(), 14), Color.parseColor("#33000000"), 14)
        plus.setOnClickListener {
            closeSw()
            newTab("")
        }
        top.addView(plus, lp(dp(44), dp(44)))
        top.addView(View(this), lp(0, 1, 1f))
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.background = swGd(cCard, 16, 1, cLine)
        pill.setPadding(dp(4), dp(4), dp(4), dp(4))
        swCount = tv("١", 18f, cText, true)
        swCount.gravity = Gravity.CENTER
        swCount.includeFontPadding = false
        swCount.background = swGd(cLine, 12)
        pill.addView(swCount, lp(dp(52), dp(36)))
        top.addView(pill, lp(WRAP, WRAP))
        top.addView(View(this), lp(0, 1, 1f))
        swMenuBtn = tv("⋮", 20f, cText)
        swMenuBtn.gravity = Gravity.CENTER
        swMenuBtn.includeFontPadding = false
        swMenuBtn.background = swPress(ColorDrawable(Color.TRANSPARENT), cLine, 12)
        swMenuBtn.setOnClickListener {
            swMenu.visibility = if (swMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        top.addView(swMenuBtn, lp(dp(38), dp(38)))
        col.addView(top, lp(MATCH, WRAP))

        // البحث
        swQuery = EditText(this)
        swQuery.hint = "البحث في علامات التبويب"
        swQuery.setHintTextColor(cMuted)
        swQuery.setTextColor(cText)
        swQuery.textSize = 15f
        swQuery.background = swGd(cLine, 22)
        swQuery.setPadding(dp(18), 0, dp(18), 0)
        swQuery.isSingleLine = true
        swQuery.layoutDirection = View.LAYOUT_DIRECTION_RTL
        swQuery.textDirection = View.TEXT_DIRECTION_RTL
        swQuery.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        swQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (swShown) swDraw(false)
            }
        })
        col.addView(swQuery, lp(MATCH, dp(44), 0f, 12, 0, 12, 0))

        swHint = tv(SW_HINT, 12f, cMuted)
        swHint.textDirection = View.TEXT_DIRECTION_RTL
        swHint.setPadding(dp(14), dp(8), dp(14), 0)
        col.addView(swHint, lp(MATCH, WRAP))

        // الشبكة
        swScroll = ScrollView(this)
        swScroll.isFillViewport = true
        swScroll.clipChildren = false
        swScroll.clipToPadding = false
        swList = LinearLayout(this)
        swList.orientation = LinearLayout.VERTICAL
        swList.clipChildren = false
        swList.setOnClickListener { if (!swSelMode) closeSw() }
        swScroll.addView(swList, FrameLayout.LayoutParams(MATCH, MATCH))
        col.addView(swScroll, lp(MATCH, 0, 1f))

        // شريط التحديد
        swSelBar = LinearLayout(this)
        swSelBar.orientation = LinearLayout.VERTICAL
        swSelBar.setBackgroundColor(cCard)
        swSelBar.visibility = View.GONE
        val line = View(this)
        line.setBackgroundColor(cLine)
        swSelBar.addView(line, lp(MATCH, dp(1)))
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.setPadding(dp(8), dp(10), dp(8), dp(10))
        fun sbtn(txt: String): TextView {
            val b = tv(txt, 14f, cText)
            b.gravity = Gravity.CENTER
            b.background = swGd(cLine, 14)
            btns.addView(b, lp(0, dp(44), 1f, 4, 0, 4, 0))
            return b
        }
        val sc = sbtn("إلغاء")
        swAll = sbtn("تحديد الكل")
        swDel = sbtn("حذف (٠)")
        swDel.background = swGd(Color.parseColor("#C62828"), 14)
        swDel.setTextColor(Color.WHITE)
        sc.setOnClickListener { swEndSel() }
        swAll.setOnClickListener {
            if (swSel.size == tabs.size) swSel.clear() else { swSel.clear(); swSel.addAll(tabs) }
            swDraw()
        }
        swDel.setOnClickListener { swDelSel() }
        swSelBar.addView(btns, lp(MATCH, WRAP))
        col.addView(swSelBar, lp(MATCH, WRAP))

        // قائمة ⋮
        swMenu = LinearLayout(this)
        swMenu.orientation = LinearLayout.VERTICAL
        swMenu.background = swGd(cCard, 14, 1, cLine)
        swMenu.setPadding(dp(6), dp(6), dp(6), dp(6))
        swMenu.minimumWidth = dp(190)
        swMenu.elevation = dp(8).toFloat()
        swMenu.visibility = View.GONE
        fun mitem(txt: String, f: () -> Unit) {
            val b = tv(txt, 14f, cText)
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.textDirection = View.TEXT_DIRECTION_RTL
            b.setPadding(dp(14), dp(12), dp(14), dp(12))
            b.background = swPress(ColorDrawable(Color.TRANSPARENT), cLine, 10)
            b.setOnClickListener {
                swMenu.visibility = View.GONE
                f()
            }
            swMenu.addView(b, lp(MATCH, WRAP))
        }
        mitem("إغلاق الأخرى") { swCloseOthers() }
        mitem("إغلاق الكل") { swCloseAll() }
        mitem("تحديد للحذف") { swStartSel() }
        val mlp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.LEFT)
        mlp.topMargin = dp(56)
        mlp.leftMargin = dp(10)
        swRoot.addView(swMenu, mlp)

        // شريط «تراجع»
        swSnack = LinearLayout(this)
        swSnack.orientation = LinearLayout.HORIZONTAL
        swSnack.gravity = Gravity.CENTER_VERTICAL
        swSnack.layoutDirection = View.LAYOUT_DIRECTION_RTL
        swSnack.background = swGd(Color.parseColor("#E6E8EC"), 14)
        swSnack.setPadding(dp(14), dp(12), dp(14), dp(12))
        swSnack.elevation = dp(10).toFloat()
        swSnack.visibility = View.GONE
        swSnackTxt = tv("", 14f, Color.parseColor("#14161A"))
        swSnackTxt.textDirection = View.TEXT_DIRECTION_RTL
        swSnack.addView(swSnackTxt, lp(0, WRAP, 1f))
        swSnackBtn = tv("", 14f, Color.parseColor("#1A4A8A"), true)
        swSnack.addView(swSnackBtn, lp(WRAP, WRAP, 0f, 10, 0, 0, 0))
        return swRoot
    }

    private fun swOpen() = swShown

    private fun openSw() {
        hideKb()
        closeNavMenu()
        cur?.let { swCapture(it) }
        swQuery.setText("")
        swSelMode = false
        swSel.clear()
        swMenu.visibility = View.GONE
        swDraw(false)
        swRoot.animate().cancel()
        swRoot.visibility = View.VISIBLE
        swRoot.alpha = 0f
        swRoot.scaleX = 0.93f
        swRoot.scaleY = 0.93f
        swRoot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
        swShown = true
        swScroll.post { swCenterCur() }
    }

    private fun closeSw() {
        swSelMode = false
        swSel.clear()
        swMenu.visibility = View.GONE
        hideKb()
        swShown = false
        swRoot.animate().cancel()
        swRoot.animate().alpha(0f).scaleX(0.93f).scaleY(0.93f).setDuration(200)
            .withEndAction { if (!swShown) swRoot.visibility = View.GONE }.start()
        if (tabs.isEmpty()) newHomeTab()
    }

    private fun swCenterCur() {
        val e = swCells.firstOrNull { tabs.getOrNull(it.second) === cur } ?: return
        val cell = e.first
        val row = cell.parent as? View ?: return
        val y = row.top + cell.top - (swScroll.height - cell.height) / 2
        swScroll.scrollTo(0, maxOf(0, y))
    }

    private fun swPick(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        closeSw()
        switchTo(t)
        showPage(0)
    }

    private fun swTitle(t: Tab): String {
        if (t.start) return "تبويب جديد"
        val u = t.web.url ?: t.pendingUrl ?: return "تبويب جديد"
        return if (t.title.isNotBlank()) t.title else u.replace(Regex("^https?://"), "")
    }

    private fun swKey(t: Tab): String {
        if (t.start) return ""
        return (t.web.url ?: t.pendingUrl ?: "").replace(Regex("^https?://"), "")
    }

    private fun swHost(t: Tab): String {
        if (t.start) return ""
        return try { Uri.parse(t.web.url ?: t.pendingUrl ?: "").host ?: "" } catch (e: Throwable) { "" }
    }

    // الدائرة الصغيرة = شعار الموقع المفتوح
    private fun swFav(t: Tab): View {
        val fl = FrameLayout(this)
        val host = swHost(t)
        val ic = if (host.isEmpty()) null else (favCache[host] ?: t.icon)
        if (host.isEmpty()) {
            fl.background = swOval(Color.parseColor("#546E7A"))
            val x = tv("+", 11f, Color.WHITE)
            x.gravity = Gravity.CENTER
            x.includeFontPadding = false
            fl.addView(x, FrameLayout.LayoutParams(MATCH, MATCH))
        } else if (ic != null) {
            fl.background = swOval(Color.WHITE)
            val iv = ImageView(this)
            iv.setImageBitmap(ic)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(3), dp(3), dp(3), dp(3))
            fl.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            // الموقع لا يعرض أيقونته: نضع أيقونة التطبيق
            fl.background = swOval(Color.WHITE)
            val iv = ImageView(this)
            iv.setImageResource(R.drawable.logo)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(3), dp(3), dp(3), dp(3))
            fl.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        return fl
    }

    private fun swUpdCount() {
        swCount.text = nAr(tabs.size)
    }

    private fun swSelUI() {
        swSelBar.visibility = if (swSelMode) View.VISIBLE else View.GONE
        swHint.text = if (swSelMode) "اضغط على البطاقات لتحديدها أو إلغاء تحديدها ثم اضغط حذف" else SW_HINT
        swDel.text = "حذف (" + nAr(swSel.size) + ")"
        swDel.isEnabled = swSel.isNotEmpty()
        swDel.alpha = if (swSel.isNotEmpty()) 1f else 0.4f
        swAll.text = if (tabs.isNotEmpty() && swSel.size == tabs.size) "إلغاء تحديد الكل" else "تحديد الكل"
    }

    private fun swDraw(keep: Boolean = true) {
        val sy = swScroll.scrollY
        swList.removeAllViews()
        swCells.clear()
        swTg.clear()
        val q = swQuery.text.toString().trim()
        val idx = ArrayList<Int>()
        for (i in tabs.indices) if (q.isEmpty() || (swTitle(tabs[i]) + swKey(tabs[i])).contains(q, true)) idx.add(i)
        val a = if (swRoot.width > 0) swRoot.width else resources.displayMetrics.widthPixels
        val gp = minOf(a * 0.044f, dp(23).toFloat()).toInt()
        swList.setPadding(gp, gp, gp, 1)
        if (idx.isEmpty()) {
            val e = tv(if (tabs.isNotEmpty()) "لا توجد نتائج" else "لا توجد تبويبات مفتوحة", 16f, cMuted)
            e.gravity = Gravity.CENTER
            e.setPadding(dp(30), dp(30), dp(30), dp(30))
            e.setOnClickListener { closeSw() }
            swList.addView(e, lp(MATCH, WRAP))
        } else {
            val cw = (a - 3 * gp) / 2
            var k = 0
            while (k < idx.size) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.layoutDirection = View.LAYOUT_DIRECTION_RTL
                row.clipChildren = false
                row.addView(swCard(idx[k], cw), LinearLayout.LayoutParams(cw, WRAP))
                if (k + 1 < idx.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(gp, 1))
                    row.addView(swCard(idx[k + 1], cw), LinearLayout.LayoutParams(cw, WRAP))
                }
                val rl = LinearLayout.LayoutParams(MATCH, WRAP)
                rl.bottomMargin = gp
                swList.addView(row, rl)
                k += 2
            }
        }
        swUpdCount()
        swSelUI()
        if (keep) swScroll.post { swScroll.scrollTo(0, sy) }
    }

    private fun swCard(i: Int, cw: Int): View {
        val t = tabs[i]
        val isCur = t === cur
        val sel = swSel.contains(t)
        val c2 = swC2()
        val cs = swCs()
        val ons = swOns()
        val txt = if (isCur) ons else cText
        val border = if (isCur && sel) ons else if (sel || isCur) cs else c2

        val cell = FrameLayout(this)
        cell.clipChildren = false
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = swGd(if (isCur) cs else c2, 22, 2, border)
        card.clipToOutline = true
        card.setPadding(dp(2), dp(2), dp(2), dp(2))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(12), dp(10), dp(12), dp(10))
        if (swSelMode) {
            val ck = FrameLayout(this)
            if (sel) {
                val fill = if (isCur) ons else cs
                ck.background = swGd(fill, 11, 2, fill).also { it.shape = GradientDrawable.OVAL }
                val m = tv("✓", 14f, if (isCur) cs else ons, true)
                m.gravity = Gravity.CENTER
                m.includeFontPadding = false
                ck.addView(m, FrameLayout.LayoutParams(MATCH, MATCH))
            } else {
                ck.background = GradientDrawable().also {
                    it.shape = GradientDrawable.OVAL
                    it.setColor(Color.TRANSPARENT)
                    it.setStroke(dp(2), txt)
                }
            }
            val p = LinearLayout.LayoutParams(dp(22), dp(22))
            p.marginEnd = dp(8)
            head.addView(ck, p)
        }
        val fp = LinearLayout.LayoutParams(dp(22), dp(22))
        fp.marginEnd = dp(8)
        head.addView(swFav(t), fp)
        val tt = tv(swTitle(t), 15f, txt)
        tt.maxLines = 1
        tt.ellipsize = TextUtils.TruncateAt.END
        tt.textDirection = View.TEXT_DIRECTION_RTL
        tt.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val tp = LinearLayout.LayoutParams(0, WRAP, 1f)
        tp.marginEnd = dp(8)
        head.addView(tt, tp)
        if (!swSelMode) {
            val x = tv("✕", 20f, txt)
            x.gravity = Gravity.CENTER
            x.includeFontPadding = false
            x.background = swPress(ColorDrawable(Color.TRANSPARENT), Color.parseColor("#33000000"), 0, true)
            x.setOnClickListener { swCloseAt(i) }
            head.addView(x, LinearLayout.LayoutParams(dp(32), dp(32)))
        }
        card.addView(head, lp(MATCH, WRAP))

        // المعاينة المصغّرة للصفحة
        val thmW = cw - dp(14)
        val thmH = (thmW * 1.17f).toInt()
        val thm = FrameLayout(this)
        val bm = t.thumb
        thm.background = swGd(if (bm != null) cBg else if (t.start) cBg else Color.WHITE, 17)
        thm.clipToOutline = true
        if (bm != null && bm.width > 0) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.MATRIX
            iv.setImageBitmap(bm)
            val m = android.graphics.Matrix()
            val s = thmW.toFloat() / bm.width
            m.setScale(s, s)
            iv.imageMatrix = m
            thm.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        card.addView(thm, lp(thmW, thmH, 0f, 5, 0, 5, 5))

        cell.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        val tg = View(this)
        val g = GradientDrawable()
        g.setColor(Color.TRANSPARENT)
        g.cornerRadius = dp(27).toFloat()
        g.setStroke(dp(3), swAcc(), dp(8).toFloat(), dp(4).toFloat())
        tg.background = g
        tg.visibility = View.GONE
        val tl = FrameLayout.LayoutParams(MATCH, MATCH)
        tl.setMargins(-dp(5), -dp(5), -dp(5), -dp(5))
        cell.addView(tg, tl)
        swCells.add(Pair(cell, i))
        swTg[i] = tg
        card.setOnTouchListener(swTouch(t, i, cell, card))
        return cell
    }

    // ضغط = فتح التبويب، ضغط مطوّل ثم سحب فوق بطاقة أخرى = تغيير الترتيب
    private fun swTouch(t: Tab, i: Int, cell: View, card: View): View.OnTouchListener {
        var sx = 0f
        var sy = 0f
        var on = false
        var moved = false
        var to = -1
        val lpr = Runnable {
            on = true
            swDragging = true
            card.alpha = 0.9f
            card.scaleX = 1.04f
            card.scaleY = 1.04f
            card.translationZ = dp(12).toFloat()
            cell.translationZ = dp(12).toFloat()
            (cell.parent as? View)?.translationZ = dp(12).toFloat()
            card.parent?.requestDisallowInterceptTouchEvent(true)
            card.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        return View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX
                    sy = ev.rawY
                    on = false
                    moved = false
                    to = -1
                    if (!swSelMode) ui.postDelayed(lpr, 350)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!on) {
                        if (Math.hypot((ev.rawX - sx).toDouble(), (ev.rawY - sy).toDouble()) > dp(10)) {
                            moved = true
                            ui.removeCallbacks(lpr)
                        }
                    } else {
                        card.translationX = ev.rawX - sx
                        card.translationY = ev.rawY - sy
                        var hit = -1
                        for (pr in swCells) {
                            if (pr.first === cell) continue
                            if (swHit(pr.first, ev.rawX, ev.rawY)) {
                                hit = pr.second
                                break
                            }
                        }
                        if (hit != to) {
                            if (to >= 0) swTg[to]?.visibility = View.GONE
                            if (hit >= 0) swTg[hit]?.visibility = View.VISIBLE
                            to = hit
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(lpr)
                    if (on) {
                        swDragging = false
                        if (to >= 0) moveTab(i, to)
                        swDraw()
                    } else if (!moved) {
                        v.performClick()
                        if (swSelMode) {
                            if (!swSel.remove(t)) swSel.add(t)
                            swDraw()
                        } else swPick(i)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(lpr)
                    if (on) {
                        swDragging = false
                        swDraw()
                    }
                    true
                }
                else -> true
            }
        }
    }

    // ---- الإغلاق والتراجع ----

    private fun swDetach(t: Tab) {
        tabs.remove(t)
        try { container.removeView(t.web) } catch (e: Throwable) {}
        try { tabsRow.removeView(t.chip) } catch (e: Throwable) {}
        try { t.web.onPause() } catch (e: Throwable) {}
    }

    private fun swReattach(c: SwClosed) {
        val at = minOf(c.i, tabs.size)
        tabs.add(at, c.t)
        container.addView(c.t.web, FrameLayout.LayoutParams(MATCH, MATCH))
        tabsRow.addView(c.t.chip, minOf(at, maxOf(0, tabsRow.childCount - 1)))
    }

    private fun swFinalize() {
        if (swClosed.isEmpty()) return
        val l = ArrayList(swClosed)
        swClosed.clear()
        for (c in l) {
            try { snapshot(c.t) } catch (e: Throwable) {}
            pages.remove(c.t.web)
            try { c.t.web.destroy() } catch (e: Throwable) {}
        }
    }

    private fun swSnackShow(msg: String, lbl: String?, fn: (() -> Unit)?) {
        swSnackTxt.text = msg
        if (lbl != null) {
            swSnackBtn.visibility = View.VISIBLE
            swSnackBtn.text = lbl
            swSnackBtn.setOnClickListener {
                ui.removeCallbacks(swHideSnack)
                swSnack.visibility = View.GONE
                fn?.invoke()
                swFinalize()
            }
        } else {
            swSnackBtn.visibility = View.GONE
        }
        swSnack.visibility = View.VISIBLE
        ui.removeCallbacks(swHideSnack)
        ui.postDelayed(swHideSnack, 4500)
    }

    private fun swCloseAt(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        val cell = swCells.firstOrNull { it.second == i }?.first
        if (cell != null && cell.tag == "x") return
        fun done() {
            val ttl = swTitle(t)
            val wasCur = t === cur
            swDetach(t)
            val rec = SwClosed(t, i)
            swClosed.add(rec)
            if (wasCur) {
                if (tabs.isNotEmpty()) switchTo(tabs[minOf(i, tabs.size - 1)]) else cur = null
            }
            relabel()
            swDraw()
            swSnackShow("أُغلق «" + ttl + "»", "تراجع") {
                if (swClosed.remove(rec)) {
                    swReattach(rec)
                    switchTo(rec.t)
                    relabel()
                    if (swShown) swDraw()
                }
            }
        }
        if (cell != null && swShown) {
            cell.tag = "x"
            cell.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(180).withEndAction { done() }.start()
        } else done()
    }

    private fun swCloseOthers() {
        val keep = cur ?: return
        for (t in ArrayList(tabs)) if (t !== keep) removeTab(t)
        relabel()
        swDraw()
        swSnackShow("أُغلقت كل التبويبات الأخرى", null, null)
        saveTabsPrefs()
    }

    private fun swCloseAll() {
        for (t in ArrayList(tabs)) removeTab(t)
        cur = null
        relabel()
        swDraw()
        swSnackShow("أُغلقت كل التبويبات", null, null)
        saveTabsPrefs()
    }

    private fun swStartSel() {
        if (tabs.isEmpty()) {
            swSnackShow("لا توجد تبويبات", null, null)
            return
        }
        swSelMode = true
        swSel.clear()
        swDraw()
    }

    private fun swEndSel() {
        swSelMode = false
        swSel.clear()
        swDraw()
    }

    private fun swDelSel() {
        if (swSel.isEmpty()) return
        val ct = cur
        val rem = ArrayList<SwClosed>()
        for (k in tabs.indices) if (swSel.contains(tabs[k])) rem.add(SwClosed(tabs[k], k))
        for (r in rem) swDetach(r.t)
        swClosed.addAll(rem)
        if (tabs.isEmpty()) cur = null
        else if (ct == null || !tabs.contains(ct)) switchTo(tabs[minOf(rem[0].i, tabs.size - 1)])
        swSelMode = false
        swSel.clear()
        relabel()
        swDraw()
        swSnackShow("أُغلق " + nAr(rem.size) + " تبويب", "تراجع") {
            swClosed.removeAll(rem.toSet())
            for (r in rem) swReattach(r)
            val back = if (ct != null && tabs.contains(ct)) ct else tabs.firstOrNull()
            if (back != null) switchTo(back)
            relabel()
            if (swShown) swDraw()
        }
    }

    // ---- المعاينة المصغّرة وشعار الموقع ----

    private fun swCapture(t: Tab) {
        try {
            val v: View = if (t.start) startView else t.web
            if (!v.isShown || v.width <= 0 || v.height <= 0) return
            val s = minOf(1f, 480f / v.width)
            val bw = maxOf(1, (v.width * s).toInt())
            val bh = maxOf(1, (v.height * s).toInt())
            val bm = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.RGB_565)
            val c = android.graphics.Canvas(bm)
            c.drawColor(if (t.start) cBg else Color.WHITE)
            c.scale(s, s)
            v.draw(c)
            t.thumb = bm
        } catch (e: Throwable) {
        }
    }

    // ---------- أدوات مساعدة ----------

    private fun clipText(): String = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip
        if (c != null && c.itemCount > 0) (c.getItemAt(0).coerceToText(this)?.toString() ?: "").trim() else ""
    } catch (e: Throwable) {
        ""
    }

    private fun downloadFromClip() {
        val u = clipText()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            toast("لا يوجد رابط في الحافظة")
            return
        }
        manualBox.setText(u)
        addManual()
    }

    private fun openFromClip() {
        val u = clipText()
        if (u.isBlank()) {
            toast("الحافظة فارغة")
            return
        }
        newTab(u)
    }

    private fun copyFound() {
        if (found.isEmpty()) toast("لا توجد روابط ملتقطة") else copyText(found.joinToString("\n"))
    }

    private fun shareFound() {
        if (found.isEmpty()) {
            toast("لا توجد روابط ملتقطة")
            return
        }
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, found.joinToString("\n"))
        startActivity(Intent.createChooser(i, "مشاركة الروابط"))
    }

    private fun copyTabs() {
        val l = tabs.mapNotNull { it.web.url }.filter { it.startsWith("http") }
        if (l.isEmpty()) toast("لا توجد روابط") else copyText(l.joinToString("\n"))
    }

    private fun freeMem() {
        for (t in tabs) if (t !== cur) try { t.web.clearCache(false) } catch (e: Throwable) {}
        System.gc()
        toast("تم تفريغ الذاكرة")
    }

    private fun perfReport() {
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

    private fun applyParallel() {
        val n = prefs().getInt("parallel", 3).coerceIn(1, 32)
        try {
            if (n > queue.maximumPoolSize) {
                queue.maximumPoolSize = n
                queue.corePoolSize = n
            } else {
                queue.corePoolSize = n
                queue.maximumPoolSize = n
            }
        } catch (e: Throwable) {
        }
    }

    // يحدّ سرعة التحميل الكلية (ك.ب/ث) بتأخير الخيط بما يناسب حجم ما قُرئ
    private fun throttle(n: Int) {
        val lim = limitKb
        if (lim <= 0 || n <= 0) return
        val ms: Long
        synchronized(thrLock) {
            val now = System.nanoTime()
            if (thrNext < now) thrNext = now
            thrNext += n * 1000000000L / (lim * 1024L)
            ms = (thrNext - now) / 1000000L
        }
        if (ms > 0) try { Thread.sleep(minOf(ms, 2000L)) } catch (e: InterruptedException) {}
    }

    // ---------- مخزن الصور الخاص (يحفظ كل صورة وأيقونة على الجهاز بغض النظر عن منع الموقع) ----------

    private val rcDir: File by lazy { File(cacheDir, "rc").also { it.mkdirs() } }
    private val rcExt = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif", ".bmp", ".woff", ".woff2", ".ttf")
    private val rcWrites = AtomicInteger(0)

    private fun rcKey(u: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(u.toByteArray())
        return d.joinToString("") { String.format("%02x", it) }
    }

    private fun rcWanted(r: WebResourceRequest, low: String, accept: String): Boolean {
        if (!prefs().getBoolean("rescache", true)) return false
        if (r.method != "GET" || r.isForMainFrame) return false
        val sc = r.url.scheme ?: return false
        if (sc != "http" && sc != "https") return false
        if (hdr(r.requestHeaders, "Range") != null) return false
        val path = low.substringBefore('?').substringBefore('#')
        if (rcExt.any { path.endsWith(it) }) return true
        return accept.contains("image/") && !accept.contains("text/html")
    }

    private fun rcResponse(ct: String, st: java.io.InputStream): WebResourceResponse {
        val hd = HashMap<String, String>()
        hd["Access-Control-Allow-Origin"] = "*"
        hd["Cache-Control"] = "max-age=86400"
        return WebResourceResponse(ct.ifEmpty { "image/jpeg" }, null, 200, "OK", hd, st)
    }

    // يعمل على خيط الشبكة؛ أي فشل يعيد null فيتولى المتصفح الطلب بنفسه
    private fun rcServe(u: String, h: Map<String, String>, stop: () -> Boolean = { false }): WebResourceResponse? {
        val key = rcKey(u)
        val bin = File(rcDir, key + ".bin")
        val meta = File(rcDir, key + ".meta")
        if (bin.exists() && meta.exists()) {
            try {
                val m = meta.readText().split("\n")
                bin.setLastModified(System.currentTimeMillis())
                return rcResponse(m[0], java.io.FileInputStream(bin))
            } catch (e: Throwable) {
            }
        }
        return try {
            val c = conn(u, h)
            val ct = (c.contentType ?: "").substringBefore(';').trim()
            val tmp = File(rcDir, key + "_" + Thread.currentThread().id + ".tmp")
            var size = 0L
            c.inputStream.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(16384)
                    while (true) {
                        if (stop()) break
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        size += n
                    }
                }
            }
            if (stop()) {
                tmp.delete()
                return null
            }
            val okType = ct.startsWith("image/") || ct.startsWith("font/") || ct.contains("font")
            if (okType && size > 0) {
                meta.writeText(ct + "\n" + System.currentTimeMillis())
                bin.delete()
                if (tmp.renameTo(bin)) {
                    if (rcWrites.incrementAndGet() % 40 == 0) rcTrim()
                    return rcResponse(ct, java.io.FileInputStream(bin))
                }
            }
            val st = java.io.FileInputStream(tmp)
            tmp.delete()
            rcResponse(ct, st)
        } catch (e: Throwable) {
            null
        }
    }

    private fun rcLocal(u: String): Pair<File, String>? {
        return try {
            if (!prefs().getBoolean("rescache", true)) return null
            val key = rcKey(u)
            val bin = File(rcDir, key + ".bin")
            val meta = File(rcDir, key + ".meta")
            if (!bin.exists() || !meta.exists()) return null
            Pair(bin, meta.readText().split("\n")[0])
        } catch (e: Throwable) {
            null
        }
    }

    private fun rcTrim() {
        try {
            val maxB = prefs().getInt("rcmb", 0) * 1048576L
            if (maxB <= 0) return
            val fs = rcDir.listFiles() ?: return
            var total = 0L
            for (f in fs) total += f.length()
            if (total <= maxB) return
            val bins = fs.filter { it.name.endsWith(".bin") }.sortedBy { it.lastModified() }
            for (b in bins) {
                if (total <= maxB * 8 / 10) break
                val m = File(rcDir, b.name.removeSuffix(".bin") + ".meta")
                total -= b.length() + m.length()
                b.delete()
                m.delete()
            }
        } catch (e: Throwable) {
        }
    }

    private fun rcSizeMb(): Long {
        var total = 0L
        try { rcDir.listFiles()?.forEach { total += it.length() } } catch (e: Throwable) {}
        return total / 1048576
    }

    private fun rcClear() {
        try { rcDir.listFiles()?.forEach { it.delete() } } catch (e: Throwable) {}
        toast("تم مسح مخزن الصور")
    }

    // خانة رقم حرة: تكتب أي قيمة تريدها، والصفر يعني بلا حد (أو تلقائي حسب الوصف)
    private fun numRow(parent: LinearLayout, title: String, sub0: String, key: String, def: Int, zeroLabel: String = "بلا حد", onChange: (Int) -> Unit = {}) {
        val sub = sub0.replace(Regex("\\s*الصفر:[^.]*\\.?"), "").trim()
        fun label(v: Int) = if (v == 0 && zeroLabel.isNotEmpty()) zeroLabel else v.toString()
        val holder = arrayOfNulls<TextView>(1)
        val p = pill(label(prefs().getInt(key, def)), false) {
            val cur0 = prefs().getInt(key, def)
            val et = EditText(this)
            et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            et.setText((if (cur0 > 0) cur0 else (if (def > 0) def else 100)).toString())
            et.setSelection(et.text.length)
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(20), dp(8), dp(20), dp(4))
            if (sub.isNotEmpty()) box.addView(tv(sub, 13f, cText))
            val cb = android.widget.CheckBox(this)
            DlgStyle.styleCheck(this, cb)
            cb.text = zeroLabel
            cb.isChecked = cur0 == 0 && zeroLabel.isNotEmpty()
            et.visibility = if (cb.isChecked) View.GONE else View.VISIBLE
            cb.setOnCheckedChangeListener { _, on -> et.visibility = if (on) View.GONE else View.VISIBLE }
            if (zeroLabel.isNotEmpty()) box.addView(cb)
            box.addView(et)
            StyledBuilder(this).setTitle(title).setView(box)
                .setPositiveButton("حفظ") { _, _ ->
                    val n = if (zeroLabel.isNotEmpty() && cb.isChecked) 0 else (et.text.toString().trim().toLongOrNull() ?: def.toLong()).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    prefs().edit().putInt(key, n).apply()
                    holder[0]?.text = label(n)
                    onChange(n)
                }
                .setNeutralButton("الأصل") { _, _ ->
                    prefs().edit().putInt(key, def).apply()
                    holder[0]?.text = label(def)
                    onChange(def)
                }
                .setNegativeButton("إلغاء", null).show()
        }
        holder[0] = p
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.addView(tv(title, 15f, cText, true))
        if (sub.isNotEmpty()) txt.addView(tv(sub, 12f, cMuted))
        row.addView(txt, lp(0, WRAP, 1f))
        row.addView(p)
        parent.addView(row, lp(MATCH, WRAP))
    }

    private fun choiceRow(parent: LinearLayout, title: String, sub: String, key: String, labels: Array<String>, values: IntArray, def: Int, onChange: (Int) -> Unit = {}) {
        val cur0 = prefs().getInt(key, def)
        val idx = values.indexOf(cur0)
        val holder = arrayOfNulls<TextView>(1)
        val p = pill(labels[if (idx >= 0) idx else 0], false) {
            StyledBuilder(this).setTitle(title).setItems(labels) { _, i ->
                prefs().edit().putInt(key, values[i]).apply()
                holder[0]?.text = labels[i]
                onChange(values[i])
            }.show()
        }
        holder[0] = p
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.addView(tv(title, 15f, cText, true))
        if (sub.isNotEmpty()) txt.addView(tv(sub, 12f, cMuted))
        row.addView(txt, lp(0, WRAP, 1f))
        row.addView(p)
        parent.addView(row, lp(MATCH, WRAP))
    }

    private fun closeOthers() {
        val keep = cur ?: return
        for (t in ArrayList(tabs)) if (t !== keep) removeTab(t)
        relabel()
        switchTo(keep)
        toast("تم إغلاق باقي التبويبات")
        saveTabsPrefs()
    }

    private fun relabel() {
        tabBtn.text = if (tabs.size > 99) "99+" else "" + tabs.size
        tabBtn.textSize = if (tabs.size > 99) 8f else if (tabs.size > 9) 10f else 12f
        for (i in tabs.indices) {
            val t = tabs[i]
            t.label.text = if (t.title.isNotBlank()) t.title else "تبويب " + (i + 1)
            t.close.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        }
    }

    private fun switchTo(t: Tab) {
        val old = cur
        if (old != null && old !== t && tabs.contains(old)) swCapture(old)
        cur = t
        for (x in tabs) {
            x.web.visibility = if (x === t) View.VISIBLE else View.GONE
            if (prefs().getBoolean("pausebg", true) && x !== t) x.web.onPause() else x.web.onResume()
            x.chip.background = bg(if (x === t) cAccentSoft else cCard, 16, if (x === t) cAccent else cLine)
        }
        val pu = t.pendingUrl
        if (pu != null) {
            t.pendingUrl = null
            t.web.loadUrl(pu)
        }
        urlBox.setText(t.web.url ?: pu ?: "")
        if (findBar.visibility == View.VISIBLE) closeFind()
        updateStart()
        updateLoadUi()
        updateCounts()
    }

    private fun fix(u: String): String {
        val s = u.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (s.contains(" ") || !s.contains(".")) return searchUrl(s)
        return "https://$s"
    }

    private fun load(u: String) {
        hideKb()
        // من الشاشة الرئيسية: يُفتح الموقع في تبويب جديد منفصل وتبقى الرئيسية تبويباً مستقلاً
        if (cur?.start == true && u.isNotBlank()) {
            newTab(u)
            return
        }
        cur?.start = false
        cur?.startBack = false
        updateStart()
        cur?.web?.loadUrl(fix(u))
        showPage(0)
    }

    // ---------- المفضلة ----------

    private fun bookmarkList(): List<String> {
        val raw = prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>()
        return raw.toList().sorted()
    }

    private fun addBookmark() {
        val u = cur?.web?.url
        if (u == null) {
            toast("افتح صفحة أولاً ثم احفظها")
            return
        }
        val set = HashSet<String>(prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>())
        set.add(u)
        prefs().edit().putStringSet("bm", set).apply()
        toast("تم الحفظ في المفضلة")
        refreshStart()
    }

    private fun removeBookmark(u: String) {
        val set = HashSet<String>(prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>())
        set.remove(u)
        prefs().edit().putStringSet("bm", set).apply()
        refreshStart()
    }

    private fun showBookmarks() {
        val items = bookmarkList()
        val b = StyledBuilder(this).setTitle("المفضلة (" + items.size + ")")
        val bmHolder = arrayOfNulls<AlertDialog>(1)
        if (items.isEmpty()) b.setMessage("لا توجد صفحات في المفضلة بعد") else {
            val rows = items.map { u ->
                val r = DlgStyle.siteRow(this, hostOf(u), u, favOf(u)) { bmHolder[0]?.dismiss(); load(u) }
                r.setOnLongClickListener {
                    removeBookmark(u)
                    bmHolder[0]?.dismiss()
                    true
                }
                r
            }
            b.setView(DlgStyle.rowsList(this, rows))
        }
        b.setPositiveButton("حفظ الصفحة الحالية") { _, _ -> addBookmark() }
        if (items.isNotEmpty()) b.setNegativeButton("مسح الكل") { _, _ ->
            prefs().edit().remove("bm").apply()
            refreshStart()
        }
        bmHolder[0] = b.show()
    }

    // ---------- تنبيهات التطبيق ----------

    private fun buildAlerts(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(14), dp(14), dp(24))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tv("تنبيهات التطبيق", 20f, cText, true), lp(0, WRAP, 1f))
        head.addView(pill("نسخ الكل", false) {
            copyText(alerts.joinToString("\n\n") { it.kind + " | " + it.msg + "\n" + it.detail })
        }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        head.addView(pill("مسح", false) { clearAlerts() })
        col.addView(head, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
        col.addView(tv("أي خطأ في التحميل أو الدمج أو الصفحات أو التطبيق يظهر هنا. اضغط على التنبيه لعرض التفاصيل.", 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 0, 0, 12))
        alertEmpty = tv("لا توجد أخطاء", 14f, cMuted)
        alertEmpty.gravity = Gravity.CENTER
        alertEmpty.setPadding(0, dp(40), 0, dp(40))
        col.addView(alertEmpty, lp(MATCH, WRAP))
        alertList = LinearLayout(this)
        alertList.orientation = LinearLayout.VERTICAL
        col.addView(alertList, lp(MATCH, WRAP))
        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.addView(col)
        return sv
    }

    // يمكن استدعاؤها من أي خيط
    private fun logErr(kind: String, msg: String, t: Throwable? = null) {
        val detail = if (t != null) android.util.Log.getStackTraceString(t).lines().take(14).joinToString("\n") else ""
        val a = AlertItem(System.currentTimeMillis(), kind, msg, detail)
        ui.post { addAlert(a, true) }
    }

    private fun addAlert(a: AlertItem, persist: Boolean) {
        alerts.add(0, a)
        if (alerts.size > 200) {
            alerts.removeAt(alerts.size - 1)
            if (alertList.childCount > 0) alertList.removeViewAt(alertList.childCount - 1)
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = bg(cCard, 14, cLine)
        row.setPadding(dp(12), dp(12), dp(12), dp(12))
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(a.time))
        row.addView(tv(a.kind + "  •  " + time, 12f, cMuted))
        row.addView(tv(a.msg, 14f, cText, true))
        if (a.detail.isNotBlank()) {
            val d = tv(a.detail, 11f, cMuted)
            d.layoutDirection = View.LAYOUT_DIRECTION_LTR
            d.visibility = View.GONE
            row.addView(d, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
            row.setOnClickListener { d.visibility = if (d.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        row.addView(pill("نسخ", false) { copyText(a.kind + " | " + a.msg + "\n" + a.detail) }, lp(WRAP, WRAP, 0f, 0, 8, 0, 0))
        alertList.addView(row, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        if (persist) persistAlert(a)
        updateCounts()
    }

    private fun persistAlert(a: AlertItem) {
        try {
            val f = File(filesDir, "alerts.tsv")
            if (f.length() > 200000) f.delete()
            val line = listOf(a.time.toString(), a.kind, a.msg, a.detail).joinToString("\t") { it.replace("\t", " ").replace("\n", "␤") }
            f.appendText(line + "\n")
        } catch (e: Exception) {
        }
    }

    private fun loadAlerts() {
        try {
            val f = File(filesDir, "alerts.tsv")
            if (!f.exists()) return
            for (l in f.readLines().takeLast(100)) {
                val p = l.split("\t")
                if (p.size < 4) continue
                addAlert(AlertItem(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3].replace("␤", "\n")), false)
            }
        } catch (e: Exception) {
        }
    }

    private fun clearAlerts() {
        alerts.clear()
        alertList.removeAllViews()
        try { File(filesDir, "alerts.tsv").delete() } catch (e: Exception) { }
        updateCounts()
    }

    // ---------- الصفحات المغلقة (استرجاع من الكاش بدون تحميل جديد) ----------

    // يحذف مجلدات التحميل غير المكتملة الأقدم من 3 أيام
    private fun cleanOldWork() {
        try {
            val base = getExternalFilesDir(null) ?: filesDir
            val lim = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            base.listFiles()?.forEach {
                if (it.isDirectory && it.name.startsWith("work_") && it.lastModified() < lim) it.deleteRecursively()
            }
        } catch (e: Exception) {
        }
    }

    private fun stateFile(id: String) = File(filesDir, "closed_$id.bin")

    private fun loadClosed() {
        val s = prefs().getString("closed", "") ?: ""
        for (l in s.split("\n")) {
            val p = l.split("\t")
            if (p.size >= 3 && stateFile(p[0]).exists()) closed.add(Closed(p[0], p[1], p[2]))
        }
    }

    private fun saveClosedPrefs() {
        prefs().edit().putString("closed", closed.joinToString("\n") { it.id + "\t" + it.title + "\t" + it.url }).apply()
    }

    private fun snapshot(t: Tab) {
        try {
            val url = t.web.url ?: return
            if (!url.startsWith("http")) return
            val b = Bundle()
            if (t.web.saveState(b) == null) return
            val id = System.currentTimeMillis().toString()
            val p = android.os.Parcel.obtain()
            try {
                p.writeBundle(b)
                stateFile(id).writeBytes(p.marshall())
            } finally {
                p.recycle()
            }
            for (x in ArrayList(closed)) if (x.url == url) {
                stateFile(x.id).delete()
                closed.remove(x)
            }
            val title = (if (t.title.isNotBlank()) t.title else url).replace("\t", " ").replace("\n", " ")
            closed.add(0, Closed(id, title, url))
            while (closed.size > 15) {
                val x = closed.removeAt(closed.size - 1)
                stateFile(x.id).delete()
            }
            saveClosedPrefs()
        } catch (e: Throwable) {
            logErr("كاش", "تعذر حفظ حالة الصفحة المغلقة", e)
        }
    }

    private fun reopen(c: Closed) {
        try {
            val d = stateFile(c.id).readBytes()
            var b: Bundle? = null
            val p = android.os.Parcel.obtain()
            try {
                p.unmarshall(d, 0, d.size)
                p.setDataPosition(0)
                b = p.readBundle(javaClass.classLoader)
            } finally {
                p.recycle()
            }
            newTab("")
            val t = cur ?: return
            t.restoring = true
            t.web.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            if (b == null || t.web.restoreState(b) == null) t.web.loadUrl(c.url)
        } catch (e: Throwable) {
            logErr("كاش", "تعذر استرجاع الصفحة، فُتحت من جديد", e)
            newTab(c.url)
        }
    }

    private fun showClosed() {
        if (closed.isEmpty()) {
            toast("لا توجد صفحات مغلقة محفوظة")
            return
        }
        val clHolder = arrayOfNulls<AlertDialog>(1)
        val rows = closed.mapIndexed { i, cp ->
            DlgStyle.siteRow(this, (if (cp.title.isNotBlank()) cp.title else cp.url).take(60), hostOf(cp.url), favOf(cp.url)) { clHolder[0]?.dismiss(); reopen(closed[i]) }
        }
        clHolder[0] = StyledBuilder(this)
            .setTitle("الصفحات المغلقة مؤخراً")
            .setView(DlgStyle.rowsList(this, rows))
            .setNegativeButton("مسح الكل") { _, _ ->
                for (x in closed) stateFile(x.id).delete()
                closed.clear()
                saveClosedPrefs()
            }
            .show()
    }

    // ---------- سكربتات JavaScript ----------

    private fun loadScripts(): org.json.JSONArray =
        try { org.json.JSONArray(prefs().getString("scripts", "[]")) } catch (e: Exception) { org.json.JSONArray() }

    private fun saveScripts(a: org.json.JSONArray) {
        prefs().edit().putString("scripts", a.toString()).apply()
    }

    private fun showScripts() {
        val a = loadScripts()
        if (a.length() == 0) {
            toast("لا توجد سكربتات بعد، أضف أول سكربت")
            editScript(-1)
            return
        }
        val names = Array(a.length()) { i ->
            val o = a.getJSONObject(i)
            (if (o.optBoolean("on", true)) "✔ " else "✖ ") + o.optString("name", "بدون اسم")
        }
        StyledBuilder(this)
            .setTitle("سكربتات JavaScript")
            .setItems(names) { _, i -> editScript(i) }
            .setPositiveButton("إضافة") { _, _ -> editScript(-1) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun editScript(idx: Int) {
        val a = loadScripts()
        val o = if (idx >= 0) a.getJSONObject(idx) else org.json.JSONObject()
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(16), dp(8), dp(16), dp(8))
        val nameE = EditText(this)
        nameE.hint = "اسم السكربت"
        nameE.setText(o.optString("name", ""))
        val matchE = EditText(this)
        matchE.hint = "المواقع (اختياري) مثل https://*.tango.me/*"
        matchE.setText(o.optString("match", ""))
        matchE.layoutDirection = View.LAYOUT_DIRECTION_LTR
        matchE.textSize = 13f
        val codeE = EditText(this)
        codeE.hint = "كود JavaScript"
        codeE.setText(o.optString("code", ""))
        codeE.layoutDirection = View.LAYOUT_DIRECTION_LTR
        codeE.typeface = Typeface.MONOSPACE
        codeE.textSize = 12f
        codeE.minLines = 8
        codeE.gravity = Gravity.TOP or Gravity.START
        codeE.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        lay.addView(nameE)
        lay.addView(matchE)
        lay.addView(codeE)
        val sv = ScrollView(this)
        sv.addView(lay)
        val on = o.optBoolean("on", true)
        val b = StyledBuilder(this)
            .setTitle(if (idx >= 0) "تعديل السكربت" else "سكربت جديد")
            .setView(sv)
            .setPositiveButton("حفظ") { _, _ ->
                val code = codeE.text.toString()
                if (code.isBlank()) {
                    toast("الكود فاضي")
                } else {
                    val n = org.json.JSONObject()
                    n.put("name", nameE.text.toString().ifBlank { "سكربت" })
                    n.put("match", matchE.text.toString().trim())
                    n.put("code", code)
                    n.put("on", on)
                    if (idx >= 0) a.put(idx, n) else a.put(n)
                    saveScripts(a)
                    toast("تم حفظ السكربت، يعمل عند فتح الصفحة")
                }
            }
        if (idx >= 0) {
            b.setNeutralButton(if (on) "تعطيل" else "تفعيل") { _, _ ->
                o.put("on", !on)
                a.put(idx, o)
                saveScripts(a)
            }
            b.setNegativeButton("حذف") { _, _ ->
                val n = org.json.JSONArray()
                for (i in 0 until a.length()) if (i != idx) n.put(a.get(i))
                saveScripts(n)
            }
        } else {
            b.setNeutralButton("لصق من الحافظة", null)
            b.setNegativeButton("إلغاء", null)
        }
        val dlg = b.show()
        if (idx < 0) {
            dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (t.isBlank()) toast("الحافظة فاضية") else codeE.setText(t)
            }
        }
    }

    private fun scriptMatches(match: String, code: String, url: String): Boolean {
        val pats = ArrayList<String>()
        if (match.isNotBlank()) {
            for (x in match.split(Regex("[\\s,]+"))) if (x.isNotBlank()) pats.add(x)
        } else {
            val re = Regex("^\\s*//\\s*@(?:match|include)\\s+(\\S+)", RegexOption.MULTILINE)
            for (m in re.findAll(code)) pats.add(m.groupValues[1])
        }
        if (pats.isEmpty()) return true
        for (pt in pats) {
            if (pt == "*" || pt == "<all_urls>") return true
            val rx = pt.split("*").joinToString(".*") { Regex.escape(it) }
            try {
                if (Regex(rx).matches(url)) return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    private fun runScripts(v: WebView, url: String) {
        try {
            val a = loadScripts()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (!o.optBoolean("on", true)) continue
                val code = o.optString("code", "")
                if (code.isBlank() || !scriptMatches(o.optString("match", ""), code, url)) continue
                val nm = o.optString("name", "")
                // new Function يجعل أخطاء الصياغة قابلة للالتقاط وتظهر في تبويب التنبيهات
                val js = "(function(){var n=" + org.json.JSONObject.quote(nm) + ";try{(new Function(" +
                    org.json.JSONObject.quote(code) + "))();}catch(e){try{TDL.err('سكربت '+n+': '+e);}catch(x){}}})();"
                v.evaluateJavascript(js, null)
            }
        } catch (e: Throwable) {
            logErr("سكربت", "تعذر تشغيل السكربتات: " + (e.message ?: e.javaClass.simpleName), e)
        }
    }

    // ---------- أزرار الفيديو والصور داخل الصفحة ----------

    inner class Bridge {
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
            } catch (e: Throwable) {
                logErr("تحميل", "تعذر استقبال ملف الصفحة: " + (e.message ?: ""))
            }
        }

        @JavascriptInterface
        fun blobEnd(id: String, name: String, mime: String) {
            val f = File(cacheDir, "blob_" + id.filter { it.isLetterOrDigit() })
            Thread {
                try {
                    if (!f.exists()) throw Exception("لم يصل أي محتوى")
                    val nm = name.ifBlank { "ملف_" + System.currentTimeMillis() }
                    FileUtil.saveToDownloads(this@MainActivity, f.inputStream(), nm, if (mime.isNotBlank()) mime else FileUtil.mimeOf(nm))
                    ui.post { toast("تم حفظ " + nm + " في مجلد التحميلات") }
                } catch (e: Throwable) {
                    logErr("تحميل", "تعذر حفظ ملف الصفحة: " + (e.message ?: ""))
                    ui.post { toast("تعذر حفظ الملف") }
                } finally {
                    f.delete()
                }
            }.start()
        }
    }

    private fun hdrFor(u: String, page: String): Map<String, String> {
        val h = HashMap<String, String>()
        h["User-Agent"] = curUa
        h["Referer"] = page
        h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
        return h
    }

    private fun handleVideo(src: String, page: String) {
        // فيديو blob: لا يُحمَّل مباشرة، فنستعمل آخر رابط التقطه التطبيق
        val u = if (src.startsWith("http")) src else bestFor(cur)?.url
        if (u == null) {
            // لا رابط ملتقط بعد: نمسح ما حمّلته الصفحة ثم نعيد المحاولة مرة واحدة
            val w = cur?.web
            if (w != null && !src.startsWith("http")) {
                w.evaluateJavascript(RESCAN_JS, null)
                ui.postDelayed({
                    val u2 = bestFor(cur)?.url
                    if (u2 == null) {
                        if (src.startsWith("blob:")) fetchBlob(src, "video_" + System.currentTimeMillis() + ".mp4", "video/mp4")
                        else toast("شغّل الفيديو لحظة ثم اضغط مرة أخرى")
                    } else {
                        addFound(u2, foundHdr[u2] ?: hdrFor(u2, page), null, cur, cur?.title ?: "")
                        foundStart[u2]?.invoke()
                        toast("تابع التحميل من تبويب التنزيلات")
                    }
                }, 1800)
            } else toast("شغّل الفيديو لحظة ثم اضغط مرة أخرى")
            return
        }
        addFound(u, foundHdr[u] ?: hdrFor(u, page), null, cur, cur?.title ?: "")
        foundStart[u]?.invoke()
        toast("تابع التحميل من تبويب التنزيلات")
    }

    private fun imgExt(ct: String, src: String): String {
        if (ct.contains("png")) return "png"
        if (ct.contains("webp")) return "webp"
        if (ct.contains("gif")) return "gif"
        if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg"
        val e = src.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (Regex("[a-z0-9]{2,4}").matches(e)) e else "jpg"
    }

    // ضغطة مطولة على صورة: نسخ الرابط / مشاركته / تحميل الصورة
    private fun onWebLongPress(w: WebView): Boolean {
        val hit = w.hitTestResult
        val t = hit.type
        if (t != WebView.HitTestResult.IMAGE_TYPE && t != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
        val src = hit.extra ?: return false
        if (src.isBlank()) return false
        val pg = w.url ?: ""
        StyledBuilder(this)
            .setTitle("الصورة")
            .setItems(arrayOf("نسخ الرابط", "مشاركة الرابط", "تحميل الصورة")) { _, i ->
                when (i) {
                    0 -> handleImage(src, pg, "copy")
                    1 -> {
                        if (src.startsWith("http")) {
                            val sh = Intent(Intent.ACTION_SEND)
                            sh.type = "text/plain"
                            sh.putExtra(Intent.EXTRA_TEXT, src)
                            startActivity(Intent.createChooser(sh, "مشاركة الرابط"))
                        } else toast("لا يوجد رابط يمكن مشاركته لهذه الصورة")
                    }
                    else -> handleImage(src, pg, "dl")
                }
            }
            .show()
        return true
    }

    private fun handleImage(src: String, page: String, act: String) {
        if (act == "copy") {
            if (src.isBlank()) toast("لا يوجد رابط للصورة") else copyText(src)
            return
        }
        if (!src.startsWith("http")) {
            toast("هذه الصورة لا يمكن تحميلها مباشرة، جرّب نسخ الرابط")
            return
        }
        val h = hdrFor(src, page)
        toast("جاري تحميل الصورة...")
        imgPool.execute {
            try {
                val c = conn(src, h)
                val ext = imgExt((c.contentType ?: "").lowercase(), src)
                val f = File(cacheDir, "tango_img_" + System.currentTimeMillis() + "." + ext)
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val mime = if (ext == "jpg") "image/jpeg" else "image/" + ext
                val r = saveToDownloads(f, mime)
                ui.post { toast("تم حفظ الصورة: " + r) }
            } catch (e: Throwable) {
                logErr("صورة", "فشل تحميل الصورة: " + (e.message ?: e.javaClass.simpleName), e)
                ui.post { toast("فشل تحميل الصورة، راجع التنبيهات") }
            }
        }
    }


    // ---------- قسم الصور (مستقل داخل التحميلات): كل صور صفحة التبويب الحالي، تحديد وتحميل جماعي ----------

    private class GImg(val u: String, var w: Int, var h: Int) {
        var sel = false
        var loaded = false
        var cell: FrameLayout? = null
        var iv: ImageView? = null
        var mark: TextView? = null
        // بعد الضغط على «تحميل المحدد» ينتقل إلى صفحة التحميلات: 1 جارٍ، 2 مكتمل، 3 فشل
        var started = false
        var state = 0
        var chip: TextView? = null
        var pbar: ProgressBar? = null
    }

    private val galPool = Executors.newFixedThreadPool(4)

    private fun saveImageSync(src: String, page: String, onProgress: ((Long, Long) -> Unit)? = null): String {
        val loc = rcLocal(src) ?: throw Exception("الصورة غير ملتقطة")
        val ext0 = imgExt(loc.second.lowercase(), src)
        val f0 = File(cacheDir, "tango_img_" + System.nanoTime() + "." + ext0)
        loc.first.copyTo(f0, true)
        onProgress?.invoke(f0.length(), f0.length())
        return saveToDownloads(f0, if (ext0 == "jpg") "image/jpeg" else "image/" + ext0)
    }

    private fun showImagesSection() {
        val t = cur
        if (t == null) {
            toast("افتح صفحة في المتصفح أولاً")
            return
        }
        val page = t.web.url ?: ""
        toast("جاري جمع صور الصفحة...")
        val js = "(function(){try{var o=[],s={};function add(u,w,h){if(!u||s[u])return;if(u.indexOf('http')!==0)return;s[u]=1;o.push([u,w|0,h|0]);}" +
            "var im=document.images;for(var i=0;i<im.length;i++){var e=im[i];var u=e.currentSrc||e.src||e.getAttribute('data-src')||e.getAttribute('data-original')||'';" +
            "if(u&&u.indexOf('http')!==0){try{u=new URL(u,location.href).href}catch(x){}}" +
            "add(u,e.naturalWidth||e.width,e.naturalHeight||e.height);}" +
            "var all=document.querySelectorAll('*');var n=all.length;for(var j=0;j<n;j++){var b=getComputedStyle(all[j]).backgroundImage;" +
            "if(b&&b.indexOf('url(')>=0){var m=/url\\([\"']?([^\"')]+)/.exec(b);if(m)add(m[1],all[j].clientWidth,all[j].clientHeight);}}" +
            "return JSON.stringify(o)}catch(x){return '[]'}})()"
        try {
            t.web.evaluateJavascript(js) { res ->
                val list = ArrayList<GImg>()
                try {
                    val v = org.json.JSONTokener(res ?: "").nextValue()
                    if (v is String) {
                        val a = org.json.JSONArray(v)
                        for (i in 0 until a.length()) {
                            val x = a.getJSONArray(i)
                            list.add(GImg(x.getString(0), x.getInt(1), x.getInt(2)))
                        }
                    }
                } catch (e: Throwable) {
                }
                galPool.execute {
                    val cap = ArrayList<GImg>()
                    for (g in list) if (rcLocal(g.u) != null) cap.add(g)
                    ui.post {
                        if (cap.isEmpty()) toast("لم أجد صوراً في هذه الصفحة") else openImagesDialog(cap, page)
                    }
                }
            }
        } catch (e: Throwable) {
            toast("تعذر قراءة صور الصفحة")
        }
    }

    private fun openImagesDialog(all: List<GImg>, page: String) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(12), dp(24), dp(12), dp(12))
        val title = tv("", 16f, cText, true)
        root.addView(title, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))

        val cntB = pill("عداد التقاط الصور", true) { }
        val dlsB = pill("التحميلات", false) { }
        val closeB = pill("إغلاق", false) { dlg.dismiss() }
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.addView(cntB, lp(0, WRAP, 1f, 0, 0, 6, 0))
        nav.addView(dlsB, lp(0, WRAP, 1f, 0, 0, 6, 0))
        nav.addView(closeB, lp(WRAP, WRAP))
        root.addView(nav, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))

        // صفحة عداد التقاط الصور
        val cntBox = LinearLayout(this)
        cntBox.orientation = LinearLayout.VERTICAL
        root.addView(cntBox, lp(MATCH, 0, 1f))
        // صفحة التحميلات
        val dlsBox = ScrollView(this)
        val dlsList = LinearLayout(this)
        dlsList.orientation = LinearLayout.VERTICAL
        dlsBox.addView(dlsList, FrameLayout.LayoutParams(MATCH, WRAP))
        dlsBox.visibility = View.GONE
        root.addView(dlsBox, lp(MATCH, 0, 1f))

        val smallB = pill("إظهار الصور الصغيرة", false) { }
        val allB = pill("تحديد الكل", false) { }
        val bar1 = LinearLayout(this)
        bar1.orientation = LinearLayout.HORIZONTAL
        bar1.addView(smallB, lp(0, WRAP, 1f, 0, 0, 6, 0))
        bar1.addView(allB, lp(0, WRAP, 1f))
        cntBox.addView(bar1, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))

        val dlB = pill("⬇ تحميل المحدد", true) { }
        cntBox.addView(dlB, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))

        val sv = ScrollView(this)
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        sv.addView(grid, FrameLayout.LayoutParams(MATCH, WRAP))
        cntBox.addView(sv, lp(MATCH, 0, 1f))

        val side = (resources.displayMetrics.widthPixels - dp(24) - dp(12)) / 3
        var showSmall = false
        var shown = 0

        fun isSmall(g: GImg): Boolean = maxOf(g.w, g.h) in 1..149
        // صفحة العداد: الصور التي لم يُضغط تحميلها بعد
        fun capList(): List<GImg> = all.filter { !it.started }
        fun visible(): List<GImg> = if (showSmall) capList() else capList().filter { !isSmall(it) }

        fun paint(g: GImg) {
            val m = g.mark ?: return
            m.text = if (g.sel) "✓" else ""
            m.background = bg(if (g.sel) cAccent else Color.argb(120, 0, 0, 0), 14, Color.WHITE)
            g.cell?.alpha = if (g.sel) 0.75f else 1f
        }

        fun updateTitle() {
            val vis = visible()
            val cap = capList()
            val hidden = cap.size - vis.size
            title.text = if (shown == 1) "تحميلات الصور: " + all.count { it.started }
                else ""
            title.visibility = if (title.text.isEmpty()) View.GONE else View.VISIBLE
            cntB.text = if (vis.isNotEmpty()) "عداد التقاط الصور (" + vis.size + ")" else "عداد التقاط الصور"
            val smallN = cap.count { isSmall(it) }
            smallB.text = (if (showSmall) "إخفاء الصور الصغيرة" else "إظهار الصور الصغيرة") + " (" + smallN + ")"
            allB.text = if (vis.isNotEmpty() && vis.all { it.sel }) "إلغاء التحديد" else "تحديد الكل"
        }

        fun loadThumb(g: GImg) {
            if (g.loaded) return
            g.loaded = true
            galPool.execute {
                try {
                    val loc = rcLocal(g.u) ?: return@execute
                    val d = loc.first.inputStream().use { readLimited(it, 8000000) }
                    val o = android.graphics.BitmapFactory.Options()
                    o.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, o)
                    var ss = 1
                    while (o.outWidth / ss > 320) ss *= 2
                    val o2 = android.graphics.BitmapFactory.Options()
                    o2.inSampleSize = ss
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, o2)
                    if (bmp != null) {
                        ui.post {
                            val iv = g.iv
                            if (iv != null && g.loaded) {
                                iv.clearColorFilter()
                                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                                iv.setImageBitmap(bmp)
                            }
                        }
                    }
                } catch (e: Throwable) {
                }
            }
        }

        fun makeCell(g: GImg): FrameLayout {
            val c = FrameLayout(this)
            c.background = bg(cCard, 8, cLine)
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER
            iv.setImageResource(R.drawable.ic_image)
            iv.setColorFilter(cMuted)
            c.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            val mk = tv("", 16f, Color.WHITE, true)
            mk.gravity = Gravity.CENTER
            val mp = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.START)
            mp.setMargins(dp(4), dp(4), 0, 0)
            c.addView(mk, mp)
            if (g.w > 0 && g.h > 0) {
                val sz = tv(g.w.toString() + "×" + g.h, 10f, Color.WHITE)
                sz.setPadding(dp(4), dp(1), dp(4), dp(1))
                sz.background = bg(Color.argb(140, 0, 0, 0), 6)
                val sp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
                sp.setMargins(0, 0, dp(4), dp(4))
                c.addView(sz, sp)
            }
            g.cell = c
            c.tag = g
            g.iv = iv
            g.mark = mk
            c.setOnClickListener {
                g.sel = !g.sel
                paint(g)
                updateTitle()
            }
            paint(g)
            return c
        }

        // يفرغ معاينة الصورة من الذاكرة (يبقى تحديدها كما هو)
        fun unloadThumb(g: GImg) {
            if (!g.loaded) return
            g.loaded = false
            val iv = g.iv ?: return
            iv.setImageResource(R.drawable.ic_image)
            iv.setColorFilter(cMuted)
            iv.scaleType = ImageView.ScaleType.CENTER
        }

        // تُحمَّل المعاينات للصفوف القريبة من الشاشة فقط، وتُفرَّغ البعيدة، فتبقى الذاكرة قليلة مهما كثرت الصور
        fun loadVisible() {
            val top = sv.scrollY - sv.height
            val bottom = sv.scrollY + sv.height * 2
            for (ri in 0 until grid.childCount) {
                val r = grid.getChildAt(ri) as? ViewGroup ?: continue
                val far = r.bottom < top || r.top > bottom
                for (ci in 0 until r.childCount) {
                    val g = r.getChildAt(ci).tag as? GImg ?: continue
                    if (far) unloadThumb(g) else loadThumb(g)
                }
            }
        }

        fun refresh() {
            for (g in all) (g.cell?.parent as? ViewGroup)?.removeView(g.cell)
            grid.removeAllViews()
            val vis = visible()
            for (g in all) if (!vis.contains(g)) unloadThumb(g)
            var i = 0
            while (i < vis.size) {
                val rowL = LinearLayout(this)
                rowL.orientation = LinearLayout.HORIZONTAL
                for (k in i until minOf(i + 3, vis.size)) {
                    val g = vis[k]
                    val c = g.cell ?: makeCell(g)
                    rowL.addView(c, lp(side, side, 0f, 0, 0, 4, 4))
                }
                grid.addView(rowL, lp(MATCH, WRAP))
                i += 3
            }
            updateTitle()
            grid.post { loadVisible() }
        }

        fun showView(v: Int) {
            shown = v
            cntBox.visibility = if (v == 0) View.VISIBLE else View.GONE
            dlsBox.visibility = if (v == 1) View.VISIBLE else View.GONE
            styleChip(cntB, v == 0)
            styleChip(dlsB, v == 1)
            updateTitle()
            if (v == 0) grid.post { loadVisible() }
        }

        // صف صورة في صفحة التحميلات: يبقى فيها سواء جارٍ أو مكتمل أو فشل
        fun addRow(g: GImg) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.background = bg(cCard, 12)
            r.setPadding(dp(8), dp(8), dp(8), dp(8))
            val th = ImageView(this)
            val bmp = (g.iv?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bmp != null) {
                th.scaleType = ImageView.ScaleType.CENTER_CROP
                th.setImageBitmap(bmp)
            } else {
                th.scaleType = ImageView.ScaleType.CENTER
                th.setImageResource(R.drawable.ic_image)
                th.setColorFilter(cMuted)
            }
            r.addView(th, lp(dp(48), dp(48)))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val nm = tv(g.u.substringBefore('?').substringBefore('#').substringAfterLast('/').ifEmpty { "image" }, 13f, cText)
            nm.maxLines = 1
            nm.ellipsize = TextUtils.TruncateAt.END
            nm.textDirection = View.TEXT_DIRECTION_LTR
            col.addView(nm, lp(MATCH, WRAP))
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            pb.isIndeterminate = true
            col.addView(pb, lp(MATCH, dp(4), 0f, 0, 6, 0, 0))
            r.addView(col, lp(0, WRAP, 1f, 10, 0, 10, 0))
            val chip = tv("جارٍ", 11f, cAccent, true)
            r.addView(chip, lp(WRAP, WRAP))
            g.chip = chip
            g.pbar = pb
            dlsList.addView(r, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        }

        // نسبة التقدم الحقيقية (إن عُرف حجم الملف) وإلا يبقى الشريط متحركاً
        fun progressImg(g: GImg, done: Long, total: Long) {
            if (g.state != 1 || total <= 0) return
            val pc = (done * 100 / total).toInt().coerceIn(0, 100)
            g.pbar?.let { it.isIndeterminate = false; it.max = 100; it.progress = pc }
            g.chip?.text = "جارٍ " + pc + "%"
        }

        fun finishImg(g: GImg, st: Int) {
            g.state = st
            g.pbar?.visibility = View.GONE
            g.chip?.text = if (st == 2) "مكتمل 100%" else "فشل"
            g.chip?.setTextColor(if (st == 2) Color.parseColor("#3DB36B") else Color.parseColor("#E05A5A"))
        }

        cntB.setOnClickListener { showView(0) }
        dlsB.setOnClickListener { showView(1) }

        sv.viewTreeObserver.addOnScrollChangedListener { loadVisible() }
        smallB.setOnClickListener {
            showSmall = !showSmall
            refresh()
        }
        allB.setOnClickListener {
            val vis = visible()
            val allSel = vis.isNotEmpty() && vis.all { it.sel }
            for (g in vis) {
                g.sel = !allSel
                paint(g)
            }
            updateTitle()
        }
        dlB.setOnClickListener {
            val sel = all.filter { it.sel && !it.started }
            if (sel.isEmpty()) {
                toast("اختر صوراً أولاً")
            } else {
                toast("جاري تحميل " + sel.size + " صورة...")
                val ok = java.util.concurrent.atomic.AtomicInteger(0)
                val bad = java.util.concurrent.atomic.AtomicInteger(0)
                for (g in sel) {
                    g.sel = false
                    g.started = true
                    g.state = 1
                    addRow(g)
                }
                // يُغلق عداد التقاط الصور ويُفتح التحميلات تلقائياً وتظهر فيها الصور
                showView(1)
                refresh()
                for (g in sel) {
                    galPool.execute {
                        try {
                            saveImageSync(g.u, page) { done, total ->
                                ui.post { progressImg(g, done, total) }
                            }
                            ok.incrementAndGet()
                            ui.post { finishImg(g, 2) }
                        } catch (e: Throwable) {
                            bad.incrementAndGet()
                            ui.post { finishImg(g, 3) }
                            logErr("صورة", "فشل تحميل الصورة: " + (e.message ?: e.javaClass.simpleName), e)
                        }
                        if (ok.get() + bad.get() == sel.size) {
                            ui.post { toast("تم حفظ " + ok.get() + " صورة" + (if (bad.get() > 0) " وفشلت " + bad.get() else "")) }
                        }
                    }
                }
            }
        }

        refresh()
        showView(0)
        dlg.setContentView(root)
        dlg.show()
    }

    // ---------- بدء التحميل مع اختيار الجودة ----------

    private fun startDl(u: String, h: Map<String, String>, r: Row) {
        r.btn.isEnabled = false
        r.btn.alpha = 0.5f
        r.bar.visibility = View.VISIBLE
        r.msg.text = "في الانتظار..."
        if (!isHls(u) && !isDash(u)) {
            enqueue(u, h, r)
            return
        }
        r.msg.text = "جاري قراءة الجودات..."
        prober.execute {
            var vs: List<Variant> = emptyList()
            try {
                vs = if (isDash(u)) dashVariants(u, h) else variants(u, h)
            } catch (e: Exception) {
                logErr("تحميل", "تعذر قراءة الجودات: " + (e.message ?: e.javaClass.simpleName), e)
            }
            ui.post {
                if (vs.size <= 1) enqueue(u, h, r) else chooseQuality(vs, u, h, r)
            }
        }
    }

    private class HlsVar(val bw: Long, val avg: Long, val w: Int, val h: Int, val codecs: String, val fps: String, val audio: String, val url: String, val hasVideo: Boolean)

    // قيمة خاصية من سطر وسم HLS (تدعم القيم بين علامتي اقتباس وفيها فواصل)
    private fun hlsAttr(l: String, name: String): String? =
        Regex("(?:^|[:,])" + Regex.escape(name) + "=(\"[^\"]*\"|[^,]*)").find(l)?.groupValues?.get(1)?.trim()?.trim('"')

    private fun codecTag(c: String): String {
        val x = c.lowercase()
        return when {
            x.contains("av01") -> "AV1"
            x.contains("hvc1") || x.contains("hev1") || x.contains("dvh") -> "HEVC"
            x.contains("vp09") || x.contains("vp9") -> "VP9"
            x.contains("avc") -> "H.264"
            else -> ""
        }
    }

    // يقرأ القائمة الرئيسية: كل المسارات مع الدقة والنطاق والترميز ومجموعة الصوت، بلا تكرار
    private fun parseMaster(base: String, pl: String): List<HlsVar> {
        val out = ArrayList<HlsVar>()
        val ls = pl.lines()
        for (i in ls.indices) {
            val l = ls[i].trim()
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val nx = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val bw = hlsAttr(l, "BANDWIDTH")?.toLongOrNull() ?: 0L
            val avg = hlsAttr(l, "AVERAGE-BANDWIDTH")?.toLongOrNull() ?: 0L
            val res = hlsAttr(l, "RESOLUTION") ?: ""
            val w = res.substringBefore('x', "").toIntOrNull() ?: 0
            val ht = res.substringAfter('x', "").toIntOrNull() ?: 0
            val codecs = hlsAttr(l, "CODECS") ?: ""
            val fps = hlsAttr(l, "FRAME-RATE") ?: ""
            val grp = hlsAttr(l, "AUDIO") ?: ""
            val cl = codecs.lowercase()
            val hasVideo = ht > 0 || codecs.isEmpty() || Regex("avc|hvc|hev|av01|vp0?9|dvh").containsMatchIn(cl)
            val url = try { absUrl(base, nx.trim()) } catch (e: Throwable) { continue }
            out.add(HlsVar(bw, avg, w, ht, codecs, fps, grp, url, hasVideo))
        }
        // نفس الرابط مرتين: نبقي الأعلى نطاقاً
        return out.groupBy { it.url }.map { g -> g.value.maxByOrNull { it.bw }!! }
    }

    // يستبعد مسارات الصوت فقط إن وُجد مسار فيه صورة، ثم يختار الأعلى (أو الأدنى في وضع التوفير)
    private fun pickHls(vs: List<HlsVar>): HlsVar? {
        val v = vs.filter { it.hasVideo }.ifEmpty { vs }
        return if (lowQ()) v.minByOrNull { it.bw } else v.maxByOrNull { it.bw }
    }

    // رابط مسار الصوت المنفصل (EXT-X-MEDIA TYPE=AUDIO) لمجموعة الصوت المرتبطة بالمسار المختار
    private fun hlsAudioUrl(base: String, pl: String, group: String): String? {
        if (group.isEmpty()) return null
        var first: String? = null
        var def: String? = null
        for (raw in pl.lines()) {
            val l = raw.trim()
            if (!l.startsWith("#EXT-X-MEDIA:")) continue
            if (hlsAttr(l, "TYPE") != "AUDIO" || hlsAttr(l, "GROUP-ID") != group) continue
            val uri = hlsAttr(l, "URI") ?: continue
            val au = try { absUrl(base, uri) } catch (e: Throwable) { continue }
            if (first == null) first = au
            if (def == null && hlsAttr(l, "DEFAULT") == "YES") def = au
        }
        return def ?: first
    }

    private fun variants(u: String, h: Map<String, String>): List<Variant> {
        val pl = text(u, h)
        if (!pl.contains("#EXT-X-STREAM-INF")) return emptyList()
        val all = parseMaster(u, pl)
        val vs = all.filter { it.hasVideo }.ifEmpty { all }
        return vs.sortedByDescending { it.bw }.map { x ->
            val bw = if (x.avg > 0) x.avg else x.bw
            val head = if (x.h > 0) x.h.toString() + "p" else "جودة"
            val fr = x.fps.toDoubleOrNull()?.let { if (it > 0) Math.round(it).toString() + "fps" else "" } ?: ""
            val ct = codecTag(x.codecs)
            val label = head + (if (fr.isNotEmpty()) " " + fr else "") + (if (ct.isNotEmpty()) " " + ct else "") + " • " + (bw / 1000) + " kbps"
            Variant(label, x.url, bw, if (x.h > 0) x.h.toString() + "p" else "")
        }
    }

    private fun chooseQuality(vs: List<Variant>, u: String, h: Map<String, String>, r: Row) {
        val qLabels = ArrayList<String>()
        val qSizes = ArrayList<String>()
        val dur = cands[u]?.meta?.dur ?: -1.0
        qLabels.add("الأعلى جودة (تلقائي)")
        qSizes.add("")
        for (v in vs) {
            qLabels.add(v.label)
            qSizes.add(if (dur > 0 && v.bw > 0) "≈ " + fmtSize(v.bw * dur / 8.0) else "")
        }
        val qHolder = arrayOfNulls<AlertDialog>(1)
        val qRows = qLabels.indices.map { i ->
            DlgStyle.qualityRow(this, qLabels[i], qSizes[i]) { qHolder[0]?.dismiss(); enqueue(if (i == 0) u else vs[i - 1].url, h, r) }
        }
        qHolder[0] = StyledBuilder(this)
            .setTitle("اختر الجودة")
            .setView(DlgStyle.rowsList(this, qRows))
            .setOnCancelListener {
                r.msg.text = ""
                r.btn.isEnabled = true
                r.btn.alpha = 1f
                r.bar.visibility = View.GONE
                r.started = false
                updateCounts()
            }
            .show()
    }

    // يُحقن عند بداية كل مستند وفي كل الإطارات: يلتقط الفيديو من fetch/XHR/عناصر الوسائط، ويتعرف على m3u8/mpd من محتواها ولو كان نوعها عاماً
    private fun hookJs(): String = HOOK_JS.replace("__READCAP__", prefs().getInt("readcap", 0).toString())
        .replace("__STREAM__", if (prefs().getBoolean("scanstream", true)) "1" else "0")
        .replace("__AUDIO__", if (capAudio()) "1" else "0")

    private val HOOK_JS = """(function(){
if(window.__tdlh){return;}
window.__tdlh=1;
var seen={};
function has(){return typeof TDL!=='undefined';}
function rpt(u){
  try{
    if(!u||!has()){return;}
    u=String(u);
    if(u.indexOf('http')!==0){try{u=new URL(u,location.href).href;}catch(e){return;}}
    if(seen[u]){return;}
    seen[u]=1;
    TDL.found(u,location.href);
  }catch(e){}
}
function vtype(ct){
  return ct.indexOf('mpegurl')>=0||ct.indexOf('dash+xml')>=0||ct.indexOf('video/')>=0||(__AUDIO__===1&&ct.indexOf('audio/')>=0);
}
function sniff(u,t){
  try{
    if(!t){return;}
    var s=t.slice(0,2000).replace(/^\uFEFF/,'').trim();
    var k=s.indexOf('<MPD');
    if(s.indexOf('#EXTM3U')===0||(k>=0&&k<400)){rpt(u);}
  }catch(e){}
}
var VRE=/https?:(?:\\?\/){2}[^\s"'<>\\)\]}]+?\.(?:m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp|ogv|wmv|avi|mp3|aac|m4a|opus|flac)(?:\?[^\s"'<>\\)\]}]*)?/gi;
function sc(txt){
  try{
    if(!txt){return;}
    var m=txt.match(VRE),i,u;
    if(m){for(i=0;i<m.length;i++){
      u=m[i].replace(/\\u0026/g,'&').replace(/\\u002F/gi,'/').replace(/\\\//g,'/').replace(/&amp;/g,'&').replace(/\\/g,'');
      rpt(u);
    }}
  }catch(e){}
}
try{
  var ofe=window.fetch;
  if(ofe){window.fetch=function(){
    var a=arguments;
    var p=ofe.apply(this,a);
    try{
      p.then(function(r){
        try{
          var ct=(r.headers.get('content-type')||'').toLowerCase();
          var ru=r.url||(typeof a[0]==='string'?a[0]:((a[0]&&a[0].url)||''));
          if(vtype(ct)){rpt(ru);return;}
          if(/^(image|font|audio)\//.test(ct)||/css|html/.test(ct)){return;}
          var len=parseInt(r.headers.get('content-length')||'0',10);
          if(__STREAM__===0&&/event-stream/.test(ct)){return;}
          var txt=/json|text|javascript|xml/.test(ct);
          if(txt||!len||__READCAP__===0||len<__READCAP__*1024){
            r.clone().text().then(function(t){sniff(ru,t);sc(t);}).catch(function(){});
          }
        }catch(e){}
      }).catch(function(){});
    }catch(e){}
    return p;
  };}
}catch(e){}
try{
  var xo=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(m,u){
    var x=this;
    this.addEventListener('readystatechange',function(){
      try{
        if(x.readyState===2){
          var c2=(x.getResponseHeader('content-type')||'').toLowerCase();
          if(vtype(c2)){rpt(x.responseURL||u);}
        }
        if(x.readyState===4&&(x.responseType===''||x.responseType==='text')){
          var ct=(x.getResponseHeader('content-type')||'').toLowerCase();
          if(vtype(ct)||/^(image|font|audio)\//.test(ct)||/css|html/.test(ct)){return;}
          var t=x.responseText;
          if(t){sniff(x.responseURL||u,t);sc(t);}
        }
      }catch(e){}
    });
    return xo.apply(this,arguments);
  };
}catch(e){}
try{
  var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
  if(d&&d.set){
    Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:true,enumerable:d.enumerable,get:d.get,set:function(v){
      try{if(typeof v==='string'&&v.indexOf('http')===0){rpt(v);}}catch(e){}
      return d.set.call(this,v);
    }});
  }
}catch(e){}
try{
  var sa=Element.prototype.setAttribute;
  Element.prototype.setAttribute=function(n,v){
    try{
      if(n==='src'&&typeof v==='string'&&v.indexOf('http')===0){
        var tg=this.tagName;
        if(tg==='VIDEO'||tg==='SOURCE'){rpt(v);}
      }
    }catch(e){}
    return sa.apply(this,arguments);
  };
}catch(e){}
try{
  setInterval(function(){
    var vs=document.querySelectorAll('video'),i;
    for(i=0;i<vs.length;i++){
      var v=vs[i];
      try{
        if(v.preload==='none'){v.preload='metadata';}
        var cs=v.currentSrc||v.src;
        if(cs&&cs.indexOf('http')===0){rpt(cs);}
      }catch(e){}
    }
  },1500);
}catch(e){}
})();
"""


    private fun w2rescan() {
        try { cur?.web?.evaluateJavascript(RESCAN_JS, null) } catch (e: Throwable) {}
    }

    // يمرر الصفحة تدريجياً حتى نهاية القائمة، ويستخرج كل روابط الفيديو من العناصر والسكربتات وطلبات الشبكة دفعة واحدة
    private fun capturePage() {
        val t = cur ?: return
        if (t.scanning) {
            t.scanning = false
            t.web.evaluateJavascript("window.__tdlRid=(window.__tdlRid||0)+1;window.__tdlScan=0;", null)
            capNote("فحص", t.lastUrl, "أوقفه المستخدم يدوياً")
            w2rescan()
            toast("تم إيقاف الفحص")
            return
        }
        if (t.web.url.isNullOrEmpty() || t.start) { toast("افتح صفحة أولاً"); return }
        t.scanning = true
        capNote("فحص", t.lastUrl, "بدأ التمرير التلقائي والتقاط كل الفيديوهات")
        toast("جارٍ التمرير والالتقاط. اضغط الزر مرة أخرى لإيقافه")
        val js = CAPTURE_JS.replace("__MAXSTEPS__", prefs().getInt("scanmax", 0).toString())
            .replace("__STILL__", prefs().getInt("scanstill", 6).toString())
            .replace("__PLAY__", if (prefs().getBoolean("scanplay", false)) "1" else "0")
            .replace("__PLAYMS__", "0")
            .replace("__DELAY__", prefs().getInt("scandelay", 750).coerceAtLeast(50).toString())
        t.web.evaluateJavascript(js, null)
    }

    private val CAPTURE_JS = """(function(){
if(window.__tdlScan){return;}
window.__tdlScan=1;
var RID=window.__tdlRid=(window.__tdlRid||0)+1;
var seen={},cnt=0,steps=0,i,j;
var EXT=/\.(?:m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp|ogv|wmv|avi|mp3|aac|m4a|opus|flac)(?:[?#]|$)/i;
var RE=/https?:(?:\\?\/){2}[^\s"'<>\\)\]}]+?\.(?:m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp|ogv|wmv|avi|mp3|aac|m4a|opus|flac)(?:\?[^\s"'<>\\)\]}]*)?/gi;
function clean(u){return u.replace(/\\u0026/g,'&').replace(/\\u002F/gi,'/').replace(/\\\//g,'/').replace(/&amp;/g,'&').replace(/\\/g,'');}
function rpt(u){
  try{
    if(!u){return;}
    u=String(u);
    if(u.indexOf('http')!==0){try{u=new URL(u,location.href).href;}catch(e){return;}}
    if(seen[u]){return;}
    seen[u]=1;cnt++;
    TDL.found(u,location.href);
  }catch(e){}
}
function scanText(t){
  try{
    if(!t){return;}
    var m=t.match(RE),k;
    if(m){for(k=0;k<m.length;k++){rpt(clean(m[k]));}}
  }catch(e){}
}
function scanDoc(doc){
  try{
    var q=doc.querySelectorAll('video,source,a[href],[data-src],[data-video],[data-url],[data-hls],[data-mp4],[data-stream]');
    var at=['src','href','data-src','data-video','data-url','data-hls','data-mp4','data-stream'];
    for(i=0;i<q.length;i++){
      var el=q[i];
      if(el.tagName==='VIDEO'){
        if(el.currentSrc&&el.currentSrc.indexOf('http')===0){rpt(el.currentSrc);}
        try{if(!el.src&&!el.currentSrc){el.preload='metadata';el.load();}}catch(e){}
        if(PLAY&&!el.__tdlp){
          el.__tdlp=1;
          (function(vd){try{var pr=vd.play();if(pr&&pr.then){pr.then(function(){if(PLAYMS>0){setTimeout(function(){try{vd.pause();}catch(e){}},PLAYMS);}}).catch(function(){});}}catch(e){}})(el);
        }
      }
      for(j=0;j<at.length;j++){
        var v=el.getAttribute(at[j]);
        if(v&&EXT.test(v)){rpt(v);}
      }
    }
    var ss=doc.querySelectorAll('script:not([src])');
    for(i=0;i<ss.length;i++){
      var tx=ss[i].textContent;
      if(tx){scanText(tx);}
    }
  }catch(e){}
}
function scanDom(){
  try{
    scanDoc(document);
    var fr=document.querySelectorAll('iframe');
    for(var f=0;f<fr.length;f++){try{if(fr[f].contentDocument){scanDoc(fr[f].contentDocument);}}catch(e){}}
    var es=performance.getEntriesByType('resource');
    for(i=0;i<es.length;i++){
      var n=es[i].name;
      if(EXT.test(n)||/mpegurl|manifest/i.test(n)){rpt(n);}
    }
  }catch(e){}
}
function scrollers(){
  var r=[],all=document.querySelectorAll('*'),k;
  for(k=0;k<all.length;k++){
    var e=all[k];
    if(e.scrollHeight>e.clientHeight+80&&e.clientHeight>120){
      var o=getComputedStyle(e).overflowY;
      if(o==='auto'||o==='scroll'||o==='overlay'){r.push(e);}
    }
  }
  r.sort(function(a,b){return b.scrollHeight-a.scrollHeight;});
  return r;
}
var last=-1,still=0,MAXS=__MAXSTEPS__,STILL=__STILL__,PLAY=__PLAY__,PLAYMS=__PLAYMS__,DELAY=__DELAY__;
var sc=scrollers();
function finish(){
  if(window.__tdlRid!==RID){return;}
  try{scanDom();}catch(e){}
  try{window.scrollTo(0,0);}catch(e){}
  try{TDL.scanDone(cnt,steps);}catch(e){}
  window.__tdlScan=0;
}
function tick(){
  if(window.__tdlRid!==RID){return;}
  try{
    steps++;
    scanDom();
    var de=document.scrollingElement||document.documentElement;
    var h=de.scrollHeight,tot=h,k;
    window.scrollBy(0,Math.max(300,window.innerHeight*0.85));
    for(k=0;k<sc.length;k++){tot+=sc[k].scrollHeight;sc[k].scrollTop+=Math.max(300,sc[k].clientHeight*0.85);}
    var atBottom=(window.innerHeight+window.pageYOffset>=de.scrollHeight-4);
    for(k=0;k<sc.length;k++){if(sc[k].scrollTop+sc[k].clientHeight<sc[k].scrollHeight-4){atBottom=false;}}
    tot+=cnt*1000+performance.getEntriesByType('resource').length;
    if(tot===last&&atBottom){still++;}else{still=0;}
    last=tot;
    if(still>=STILL||(MAXS>0&&steps>=MAXS)){setTimeout(finish,DELAY*2);return;}
    setTimeout(tick,DELAY);
  }catch(e){finish();}
}
tick();
})();
"""

    // إعادة مسح سريعة لما حمّلته الصفحة (تُستعمل عند الضغط على فيديو blob ولا يوجد رابط ملتقط)
    private val RESCAN_JS = """(function(){
try{
  if(typeof TDL==='undefined'){return;}
  var es=performance.getEntriesByType('resource');
  for(var i=0;i<es.length;i++){
    var n=es[i].name;
    if(/\.(?:m3u8|mpd|mp4|webm|m4v|mov|mkv|flv)(?:[?#]|\b)/i.test(n)||/mpegurl|manifest/i.test(n)){TDL.found(n,location.href);}
  }
}catch(e){}
})();
"""

    private fun overlayJs(): String = OVERLAY_JS.replace("__MAXN__", prefs().getInt("maxoverlay", 0).toString())

    private val OVERLAY_JS = """(function(){
if(window.__tdl){return;}
window.__tdl=1;
var map=new Map();
var MAXN=__MAXN__;
function has(){return typeof TDL!=='undefined';}
var rpt={};
function rep(u,ct){
  try{
    if(!has()||!u){return;}
    ct=(ct||'').toLowerCase();
    if(ct.indexOf('mpegurl')>=0||ct.indexOf('dash+xml')>=0||ct.indexOf('video/')>=0){
      u=String(u);
      if(!rpt[u]){rpt[u]=1;TDL.found(u,location.href);}
    }
  }catch(e){}
}
try{
  var ofe=window.__tdlh?null:window.fetch;
  if(ofe){window.fetch=function(){
    var a=arguments;
    return ofe.apply(this,a).then(function(r){
      try{
        var ct=r.headers.get('content-type')||'';
        rep(r.url||a[0],ct);
        if(/json|text|javascript/i.test(ct)){
          r.clone().text().then(function(t){if(t){sc(t);}}).catch(function(){});
        }
      }catch(e){}
      return r;
    });
  };}
  var xo=XMLHttpRequest.prototype.open;
  if(!window.__tdlh)XMLHttpRequest.prototype.open=function(m,u){
    var x=this;
    this.addEventListener('readystatechange',function(){
      if(x.readyState===2){try{rep(x.responseURL||u,x.getResponseHeader('content-type'));}catch(e){}}
      if(x.readyState===4){
        try{
          var ct=x.getResponseHeader('content-type')||'';
          if((x.responseType===''||x.responseType==='text')&&/json|text|javascript/i.test(ct)){
            var t=x.responseText;if(t){sc(t);}
          }
        }catch(e){}
      }
    });
    return xo.apply(this,arguments);
  };
}catch(e){}
var VRE=/https?:(?:\\?\/){2}[^\s"'<>\\)\]}]+?\.(?:m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp)(?:\?[^\s"'<>\\)\]}]*)?/gi;
var RRE=/["'](\/[^"'\s<>\\]+?\.(?:m3u8|mpd|mp4|m4v|mov|webm)(?:\?[^"'\s<>\\]*)?)["']/gi;
function sc(txt){
  try{
    if(!txt||!has()){return;}
    var m=txt.match(VRE),i,u;
    if(m){for(i=0;i<m.length;i++){
      u=m[i].replace(/\\u0026/g,'&').replace(/\\u002F/gi,'/').replace(/\\\//g,'/').replace(/&amp;/g,'&').replace(/\\/g,'');
      if(!rpt[u]){rpt[u]=1;TDL.found(u,location.href);}
    }}
    var r2=txt.match(RRE);
    if(r2){for(i=0;i<r2.length&&i<200;i++){
      try{u=new URL(r2[i].slice(1,-1),location.href).href;}catch(e){continue;}
      if(!rpt[u]){rpt[u]=1;TDL.found(u,location.href);}
    }}
  }catch(e){}
}
var lastLen=-1;
function deep(){
  try{
    var es=performance.getEntriesByType('resource');
    for(var i=0;i<es.length;i++){
      var n=es[i].name,p=n.split('?')[0].toLowerCase();
      if(!rpt[n]&&(es[i].initiatorType==='video'||/\.(m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp)$/.test(p))){
        rpt[n]=1;if(has()){TDL.found(n,location.href);}
      }
    }
  }catch(e){}
  try{
    var html=document.documentElement.innerHTML;
    if(html.length!==lastLen){lastLen=html.length;sc(html);}
  }catch(e){}
}
var POS={fx:0.5,fy:0.04};
try{
  var sp=localStorage.getItem('__tdlpos');
  if(sp){
    var po=JSON.parse(sp);
    if(isFinite(po.fx)&&isFinite(po.fy)){POS.fx=Math.min(1,Math.max(0,po.fx));POS.fy=Math.min(1,Math.max(0,po.fy));}
  }
}catch(e){}
function savePos(){try{localStorage.setItem('__tdlpos',JSON.stringify(POS));}catch(e){}}
var P_LINK='M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z';
var P_DL='M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z';
var P_RT='M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z';
function ico(d){
  var NS='http://www.w3.org/2000/svg';
  var sv=document.createElementNS(NS,'svg');
  sv.setAttribute('viewBox','0 0 24 24');
  sv.setAttribute('width','20');sv.setAttribute('height','20');
  sv.setAttribute('fill','#fff');
  var pa=document.createElementNS(NS,'path');
  pa.setAttribute('d',d);
  sv.appendChild(pa);
  return sv;
}
function mk(d,fn,st){
  var b=document.createElement('div');
  b.appendChild(ico(d));
  b.setAttribute('style','flex:0 0 auto;width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.16);border-radius:16px;cursor:pointer;-webkit-user-select:none;user-select:none;');
  b.addEventListener('click',function(e){
    e.stopPropagation();e.preventDefault();
    if(st&&st.moved){st.moved=false;return;}
    fn();
  },true);
  return b;
}
function srcOf(v){
  var s=v.currentSrc||v.src||'';
  if(!s){var so=v.querySelector('source');if(so){s=so.src||'';}}
  return s;
}
function fmt(t){
  if(!isFinite(t)){return '0:00';}
  t=Math.floor(t);
  var m=Math.floor(t/60),s=t%60;
  return m+':'+(s<10?'0':'')+s;
}
function visRect(el){
  var r=el.getBoundingClientRect();
  var l=Math.max(r.left,0),t=Math.max(r.top,0);
  var rr=Math.min(r.right,window.innerWidth),bb=Math.min(r.bottom,window.innerHeight);
  return {l:l,t:t,w:Math.max(rr-l,0),h:Math.max(bb-t,0)};
}
function place(st,el){
  var v=visRect(el);
  var bw=st.box.offsetWidth,bh=st.box.offsetHeight;
  var x=v.l+POS.fx*Math.max(v.w-bw,0);
  var y=v.t+POS.fy*Math.max(v.h-bh,0);
  st.box.style.transform='translate('+Math.round(x)+'px,'+Math.round(y)+'px)';
}
function setSl(st,f){
  st.fil.style.width=(f*100)+'%';
  st.thb.style.left=(f*100)+'%';
}
// أيقونة واحدة لكل فيديو: [نسخ الرابط] [شريط المدة] [تحميل]، وتُسحب بإصبع من أي مكان فيها عدا الشريط
function setup(el){
  var st={box:null,isV:true,ok:false,stalled:false,wt:null,drag:false,dragging:false,moved:false,errSent:false,sl:null,tm:null,rt:null,mid:null,lastTxt:'',lastW:''};
  var box=document.createElement('div');
  box.className='__tdl';
  box.setAttribute('style','position:fixed;left:0;top:0;z-index:2147483647;display:none;flex-direction:row;align-items:center;gap:6px;padding:5px 7px;background:rgba(0,0,0,.74);border-radius:24px;direction:ltr;box-sizing:border-box;will-change:transform;touch-action:none;pointer-events:auto;-webkit-user-select:none;user-select:none;');
  var cp=mk(P_LINK,function(){
    if(!has()){return;}
    TDL.copyVideo(srcOf(el));
  },st);
  var mid=document.createElement('div');
  mid.setAttribute('style','flex:1 1 auto;min-width:0;display:flex;flex-direction:column;align-items:stretch;justify-content:center;');
  var grip=document.createElement('div');
  grip.setAttribute('style','width:30px;height:4px;border-radius:2px;background:rgba(255,255,255,.75);margin:0 auto 3px auto;');
  var sl=document.createElement('div');
  sl.setAttribute('style','position:relative;width:100%;height:20px;margin:0;padding:0;display:none;touch-action:none;');
  var trk=document.createElement('div');
  trk.setAttribute('style','position:absolute;left:0;right:0;top:8px;height:4px;border-radius:2px;background:rgba(255,255,255,.3);');
  var fil=document.createElement('div');
  fil.setAttribute('style','position:absolute;left:0;top:8px;height:4px;width:0;border-radius:2px;background:#5B9BFF;');
  var thb=document.createElement('div');
  thb.setAttribute('style','position:absolute;top:4px;left:0;width:12px;height:12px;margin-left:-6px;border-radius:6px;background:#5B9BFF;box-shadow:0 0 0 2px rgba(0,0,0,.35);');
  sl.appendChild(trk);sl.appendChild(fil);sl.appendChild(thb);
  st.fil=fil;st.thb=thb;st.val=0;
  var tm=document.createElement('div');
  tm.setAttribute('style','font:11px sans-serif;color:#fff;text-align:center;display:none;');
  mid.appendChild(grip);
  mid.appendChild(sl);
  mid.appendChild(tm);
  var rt=mk(P_RT,function(){
    var t=el.currentTime;
    if(el.error||el.networkState===3){
      try{el.load();}catch(e){}
      var f=function(){
        el.removeEventListener('loadedmetadata',f);
        try{el.currentTime=t;}catch(e){}
        try{el.play();}catch(e){}
      };
      el.addEventListener('loadedmetadata',f);
    }else{
      try{el.play();}catch(e){}
    }
    st.stalled=false;st.errSent=false;
  },st);
  rt.style.display='none';
  var dl=mk(P_DL,function(){
    if(!has()){return;}
    TDL.video(srcOf(el),location.href);
  },st);
  box.appendChild(cp);
  box.appendChild(mid);
  box.appendChild(rt);
  box.appendChild(dl);
  st.sl=sl;st.tm=tm;st.rt=rt;st.mid=mid;
  var stop=function(e){e.stopPropagation();};
  ['touchstart','touchmove','touchend','mousedown','mouseup','click'].forEach(function(n){sl.addEventListener(n,stop,false);});
  var seekAt=function(cx){
    var rc=sl.getBoundingClientRect();
    var f=rc.width>0?Math.min(1,Math.max(0,(cx-rc.left)/rc.width)):0;
    st.val=Math.round(f*1000);
    setSl(st,f);
    if(isFinite(el.duration)&&el.duration>0){try{el.currentTime=el.duration*f;}catch(e){}}
  };
  sl.addEventListener('touchstart',function(e){st.drag=true;seekAt(e.touches[0].clientX);e.preventDefault();},{passive:false});
  sl.addEventListener('touchmove',function(e){if(st.drag){seekAt(e.touches[0].clientX);e.preventDefault();}},{passive:false});
  var sEnd=function(){st.drag=false;};
  sl.addEventListener('touchend',sEnd,false);
  sl.addEventListener('touchcancel',sEnd,false);
  var mm=function(e){if(st.drag){seekAt(e.clientX);}};
  sl.addEventListener('mousedown',function(e){st.drag=true;seekAt(e.clientX);
    var up=function(){st.drag=false;document.removeEventListener('mousemove',mm,true);document.removeEventListener('mouseup',up,true);};
    document.addEventListener('mousemove',mm,true);document.addEventListener('mouseup',up,true);});
  // سحب الأيقونة من مكانها (يُحفظ المكان ويُطبَّق على كل الفيديوهات)
  var tx0=0,ty0=0,bx0=0,by0=0,tr=false;
  box.addEventListener('touchstart',function(e){
    if(e.touches.length!==1){return;}
    var t=e.touches[0],rc=box.getBoundingClientRect();
    tx0=t.clientX;ty0=t.clientY;bx0=rc.left;by0=rc.top;tr=true;st.dragging=false;
  },{passive:true});
  box.addEventListener('touchmove',function(e){
    if(!tr){return;}
    var t=e.touches[0],dx=t.clientX-tx0,dy=t.clientY-ty0;
    if(!st.dragging){
      if(Math.abs(dx)+Math.abs(dy)<8){return;}
      st.dragging=true;st.moved=true;
    }
    e.preventDefault();
    var v=visRect(el),bw=box.offsetWidth,bh=box.offsetHeight;
    var rx=Math.max(v.w-bw,1),ry=Math.max(v.h-bh,1);
    POS.fx=Math.min(1,Math.max(0,(bx0+dx-v.l)/rx));
    POS.fy=Math.min(1,Math.max(0,(by0+dy-v.t)/ry));
    place(st,el);
  },{passive:false});
  var endDrag=function(){
    if(!tr){return;}
    tr=false;
    if(st.dragging){
      st.dragging=false;
      savePos();
      setTimeout(function(){st.moved=false;},350);
    }
  };
  box.addEventListener('touchend',endDrag,{passive:true});
  box.addEventListener('touchcancel',endDrag,{passive:true});
  box.addEventListener('click',function(e){e.stopPropagation();},false);
  var clr=function(){if(st.wt){clearTimeout(st.wt);st.wt=null;}st.stalled=false;st.errSent=false;};
  el.addEventListener('waiting',function(){
    if(!st.wt){st.wt=setTimeout(function(){st.stalled=true;st.wt=null;},6000);}
  });
  el.addEventListener('playing',clr);
  el.addEventListener('canplay',clr);
  el.addEventListener('timeupdate',function(){if(st.wt||st.stalled){clr();}});
  el.addEventListener('error',function(){
    st.stalled=true;
    if(!st.errSent&&has()){
      st.errSent=true;
      var c=el.error?el.error.code:0;
      TDL.err('توقف الفيديو (رمز '+c+'): '+srcOf(el).slice(0,150));
    }
  });
  document.body.appendChild(box);
  st.box=box;
  map.set(el,st);
}
// خفيف: يُستدعى أثناء التمرير وكل 400 مللي ثانية، يحرّك الأيقونة مع الفيديو
function upd(){
  var vw=window.innerWidth,vh=window.innerHeight;
  map.forEach(function(st,el){
    if(!st.ok){if(st.box.style.display!=='none'){st.box.style.display='none';}return;}
    var r=el.getBoundingClientRect();
    if(r.width<1||r.bottom<0||r.top>vh||r.right<0||r.left>vw){
      if(st.box.style.display!=='none'){st.box.style.display='none';}
      return;
    }
    if(st.box.style.display!=='flex'){st.box.style.display='flex';}
    var d=el.duration;
    var ok=isFinite(d)&&d>0;
    var dsp=ok?'block':'none';
    if(st.sl.style.display!==dsp){st.sl.style.display=dsp;st.tm.style.display=dsp;}
    var w=ok?Math.max(170,Math.min(r.width-12,300))+'px':'';
    if(w!==st.lastW){st.lastW=w;st.box.style.width=w;}
    if(ok){
      if(!st.drag){setSl(st,Math.min(1,Math.max(0,el.currentTime/d)));}
      var tx=fmt(el.currentTime)+' / '+fmt(d);
      if(tx!==st.lastTxt){st.lastTxt=tx;st.tm.textContent=tx;}
    }
    var rs=(st.stalled||el.error)?'block':'none';
    if(st.rt.style.display!==rs){st.rt.style.display=rs;}
    if(!st.dragging){place(st,el);}
  });
}
function scan(){
  var list=document.querySelectorAll('video');
  for(var j=0;j<list.length;j++){
    var cs0=list[j].currentSrc||list[j].src||'';
    if(cs0.indexOf('http')===0&&!rpt[cs0]&&has()){rpt[cs0]=1;try{TDL.found(cs0,location.href);}catch(e){}}
  }
  for(var i=0;i<list.length;i++){
    var el=list[i];
    if(!map.has(el)&&(MAXN<=0||map.size<MAXN)){
      if(el.tagName==='VIDEO'){setup(el);}
    }
  }
  map.forEach(function(st,el){
    if(!document.contains(el)){
      if(st.box&&st.box.parentNode){st.box.parentNode.removeChild(st.box);}
      map.delete(el);
    }else{
      var r2=el.getBoundingClientRect();
      var cs=window.getComputedStyle(el);
      var big=st.isV?(r2.width>=80&&r2.height>=60):(r2.width>=100&&r2.height>=100);
      st.ok=big&&cs.display!=='none'&&cs.visibility!=='hidden';
    }
  });
  upd();
}
var tick=false;
function sched(){
  if(tick){return;}
  tick=true;
  requestAnimationFrame(function(){tick=false;upd();});
}
window.addEventListener('scroll',sched,{capture:true,passive:true});
window.addEventListener('touchmove',sched,{capture:true,passive:true});
window.addEventListener('resize',sched,false);
setInterval(function(){if(!document.hidden){upd();}},400);
setInterval(function(){if(!document.hidden){scan();}},1200);
setInterval(function(){if(!document.hidden){deep();}},2500);
scan();
deep();
})();
"""

    // ---------- قائمة الروابط الملتقطة ----------

    private fun updateCounts() {
        val n = found.size
        val cap = allRows().count { it.state == 0 && !it.started }
        // الوميض الأحمر عند التقاط فيديو جديد فقط، ويتوقف بفتح العداد
        if (cap > lastFound) badgeSeen = (dlView == 1)
        lastFound = cap
        badgeBox.visibility = View.VISIBLE
        badgeNum.text = cap.toString()
        badgeNum.visibility = if (cap > 0) View.VISIBLE else View.GONE
        if (badgeSeen || cap == 0) { badgeNum.clearAnimation(); badgeNum.alpha = 1f }
        else if (badgeNum.animation == null) {
            val an = android.view.animation.AlphaAnimation(1f, 0.25f)
            an.duration = 600
            an.repeatMode = android.view.animation.Animation.REVERSE
            an.repeatCount = android.view.animation.Animation.INFINITE
            badgeNum.startAnimation(an)
        }
        badgeBox.background = if (dlView == 1) bg(cAccent, 22, Color.WHITE) else bg(cAccent, 22)
        tabDl.background = if (dlView == 0) bg(cAccent, 22, Color.WHITE) else bg(cAccent, 22)
        dlActions.visibility = View.VISIBLE
        val act = activeBox.childCount
        navItems[1].second.text = if (act > 0) "جارٍ ($act)" else if (n > 0) "التنزيلات ($n)" else "التنزيلات"

        val rows = allRows()
        var visAct = 0
        var visList = 0
        for (r in rows) {
            val show = inFilter(r)
            r.view.visibility = if (show) View.VISIBLE else View.GONE
            if (show) { if (r.view.parent === activeBox) visAct++ else visList++ }
        }
        activeTitle.text = "جارٍ التحميل الآن ($visAct)"
        activeTitle.visibility = if (visAct > 0) View.VISIBLE else View.GONE
        listTitle.visibility = if (visList > 0) View.VISIBLE else View.GONE
        val none = visAct + visList == 0
        emptyTv.visibility = if (none && (dlView == 1 || n == 0)) View.VISIBLE else View.GONE
        emptyTv.text = if (dlView == 1) "لا توجد فيديوهات ملتقطة" else "لا توجد روابط ملتقطة بعد"
        if (navItems.size > 4) navItems[4].second.text = if (alerts.isNotEmpty()) "التنبيهات (" + alerts.size + ")" else "التنبيهات"
        alertEmpty.visibility = if (alerts.isNotEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearFound() {
        for (cc in cands.values) if (cc.rw.view.parent !== activeBox) cc.rw.thumbStop = true
        for (cc in cands.values) forgetTask(cc.rw.id)
        list.removeAllViews()
        found.clear()
        cands.clear()
        considered.clear()
        subUrls.clear()
        foundStart.clear()
        foundHdr.clear()
        updateCounts()
    }

    private fun isHls(u: String): Boolean {
        val l = u.lowercase()
        return l.contains(".m3u8") || l.contains("mpegurl") || hlsSet.contains(u)
    }

    private fun fmtDur(sec: Double): String = fmtTime(sec.toLong())

    private fun dropCand(c: Cand) {
        c.rw.thumbStop = true
        forgetTask(c.rw.id)
        list.removeView(c.view)
        found.remove(c.url)
        foundStart.remove(c.url)
        foundHdr.remove(c.url)
        cands.remove(c.url)
    }

    private fun tabKey(t: Tab?): String = System.identityHashCode(t).toString() + "|"

    // فيديوهات التبويب الحالي المسجلة (غير البث المباشر)
    private fun candsOf(t: Tab?): List<Cand> =
        if (t == null) emptyList() else cands.values.filter { it.owner === t && it.meta?.vod != false }

    // أفضل مرشح للتبويب: قوائم البث (m3u8/mpd) أولاً ثم الأطول مدة والأكبر حجماً، وعند التساوي الأحدث
    private fun bestFor(t: Tab?): Cand? {
        val l = candsOf(t)
        var best: Cand? = null
        var bs = -1.0
        for (c in l) {
            val m = c.meta
            var sc = 0.0
            if (isHls(c.url) || isDash(c.url)) sc += 1e12
            if (m != null) {
                if (m.dur > 0) sc += m.dur * 1000.0
                if (m.size > 0) sc += m.size / 1024.0
            }
            if (sc >= bs) {
                bs = sc
                best = c
            }
        }
        return best
    }

    // الانتقال لصفحة أخرى: نمسح روابط هذا التبويب التي لم يبدأ تحميلها حتى لا تختلط فيديوهات الصفحات
    private fun pruneTab(t: Tab) {
        val k = tabKey(t)
        considered.removeAll { it.startsWith(k) }
        for (c in ArrayList(cands.values)) {
            if (c.owner === t && c.rw.btn.isEnabled && c.rw.bar.visibility != View.VISIBLE) dropCand(c)
        }
        updateCounts()
    }

    private fun startCand(c: Cand) {
        val go = foundStart[c.url]
        if (go == null) {
            toast("تعذر بدء التحميل")
            return
        }
        go()
        toast("تابع التحميل من تبويب التنزيلات")
    }

    // ---------- حفظ مهام التنزيل واستعادتها (كل مهمة مستقلة) ----------

    private val taskIo = Executors.newSingleThreadExecutor()

    private fun engineFor(prot: Int, fmt: String): Int {
        val kind = when (fmt) {
            "HLS/M3U8" -> "HLS"
            "DASH/MPD" -> "DASH"
            else -> "FILE"
        }
        return PlayerCore.choose(PlayerCore.Probe(kind, prot, false, null, ""), fmt.lowercase(java.util.Locale.US))
    }

    // يحفظ حالة المهمة الآن (يُستدعى من الخيط الرئيسي)
    private fun saveTask(r: Row) {
        if (r.quiet || r.removed || r.id.isEmpty()) return
        val c = cands[r.url] ?: return
        val m = c.meta
        val mj = if (m != null) TaskStore.metaToJson(m.vod, m.dur, m.size, m.sizeEst, m.res, m.kbps, m.subs, m.conf, m.why) else null
        val rec = TaskRec(
            r.id, r.url, foundHdr[r.url] ?: emptyMap(), c.pageTitle, c.hostTxt, mj,
            r.state, r.bar.progress, r.bar.max, r.ctl.bytes.get(), r.msg.text.toString(),
            r.kindProt, r.kindFmt, r.engine, r.finalLoc, r.offlineId, r.protectedStream,
            r.watch?.visibility == View.VISIBLE, foundName[r.url], foundMime[r.url], r.created, System.currentTimeMillis(), r.pageUrl
        )
        try { taskIo.execute { TaskStore.save(this, rec) } } catch (e: Throwable) {}
    }

    private fun forgetTask(id: String) {
        if (id.isEmpty()) return
        try { taskIo.execute { TaskStore.delete(this, id) } } catch (e: Throwable) {}
    }

    private fun metaFromJson(o: org.json.JSONObject?): Meta? {
        if (o == null) return null
        val subs = ArrayList<String>()
        val a = o.optJSONArray("subs")
        if (a != null) for (i in 0 until a.length()) subs.add(a.optString(i))
        return Meta(o.optBoolean("vod", true), o.optDouble("dur", -1.0), o.optLong("size"), o.optBoolean("sizeEst"),
            o.optString("res"), o.optLong("kbps"), subs, o.optInt("conf", 100), o.optString("why"))
    }

    // يقرأ المهام المحفوظة في الخلفية ثم يعيدها إلى القائمة بحالاتها
    private fun restoreTasks() {
        Thread {
            val recs = try { TaskStore.load(this) } catch (e: Throwable) { emptyList<TaskRec>() }
            ui.post { for (rec in recs) try { restoreOne(rec) } catch (e: Throwable) { logErr("استعادة", "تعذر استعادة مهمة: " + (e.message ?: e.javaClass.simpleName), e) } }
        }.start()
    }

    private fun finalExists(loc: String): Boolean = try {
        if (loc.startsWith("content:")) {
            contentResolver.openFileDescriptor(Uri.parse(loc), "r")?.use { true } ?: false
        } else File(loc).exists()
    } catch (e: Throwable) {
        false
    }

    private fun restoreOne(rec: TaskRec) {
        val u = rec.url
        if (found.contains(u)) return
        rec.fname?.let { foundName[u] = it }
        rec.mime?.let { foundMime[u] = it }
        addFound(u, rec.hdr, metaFromJson(rec.meta), null, rec.pageTitle, rec)
        val c = cands[u] ?: return
        val r = c.rw
        r.quiet = true
        r.created = rec.created
        rec.thumbBmp?.let { setThumb(r, it) }
        if (rec.kindFmt.isNotEmpty()) setKind(r, rec.kindProt, rec.kindFmt)
        r.engine = if (rec.engine != 0) rec.engine else if (rec.kindFmt.isNotEmpty()) engineFor(rec.kindProt, rec.kindFmt) else 0
        var st = rec.state
        var msg = rec.msg
        when (st) {
            3 -> {
                val oid = rec.offlineId
                val fl = rec.finalLoc
                if (oid != null && OfflineDrm.get(this, oid) != null) {
                    r.offlineId = oid
                } else if (oid == null && fl != null && finalExists(fl)) {
                    r.finalLoc = fl
                    r.finalIsUri = fl.startsWith("content:")
                } else {
                    st = 4
                    msg = "الملف المحفوظ لم يعد موجوداً. اضغط «تحميل» لإعادة التنزيل."
                }
            }
            1, 2 -> {
                st = 4
                msg = "توقف التحميل لأن التطبيق أُغلق. اضغط «تحميل» للمتابعة من حيث توقف."
            }
        }
        if (rec.protectedStream && st == 4) {
            r.protectedStream = true
            r.srcUrl = u
            r.srcHdr = rec.hdr
        }
        if (st == 3 || st == 4) {
            r.msg.text = msg
            if (rec.total > 0) {
                r.bar.max = rec.total
                r.bar.progress = if (st == 3) rec.total else rec.done
                r.bar.visibility = View.VISIBLE
            }
        }
        if (st == 3) {
            r.btn.isEnabled = false
            r.btn.alpha = 0.5f
        }
        // البيانات الخام المشفّرة تبقى مكتملة لكن زر المشاهدة مخفي كما كان قبل الإغلاق
        r.watch?.visibility = if ((st == 3 && rec.watchOk && (r.finalLoc != null || r.offlineId != null)) || (st == 4 && r.protectedStream)) View.VISIBLE else View.GONE
        val wk = File(getExternalFilesDir(null) ?: filesDir, "work_" + r.id)
        if (st == 4 && wk.exists()) {
            r.work = wk
            r.watch?.visibility = View.VISIBLE
        }
        setState(r, st)
        r.quiet = false
        saveTask(r)
        // مهمة لم تُفحص صيغتها قبل الإغلاق: نفحصها الآن
        if (rec.kindFmt.isEmpty()) classifyCand(c, rec.hdr)
    }

    // ---------- الصور المصغّرة وحالة الحماية والصيغة ----------

    private class Kind(val prot: Int, val fmt: String)

    private fun shrinkBmp(b: android.graphics.Bitmap): android.graphics.Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= 320) return b
        val k = 320f / m
        return android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
    }

    // الفيديو حُذف من القائمة: لا فائدة من جلب صورته
    private fun gone(r: Row): Boolean = r.removed || r.thumbStop

    // صورة كل فيديو تُحفظ مرة واحدة بعد جلبها، فإذا عاد الفيديو للالتقاط (بعد التنقل بين الصفحات) تُستخدم المحفوظة ولا تُطلب من جديد
    private val videoThumbs = object : LinkedHashMap<String, android.graphics.Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean = size > 64
    }

    private fun setThumb(r: Row, b: android.graphics.Bitmap?) {
        val iv = r.thumb ?: return
        if (b == null) return
        iv.clearColorFilter()
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(b)
        r.thumbSet = true
        if (r.url.isNotEmpty()) synchronized(videoThumbs) { videoThumbs[r.url] = b }
        if (!r.quiet && r.id.isNotEmpty()) {
            val id = r.id
            try { taskIo.execute { TaskStore.saveThumb(this, id, b) } } catch (e: Throwable) {}
            saveTask(r)
        }
    }

    private fun readLimited(s: java.io.InputStream, max: Int, stop: () -> Boolean = { false }): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (o.size() < max) {
            if (stop()) break
            val n = s.read(buf, 0, minOf(buf.size, max - o.size()))
            if (n < 0) break
            o.write(buf, 0, n)
        }
        return o.toByteArray()
    }

    // صورة المعاينة التي يوفرها الموقع (poster أو og:image): تُنزَّل مرة واحدة فقط لكل رابط وتُستخدم لكل الفيديوهات التي تشترك فيها، وتتوقف إذا حُذفت كل الفيديوهات التي تنتظرها
    private val posterFail = HashSet<String>()
    private val posterWait = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Row>>()
    private val posterBmp = object : LinkedHashMap<String, android.graphics.Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean = size > 16
    }

    private fun thumbFromUrl(r: Row, src: String, h: Map<String, String>) {
        if (gone(r)) return
        if (posterFail.contains(src)) { r.thumbRetry?.invoke(); return }
        val have = posterBmp[src]
        if (have != null) {
            if (!r.thumbSet) setThumb(r, have)
            return
        }
        val waiting = posterWait[src]
        if (waiting != null) {
            r.thumbBusy = true
            waiting.add(r)
            return
        }
        val rows = java.util.concurrent.CopyOnWriteArrayList<Row>()
        rows.add(r)
        r.thumbBusy = true
        posterWait[src] = rows
        val stop: () -> Boolean = { rows.all { gone(it) } }
        try {
            imgPool.execute {
                var sb: android.graphics.Bitmap? = null
                try {
                    if (!stop()) {
                        val d: ByteArray? = if (prefs().getBoolean("rescache", true)) {
                            rcServe(src, h, stop)?.data?.use { readLimited(it, 4000000, stop) }
                        } else {
                            val c = conn(src, h)
                            try { c.inputStream.use { readLimited(it, 4000000, stop) } } finally { try { c.disconnect() } catch (e: Throwable) {} }
                        }
                        if (d != null && !stop()) {
                            val o = android.graphics.BitmapFactory.Options()
                            o.inJustDecodeBounds = true
                            android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, o)
                            var ss = 1
                            while (o.outWidth / ss > 640) ss *= 2
                            val o2 = android.graphics.BitmapFactory.Options()
                            o2.inSampleSize = ss
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, o2)
                            if (bmp != null) sb = shrinkBmp(bmp)
                        }
                    }
                } catch (e: Throwable) {
                }
                val fin = sb
                ui.post {
                    if (fin != null) posterBmp[src] = fin
                    else if (!stop()) posterFail.add(src)
                    val ws = posterWait.remove(src)
                    if (ws != null) for (w in ws) {
                        w.thumbBusy = false
                        if (w.thumbSet || gone(w)) continue
                        if (fin != null) setThumb(w, fin) else if (!stop()) w.thumbRetry?.invoke()
                    }
                }
            }
        } catch (e: Throwable) {
            posterWait.remove(src)
            for (w in rows) w.thumbBusy = false
        }
    }

    // لقطة من الفيديو: فقط بعد فشل المحاولات الثلاث، ولا تؤخذ أبداً إذا كان «مخزن الصور والأيقونات» مطفأً
    private fun frameFallback(rw: Row, vurl: String, h: Map<String, String>) {
        if (rw.thumbSet || gone(rw) || rw.thumbBusy) return
        if (!prefs().getBoolean("rescache", true)) return
        // الفيديو نُزّل فعلاً: تؤخذ اللقطة من الملف المحفوظ على الجهاز دون استهلاك إنترنت
        val fl = rw.finalLoc
        if (fl != null) { thumbFromFile(rw, fl, rw.finalIsUri); return }
        rw.thumbBusy = true
        try {
            analyzer.execute {
                try {
                    if (gone(rw)) return@execute
                    val r = MediaMetadataRetriever()
                    try {
                        val hh = HashMap<String, String>()
                        for ((k, v) in h) if (v.isNotEmpty()) hh[k] = v
                        r.setDataSource(vurl, hh)
                        if (gone(rw)) return@execute
                        val du = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val fr = try { r.getFrameAtTime(if (du > 4000L) 2000000L else 0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (e: Throwable) { null }
                        if (fr != null) {
                            val sb = shrinkBmp(fr)
                            ui.post { if (!rw.thumbSet && !gone(rw)) setThumb(rw, sb) }
                        }
                    } finally {
                        try { r.release() } catch (e: Throwable) {}
                    }
                } catch (e: Throwable) {
                } finally {
                    rw.thumbBusy = false
                }
            }
        } catch (e: Throwable) {
            rw.thumbBusy = false
        }
    }

    // يسأل الصفحة عن صورة المعاينة (تُعاد المحاولة لأن الصفحة قد لا تكون جاهزة وقت الالتقاط)
    private fun findPagePoster(c: Cand, owner: Tab?, h: Map<String, String>, tries: Int) {
        val t = owner ?: return
        if (c.rw.thumbSet || gone(c.rw)) return
        c.rw.thumbTries = maxOf(tries, 0)
        if (tries <= 0) { frameFallback(c.rw, c.url, h); return }
        val js = "(function(){try{var v=document.querySelector('video[poster]');if(v&&v.poster)return v.poster;" +
            "var m=document.querySelector('meta[property=\"og:image\"],meta[name=\"twitter:image\"]');" +
            "return m&&m.content?m.content:''}catch(e){return ''}})()"
        try {
            t.web.evaluateJavascript(js) { res ->
                var s = ""
                try {
                    val v = org.json.JSONTokener(res ?: "").nextValue()
                    if (v is String) s = v
                } catch (e: Throwable) {
                }
                if (s.startsWith("http://") || s.startsWith("https://")) {
                    if (!c.rw.thumbSet) {
                        c.rw.thumbRetry = { ui.postDelayed({ findPagePoster(c, owner, h, tries - 1) }, 3000) }
                        thumbFromUrl(c.rw, s, h)
                    }
                } else {
                    ui.postDelayed({ findPagePoster(c, owner, h, tries - 1) }, 3000)
                }
            }
        } catch (e: Throwable) {
        }
    }

    // لقطة من وسائط قابلة للقراءة (ملف محفوظ)؛ لا تُستخرج من محتوى مشفّر
    private fun thumbFromFile(r: Row, loc: String, isUri: Boolean) {
        if (r.thumbSet || gone(r)) return
        // لقطة من الفيديو: لا تؤخذ أبداً إذا كان المخزن مطفأً، ولا قبل انتهاء المحاولات الثلاث (وقتها تتولاها سلسلة المحاولات)
        if (!prefs().getBoolean("rescache", true)) return
        if (r.thumbTries > 0) return
        if (r.thumbBusy) {
            ui.postDelayed({ thumbFromFile(r, loc, isUri) }, 1500)
            return
        }
        r.thumbBusy = true
        try {
            imgPool.execute {
                val mr = MediaMetadataRetriever()
                try {
                    if (isUri) mr.setDataSource(this, Uri.parse(loc)) else mr.setDataSource(loc)
                    val dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val at = if (dur > 4000L) 2000000L else 0L
                    val fr = mr.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (fr != null) {
                        val sb = shrinkBmp(fr)
                        ui.post { if (!r.thumbSet) setThumb(r, sb) }
                    }
                } catch (e: Throwable) {
                } finally {
                    try { mr.release() } catch (e: Throwable) {}
                    r.thumbBusy = false
                }
            }
        } catch (e: Throwable) {
            r.thumbBusy = false
        }
    }

    // prot: 0 عادي، 1 محمي، 2 غير محدد
    private fun setKind(r: Row, prot: Int, fmt: String) {
        val t = r.kindTv ?: return
        val st = when (prot) {
            0 -> "عادي"
            1 -> "محمي"
            else -> "غير محدد"
        }
        val col = when (prot) {
            0 -> Color.parseColor("#3DB36B")
            1 -> Color.parseColor("#E0A030")
            else -> cMuted
        }
        t.text = st + " — " + fmt
        t.setTextColor(col)
        r.kindProt = prot
        r.kindFmt = fmt
        r.engine = engineFor(prot, fmt)
        saveTask(r)
    }

    private fun hasAny(s: String, vararg k: String): Boolean {
        for (x in k) if (s.contains(x)) return true
        return false
    }

    // يحدد الصيغة والحماية من بيانات الملف الفعلية (محتوى القائمة أو أول بايتات الملف) وليس من الامتداد وحده
    private fun sniffKind(u0: String, h: Map<String, String>): Kind {
        val u = u0.substringBefore("#tdlrep=")
        if (isHls(u) || isDash(u)) {
            val p = PlayerCore.probe(u, h, if (isHls(u)) "HLS" else "DASH")
            return Kind(p.prot, if (p.kind == "HLS") "HLS/M3U8" else "DASH/MPD")
        }
        val c = conn(u, h, "bytes=0-262143")
        val ct = (c.contentType ?: "").lowercase()
        val b = try { c.inputStream.use { readLimited(it, 262144) } } finally { try { c.disconnect() } catch (e: Throwable) {} }
        if (b.size < 12) return Kind(2, "غير معروف")
        val s = String(b, Charsets.ISO_8859_1)
        val head = s.substring(0, minOf(s.length, 64)).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (head.startsWith("#EXTM3U")) {
            val p = PlayerCore.probe(u, h, "HLS")
            return Kind(p.prot, "HLS/M3U8")
        }
        if (head.startsWith("<") && s.contains("<MPD")) {
            val p = PlayerCore.probe(u, h, "DASH")
            return Kind(p.prot, "DASH/MPD")
        }
        if (s.substring(4, 8) == "ftyp") {
            val brand = s.substring(8, 12)
            val fmt = if (brand.startsWith("qt")) "MOV" else "MP4"
            val marked = hasAny(s, "pssh", "cenc", "cbcs", "cbc1", "cens")
            val prot = if (marked) 1 else if (s.contains("moov")) 0 else 2
            return Kind(prot, fmt)
        }
        if (b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()) {
            val fmt = if (s.substring(0, minOf(s.length, 64)).contains("webm")) "WEBM" else "MKV"
            val enc = s.contains("\u006D\u0080")
            return Kind(if (enc) 1 else 0, fmt)
        }
        if (s.startsWith("OggS")) return Kind(0, "OGG")
        if (s.startsWith("RIFF") && s.substring(8, 12) == "AVI ") return Kind(0, "AVI")
        if (s.startsWith("FLV")) return Kind(0, "FLV")
        if (b[0] == 0x47.toByte() && b.size > 376 && b[188] == 0x47.toByte() && b[376] == 0x47.toByte()) return Kind(2, "TS")
        val fmt = when {
            ct.contains("mp4") -> "MP4"
            ct.contains("webm") -> "WEBM"
            ct.contains("matroska") -> "MKV"
            else -> "غير معروف"
        }
        return Kind(2, fmt)
    }

    private fun classifyCand(c: Cand, h: Map<String, String>) {
        try {
            analyzer.execute {
                val k = try {
                    sniffKind(c.url, h)
                } catch (e: Throwable) {
                    Kind(2, if (isHls(c.url)) "HLS/M3U8" else if (isDash(c.url)) "DASH/MPD" else "غير معروف")
                }
                ui.post {
                    // ما ثبت بعد فشل التحميل أو بعد الحفظ المحمي لا يُستبدل بنتيجة أضعف
                    if (cands[c.url] === c && c.rw.kindProt != 1) setKind(c.rw, k.prot, k.fmt)
                }
            }
        } catch (e: Throwable) {
        }
    }

    private fun refreshInfo(c: Cand) {
        val m = c.meta
        val tags = ArrayList<String>()
        if (m != null) {
            tags.add((if (m.vod) "مسجل" else "بث مباشر") + (if (m.why.startsWith("يدوي")) " (يدوي)" else if (m.conf in 1..99) " (تقديري " + m.conf + "%)" else ""))
            if (m.dur > 0) tags.add(fmtDur(m.dur))
            if (m.res.isNotBlank()) tags.add("الجودة " + m.res)
            if (m.kbps > 0) tags.add(m.kbps.toString() + " kbps")
            if (m.size > 0) tags.add("الحجم " + (if (m.sizeEst) "≈ " else "") + fmtSize(m.size.toDouble()))
        }
        c.info.text = c.hostTxt + (if (tags.isNotEmpty()) "\n" + tags.joinToString(" • ") else "")
        c.title = c.pageTitle + (if (tags.isNotEmpty()) " — " + tags.joinToString(" • ") else "")
        saveTask(c.rw)
    }

    // جودة وأبعاد الملف المباشر (mp4/webm...) من بيانات الملف نفسه، بعد ظهوره في القائمة
    private fun enrich(c: Cand, h: Map<String, String>) {
        try {
            analyzer.execute {
                try {
                    val r = MediaMetadataRetriever()
                    try {
                        val hh = HashMap<String, String>()
                        for ((k, v) in h) if (v.isNotEmpty()) hh[k] = v
                        r.setDataSource(c.url, hh)
                        val ht = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val wd = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                        val du = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val side = if (ht > 0) minOf(ht, if (wd > 0) wd else ht).takeIf { wd in 1 until ht } ?: ht else 0
                        ui.post {
                            val m = c.meta
                            if (m != null && cands[c.url] === c) {
                                c.meta = m.copy(
                                    res = if (side > 0) side.toString() + "p" else m.res,
                                    kbps = if (br > 0) br / 1000 else m.kbps,
                                    dur = if (du > 0) du / 1000.0 else m.dur
                                )
                                refreshInfo(c)
                            }
                        }
                    } finally {
                        try { r.release() } catch (e: Throwable) {}
                    }
                } catch (e: Throwable) {
                }
            }
        } catch (e: Throwable) {
        }
    }

    // مشاهدة الفيديو: النهائي إن اكتمل، وإلا الجزء المحمّل حتى الآن
    private fun watchRow(r: Row) {
        val oid = r.offlineId
        if (oid != null) {
            playOffline(oid)
            return
        }
        val su = r.srcUrl
        if (r.protectedStream && r.finalLoc == null && su != null) {
            playStream(su, r.srcHdr ?: emptyMap())
            return
        }
        val fl = r.finalLoc
        if (fl != null) {
            playVideo(fl, r.finalIsUri, r.engine)
            return
        }
        val w = r.work
        if (w == null || !w.exists()) {
            toast("لم يُحمَّل أي جزء بعد")
            return
        }
        if (r.state == 1 || r.state == 2) {
            // تشغيل أثناء التنزيل: المشغّل يتابع الأجزاء الجديدة ويكمل تلقائياً
            playLive(w.absolutePath)
            return
        }
        toast("جاري تجهيز الجزء المحمّل للمشاهدة...")
        try {
            previewPool.execute {
                var f: File? = null
                try {
                    f = buildPreview(w)
                } catch (e: Throwable) {
                    logErr("مشاهدة", "تعذر تجهيز المعاينة: " + (e.message ?: e.javaClass.simpleName), e)
                }
                val pf = f
                ui.post {
                    if (pf == null) toast("لم يُحمَّل جزء كافٍ للمشاهدة بعد، انتظر قليلاً")
                    else playVideo(pf.absolutePath, false)
                }
            }
        } catch (e: Throwable) {
        }
    }

    private val liveRows = java.util.concurrent.ConcurrentHashMap<String, Row>()

    private fun playOffline(id: String) {
        val i = Intent(this, VideoPlayerActivity::class.java)
        i.putExtra(VideoPlayerActivity.X_OFFLINE, id)
        try {
            startActivity(i)
        } catch (e: Throwable) {
            toast("تعذر فتح المشغّل")
        }
    }

    // إدارة المحتوى المحمي المحفوظ: عرض حالة الترخيص، تجديده، التشغيل، الحذف
    private fun showOfflineManager() {
        val items = OfflineDrm.list(this)
        if (items.isEmpty()) {
            toast("لا توجد فيديوهات محمية محفوظة")
            return
        }
        toast("جاري قراءة حالة التراخيص...")
        previewPool.execute {
            val st = items.map { OfflineDrm.remainingText(this, it) }
            ui.post {
                val ofHolder = arrayOfNulls<AlertDialog>(1)
                val ofRows = items.mapIndexed { i, it ->
                    DlgStyle.thumbRow(
                        this,
                        if (it.name.isBlank()) "فيديو محمي" else it.name,
                        fmtSize(it.bytes.toDouble()) + "  •  الترخيص: " + st[i],
                        null
                    ) { ofHolder[0]?.dismiss(); offlineItemDialog(items[i], st[i]) }
                }
                ofHolder[0] = StyledBuilder(this)
                    .setTitle("المحمية — دون اتصال")
                    .setView(DlgStyle.rowsList(this, ofRows))
                    .setNegativeButton("إغلاق", null)
                    .show()
            }
        }
    }

    private fun offlineItemDialog(it: OfflineDrm.Rec, status: String) {
        val msg = "هذا المحتوى محفوظ مشفّراً كما أرسلته المنصة، ولا يمكن فتحه خارج التطبيق.\n\n" +
            "• يعمل داخل مشغّل التطبيق فقط وبترخيص صالح.\n" +
            "• حالة الترخيص الآن: " + status + "\n" +
            "• عند انتهاء الترخيص يلزم اتصال وقبول من المنصة لتجديده، وإلا لا يعمل."
        StyledBuilder(this)
            .setTitle(if (it.name.isBlank()) "فيديو محمي" else it.name)
            .setView(DlgStyle.bulletText(this, msg))
            .setPositiveButton("تشغيل") { _, _ -> playOffline(it.id) }
            .setNeutralButton("تجديد الترخيص") { _, _ ->
                toast("جاري طلب التجديد من المنصة...")
                previewPool.execute {
                    val err = try {
                        OfflineDrm.renew(this, it.id)
                        null
                    } catch (e: Throwable) {
                        e.message ?: "تعذر التجديد"
                    }
                    ui.post { toast(err ?: "تم تجديد الترخيص") }
                }
            }
            .setNegativeButton("حذف") { _, _ ->
                previewPool.execute {
                    OfflineDrm.delete(this, it.id)
                    ui.post {
                        for (rw in allRows()) if (rw.offlineId == it.id) {
                            rw.offlineId = null
                            rw.msg.text = "حُذف المحتوى المحمي"
                            rw.watch?.visibility = View.GONE
                            setState(rw, 4)
                        }
                        toast("تم الحذف")
                    }
                }
            }
            .show()
    }

    private fun playLive(workPath: String) {
        val i = Intent(this, VideoPlayerActivity::class.java)
        i.putExtra(VideoPlayerActivity.X_LIVE, workPath)
        try {
            startActivity(i)
        } catch (e: Throwable) {
            toast("تعذر فتح المشغّل")
        }
    }

    // حالة بناء المعاينة الحية: نضيف البيانات الجديدة فقط إلى ملف المعاينة بدل إعادة نسخ كل شيء
    private class Inc { var hdr = false; var copied = 0L; var next = 0 }
    private class LiveB(val dir: File) { val v = Inc(); val a = Inc() }
    private val liveB = java.util.concurrent.ConcurrentHashMap<String, LiveB>()

    private fun copyRange(f: File, from: Long, count: Long, o: java.io.OutputStream) {
        java.io.FileInputStream(f).use { i ->
            i.channel.position(from)
            val buf = ByteArray(1 shl 16)
            var left = count
            while (left > 0) {
                val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                o.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun countSegs(seg: (Int) -> File): Int {
        var k = 0
        while (true) {
            val f = seg(k)
            if (f.exists() && f.length() > 0) k++ else break
        }
        return k
    }

    // يضيف الأجزاء المتتالية الجديدة فقط (من inc.next حتى limit) إلى نهاية ملف المعاينة
    private fun appendSegs(inc: Inc, out: File, ini: File?, seg: (Int) -> File, limit: Int): Boolean {
        if (inc.hdr && !out.exists()) { inc.hdr = false; inc.copied = 0L; inc.next = 0 }
        if (!inc.hdr) {
            out.delete()
            out.outputStream().buffered().use { o ->
                if (ini != null && ini.exists() && ini.length() > 0) {
                    ini.inputStream().use { it.copyTo(o) }
                }
                o.flush()
            }
            inc.hdr = true
            inc.next = 0
        }
        var k = inc.next
        if (k < limit) {
            java.io.FileOutputStream(out, true).buffered().use { o ->
                while (k < limit) {
                    val f = seg(k)
                    if (f.exists() && f.length() > 0) { f.inputStream().use { it.copyTo(o) }; k++ } else break
                }
            }
            inc.next = k
        }
        return inc.next > 0
    }

    // تنزيل مباشر لملف mp4: يكتب ftyp + moov المعدّل مرة واحدة ثم يضيف بايتات mdat الجديدة فقط
    private fun appendDirect(inc: Inc, out: File, dir: File): Boolean {
        if (inc.hdr && !out.exists()) { inc.hdr = false; inc.copied = 0L; inc.next = 0 }
        val mf = File(dir, "moovmeta.txt")
        val mb = File(dir, "moov.bin")
        if (!(mf.exists() && mb.exists())) {
            val c0 = File(dir, "chunk_0.part")
            val src = if (c0.exists() && c0.length() > 0) c0
            else dir.listFiles()?.filter { it.name.endsWith(".mp4") && it.length() > 0 }?.maxByOrNull { it.length() }
            if (src == null) return false
            if (out.length() != src.length()) src.copyTo(out, true)
            return out.length() > 0
        }
        val meta = mf.readText().split(",")
        val mdatStart = meta[0].toLong()
        val n = meta[1].toInt()
        val chunk = meta[2].toLong()
        val files = ArrayList<File>()
        val lens = ArrayList<Long>()
        var avail = 0L
        for (k in 0 until n) {
            val f = File(dir, "chunk_" + k + ".part")
            if (!f.exists()) break
            val l = f.length()
            files.add(f)
            lens.add(l)
            avail += l
            if (l < chunk) break
        }
        if (!inc.hdr) {
            if (avail <= mdatStart + 64) return false
            val moov = mb.readBytes()
            val ftyp = File(dir, "ftyp.bin").readBytes()
            patchMoov(moov, (ftyp.size + moov.size).toLong() - mdatStart)
            out.delete()
            out.outputStream().buffered().use { o -> o.write(ftyp); o.write(moov) }
            inc.hdr = true
            inc.copied = mdatStart
        }
        if (avail > inc.copied) {
            java.io.FileOutputStream(out, true).buffered().use { o ->
                var pos = 0L
                for (i in files.indices) {
                    val len = lens[i]
                    val st = maxOf(pos, inc.copied)
                    val en = pos + len
                    if (en > st) copyRange(files[i], st - pos, en - st, o)
                    pos += len
                }
            }
            inc.copied = avail
        }
        return true
    }

    // يُستدعى من المشغّل المدمج على خيط خلفي: يحدّث ملف المعاينة بالبيانات الجديدة فقط، أو يعيد الملف النهائي إن اكتمل
    private fun liveSnapshot(key: String, lastSig: Long): PlayerCore.Live {
        val r = liveRows[key] ?: return PlayerCore.Live(null, false, true, 0L, "")
        val fl = r.finalLoc
        if (fl != null) {
            liveRows.remove(key)
            liveB.remove(key)
            return PlayerCore.Live(fl, fl.startsWith("content:"), true, 0L, "")
        }
        val w = r.work ?: return PlayerCore.Live(null, false, true, 0L, "")
        if (!w.exists()) return PlayerCore.Live(null, false, true, 0L, "")
        // حالة 4 = أُلغي أو فشل: لا بيانات جديدة بعد الآن
        val over = r.state == 4
        var sig = 0L
        try {
            w.walkTopDown().forEach { f -> if (f.isFile && f.parentFile?.name != "preview") sig += f.length() }
        } catch (e: Throwable) {}
        if (sig == lastSig) return PlayerCore.Live(null, false, over, sig, "")
        val b = liveB.getOrPut(key) {
            val d = File(cacheDir, "live_preview/" + TaskStore.id(key))
            d.mkdirs()
            LiveB(d)
        }
        b.dir.mkdirs()
        var vOut: File? = null
        var aOut: File? = null
        try {
            if (File(w, "dv_00000.seg").exists()) {
                // DASH بأجزاء: صورة وصوت في ملفين، يجمعهما المشغّل معاً بلا دمج ولا إعادة نسخ
                val vc = countSegs { File(w, "dv_%05d.seg".format(it)) }
                val ac = countSegs { File(w, "da_%05d.seg".format(it)) }
                val n = if (ac > 0) minOf(vc, ac) else vc
                val vo = File(b.dir, "live_v.mp4")
                if (appendSegs(b.v, vo, File(w, "dv_init.mp4"), { File(w, "dv_%05d.seg".format(it)) }, n)) vOut = vo
                if (vOut != null && ac > 0 && n > 0) {
                    val ao = File(b.dir, "live_a.mp4")
                    if (appendSegs(b.a, ao, File(w, "da_init.mp4"), { File(w, "da_%05d.seg".format(it)) }, n)) aOut = ao
                }
            } else if (File(w, "dv").isDirectory) {
                val vo = File(b.dir, "live_v.mp4")
                if (appendDirect(b.v, vo, File(w, "dv"))) vOut = vo
                if (vOut != null && File(w, "da").isDirectory) {
                    val ao = File(b.dir, "live_a.mp4")
                    if (appendDirect(b.a, ao, File(w, "da"))) aOut = ao
                }
            } else if (File(w, "part_00000.seg").exists()) {
                val fam = File(w, "fam_mp4").exists()
                val ini = File(w, "init.mp4").takeIf { it.exists() && it.length() > 0 }
                if (fam && ini == null) {
                    // أجزاء mp4 مستقلة تحتاج دمجاً فعلياً: لا مفر من إعادة البناء
                    val pv = buildPreview(w)
                    if (pv != null && pv.exists() && pv.length() > 0) {
                        val vo = File(b.dir, "live_v.mp4")
                        if (pv.parentFile?.name == "preview" && pv.renameTo(vo)) vOut = vo
                        else { pv.copyTo(vo, true); vOut = vo }
                    }
                } else {
                    val vo = File(b.dir, if (fam) "live_v.mp4" else "live_v.ts")
                    val n = countSegs { File(w, "part_%05d.seg".format(it)) }
                    if (appendSegs(b.v, vo, ini, { File(w, "part_%05d.seg".format(it)) }, n)) vOut = vo
                }
            } else {
                val vo = File(b.dir, "live_v.mp4")
                if (appendDirect(b.v, vo, w)) vOut = vo
            }
        } catch (e: Throwable) {
            logErr("مشاهدة", "تعذر تحديث المعاينة الحية: " + (e.message ?: e.javaClass.simpleName), e)
        }
        val vf = vOut
        if (vf == null || !vf.exists() || vf.length() == 0L) return PlayerCore.Live(null, false, over, sig, "")
        return PlayerCore.Live(vf.absolutePath, false, over, sig, "", aOut?.absolutePath)
    }

    // يجمع الأجزاء المتتالية من البداية (بدون ثغرات) في ملف قابل للتشغيل
    private fun buildPreview(work: File): File? {
        val pdir0 = File(work, "preview")
        // DASH: مسار الصورة فقط (بدون صوت) من الأجزاء المتتالية
        if (File(work, "dv_00000.seg").exists()) {
            val dp = ArrayList<File>()
            var k = 0
            while (true) {
                val f = File(work, "dv_%05d.seg".format(k))
                if (f.exists() && f.length() > 0) { dp.add(f); k++ } else break
            }
            if (dp.isEmpty()) return null
            val di = File(work, "dv_init.mp4").takeIf { it.exists() && it.length() > 0 }
            pdir0.deleteRecursively()
            pdir0.mkdirs()
            val dout = File(pdir0, "preview.mp4")
            dout.outputStream().buffered().use { o ->
                if (di != null) di.inputStream().use { it.copyTo(o) }
                for (p in dp) p.inputStream().use { it.copyTo(o) }
            }
            return dout
        }
        if (File(work, "dv").isDirectory) {
            pdir0.deleteRecursively()
            return previewDirect(File(work, "dv"), pdir0)
        }
        val first = File(work, "part_00000.seg")
        if (!first.exists()) {
            // تحميل مباشر: يعمل حتى لو كان moov في آخر الملف
            pdir0.deleteRecursively()
            return previewDirect(work, pdir0)
        }
        val parts = ArrayList<File>()
        var i = 0
        while (true) {
            val f = File(work, "part_%05d.seg".format(i))
            if (f.exists() && f.length() > 0) { parts.add(f); i++ } else break
        }
        if (parts.isEmpty()) return null
        val ini = File(work, "init.mp4").takeIf { it.exists() && it.length() > 0 }
        val fam = File(work, "fam_mp4").exists()
        val pdir = File(work, "preview")
        pdir.deleteRecursively()
        pdir.mkdirs()
        if (fam && ini == null) {
            // أجزاء mp4 مستقلة: ندمجها فعلياً
            return merge(HlsJob(parts, null, true), pdir, "preview")
        }
        val out = File(pdir, if (fam) "preview.mp4" else "preview.ts")
        out.outputStream().buffered().use { o ->
            if (ini != null) ini.inputStream().use { it.copyTo(o) }
            for (p in parts) p.inputStream().use { it.copyTo(o) }
        }
        return out
    }

    // التشغيل عبر المشغّل المدمج (Media3) المخصص لتبويب التنزيلات
    private fun playVideo(src: String, isUri: Boolean, engine: Int = 0) {
        val i = Intent(this, VideoPlayerActivity::class.java)
        if (engine != 0) i.putExtra(VideoPlayerActivity.X_ENGINE, engine)
        i.putExtra(VideoPlayerActivity.X_SRC, src)
        i.putExtra(VideoPlayerActivity.X_URI, isUri)
        try {
            startActivity(i)
        } catch (e: Throwable) {
            toast("تعذر فتح المشغّل")
        }
    }

    // تشغيل مباشر من الرابط (للفيديو المحمي) بنفس ترويسات الالتقاط
    private fun playStream(u: String, h: Map<String, String>) {
        val i = Intent(this, VideoPlayerActivity::class.java)
        i.putExtra(VideoPlayerActivity.X_SRC, u)
        i.putExtra(VideoPlayerActivity.X_REMOTE, true)
        i.putExtra(VideoPlayerActivity.X_KIND, if (isHls(u)) "HLS" else if (isDash(u)) "DASH" else "")
        val b = Bundle()
        for ((k, v) in h) b.putString(k, v)
        i.putExtra(VideoPlayerActivity.X_HDR, b)
        try {
            startActivity(i)
        } catch (e: Throwable) {
            toast("تعذر فتح المشغّل")
        }
    }

    // يقرر هل الرابط فيديو حقيقي: يقرأ قائمة m3u8 (مسجل أم مباشر + المدة + الجودة + الحجم التقديري)، ويتحقق من نوع الملفات المباشرة
    private fun consider(u: String, h: Map<String, String>, v: WebView?) {
        val t = if (v != null) hasTab(v) else cur
        val key = tabKey(t) + u
        if (!showKnownSeg() && segUrls.contains(u)) { capNote("أُهمل", u, "جزء من قائمة بث معروفة (يمكنك إظهاره من الإعدادات: إظهار أجزاء القوائم)"); return }
        if (!considered.add(key)) return
        try {
            analyzer.execute {
                var meta: Meta? = null
                var ok = true
                try {
                    meta = analyze(u, h, getOvr(u))
                    if (meta == null) { ok = false; capNote("أُهمل", u, "ليس فيديو: نوع المحتوى ليس فيديو، أو القائمة فارغة أو غير صالحة") }
                } catch (e: Throwable) {
                    meta = null // تعذر الفحص: نعرض الرابط كما هو بدل أن نخسره
                    capNote("تنبيه", u, "تعذر فحص الرابط (" + (e.message ?: e.javaClass.simpleName) + ") فعُرض كما هو")
                    logErr("التقاط", "تعذر فحص الرابط: " + (e.message ?: e.javaClass.simpleName), e)
                }
                val m: Meta? = meta
                val thr = prefs().getInt("uncert", 75)
                val unsure = m != null && thr > 0 && m.conf < thr && !m.why.startsWith("يدوي")
                if (ok && m != null && !m.vod && !prefs().getBoolean("showlive", true) && !unsure) {
                    ok = false
                    excluded[u] = Excl(u, h, m, t, t?.title ?: "")
                    capNote("أُهمل", u, "بث مباشر (" + m.why + "، ثقة " + m.conf + "%). يمكنك إضافته من «الروابط المستبعدة»")
                }
                if (ok && !showKnownSeg() && segUrls.contains(u)) { ok = false; capNote("أُهمل", u, "جزء من بث (segment)") }
                if (ok) capNote("قُبل", u, if (m == null) "قُبل بلا معلومات" else (if (m.vod) "فيديو مسجل" else "بث مباشر") + " (" + m.why + "، ثقة " + m.conf + "%)" + (if (m.dur > 0) " • " + fmtDur(m.dur) else "") + (if (m.res.isNotEmpty()) " • " + m.res else ""))
                if (ok) ui.post {
                    if (t == null || tabs.contains(t)) {
                        addFound(u, h, m, t, t?.title ?: "")
                        val c = cands[u]
                        if (c != null && c.meta != null && !isHls(u) && !isDash(u)) enrich(c, h)
                    }
                    // أي جزء بث (segment) ظهر قبل معرفة قائمته يُحذف
                    for (c in ArrayList(cands.values)) {
                        if (!showKnownSeg() && segUrls.contains(c.url) && c.rw.btn.isEnabled && c.rw.bar.visibility != View.VISIBLE) dropCand(c)
                    }
                    updateCounts()
                }
            }
        } catch (e: Throwable) {
        }
    }

    private fun analyze(u: String, h: Map<String, String>, force: Boolean? = null): Meta? {
        var hls = isHls(u)
        var dashMode = isDash(u)
        var size = -1L
        var ct = ""
        if (!hls && !dashMode) {
            // نقرأ أول بايت فقط: نوع المحتوى والحجم الكلي
            val c = try { conn(u, h, "bytes=0-0") } catch (e: Exception) { conn(u, h) } // بعض الخوادم ترفض Range: نقرأ الرؤوس بطلب عادي
            try {
                ct = (c.contentType ?: "").lowercase()
                size = if (c.responseCode == 206) {
                    (c.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull() ?: -1L
                } else c.contentLengthLong
            } finally {
                try { c.disconnect() } catch (e: Throwable) {}
            }
            if (ct.contains("dash+xml")) dashMode = true
            else if (ct.contains("mpegurl")) hls = true
            else if (ct.startsWith("text/") || ct.contains("json") || ct.contains("html") || ct.contains("javascript") ||
                ct.startsWith("image/") || (ct.startsWith("audio/") && !capAudio()) || ct.contains("xml") || ct.contains("css")) return null
        }
        if (dashMode) {
            val base = u.substringBefore("#tdlrep=")
            val xml = text(base, h)
            if (!xml.contains("<MPD")) return null
            dashSet.add(base)
            val info = parseMpd(base, xml)
            if (info.video.isEmpty() && info.audio.isEmpty()) return null
            for (r in info.video + info.audio) {
                r.init?.let { segUrls.add(it) }
                for (x in r.segs) segUrls.add(x)
                r.single?.let { segUrls.add(it) }
            }
            val bv = info.video.maxByOrNull { it.bw }
            val bwTot = (bv?.bw ?: 0L) + (info.audio.maxByOrNull { it.bw }?.bw ?: 0L)
            val total = if (bwTot > 0 && info.dur > 0) (bwTot * info.dur / 8.0).toLong() else -1L
            var isLive = info.live
            var conf = 100
            var why = ""
            if (force != null) {
                isLive = !force
                why = "يدوي"
            } else if (isLive) {
                // type=dynamic لا يعني بثاً دائماً: نقارن المانيفست بعد انتظار مرتين
                val hasMup = xml.contains("minimumUpdatePeriod")
                var changed = false
                var ended = false
                var failed = false
                try {
                    val w = prefs().getInt("livewait", 0).let { if (it > 0) it * 1000L else 5000L }
                    val norm = { x: String -> x.replace(Regex("\\s+"), "") }
                    val first = norm(xml)
                    for (round in 0 until 2) {
                        Thread.sleep(w)
                        val xml2 = text(base, h)
                        if (xml2.contains("type=\"static\"")) { ended = true; break }
                        if (norm(xml2) != first) { changed = true; break }
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) {
                    failed = true
                }
                if (ended) { isLive = false; conf = 100; why = "المانيفست صار static" }
                else if (changed) { isLive = true; conf = 95; why = "المانيفست يتجدد" }
                else if (failed) { isLive = true; conf = 30; why = "تعذر إعادة فحص المانيفست" }
                else { isLive = false; conf = if (hasMup) 60 else 80; why = "المانيفست ثابت" }
                capNote("تحليل", u, "DASH: " + why + " (" + conf + "%)")
            }
            return Meta(!isLive, if (info.dur > 0) info.dur else -1.0, total, true,
                if (bv != null && bv.h > 0) bv.h.toString() + "p" else "", bwTot / 1000, emptyList(), conf, why)
        }
        if (hls) {
            var pl = text(u, h)
            if (!pl.trimStart().startsWith("#EXTM3U")) return null
            hlsSet.add(u)
            var base = u
            var res = ""
            var kbps = 0L
            var bw = 0L
            val subs = ArrayList<String>()
            if (pl.contains("#EXT-X-STREAM-INF")) {
                val vs = variants(u, h)
                if (vs.isEmpty()) return Meta(true, -1.0, -1L, false, "", 0L, subs)
                for (x in vs) { subs.add(x.url); hlsSet.add(x.url) }
                res = vs[0].res
                bw = vs[0].bw
                kbps = bw / 1000
                base = vs[0].url
                pl = text(base, h)
            }
            var vod = pl.contains("#EXT-X-ENDLIST") || pl.contains("#EXT-X-PLAYLIST-TYPE:VOD")
            var dur = 0.0
            var count = 0
            var firstSeg: String? = null
            for (raw in pl.lineSequence()) {
                val l = raw.trim()
                if (l.startsWith("#EXTINF:")) dur += l.substring(8).substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                else if (l.startsWith("#EXT-X-MAP")) {
                    Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { segUrls.add(absUrl(base, it)) }
                } else if (l.isNotEmpty() && !l.startsWith("#")) {
                    val su = absUrl(base, l)
                    segUrls.add(su)
                    if (firstSeg == null) firstSeg = su
                    count++
                }
            }
            // الحكم: علامة النهاية قاطعة. وإلا نقارن القائمة مرتين بعد انتظار أطول من target-duration:
            // تجدد = بث مباشر، ثبات = مسجل مع درجة ثقة تُخفض عند القرائن المشبوهة (تسلسل لا يبدأ من 0، نوع EVENT، مدة قصيرة)
            var conf = 100
            var why = ""
            if (force != null) {
                vod = force
                why = "يدوي"
            } else if (vod) {
                why = "علامة النهاية موجودة"
            } else if (count > 0) {
                val td = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(pl)?.groupValues?.get(1)?.toLongOrNull() ?: 4L
                val lw = prefs().getInt("livewait", 0)
                val waitMs = if (lw > 0) lw * 1000L else (td * 1500L).coerceAtLeast(3500L)
                fun sig(x: String): String {
                    val sq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(x)?.groupValues?.get(1) ?: ""
                    val ls = x.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: ""
                    val n = x.lineSequence().count { it.isNotBlank() && !it.trim().startsWith("#") }
                    return sq + "|" + ls + "|" + n
                }
                var lastSig = sig(pl)
                var changed = false
                var ended = false
                var failed = false
                try {
                    for (round in 0 until 2) {
                        Thread.sleep(waitMs)
                        val p2 = text(base, h)
                        if (p2.contains("#EXT-X-ENDLIST")) { ended = true; break }
                        val s2 = sig(p2)
                        if (s2 != lastSig) { changed = true; break }
                        lastSig = s2
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) {
                    failed = true
                }
                if (ended) { vod = true; conf = 100; why = "ظهرت علامة نهاية" }
                else if (changed) { vod = false; conf = 95; why = "القائمة تتجدد" }
                else if (failed) { vod = false; conf = 30; why = "تعذر إعادة فحص القائمة" }
                else {
                    vod = true
                    conf = 85
                    why = "القائمة ثابتة بلا علامة نهاية"
                    val seq0 = (Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(pl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L) == 0L
                    if (!seq0) conf -= 20
                    if (pl.contains("#EXT-X-PLAYLIST-TYPE:EVENT")) conf -= 15
                    if (dur < 30.0) conf -= 10
                }
                capNote("تحليل", u, "HLS: " + (if (vod) "مسجل" else "بث") + " - " + why + " (" + conf + "%)")
            } else {
                vod = false
                conf = 40
                why = "قائمة بلا أجزاء"
            }
            // الحجم: من معدل البت إن عُرف، وإلا من حجم أول جزء × عدد الأجزاء
            var total = -1L
            var est = true
            if (bw > 0 && dur > 0) total = (bw * dur / 8.0).toLong()
            else if (firstSeg != null && count > 0) {
                try {
                    val c = conn(firstSeg, h, "bytes=0-0")
                    try {
                        val one = if (c.responseCode == 206) {
                            (c.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull() ?: -1L
                        } else c.contentLengthLong
                        if (one > 0) total = one * count
                        if (one > 0 && dur > 0) kbps = (one * count * 8.0 / dur / 1000.0).toLong()
                    } finally {
                        try { c.disconnect() } catch (e: Throwable) {}
                    }
                } catch (e: Throwable) {
                }
            }
            return Meta(vod, if (dur > 0) dur else -1.0, total, est, res, kbps, subs, conf, why)
        }
        // ملف فيديو مباشر (mp4 / webm / mov / mkv ...): الحجم الفعلي من الخادم
        return Meta(force != false, -1.0, size, false, "", 0L, emptyList(),
            if (force != null) 100 else if (size <= 0) 70 else 100,
            if (force != null) "يدوي" else if (size <= 0) "حجم غير معروف: قد يكون بثاً" else "")
    }

    private fun addManual() {
        val u = manualBox.text.toString().trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            toast("أدخل رابطاً صحيحاً يبدأ بـ http")
            return
        }
        val h = HashMap<String, String>()
        h["User-Agent"] = curUa
        h["Referer"] = cur?.web?.url ?: ""
        h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
        addFound(u, h, null, cur, "")
        manualBox.setText("")
        showPage(1)
    }

    private fun addFound(u: String, h: Map<String, String>, meta: Meta? = null, owner: Tab? = null, pageTitle: String = "", restore: TaskRec? = null) {
        if (found.contains(u) || subUrls.contains(u) || (!showKnownSeg() && segUrls.contains(u))) return
        // ملف رئيسي (master): نحذف أي جودة فرعية ظهرت قبله لنفس الفيديو ولم يبدأ تحميلها
        if (meta != null && meta.subs.isNotEmpty()) {
            subUrls.addAll(meta.subs)
            for (su in meta.subs) {
                val c = cands[su] ?: continue
                if (c.rw.btn.isEnabled && c.rw.bar.visibility != View.VISIBLE) dropCand(c)
            }
        }
        found.add(u)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = bg(cCard, 14, cLine)
        row.setPadding(dp(12), dp(12), dp(12), dp(12))

        val name = u.substringAfterLast('/').substringBefore('?').take(60)
        val shownName = if (pageTitle.isNotBlank()) pageTitle.take(70) else if (name.isBlank()) "فيديو" else name
        val title = tv(shownName, 14f, cText, true)
        val host = tv("", 12f, cMuted)
        val msg = tv("", 12f, cMuted)
        // شريط تقدم بشكل سلايدر (للعرض فقط، لا يُسحب باللمس)
        val bar = android.widget.SeekBar(this)
        bar.max = 100
        bar.progressTintList = ColorStateList.valueOf(cAccent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(cLine)
        bar.thumbTintList = ColorStateList.valueOf(cAccent)
        bar.setPadding(dp(10), 0, dp(10), 0)
        bar.setOnTouchListener { _, _ -> true }
        bar.visibility = View.GONE

        val btn = dlPill("تحميل", true) { }
        val ctl = Ctl()
        val pauseB = dlPill("إيقاف مؤقت", false) { }
        val cancelB = dlPill("إلغاء", false) { }
        pauseB.visibility = View.GONE
        cancelB.visibility = View.GONE
        pauseB.setOnClickListener {
            ctl.paused = !ctl.paused
            pauseB.text = if (ctl.paused) "استئناف" else "إيقاف مؤقت"
            msg.text = if (ctl.paused) "متوقف مؤقتاً" else "جاري التحميل..."
            cands[u]?.rw?.let { setState(it, if (ctl.paused) 2 else 1) }
        }
        cancelB.setOnClickListener {
            // إلغاء التحميل = إيقاف العملية الجارية وحذفها من القائمة
            cands[u]?.let { deleteRow(it) }
        }
        val rw = Row(bar, msg, btn, pauseB, cancelB, ctl, row)
        rw.url = u
        rw.id = TaskStore.id(u)
        rw.pageUrl = restore?.pageUrl ?: (try { owner?.web?.url ?: "" } catch (e: Throwable) { "" })
        if (!rw.pageUrl.startsWith("http://") && !rw.pageUrl.startsWith("https://")) rw.pageUrl = ""
        rw.quiet = restore != null
        val go: () -> Unit = {
            if (!btn.isEnabled) toast("قيد التحميل أو تم تحميله")
            else {
                rw.started = true
                startDl(u, h, rw)
                if (dlView == 1) setDlView(0) else updateCounts()
            }
        }
        btn.setOnClickListener { go() }
        foundStart[u] = go
        foundHdr[u] = h
        val cand = Cand(u, owner, meta, shownName, rw, row, host, Uri.parse(u).host ?: "", shownName)
        cands[u] = cand
        refreshInfo(cand)
        rw.work = null
        val copy = dlPill("نسخ الرابط", false) { copyText(u) }
        val watchB = dlPill("▶ مشاهدة", false) { watchRow(rw) }
        watchB.visibility = View.GONE
        rw.watch = watchB
        val delB = dlPill("إلغاء", false) { cands[u]?.let { deleteDialog(it) } }
        rw.del = delB
        val classB = dlPill("تصنيف", false) { cands[u]?.let { classifyDialog(it) } }

        // السطر الأول: اسم الفيديو وحالته
        val trow = LinearLayout(this)
        trow.orientation = LinearLayout.HORIZONTAL
        trow.gravity = Gravity.CENTER_VERTICAL
        val chip = tv("ملتقط", 11f, cMuted, true)
        chip.setPadding(dp(10), dp(3), dp(10), dp(3))
        chip.background = bg(Color.TRANSPARENT, 10, cMuted)
        rw.chip = chip
        trow.addView(title, lp(0, WRAP, 1f, 0, 0, 8, 0))
        trow.addView(chip, lp(WRAP, WRAP))

        // أزرار التحميل (أساسية) ثم أزرار الأدوات (ثانوية)
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.addView(btn, lp(0, MATCH, 1f, 3, 0, 3, 0))
        btns.addView(pauseB, lp(0, MATCH, 1f, 3, 0, 3, 0))
        btns.addView(cancelB, lp(0, MATCH, 1f, 3, 0, 3, 0))
        val btns2 = LinearLayout(this)
        btns2.orientation = LinearLayout.HORIZONTAL
        btns2.addView(watchB, lp(0, MATCH, 1f, 3, 0, 3, 0))
        btns2.addView(copy, lp(0, MATCH, 1f, 3, 0, 3, 0))
        btns2.addView(classB, lp(0, MATCH, 1f, 3, 0, 3, 0))
        btns2.addView(delB, lp(0, MATCH, 1f, 3, 0, 3, 0))

        // الصورة المصغّرة (أيقونة افتراضية حتى تتوفر صورة) وبجانبها الاسم والمصدر وحالة الحماية والصيغة
        val thumb = ImageView(this)
        thumb.setImageResource(R.drawable.ic_image)
        thumb.setColorFilter(cMuted)
        thumb.scaleType = ImageView.ScaleType.CENTER
        thumb.background = bg(cBg, 10, cLine)
        thumb.clipToOutline = true
        rw.thumb = thumb
        val kindTv = tv("جارٍ الفحص...", 11f, cMuted, true)
        rw.kindTv = kindTv
        val infoCol = LinearLayout(this)
        infoCol.orientation = LinearLayout.VERTICAL
        infoCol.addView(trow)
        infoCol.addView(host)
        infoCol.addView(kindTv, lp(WRAP, WRAP, 0f, 0, 2, 0, 0))
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.addView(thumb, lp(dp(96), dp(60), 0f, 0, 0, 10, 0))
        topRow.addView(infoCol, lp(0, WRAP, 1f))
        row.addView(topRow)
        row.addView(bar, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
        row.addView(msg, lp(MATCH, WRAP, 0f, 0, 2, 0, 0))
        row.addView(btns, lp(MATCH, WRAP, 0f, -3, 8, -3, 0))
        row.addView(btns2, lp(MATCH, WRAP, 0f, -3, 6, -3, 0))
        row.tag = rw
        list.addView(row, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        updateCounts()
        if (restore == null) {
            classifyCand(cand, h)
            val kept = synchronized(videoThumbs) { videoThumbs[u] }
            if (kept != null) setThumb(rw, kept) else findPagePoster(cand, owner, h, 3)
        }
    }

    // الحفظ الرسمي لفيديو DASH المحمي بنظام Widevine دون اتصال. يرمي ProtectedException بشرح السبب إن لم يكن ممكناً
    private fun offlineProtected(u: String, h: Map<String, String>, r: Row, ctl: Ctl, progress: (Int, Int) -> Unit, pe: ProtectedException): OfflineDrm.Rec {
        val base = u.substringBefore("#tdlrep=")
        val pr = PlayerCore.probe(base, h, "DASH")
        if (pr.prot != 1 || !pr.widevine) {
            throw ProtectedException("هذا الفيديو محمي بنظام غير Widevine، والحفظ دون اتصال غير مدعوم له. اضغط «▶ مشاهدة» لتجربة التشغيل المباشر.")
        }
        val lu = pr.licenseUrl
        if (lu == null) {
            throw ProtectedException("هذا الفيديو محمي بنظام Widevine لكن المنصة لا تعلن رابط الترخيص داخل الملف، فلا يمكن طلب ترخيص للحفظ دون اتصال. القرار للمنصة وترخيصها. جرّب «▶ مشاهدة» للتشغيل المباشر.")
        }
        val nm = foundName[u]?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val title = if (nm.isNullOrBlank()) "tango_" + System.currentTimeMillis() else nm
        try {
            return OfflineDrm.download(
                this, base, h, lu, lowQ(), title,
                { pc, b ->
                    ctl.bytes.set(b)
                    progress(pc, 100)
                },
                { s -> ui.post { r.msg.text = s } },
                { ctl.cancelled },
                { ctl.paused }
            )
        } catch (e: java.util.concurrent.CancellationException) {
            throw CancelledException()
        } catch (e: ProtectedException) {
            throw e
        } catch (e: Throwable) {
            throw ProtectedException((e.message ?: e.javaClass.simpleName) + "\nيمكنك تجربة «▶ مشاهدة» للتشغيل المباشر.")
        }
    }

    private fun enqueue(u: String, h: Map<String, String>, r: Row) {
        if (r.removed) return
        val ctl = r.ctl
        ctl.cancelled = false
        ctl.paused = false
        r.pause.text = "إيقاف مؤقت"
        r.pause.visibility = View.VISIBLE
        r.cancel.visibility = View.VISIBLE
        ctl.bytes.set(0)
        setActive(r, true)
        setState(r, 1)
        val t0 = System.currentTimeMillis()
        var lastT = t0
        var lastB = 0L
        var speed = 0.0
        var lastSave = 0L
        val progress: (Int, Int) -> Unit = { done, total ->
            ui.post {
                r.bar.max = if (total > 0) total else 100
                r.bar.progress = done
                val tn = System.currentTimeMillis()
                if (tn - lastSave > 3000) {
                    lastSave = tn
                    saveTask(r)
                }
                if (!ctl.paused && !ctl.cancelled) {
                    val now = System.currentTimeMillis()
                    val b = ctl.bytes.get()
                    if (now - lastT >= 800) {
                        val inst = (b - lastB) * 1000.0 / (now - lastT)
                        speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                        lastT = now
                        lastB = b
                    }
                    val frac = if (total > 0) done.toDouble() / total else 0.0
                    val el = (now - t0) / 1000
                    val pc = (frac * 100).toInt()
                    val sb = StringBuilder()
                    if (total > 0) sb.append(pc).append("%  •  ")
                    sb.append(fmtSize(b.toDouble()))
                    if (speed > 0) sb.append("  •  ").append(fmtSize(speed)).append("/ث")
                    sb.append("\n").append("مضى ").append(fmtTime(el))
                    if (frac > 0.02) sb.append("  •  متبقي ").append(fmtTime((el * (1 - frac) / frac).toLong()))
                    r.msg.text = sb.toString()
                }
            }
        }
        queue.execute {
            // مجلد ثابت لكل رابط: عند الفشل تبقى الأجزاء المكتملة ويكمل من حيث توقف
            val work = File(getExternalFilesDir(null) ?: filesDir, "work_" + TaskStore.id(u))
            var keep = false
            r.work = work
            liveRows[work.absolutePath] = r
            ui.post { r.watch?.visibility = View.VISIBLE }
            try {
                work.mkdirs()
                val name = "tango_" + System.currentTimeMillis()
                var result: String
                val saved: Pair<String, String>
                var offRec: OfflineDrm.Rec? = null
                var rawDrm = false
                if (isHls(u)) {
                    val job = hls(u, h, work, ctl, progress)
                    ui.post { r.msg.text = "جاري دمج الأجزاء..." }
                    val out = mergeHls(job, work, name)
                    saved = saveToDownloadsEx(out, "video/mp4")
                } else if (isDash(u)) {
                    var job: DashJob? = null
                    try {
                        job = dash(u, h, work, ctl, progress)
                    } catch (pe: ProtectedException) {
                        // محمي: نحاول الحفظ الرسمي دون اتصال (ترخيص من المنصة + أجزاء مشفّرة)، وإلا يُشرح السبب
                        offRec = offlineProtected(u, h, r, ctl, progress, pe)
                    }
                    if (job != null) {
                        rawDrm = job.drm
                        ui.post { r.msg.text = "جاري دمج الصوت والصورة..." }
                        val out = mergeDash(job, work, name)
                        saved = saveToDownloadsEx(out, "video/mp4")
                    } else {
                        saved = Pair("", "")
                    }
                } else {
                    val nm = foundName[u]?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val mp4 = File(work, if (nm.isNullOrBlank()) "$name.mp4" else nm)
                    direct(u, h, mp4, work, ctl, progress)
                    saved = saveToDownloadsEx(mp4, foundMime[u] ?: "video/mp4")
                }
                val orr = offRec
                if (orr != null) {
                    // محتوى محمي محفوظ دون اتصال: لا ملف نهائي عادي، التشغيل عبر مشغّل التطبيق فقط
                    r.offlineId = orr.id
                    ui.post {
                        setKind(r, 1, "DASH/MPD")
                        r.msg.text = OfflineDrm.savedNote()
                        r.bar.progress = r.bar.max
                        r.watch?.visibility = View.VISIBLE
                        if (!r.removed) setState(r, 3)
                    }
                } else {
                    result = saved.first
                    r.finalLoc = saved.second
                    r.finalIsUri = saved.second.startsWith("content:")
                    if (!rawDrm) thumbFromFile(r, saved.second, r.finalIsUri)
                    ui.post {
                        if (rawDrm) setKind(r, 1, "DASH/MPD")
                        r.msg.text = "تم: $result" + (if (rawDrm) "\nتنبيه: الفيديو معلن عنه كمحمي، فالبيانات المحفوظة مشفّرة غالباً ولا تعمل كفيديو عادي. استخدم «المحمية» للحفظ الرسمي دون اتصال إن سمحت المنصة." else "")
                        r.bar.progress = r.bar.max
                        r.watch?.visibility = if (rawDrm) View.GONE else View.VISIBLE
                        if (!r.removed) setState(r, 3)
                    }
                }
            } catch (e: CancelledException) {
                ui.post {
                    r.msg.text = "أُلغي التحميل"
                    r.btn.isEnabled = true
                    r.btn.alpha = 1f
                    r.bar.visibility = View.GONE
                    if (!r.removed) setState(r, 4)
                }
            } catch (e: Throwable) {
                keep = true
                logErr("تحميل", "فشل تحميل " + u.substringAfterLast('/').substringBefore('?').take(40) + ": " + (e.message ?: e.javaClass.simpleName), e)
                val prot = e is ProtectedException
                if (prot) {
                    ui.post { setKind(r, 1, if (isDash(u)) "DASH/MPD" else "HLS/M3U8") }
                    r.protectedStream = true
                    r.srcUrl = u
                    r.srcHdr = h
                }
                ui.post {
                    r.msg.text = if (prot) (e.message ?: "") else "فشل: " + (e.message ?: e.javaClass.simpleName) + " (اضغط تحميل للإكمال من حيث توقف)"
                    r.btn.isEnabled = true
                    r.btn.alpha = 1f
                    if (prot) r.watch?.visibility = View.VISIBLE
                    if (!r.removed) setState(r, 4)
                }
            } finally {
                if (!keep) {
                    // يمنح المشغّل فرصة للانتقال إلى الملف النهائي قبل حذف مجلد العمل
                    if (r.finalLoc == null) liveRows.remove(work.absolutePath)
                    work.deleteRecursively()
                }
                if (!keep) r.work = null
                ui.post {
                    if (r.finalLoc == null && r.work == null) r.watch?.visibility = View.GONE
                    r.pause.visibility = View.GONE
                    r.cancel.visibility = View.GONE
                    setActive(r, false)
                }
            }
        }
    }

    // ---------- DASH (.mpd) ----------

    private class DRep(val id: String, val bw: Long, val w: Int, val h: Int, val init: String?, val segs: List<String>, val single: String?,
                       val lang: String = "", val main: Boolean = true, val codecs: String = "", val fps: String = "")
    private class DashInfo(val live: Boolean, val dur: Double, val video: List<DRep>, val audio: List<DRep>, val drm: Boolean)
    private class DashJob(val v: File, val a: File?, val drm: Boolean = false)
    private val dashSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun isDash(u: String): Boolean {
        val b = u.substringBefore("#tdlrep=")
        return b.lowercase().substringBefore('?').endsWith(".mpd") || dashSet.contains(b)
    }

    private fun isoDur(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        val m = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?").matchEntire(s.trim()) ?: return 0.0
        val g = m.groupValues
        return (g[1].toDoubleOrNull() ?: 0.0) * 86400 + (g[2].toDoubleOrNull() ?: 0.0) * 3600 +
            (g[3].toDoubleOrNull() ?: 0.0) * 60 + (g[4].toDoubleOrNull() ?: 0.0)
    }

    private fun xKids(e: org.w3c.dom.Element, name: String): List<org.w3c.dom.Element> {
        val out = ArrayList<org.w3c.dom.Element>()
        val ns = e.childNodes
        for (i in 0 until ns.length) {
            val n = ns.item(i)
            if (n is org.w3c.dom.Element && n.tagName.substringAfter(':') == name) out.add(n)
        }
        return out
    }

    private fun xBase(base: String, e: org.w3c.dom.Element): String {
        val b = xKids(e, "BaseURL").firstOrNull()?.textContent?.trim()
        return if (b.isNullOrEmpty()) base else absUrl(base, b)
    }

    private fun dashFill(t: String, id: String, bw: Long, number: Long, time: Long): String {
        var r = t.replace("\$RepresentationID\$", id).replace("\$Bandwidth\$", bw.toString())
        r = Regex("\\\$Number(?:%0(\\d+)d)?\\\$").replace(r) { m ->
            number.toString().padStart(m.groupValues[1].toIntOrNull() ?: 0, '0')
        }
        r = Regex("\\\$Time(?:%0(\\d+)d)?\\\$").replace(r) { m ->
            time.toString().padStart(m.groupValues[1].toIntOrNull() ?: 0, '0')
        }
        return r.replace("\$\$", "\$")
    }

    private fun parseMpd(u: String, xml: String): DashInfo {
        val db = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = db.parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        val root = doc.documentElement
        val live = root.getAttribute("type") == "dynamic"
        val total = isoDur(root.getAttribute("mediaPresentationDuration"))
        var drm = false
        val base0 = xBase(u, root)
        var bestV: List<DRep> = emptyList()
        var bestA: List<DRep> = emptyList()
        var bestDur = -1.0
        for (per in xKids(root, "Period")) {
            val pBase = xBase(base0, per)
            val pd0 = isoDur(per.getAttribute("duration"))
            val pd = if (pd0 > 0) pd0 else total
            val vs = ArrayList<DRep>()
            val au = ArrayList<DRep>()
            for (aset in xKids(per, "AdaptationSet")) {
                val aBase = xBase(pBase, aset)
                if (xKids(aset, "ContentProtection").isNotEmpty()) drm = true
                for (rep in xKids(aset, "Representation")) {
                    if (xKids(rep, "ContentProtection").isNotEmpty()) drm = true
                    val mime = rep.getAttribute("mimeType").ifEmpty { aset.getAttribute("mimeType") }.lowercase()
                    val ctype = aset.getAttribute("contentType").lowercase()
                    val wd = rep.getAttribute("width").toIntOrNull() ?: aset.getAttribute("width").toIntOrNull() ?: 0
                    val ht = rep.getAttribute("height").toIntOrNull() ?: aset.getAttribute("height").toIntOrNull() ?: 0
                    val isA = mime.startsWith("audio") || ctype == "audio"
                    val isV = mime.startsWith("video") || ctype == "video" || ht > 0
                    if (!isA && !isV) continue
                    val rid = rep.getAttribute("id")
                    val bw = rep.getAttribute("bandwidth").toLongOrNull() ?: 0L
                    val rBase = xBase(aBase, rep)
                    // القالب قد يتوزع على التمثيل ومجموعة التكيّف والفترة: نقرأ كل خاصية من أقرب مستوى يحددها
                    val tmpls = listOfNotNull(
                        xKids(rep, "SegmentTemplate").firstOrNull(),
                        xKids(aset, "SegmentTemplate").firstOrNull(),
                        xKids(per, "SegmentTemplate").firstOrNull()
                    )
                    val tmpl = tmpls.firstOrNull()
                    fun ta(n: String): String = tmpls.map { it.getAttribute(n) }.firstOrNull { it.isNotEmpty() } ?: ""
                    var init: String? = null
                    val segs = ArrayList<String>()
                    var single: String? = null
                    if (tmpl != null) {
                        val ts = ta("timescale").toLongOrNull() ?: 1L
                        val sn = ta("startNumber").toLongOrNull() ?: 1L
                        val media = ta("media")
                        val ini = ta("initialization")
                        if (ini.isNotEmpty()) init = absUrl(rBase, dashFill(ini, rid, bw, 0L, 0L))
                        val tl = tmpls.firstNotNullOfOrNull { xKids(it, "SegmentTimeline").firstOrNull() }
                        if (tl != null) {
                            var t = 0L
                            var num = sn
                            var startT = -1L
                            for (s in xKids(tl, "S")) {
                                val st = s.getAttribute("t")
                                if (st.isNotEmpty()) t = st.toLong()
                                if (startT < 0) startT = t
                                val d = s.getAttribute("d").toLongOrNull() ?: continue
                                var rr = s.getAttribute("r").toIntOrNull() ?: 0
                                if (rr < 0) {
                                    rr = if (pd > 0 && d > 0) (((pd * ts - (t - startT)) / d).toInt() - 1).coerceAtLeast(0) else 0
                                }
                                for (k in 0..rr) {
                                    segs.add(absUrl(rBase, dashFill(media, rid, bw, num, t)))
                                    t += d
                                    num++
                                }
                            }
                        } else {
                            val d = ta("duration").toLongOrNull() ?: 0L
                            if (d > 0 && pd > 0) {
                                val cnt = Math.ceil(pd * ts / d).toLong()
                                for (k in 0 until cnt) segs.add(absUrl(rBase, dashFill(media, rid, bw, sn + k, k * d)))
                            }
                        }
                    } else {
                        val sl = xKids(rep, "SegmentList").firstOrNull() ?: xKids(aset, "SegmentList").firstOrNull()
                        if (sl != null) {
                            val src = xKids(sl, "Initialization").firstOrNull()?.getAttribute("sourceURL") ?: ""
                            if (src.isNotEmpty()) init = absUrl(rBase, src)
                            for (su in xKids(sl, "SegmentURL")) {
                                val m = su.getAttribute("media")
                                if (m.isNotEmpty()) segs.add(absUrl(rBase, m))
                            }
                        } else if (xKids(rep, "BaseURL").isNotEmpty() ||
                            ((xKids(rep, "SegmentBase").isNotEmpty() || xKids(aset, "SegmentBase").isNotEmpty()) && rBase != u)) {
                            // ملف واحد (BaseURL أو SegmentBase بفهرس sidx)
                            single = rBase
                        }
                    }
                    if (single == null && segs.isEmpty()) continue
                    val role = xKids(aset, "Role").firstOrNull()?.getAttribute("value") ?: ""
                    val isMain = role.isEmpty() || role == "main"
                    val cod = rep.getAttribute("codecs").ifEmpty { aset.getAttribute("codecs") }
                    val fr = rep.getAttribute("frameRate").ifEmpty { aset.getAttribute("frameRate") }
                    val dr = DRep(rid, bw, wd, ht, init, segs, single, aset.getAttribute("lang"), isMain, cod, fr)
                    if (isA && !isV) au.add(dr) else vs.add(dr)
                }
            }
            if (pd > bestDur) {
                bestDur = pd
                bestV = vs
                bestA = au
            }
        }
        return DashInfo(live, if (bestDur > 0) bestDur else total, bestV, bestA, drm)
    }

    // الصوت: المسار الرئيسي (لا الوصف الصوتي ولا التعليق) بلغة أول مجموعة، ثم الأعلى نطاقاً
    private fun pickDashAudio(a: List<DRep>): DRep? {
        if (a.isEmpty()) return null
        val m = a.filter { it.main }.ifEmpty { a }
        val lang = m.first().lang
        val g = m.filter { it.lang == lang }.ifEmpty { m }
        return if (lowQ()) g.minByOrNull { it.bw } else g.maxByOrNull { it.bw }
    }

    // الصورة: الأعلى نطاقاً، مع تفضيل H.264 إن كان الأعلى بترميز يصعب دمجه ويوجد H.264 بنفس الارتفاع أو أعلى
    private fun pickDashVideo(v: List<DRep>): DRep? {
        if (v.isEmpty()) return null
        if (lowQ()) return v.minByOrNull { it.bw }
        val best = v.maxByOrNull { it.bw }!!
        val isAvc = { r: DRep -> r.codecs.isEmpty() || r.codecs.lowercase().startsWith("avc") }
        if (isAvc(best)) return best
        return v.filter { isAvc(it) && it.h >= best.h }.maxByOrNull { it.bw } ?: best
    }

    private fun dashVariants(u: String, h: Map<String, String>): List<Variant> {
        val base = u.substringBefore("#tdlrep=")
        val info = parseMpd(base, text(base, h))
        val ab = pickDashAudio(info.audio)?.bw ?: 0L
        return info.video.sortedByDescending { it.bw }.map {
            val res = if (it.h > 0) it.h.toString() + "p" else ""
            val fr = it.fps.substringBefore('/').toDoubleOrNull()?.let { f -> if (f > 0) Math.round(f).toString() + "fps" else "" } ?: ""
            val ct = codecTag(it.codecs)
            val label = (if (res.isNotEmpty()) res else "جودة") + (if (fr.isNotEmpty()) " " + fr else "") +
                (if (ct.isNotEmpty()) " " + ct else "") + " • " + (it.bw / 1000) + " kbps"
            Variant(label, base + "#tdlrep=" + it.id, it.bw + ab, res)
        }
    }

    private fun dashRep(r: DRep, tag: String, work: File, h: Map<String, String>, ctl: Ctl,
                        done: AtomicInteger, total: Int, progress: (Int, Int) -> Unit): File {
        val out = File(work, "dash_$tag.mp4")
        if (r.single != null) {
            val sub = File(work, tag)
            sub.mkdirs()
            direct(r.single, h, out, sub, ctl) { d, t -> progress(d, t) }
            return out
        }
        val iniF = r.init?.let { iu ->
            val f = File(work, tag + "_init.mp4")
            if (!(f.exists() && f.length() > 0)) f.writeBytes(retry(3) { segBytes(iu, h) })
            f
        }
        val parts = r.segs.indices.map { File(work, tag + "_%05d.seg".format(it)) }
        val pool = Executors.newFixedThreadPool(prefs().getInt("threads", 8).coerceIn(1, 128))
        try {
            val fs = r.segs.mapIndexed { i, su ->
                pool.submit(Callable<Unit> {
                    gate(ctl)
                    val part = parts[i]
                    if (!(part.exists() && part.length() > 0)) {
                        val d = retry(5) { gate(ctl); segBytes(su, h) }
                        ctl.bytes.addAndGet(d.size.toLong())
                        throttle(d.size)
                        val tmp = File(work, tag + "_%05d.tmp".format(i))
                        tmp.writeBytes(d)
                        if (!tmp.renameTo(part)) {
                            part.writeBytes(d)
                            tmp.delete()
                        }
                    }
                    progress(done.incrementAndGet(), total)
                })
            }
            for (f in fs) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    val c = e.cause
                    if (c is CancelledException) throw c
                    throw Exception(c?.message ?: "فشل تحميل جزء")
                }
            }
        } finally {
            pool.shutdownNow()
        }
        out.outputStream().buffered().use { o ->
            iniF?.inputStream()?.use { it.copyTo(o) }
            for (p in parts) p.inputStream().use { it.copyTo(o) }
        }
        return out
    }

    private fun dash(u0: String, h: Map<String, String>, work: File, ctl: Ctl, progress: (Int, Int) -> Unit): DashJob {
        val base = u0.substringBefore("#tdlrep=")
        val want = u0.substringAfter("#tdlrep=", "")
        val info = parseMpd(base, text(base, h))
        if (info.drm && !prefs().getBoolean("ignoredrm", false)) throw ProtectedException("الفيديو يعلن حماية DRM فلا يمكن تنزيله كملف قابل للتشغيل. اضغط «▶ مشاهدة» لتجربة تشغيله مباشرة بنظام الحماية الرسمي إن توفّر الترخيص. (للتنزيل الخام رغم ذلك فعّل «محاولة التحميل رغم علامة DRM» من الإعدادات)")
        val v = info.video.firstOrNull { it.id == want }
            ?: pickDashVideo(info.video)
        val a = pickDashAudio(info.audio)
        if (v == null && a == null) throw Exception("لا توجد مسارات فيديو أو صوت في الملف")
        val total = (v?.segs?.size ?: 0) + (a?.segs?.size ?: 0)
        val done = AtomicInteger(0)
        // الصورة والصوت معاً بالتوازي حتى تعمل المشاهدة أثناء التنزيل بصوت وصورة
        var vf: File? = null
        var af: File? = null
        if (v != null && a != null) {
            val ex = Executors.newSingleThreadExecutor()
            val fut = ex.submit(Callable<File> { dashRep(a, "da", work, h, ctl, done, total, progress) })
            try {
                vf = dashRep(v, "dv", work, h, ctl, done, total, progress)
            } catch (e: Throwable) {
                fut.cancel(true)
                ex.shutdownNow()
                throw e
            }
            try {
                af = fut.get()
            } catch (e: ExecutionException) {
                val c = e.cause
                if (c is CancelledException) throw c
                throw Exception(c?.message ?: "فشل تحميل الصوت")
            } finally {
                ex.shutdownNow()
            }
        } else {
            vf = v?.let { dashRep(it, "dv", work, h, ctl, done, total, progress) }
            af = a?.let { dashRep(it, "da", work, h, ctl, done, total, progress) }
        }
        return DashJob(vf ?: af!!, if (vf != null) af else null, info.drm)
    }

    // دمج مسار الصورة ومسار الصوت في ملف mp4 واحد: mp4parser أولاً، وMediaMuxer كبديل
    private fun mergeDash(job: DashJob, work: File, name: String): File {
        val out = File(work, "$name.mp4")
        try {
            val movie = Movie()
            for (t in MovieCreator.build(job.v.absolutePath).tracks) movie.addTrack(t)
            val a = job.a
            if (a != null) {
                for (t in MovieCreator.build(a.absolutePath).tracks) if (t.handler == "soun") movie.addTrack(t)
            }
            if (movie.tracks.isEmpty()) throw Exception("لا توجد مسارات")
            val c = DefaultMp4Builder().build(movie)
            FileOutputStream(out).use { fo -> c.writeContainer(fo.channel) }
            return out
        } catch (e: Throwable) {
            out.delete()
            logErr("دمج", "فشل دمج DASH عبر mp4parser، جاري تجربة MediaMuxer: " + (e.message ?: e.javaClass.simpleName), e)
            val srcs = ArrayList<File>()
            srcs.add(job.v)
            job.a?.let { srcs.add(it) }
            if (remuxMany(srcs, out)) return out
            out.delete()
            throw Exception("فشل دمج الصوت والصورة: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun remuxMany(srcs: List<File>, mp4: File): Boolean {
        var mux: MediaMuxer? = null
        var started = false
        try {
            mux = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val exs = ArrayList<MediaExtractor>()
            val maps = ArrayList<HashMap<Int, Int>>()
            var haveV = false
            var haveA = false
            for (s in srcs) {
                val ex = MediaExtractor()
                ex.setDataSource(s.absolutePath)
                exs.add(ex)
                val map = HashMap<Int, Int>()
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && !haveV) {
                        map[i] = mux.addTrack(f); ex.selectTrack(i); haveV = true
                    } else if (mime.startsWith("audio/") && !haveA) {
                        map[i] = mux.addTrack(f); ex.selectTrack(i); haveA = true
                    }
                }
                maps.add(map)
            }
            if (!haveV && !haveA) return false
            mux.start()
            started = true
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for (k in exs.indices) {
                val ex = exs[k]
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    info.offset = 0
                    info.size = n
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    val t = maps[k][ex.sampleTrackIndex]
                    if (t != null) mux.writeSampleData(t, buf, info)
                    ex.advance()
                }
                try { ex.release() } catch (e: Exception) { }
            }
            mux.stop()
            started = false
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { if (started) mux?.stop() } catch (e: Exception) { }
            try { mux?.release() } catch (e: Exception) { }
        }
    }

    // ---------- فيديو مباشر بـ moov في آخر الملف: نجلب moov مبكراً لنتمكن من المشاهدة الجزئية ----------

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    private fun be64(b: ByteArray, o: Int): Long = (be32(b, o) shl 32) or be32(b, o + 4)

    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
    }

    private fun put64(b: ByteArray, o: Int, v: Long) {
        put32(b, o, v shr 32); put32(b, o + 4, v and 0xffffffffL)
    }

    private fun rangeBytes(u: String, h: Map<String, String>, a: Long, b: Long): ByteArray {
        val c = conn(u, h, "bytes=$a-$b")
        try {
            if (c.responseCode != 206) throw Exception("الخادم لا يدعم التجزئة")
            return c.inputStream.use { it.readBytes() }
        } finally {
            try { c.disconnect() } catch (e: Throwable) {}
        }
    }

    private fun prepMoov(u: String, h: Map<String, String>, total: Long, work: File, n: Int, chunk: Long) {
        try {
            if (File(work, "moov.bin").exists()) return
            var pos = 0L
            var ftyp: ByteArray? = null
            var mdatStart = -1L
            var moovAt = -1L
            var moovSz = 0L
            var guard = 0
            while (pos < total && guard++ < 64) {
                val hd = rangeBytes(u, h, pos, minOf(pos + 15, total - 1))
                if (hd.size < 8) break
                var sz = be32(hd, 0)
                val type = String(hd, 4, 4, Charsets.ISO_8859_1)
                if (sz == 1L && hd.size >= 16) sz = be64(hd, 8) else if (sz == 0L) sz = total - pos
                if (sz < 8) break
                if (type == "ftyp" && sz < 4096) ftyp = rangeBytes(u, h, pos, pos + sz - 1)
                if (type == "mdat" && mdatStart < 0) mdatStart = pos
                if (type == "moov") {
                    moovAt = pos
                    moovSz = sz
                    break
                }
                pos += sz
            }
            // moov قبل البيانات: بداية الملف تكفي للمشاهدة بدون أي معالجة
            if (moovAt < 0 || mdatStart < 0 || moovAt < mdatStart || moovSz > 80L * 1048576L) return
            val moov = rangeBytes(u, h, moovAt, moovAt + moovSz - 1)
            File(work, "ftyp.bin").writeBytes(ftyp ?: ByteArray(0))
            File(work, "moovmeta.txt").writeText("$mdatStart,$n,$chunk")
            File(work, "moov.bin").writeBytes(moov)
        } catch (e: Throwable) {
        }
    }

    // يضبط مواضع الأجزاء (stco/co64) داخل moov بعد نقله لبداية الملف
    private fun patchMoov(m: ByteArray, delta: Long) {
        fun walk(start: Int, end: Int) {
            var p = start
            while (p + 8 <= end) {
                val sz = be32(m, p).toInt()
                if (sz < 8 || p + sz > end) break
                when (String(m, p + 4, 4, Charsets.ISO_8859_1)) {
                    "trak", "mdia", "minf", "stbl" -> walk(p + 8, p + sz)
                    "stco" -> {
                        val cnt = be32(m, p + 12).toInt()
                        for (i in 0 until cnt) {
                            val o = p + 16 + i * 4
                            if (o + 4 > p + sz) break
                            put32(m, o, be32(m, o) + delta)
                        }
                    }
                    "co64" -> {
                        val cnt = be32(m, p + 12).toInt()
                        for (i in 0 until cnt) {
                            val o = p + 16 + i * 8
                            if (o + 8 > p + sz) break
                            put64(m, o, be64(m, o) + delta)
                        }
                    }
                }
                p += sz
            }
        }
        val hl = if (be32(m, 0) == 1L) 16 else 8
        walk(hl, m.size)
    }

    private fun previewDirect(dir: File, pdir: File): File? {
        val mf = File(dir, "moovmeta.txt")
        if (mf.exists() && File(dir, "moov.bin").exists()) {
            val meta = mf.readText().split(",")
            val mdatStart = meta[0].toLong()
            val n = meta[1].toInt()
            val chunk = meta[2].toLong()
            val files = ArrayList<File>()
            var avail = 0L
            for (k in 0 until n) {
                val f = File(dir, "chunk_" + k + ".part")
                if (!f.exists()) break
                files.add(f)
                avail += f.length()
                if (f.length() < chunk) break
            }
            if (avail <= mdatStart + 64) return null
            val moov = File(dir, "moov.bin").readBytes()
            val ftyp = File(dir, "ftyp.bin").readBytes()
            patchMoov(moov, (ftyp.size + moov.size).toLong() - mdatStart)
            pdir.mkdirs()
            val out = File(pdir, "preview.mp4")
            out.outputStream().buffered().use { o ->
                o.write(ftyp)
                o.write(moov)
                var pos = 0L
                for (f in files) {
                    val len = f.length()
                    if (pos + len > mdatStart) {
                        val startIn = maxOf(0L, mdatStart - pos)
                        java.io.FileInputStream(f).use { i ->
                            i.channel.position(startIn)
                            i.copyTo(o)
                        }
                    }
                    pos += len
                }
            }
            return out
        }
        val c0 = File(dir, "chunk_0.part")
        if (c0.exists() && c0.length() > 0) return c0
        return dir.listFiles()?.filter { it.name.endsWith(".mp4") && it.length() > 0 }?.maxByOrNull { it.length() }
    }

    // ---------- الشبكة ----------

    private val skipHeaders = setOf(
        "range", "accept-encoding", "host", "connection", "content-length", "if-none-match", "if-modified-since"
    )

    private val probed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val prober = java.util.concurrent.ThreadPoolExecutor(
        8, 8, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>()
    ).also { it.allowCoreThreadTimeOut(true) }
    private fun applyPools() {
        val a = prefs().getInt("anthreads", 16).coerceAtLeast(1)
        val b = prefs().getInt("prthreads", 8).coerceAtLeast(1)
        try { analyzer.maximumPoolSize = maxOf(a, analyzer.corePoolSize); analyzer.corePoolSize = a; analyzer.maximumPoolSize = a } catch (e: Throwable) {}
        try { prober.maximumPoolSize = maxOf(b, prober.corePoolSize); prober.corePoolSize = b; prober.maximumPoolSize = b } catch (e: Throwable) {}
    }
    private val staticExt = listOf(
        ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".woff", ".woff2", ".ttf",
        ".ico", ".html", ".map"
    )
    private val audioExt = listOf(".mp3", ".aac", ".m4a", ".ogg", ".oga", ".opus", ".wav", ".flac", ".weba")
    private val segExt = listOf(".ts", ".m4s")
    private fun capAudio() = prefs().getBoolean("capaudio", true)
    private fun capSeg() = prefs().getBoolean("capseg", true)
    private fun showKnownSeg() = prefs().getBoolean("showseg", false)
    private fun customHdrs(): List<Pair<String, String>> =
        prefs().getString("customhdr", "")!!.lines().mapNotNull {
            val i = it.indexOf(':')
            if (i <= 0) null else Pair(it.substring(0, i).trim(), it.substring(i + 1).trim())
        }.filter { it.first.isNotEmpty() }
    private val videoExt = listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".flv", ".3gp", ".avi", ".wmv", ".ogv")

    private class ProbeRes(val code: Int, val ct: String, val len: Long)

    private fun probeReq(u: String, h: Map<String, String>, head: Boolean): ProbeRes? {
        var c: HttpURLConnection? = null
        try {
            val k = URL(u).openConnection() as HttpURLConnection
            c = k
            k.connectTimeout = 8000
            k.readTimeout = 8000
            if (head) k.requestMethod = "HEAD"
            for ((a, b) in h) if (b.isNotEmpty() && a.lowercase() !in skipHeaders) k.setRequestProperty(a, b)
            for ((ck, cv) in customHdrs()) k.setRequestProperty(ck, cv)
            if (!head) k.setRequestProperty("Range", "bytes=0-0")
            val code = k.responseCode
            val ct = k.contentType ?: ""
            val len = if (code == 206) (k.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull() ?: -1L
            else k.contentLengthLong
            return ProbeRes(code, ct, len)
        } catch (e: Exception) {
            return null
        } finally {
            try { c?.disconnect() } catch (e: Throwable) {}
        }
    }

    private fun probeIsVideo(ct: String, len: Long, u: String): Boolean {
        val t = ct.lowercase()
        if (t.contains("mpegurl") || t.contains("dash+xml") || t.startsWith("video/") || (t.startsWith("audio/") && capAudio())) return true
        if (t.contains("octet-stream") || t.startsWith("binary/")) {
            val p = u.lowercase().substringBefore('?').substringBefore('#')
            val bad = listOf(".zip", ".apk", ".rar", ".7z", ".exe", ".pdf", ".iso", ".dmg", ".gz", ".tar", ".bin", ".jar", ".torrent")
            if (bad.any { p.endsWith(it) }) return false
            val big = len < 0 || len > 300000L
            return big && (videoExt.any { p.endsWith(it) } || Regex("video|stream|media|play|mp4|movie|film|vod").containsMatchIn(p))
        }
        return false
    }

    // فحص رأس الرد للطلبات التي لا تبدو ملفات ثابتة: HEAD أولاً، وإن رفضه الخادم نجرب GET ببايت واحد
    private fun probe(u: String, h: Map<String, String>, v: WebView) {
        if (!probed.add(u)) return
        try {
            prober.execute {
                try {
                    var r = probeReq(u, h, true)
                    if (r == null || r.code !in 200..299) r = probeReq(u, h, false)
                    if (r != null && r.code in 200..299 && probeIsVideo(r.ct, r.len, u)) ui.post { consider(u, h, v) }
                } catch (e: Throwable) {
                }
            }
        } catch (e: Throwable) {
            probed.remove(u) // الطابور ممتلئ: نسمح بإعادة الفحص لاحقاً بدل أن نخسر الرابط
        }
    }

    private fun hdr(m: Map<String, String>, n: String): String? =
        m.entries.firstOrNull { it.key.equals(n, true) }?.value

    private fun <T> retry(n: Int, f: () -> T): T {
        var last: Exception? = null
        for (i in 0 until n) {
            try {
                return f()
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                last = e
                try { Thread.sleep(500L * (i + 1)) } catch (ie: InterruptedException) { throw e }
            }
        }
        throw last!!
    }

    // نفس الهيدرز (User-Agent / Referer / Cookie ...) لكل طلب، مع متابعة التحويلات يدوياً
    private fun conn(u0: String, h: Map<String, String>, range: String? = null): HttpURLConnection {
        var u = u0
        val maxHop = prefs().getInt("redirects", 30).let { if (it <= 0) 100000 else it }
        for (hop in 0..maxHop) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = prefs().getInt("conntimeout", 45) * 1000
            c.readTimeout = prefs().getInt("readtimeout", 120) * 1000
            c.instanceFollowRedirects = false
            for ((k, v) in h) {
                if (v.isNotEmpty() && k.lowercase() !in skipHeaders) c.setRequestProperty(k, v)
            }
            for ((ck, cv) in customHdrs()) c.setRequestProperty(ck, cv)
            c.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw Exception("HTTP $code")
                u = URL(URL(u), loc).toString()
                continue
            }
            if (code >= 400) {
                c.disconnect()
                throw Exception("HTTP $code")
            }
            return c
        }
        throw Exception("تحويلات كثيرة")
    }

    private fun text(u: String, h: Map<String, String>): String =
        conn(u, h).inputStream.bufferedReader().use { it.readText() }

    private fun bytes(u: String, h: Map<String, String>): ByteArray =
        conn(u, h).inputStream.use { it.readBytes() }

    // جزء HLS/DASH: لا يُقبل إلا إذا وصل بالحجم الذي أعلنه الخادم (الجزء المقطوع لا يُحفظ كأنه مكتمل)
    private fun segBytes(u: String, h: Map<String, String>): ByteArray {
        val c = conn(u, h)
        try {
            val expect = c.contentLengthLong
            val d = c.inputStream.use { it.readBytes() }
            if (d.isEmpty()) throw Exception("جزء فارغ")
            if (expect > 0 && d.size.toLong() != expect) throw Exception("جزء ناقص: وصل " + d.size + " من " + expect)
            return d
        } finally {
            try { c.disconnect() } catch (e: Throwable) {}
        }
    }

    private fun absUrl(base: String, ref: String): String = URL(URL(base), ref).toString()

    private fun direct(u: String, h: Map<String, String>, out: File, work: File, ctl: Ctl, progress: (Int, Int) -> Unit) {
        gate(ctl)
        var total = -1L
        var ranges = false
        try {
            val c = conn(u, h, "bytes=0-0")
            if (c.responseCode == 206) {
                val t = (c.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull()
                if (t != null && t > 0) {
                    total = t
                    ranges = true
                }
            } else {
                total = c.contentLengthLong
            }
            c.disconnect()
        } catch (e: Exception) {
        }

        // الخادم لا يدعم التجزئة أو الملف صغير: تحميل عادي
        if (!ranges || total < 2000000L) {
            val c = conn(u, h)
            val tl = c.contentLengthLong
            var done = 0L
            c.inputStream.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        gate(ctl)
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        ctl.bytes.addAndGet(n.toLong())
                        throttle(n)
                        if (tl > 0) progress((done * 100 / tl).toInt(), 100) else progress(0, 0)
                    }
                }
            }
            return
        }

        // تحميل متوازٍ: كل جزء من الملف في اتصال مستقل، وكل جزء قابل للاستئناف
        // خوارزمية ذكية: عدد الاتصالات = ما اخترته لكن لا يقل نصيب الاتصال عن 1 م.ب (حتى لا تُهدر اتصالات على ملف صغير)
        val want = prefs().getInt("threads", 8).coerceIn(2, 128)
        val n = minOf(want, maxOf(2, (total / (1024L * 1024)).toInt()))
        val size = (total + n - 1) / n
        val got = java.util.concurrent.atomic.AtomicLong(0)
        prepMoov(u, h, total, work, n, size)
        val files = ArrayList<File>()
        for (k in 0 until n) files.add(File(work, "chunk_" + k + ".part"))
        for (f in files) got.addAndGet(f.length())
        val pool = Executors.newFixedThreadPool(n)
        try {
            val fs = (0 until n).map { k ->
                pool.submit(Callable<Unit> {
                    val start = k * size
                    val end = minOf(total - 1, start + size - 1)
                    val f = files[k]
                    var tries = 0
                    while (true) {
                        gate(ctl)
                        val have = f.length()
                        if (start + have > end) break
                        try {
                            val c = conn(u, h, "bytes=" + (start + have) + "-" + end)
                            if (c.responseCode != 206) throw Exception("الخادم لم يدعم التحميل المتجزئ")
                            c.inputStream.use { i ->
                                java.io.FileOutputStream(f, true).use { o ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        gate(ctl)
                                        val m = i.read(buf)
                                        if (m < 0) break
                                        o.write(buf, 0, m)
                                        val g = got.addAndGet(m.toLong())
                                        ctl.bytes.addAndGet(m.toLong())
                        throttle(m)
                                        progress((g * 100 / total).toInt(), 100)
                                    }
                                }
                            }
                        } catch (e: CancelledException) {
                            throw e
                        } catch (e: Exception) {
                            tries++
                            if (tries > 20) throw e
                            Thread.sleep(minOf(600L * tries, 6000L))
                        }
                        if (f.length() == have) {
                            tries++
                            if (tries > 20) throw Exception("توقف وصول البيانات من الخادم")
                            Thread.sleep(minOf(600L * tries, 6000L))
                        }
                    }
                })
            }
            for (f in fs) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    val c = e.cause
                    if (c is CancelledException) throw c
                    throw Exception(c?.message ?: "فشل تحميل جزء من الملف")
                }
            }
        } finally {
            pool.shutdownNow()
        }
        out.outputStream().use { o ->
            for (f in files) f.inputStream().use { it.copyTo(o, 256 * 1024) }
        }
        for (f in files) f.delete()
    }

    private class Seg(val index: Int, val url: String, val key: ByteArray?, val iv: ByteArray, val rs: Long = -1L, val re: Long = -1L)
    private class HlsJob(val parts: List<File>, val init: File?, val mp4Family: Boolean, val audio: HlsJob? = null, val audioDir: File? = null)

    private fun makeIv(hex: String?, n: Int): ByteArray {
        if (hex != null) {
            val b = hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return if (b.size > 16) b.copyOfRange(b.size - 16, b.size) else b
        }
        val iv = ByteArray(16)
        for (k in 0..3) iv[15 - k] = (n shr (8 * k)).toByte()
        return iv
    }

    // 4 + 5 + 6 + 7: اختيار الجودة، قراءة القائمة، التحميل المتوازي، فك التشفير
    private fun hls(u0: String, h: Map<String, String>, work: File, ctl: Ctl, progress: (Int, Int) -> Unit): HlsJob {
        var url = u0
        var pl = text(url, h)

        // قائمة رئيسية: اختر المسار (الأعلى جودة أو الأدنى في وضع التوفير، بدون مسارات الصوت فقط) وافتح قائمته
        var audioUrl: String? = null
        if (pl.contains("#EXT-X-STREAM-INF")) {
            val best = pickHls(parseMaster(url, pl)) ?: throw Exception("لم يتم العثور على جودة في القائمة الرئيسية")
            audioUrl = hlsAudioUrl(url, pl, best.audio)
            url = best.url
            pl = text(url, h)
        }

        // قراءة القائمة: كل سطر لا يبدأ بـ # هو جزء، والنسبي يُضاف لعنوان القائمة
        val seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(pl)?.groupValues?.get(1)?.toInt() ?: 0
        var curKey: ByteArray? = null
        var curIv: String? = null
        var initUrl: String? = null
        var initRs = -1L
        var initRe = -1L
        var pendLen = -1L
        var pendOff = -1L
        var lastUri = ""
        var lastEnd = 0L
        val keyCache = HashMap<String, ByteArray>()
        val segs = ArrayList<Seg>()
        for (raw in pl.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXT-X-KEY")) {
                val method = Regex("METHOD=([A-Z0-9-]+)").find(l)?.groupValues?.get(1) ?: "NONE"
                if (method == "NONE") {
                    curKey = null
                    curIv = null
                } else if (method == "AES-128") {
                    val ku = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                        ?: throw Exception("المفتاح بدون رابط")
                    val ka = absUrl(url, ku)
                    curKey = keyCache.getOrPut(ka) { retry(3) { bytes(ka, h) } }
                    curIv = Regex("IV=0x([0-9A-Fa-f]+)").find(l)?.groupValues?.get(1)
                } else if (prefs().getBoolean("ignoreenc", false)) {
                    curKey = null
                    curIv = null
                } else {
                    throw ProtectedException("نوع تشفير غير مدعوم للتنزيل: $method. اضغط «▶ مشاهدة» لتجربة التشغيل المباشر إن كان الفيديو يدعم ذلك. (للتنزيل الخام فعّل «تحميل خام عند تشفير HLS غير مدعوم» من الإعدادات)")
                }
            } else if (l.startsWith("#EXT-X-MAP")) {
                val mu = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                if (mu != null) initUrl = absUrl(url, mu)
                val br = hlsAttr(l, "BYTERANGE")
                if (br != null) {
                    val ln = br.substringBefore('@').toLongOrNull() ?: -1L
                    val of = br.substringAfter('@', "0").toLongOrNull() ?: 0L
                    if (ln > 0) { initRs = of; initRe = of + ln - 1 }
                }
            } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                // الجزء مقطع من ملف أكبر: الطول@الإزاحة، وبدون إزاحة يكمل من نهاية المقطع السابق لنفس الملف
                val v = l.substringAfter(':').trim()
                pendLen = v.substringBefore('@').toLongOrNull() ?: -1L
                pendOff = if (v.contains('@')) (v.substringAfter('@').toLongOrNull() ?: -1L) else -1L
            } else if (!l.startsWith("#")) {
                val i = segs.size
                val su = absUrl(url, l)
                var rs = -1L
                var re = -1L
                if (pendLen > 0) {
                    val st = if (pendOff >= 0) pendOff else if (su == lastUri) lastEnd else 0L
                    rs = st
                    re = st + pendLen - 1
                    lastUri = su
                    lastEnd = st + pendLen
                    pendLen = -1L
                    pendOff = -1L
                }
                segs.add(Seg(i, su, curKey, makeIv(curIv, seq + i), rs, re))
            }
        }
        if (segs.isEmpty()) throw Exception("القائمة فاضية")

        // نحمّل ملف التهيئة أولاً (يلزم لمشاهدة الجزء المحمّل قبل اكتمال التحميل)
        val initFile = initUrl?.let { iu ->
            val f = File(work, "init.mp4")
            if (!(f.exists() && f.length() > 0)) {
                val d = retry(3) { if (initRs >= 0) rangeBytes(iu, h, initRs, initRe) else segBytes(iu, h) }
                if (d.isEmpty()) throw Exception("ملف التهيئة فارغ")
                f.writeBytes(d)
            }
            f
        }
        // مسار الصوت المنفصل (إن وُجد) يُحمَّل أولاً في مجلد فرعي، وفشله يُعلَن ولا يُهمَل بصمت
        var audioJob: HlsJob? = null
        var audioDir: File? = null
        val au = audioUrl
        if (au != null && au != url) {
            val ad = File(work, "aud")
            ad.mkdirs()
            try {
                audioJob = hls(au, h, ad, ctl) { _, _ -> }
                audioDir = ad
            } catch (e: CancelledException) {
                throw e
            } catch (e: ProtectedException) {
                throw e
            } catch (e: Throwable) {
                throw Exception("فشل تحميل مسار الصوت المنفصل: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        val ext = segs[0].url.substringBefore('?').substringAfterLast('.').lowercase()
        val mp4Family = initFile != null || ext in setOf("mp4", "m4s", "m4v", "m4a", "cmfv", "cmfa")
        try { if (mp4Family) File(work, "fam_mp4").writeText("1") } catch (e: Throwable) {}

        // كل جزء يُحفظ في ملف برقمه (part_00000.seg ...) لضمان الترتيب وعدم ملء الذاكرة
        val parts = ArrayList<File>()
        for (s in segs) parts.add(File(work, "part_%05d.seg".format(s.index)))
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(prefs().getInt("threads", 8).coerceIn(1, 128))
        try {
            val futures = segs.map { s ->
                pool.submit(Callable<Unit> {
                    gate(ctl)
                    val part = parts[s.index]
                    // جزء مكتمل من محاولة سابقة: لا يُعاد تحميله
                    if (!(part.exists() && part.length() > 0)) {
                        // الجزء لا يُعدّ مكتملاً إلا إذا وصل بالحجم المتوقع (للمقاطع المحدّدة بالبايت) وغير فارغ
                        var d = retry(5) {
                            gate(ctl)
                            val x = if (s.rs >= 0) rangeBytes(s.url, h, s.rs, s.re) else segBytes(s.url, h)
                            if (x.isEmpty()) throw Exception("جزء فارغ")
                            if (s.rs >= 0 && x.size.toLong() != s.re - s.rs + 1) throw Exception("جزء ناقص")
                            x
                        }
                        ctl.bytes.addAndGet(d.size.toLong())
                        throttle(d.size)
                        if (s.key != null) {
                            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(s.key, "AES"), IvParameterSpec(s.iv))
                            d = c.doFinal(d)
                        }
                        val tmp = File(work, "part_%05d.tmp".format(s.index))
                        tmp.writeBytes(d)
                        if (!tmp.renameTo(part)) {
                            part.writeBytes(d)
                            tmp.delete()
                        }
                    }
                    progress(done.incrementAndGet(), segs.size)
                })
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    val c = e.cause
                    if (c is CancelledException) throw c
                    throw Exception(c?.message ?: "فشل تحميل جزء")
                }
            }
        } finally {
            pool.shutdownNow()
        }

        return HlsJob(parts, initFile, mp4Family, audioJob, audioDir)
    }

    // دمج مسار الصورة مع مسار الصوت المنفصل إن وُجد (HLS بقوائم صوت مستقلة)
    private fun mergeHls(job: HlsJob, work: File, name: String): File {
        val aj = job.audio ?: return merge(job, work, name)
        val vOut = merge(HlsJob(job.parts, job.init, job.mp4Family), work, name + "_v")
        val adir = job.audioDir ?: File(work, "aud")
        return try {
            val aOut = merge(aj, adir, "a")
            mergeDash(DashJob(vOut, aOut), work, name)
        } catch (e: Throwable) {
            logErr("دمج", "تعذر دمج الصوت المنفصل، سيُحفظ الفيديو بدون صوت: " + (e.message ?: e.javaClass.simpleName), e)
            vOut
        }
    }

    // 8: ترتيب الأجزاء ودمجها في ملف واحد: mp4parser أولاً، وMediaMuxer كبديل (يشمل HEVC)
    private fun merge(job: HlsJob, work: File, name: String): File {
        val out = File(work, "$name.mp4")
        val ini = job.init
        // الملف المتسلسل بترتيب الأجزاء (TS كامل، أو fMP4 مع ملف التهيئة) ليستعمله MediaMuxer عند الحاجة
        val whole: File? = if (!job.mp4Family) File(work, "whole.ts") else if (ini != null) File(work, "whole.mp4") else null
        if (whole != null) {
            whole.outputStream().buffered().use { o ->
                if (ini != null) ini.inputStream().use { it.copyTo(o) }
                for (p in job.parts) p.inputStream().use { it.copyTo(o) }
            }
        }
        try {
            val movie: Movie
            if (!job.mp4Family) {
                // TS: تُفكّ الحزم إلى مسار H.264 ومسار AAC ثم يبنيهما mp4parser
                val v = File(work, "video.h264")
                val a = File(work, "audio.aac")
                val used = demuxTs(job.parts, v, a)
                movie = Movie()
                if (used.first) movie.addTrack(H264TrackImpl(FileDataSourceImpl(v)))
                if (used.second) movie.addTrack(AACTrackImpl(FileDataSourceImpl(a)))
                if (movie.tracks.isEmpty()) throw Exception("لا يوجد فيديو H.264 أو صوت AAC داخل الأجزاء")
            } else if (whole != null) {
                movie = MovieCreator.build(whole.absolutePath)
            } else {
                val video = ArrayList<Track>()
                val audio = ArrayList<Track>()
                for (p in job.parts) {
                    for (t in MovieCreator.build(p.absolutePath).tracks) {
                        if (t.handler == "vide") video.add(t) else if (t.handler == "soun") audio.add(t)
                    }
                }
                movie = Movie()
                if (video.isNotEmpty()) movie.addTrack(AppendTrack(*video.toTypedArray()))
                if (audio.isNotEmpty()) movie.addTrack(AppendTrack(*audio.toTypedArray()))
            }
            val c = DefaultMp4Builder().build(movie)
            FileOutputStream(out).use { fo -> c.writeContainer(fo.channel) }
            return out
        } catch (e: Throwable) {
            // بديل: MediaMuxer (يدعم HEVC وأي ترميز يقرأه النظام) على الملف المتسلسل
            out.delete()
            logErr("دمج", "فشل mp4parser، جاري تجربة MediaMuxer: " + (e.message ?: e.javaClass.simpleName), e)
            if (whole != null && remux(whole, out)) return out
            if (whole == null && remuxParts(job.parts, out)) return out
            out.delete()
            throw Exception("فشل دمج الأجزاء: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // يحوّل الملف المتسلسل إلى mp4 بدون إعادة ترميز (MediaExtractor + MediaMuxer)
    private fun remux(src: File, mp4: File): Boolean {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(src.absolutePath)
            mux = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    map[i] = mux.addTrack(f)
                    ex.selectTrack(i)
                }
            }
            if (map.isEmpty()) return false
            mux.start()
            started = true
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                val t = map[ex.sampleTrackIndex]
                if (t != null) mux.writeSampleData(t, buf, info)
                ex.advance()
            }
            mux.stop()
            started = false
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { ex.release() } catch (e: Exception) { }
            try { if (started) mux?.stop() } catch (e: Exception) { }
            try { mux?.release() } catch (e: Exception) { }
        }
    }

    // بديل MediaMuxer لأجزاء MP4 المستقلة: يقرأ كل جزء بالترتيب ويكتب عيّناته في ملف واحد مع إزاحة الزمن
    private fun remuxParts(parts: List<File>, mp4: File): Boolean {
        var mux: MediaMuxer? = null
        var started = false
        try {
            mux = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val kindToTrack = HashMap<String, Int>()
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var offset = 0L
            for (pf in parts) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(pf.absolutePath)
                    val map = HashMap<Int, Int>()
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                        val kind = if (mime.startsWith("video/")) "video" else if (mime.startsWith("audio/")) "audio" else continue
                        if (!started && !kindToTrack.containsKey(kind)) kindToTrack[kind] = mux.addTrack(f)
                        val mt = kindToTrack[kind] ?: continue
                        map[i] = mt
                        ex.selectTrack(i)
                    }
                    if (!started) {
                        if (kindToTrack.isEmpty()) return false
                        mux.start()
                        started = true
                    }
                    var maxPts = 0L
                    while (true) {
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val t = map[ex.sampleTrackIndex]
                        val pts = ex.sampleTime
                        if (t != null) {
                            info.offset = 0
                            info.size = n
                            info.presentationTimeUs = pts + offset
                            info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            mux.writeSampleData(t, buf, info)
                            if (pts > maxPts) maxPts = pts
                        }
                        ex.advance()
                    }
                    offset += maxPts + 33000L
                } finally {
                    try { ex.release() } catch (e: Exception) { }
                }
            }
            mux.stop()
            started = false
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { if (started) mux?.stop() } catch (e: Exception) { }
            try { mux?.release() } catch (e: Exception) { }
        }
    }

    // يفكّ حزم MPEG-TS (188 بايت) بترتيب الأجزاء: PAT ثم PMT ثم PES، ويكتب مسار H.264 ومسار AAC(ADTS)
    private fun demuxTs(parts: List<File>, vFile: File, aFile: File): Pair<Boolean, Boolean> {
        var pmtPid = -1
        var vPid = -1
        var aPid = -1
        var hevc = false
        var vUsed = false
        var aUsed = false
        val vo = vFile.outputStream().buffered()
        val ao = aFile.outputStream().buffered()
        try {
            for (part in parts) {
                val d = part.readBytes()
                var i = 0
                while (i + 188 <= d.size) {
                    if (d[i] != 0x47.toByte()) { i++; continue }
                    val pid = ((d[i + 1].toInt() and 0x1F) shl 8) or (d[i + 2].toInt() and 0xFF)
                    val pusi = (d[i + 1].toInt() and 0x40) != 0
                    val afc = (d[i + 3].toInt() shr 4) and 3
                    var off = i + 4
                    if ((afc and 2) != 0) off += 1 + (d[i + 4].toInt() and 0xFF)
                    val end = i + 188
                    if ((afc and 1) != 0 && off < end) {
                        try {
                            if (pid == 0 && pusi) {
                                val s0 = off + 1 + (d[off].toInt() and 0xFF)
                                val secLen = ((d[s0 + 1].toInt() and 0x0F) shl 8) or (d[s0 + 2].toInt() and 0xFF)
                                var q = s0 + 8
                                val qend = minOf(s0 + 3 + secLen - 4, end)
                                while (q + 4 <= qend) {
                                    val prog = ((d[q].toInt() and 0xFF) shl 8) or (d[q + 1].toInt() and 0xFF)
                                    val pp = ((d[q + 2].toInt() and 0x1F) shl 8) or (d[q + 3].toInt() and 0xFF)
                                    if (prog != 0) { pmtPid = pp; break }
                                    q += 4
                                }
                            } else if (pid == pmtPid && pusi) {
                                val s0 = off + 1 + (d[off].toInt() and 0xFF)
                                val secLen = ((d[s0 + 1].toInt() and 0x0F) shl 8) or (d[s0 + 2].toInt() and 0xFF)
                                val piLen = ((d[s0 + 10].toInt() and 0x0F) shl 8) or (d[s0 + 11].toInt() and 0xFF)
                                var q = s0 + 12 + piLen
                                val qend = minOf(s0 + 3 + secLen - 4, end)
                                while (q + 5 <= qend) {
                                    val st = d[q].toInt() and 0xFF
                                    val ep = ((d[q + 1].toInt() and 0x1F) shl 8) or (d[q + 2].toInt() and 0xFF)
                                    val esLen = ((d[q + 3].toInt() and 0x0F) shl 8) or (d[q + 4].toInt() and 0xFF)
                                    if (st == 0x1B && vPid < 0) vPid = ep
                                    else if (st == 0x0F && aPid < 0) aPid = ep
                                    else if (st == 0x24) hevc = true
                                    q += 5 + esLen
                                }
                            } else if (pid == vPid || pid == aPid) {
                                var ps = off
                                if (pusi && ps + 9 <= end && d[ps].toInt() == 0 && d[ps + 1].toInt() == 0 && d[ps + 2].toInt() == 1) {
                                    ps += 9 + (d[ps + 8].toInt() and 0xFF)
                                }
                                if (ps < end) {
                                    if (pid == vPid) { vo.write(d, ps, end - ps); vUsed = true }
                                    else { ao.write(d, ps, end - ps); aUsed = true }
                                }
                            }
                        } catch (e: IndexOutOfBoundsException) {
                        }
                    }
                    i += 188
                }
            }
        } finally {
            vo.close()
            ao.close()
        }
        if (hevc && !vUsed) throw Exception("HEVC: يتولاه MediaMuxer")
        return Pair(vUsed, aUsed)
    }

    // ينقل الملف النهائي إلى مجلد التحميلات (Download)
    private fun saveToDownloads(f: File, mime: String): String = saveToDownloadsEx(f, mime).first

    // يرجع (وصف المكان، رابط/مسار الملف النهائي للمشاهدة)
    private fun saveToDownloadsEx(f: File, mime: String): Pair<String, String> {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues()
            v.put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            v.put(MediaStore.Downloads.MIME_TYPE, mime)
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            v.put(MediaStore.Downloads.IS_PENDING, 1)
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw Exception("تعذر إنشاء الملف في مجلد التحميلات")
            contentResolver.openOutputStream(uri)!!.use { o ->
                f.inputStream().use { it.copyTo(o) }
            }
            val done = ContentValues()
            done.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, done, null, null)
            f.delete()
            return Pair("Download/" + f.name, uri.toString())
        }
        val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        d.mkdirs()
        val out = File(d, f.name)
        f.copyTo(out, true)
        f.delete()
        return Pair(out.absolutePath, out.absolutePath)
    }

    // ---------- المتصفح الحقيقي: ملفات وصلاحيات وملء الشاشة وتحميل ----------

    private fun askWebPermission(r: PermissionRequest) {
        val need = ArrayList<String>()
        val names = ArrayList<String>()
        for (x in r.resources) {
            if (x == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                need.add(android.Manifest.permission.CAMERA)
                names.add("الكاميرا")
            } else if (x == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                need.add(android.Manifest.permission.RECORD_AUDIO)
                names.add("الميكروفون")
            }
        }
        if (need.isEmpty()) {
            try { r.grant(r.resources) } catch (e: Throwable) {}
            return
        }
        StyledBuilder(this).setTitle("إذن الموقع")
            .setMessage((r.origin.host ?: "الموقع") + " يطلب استخدام " + names.joinToString(" و "))
            .setPositiveButton("سماح") { _, _ ->
                val miss = need.filter { Build.VERSION.SDK_INT >= 23 && checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                if (miss.isEmpty()) {
                    try { r.grant(r.resources) } catch (e: Throwable) {}
                } else {
                    permReq = r
                    requestPermissions(miss.toTypedArray(), 80)
                }
            }
            .setNegativeButton("منع") { _, _ -> try { r.deny() } catch (e: Throwable) {} }
            .setOnCancelListener { try { r.deny() } catch (e: Throwable) {} }
            .show()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 78) {
            val ok = res.isNotEmpty() && res.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            try { geoCb?.invoke(geoOrigin, ok, false) } catch (e: Throwable) {}
            geoCb = null
        } else if (code == 80) {
            val r = permReq
            permReq = null
            if (r != null) {
                val ok = res.isNotEmpty() && res.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
                try { if (ok) r.grant(r.resources) else r.deny() } catch (e: Throwable) {}
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
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Throwable) {}
                openViewer(u)
            }
            return
        }
        super.onActivityResult(req, res, data)
    }

    private fun showFull(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (fullView != null) {
            cb.onCustomViewHidden()
            return
        }
        fullView = v
        fullCb = cb
        v.setBackgroundColor(Color.BLACK)
        (window.decorView as ViewGroup).addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        fullFlags = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun hideFull() {
        val v = fullView ?: return
        fullView = null
        try { (window.decorView as ViewGroup).removeView(v) } catch (e: Throwable) {}
        window.decorView.systemUiVisibility = fullFlags
        try { fullCb?.onCustomViewHidden() } catch (e: Throwable) {}
        fullCb = null
    }

    // أي ملف يطلب الموقع تنزيله: يدخل محرك التحميل السريع ويبدأ فوراً
    private fun onWebDownload(url: String, ua: String?, cd: String?, mime: String?, len: Long) {
        val name = URLUtil.guessFileName(url, cd, mime)
        if (url.startsWith("blob:")) {
            fetchBlob(url, name, mime ?: "")
            return
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("هذا النوع من الروابط غير مدعوم للتحميل")
            return
        }
        val h = HashMap<String, String>()
        h["User-Agent"] = if (!ua.isNullOrEmpty()) ua else curUa
        h["Referer"] = cur?.web?.url ?: ""
        h["Cookie"] = CookieManager.getInstance().getCookie(url) ?: ""
        foundName[url] = name
        foundMime[url] = if (!mime.isNullOrEmpty() && mime != "application/octet-stream") mime else FileUtil.mimeOf(name)
        addFound(url, h, Meta(true, -1.0, len, false, "", 0L, emptyList()), cur, name)
        foundStart[url]?.invoke()
        showPage(1)
        toast("بدأ تحميل: " + name)
    }

    private fun fetchBlob(url: String, name: String, mime: String) {
        val w = cur?.web ?: return
        val js = "(function(){var u=" + org.json.JSONObject.quote(url) + ";fetch(u).then(function(r){return r.blob();}).then(function(b){" +
            "var id=String(Date.now());var size=b.size;var ch=393216;var i=0;" +
            "function nx(){if(i>=size){TDL.blobEnd(id," + org.json.JSONObject.quote(name) + "," + org.json.JSONObject.quote(mime) + ");return;}" +
            "var fr=new FileReader();fr.onload=function(){var d=fr.result;TDL.blobPart(id,d.substring(d.indexOf(',')+1));i+=ch;nx();};" +
            "fr.onerror=function(){TDL.err('blob read');};fr.readAsDataURL(b.slice(i,i+ch));}" +
            "if(size==0){TDL.err('blob empty');return;}nx();}).catch(function(e){TDL.err('blob: '+e);});})();"
        w.evaluateJavascript(js, null)
        toast("جاري حفظ الملف...")
    }

    private fun openStorage() {
        startActivity(Intent(this, StorageActivity::class.java))
    }

    private fun openViewer(u: Uri) {
        val i = Intent(this, ViewerActivity::class.java)
        i.data = u
        startActivity(i)
    }

    private fun openHtmlFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            startActivityForResult(i, 79)
        } catch (e: Throwable) {
            toast("تعذر فتح منتقي الملفات")
        }
    }

    // ---------- التحكم الحر بالسرعة ----------

    private fun labeledSeek(col: LinearLayout, title: String, min: Int, max: Int, start: Int, fmt: (Int) -> String, onStop: (Int) -> Unit): SeekBar {
        val hdr = LinearLayout(this)
        hdr.orientation = LinearLayout.HORIZONTAL
        hdr.gravity = Gravity.CENTER_VERTICAL
        hdr.addView(tv(title, 13f, cText, true), lp(0, WRAP, 1f))
        val t = tv(fmt(start), 12f, cAccent, true)
        t.background = bg(cBg, 12, cLine)
        t.setPadding(dp(9), dp(2), dp(9), dp(2))
        hdr.addView(t, lp(WRAP, WRAP))
        col.addView(hdr, lp(MATCH, WRAP, 0f, 0, 10, 0, 2))
        val s = SeekBar(this)
        s.max = max - min
        s.progress = (start - min).coerceIn(0, max - min)
        s.progressTintList = ColorStateList.valueOf(cAccent)
        s.thumbTintList = ColorStateList.valueOf(cAccent)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                t.text = fmt(p + min)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {
                onStop(sb.progress + min)
            }
        })
        col.addView(s, lp(MATCH, WRAP))
        return s
    }

    private fun showSpeed() {
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
    private fun speedTest() {
        toast("جاري قياس السرعة لمدة 6 ثوانٍ...")
        Thread {
            val total = java.util.concurrent.atomic.AtomicLong(0)
            val t0 = System.nanoTime()
            val end = t0 + 6000000000L
            val ex = Executors.newFixedThreadPool(4)
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
                    } catch (e: Throwable) {
                    }
                }))
            }
            for (f in fs) try { f.get() } catch (e: Throwable) {}
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
    private fun inspectLink() {
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
                Thread {
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
                    } catch (e: Throwable) {
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
    private fun batchLinks() {
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

    // ---------- أحجام الأزرار والأيقونات ----------

    private fun regStatic() {
        sizeIds["bar_back"] = Pair("شريط المتصفح العلوي", "رجوع")
        sizeIds["bar_fwd"] = Pair("شريط المتصفح العلوي", "تقدم")
        sizeIds["bar_tabs"] = Pair("شريط المتصفح العلوي", "التبويبات")
        sizeIds["bar_menu"] = Pair("شريط المتصفح العلوي", "القائمة (الثلاث نقاط)")
        sizeIds["bar_plus"] = Pair("شريط المتصفح العلوي", "تبويب جديد")
        sizeIds["bar_dl"] = Pair("شريط المتصفح العلوي", "تحميل الفيديو")
        sizeIds["bar_batch"] = Pair("شريط المتصفح العلوي", "التقاط كل الفيديوهات")
        sizeIds["bar_home"] = Pair("شريط المتصفح العلوي", "الرئيسية")
        sizeIds["txt_pill"] = Pair("الأزرار النصية", "تحميل، نسخ الرابط، إيقاف مؤقت، إلغاء وأمثالها")
        val navs = listOf("المتصفح", "التنزيلات", "الأدوات", "الإعدادات", "التنبيهات")
        for (i in navs.indices) sizeIds["nav_" + i] = Pair("القائمة الجانبية", navs[i])
    }

    private fun pctB(id: String): Int = prefs().getInt("szb_" + id, 100).coerceIn(30, 300)
    private fun pctI(id: String): Int = prefs().getInt("szi_" + id, 100).coerceIn(30, 300)

    private fun applyBarSizes() {
        // الحد الأقصى لعرض كل زر كي يبقى مربع الرابط ظاهراً: 4 أزرار + 150dp للرابط والهوامش
        val maxBox = (resources.displayMetrics.widthPixels - dp(110)) / 6
        fun box(id: String): Int = minOf(dp(38 * pctB(id) / 100), maxBox).coerceAtLeast(dp(12))
        fun ap(id: String, v: View, defI: Int) {
            val b = box(id)
            val ic = minOf(b, dp(defI * pctI(id) / 100))
            val pad = (b - ic) / 2
            v.setPadding(pad, pad, pad, pad)
            val p = v.layoutParams
            if (p != null) {
                p.width = b
                p.height = b
                v.layoutParams = p
            }
        }
        try {
            ap("bar_back", barBack, 22)
            ap("bar_fwd", barFwd, 22)
            ap("bar_reload", reloadBtn, 22)
            ap("bar_menu", barMenu, 22)
            ap("bar_plus", barPlus, 22)
            ap("bar_dl", barDl, 22)
            ap("bar_batch", barBatch, 22)
            ap("bar_home", barHome, 22)
            ap("bar_tabs", barTabs, 22)
            val ic = minOf(box("bar_tabs"), dp(22 * pctI("bar_tabs") / 100))
            val q = tabBtn.layoutParams
            if (q != null) {
                q.width = ic
                q.height = ic
                tabBtn.layoutParams = q
            }
        } catch (e: Throwable) {
            logErr("واجهة", "تعذر تطبيق أحجام الشريط العلوي: " + (e.message ?: e.javaClass.simpleName), e)
        }
    }

    // يعيد بناء الأدوات والقائمة الجانبية بالأحجام الجديدة
    private fun applySizes() {
        try {
            applyBarSizes()
            val oldT = pagesList[2]
            val nt = buildTools()
            pagesList[2] = nt
            val ti = pageHost.indexOfChild(oldT)
            pageHost.removeViewAt(ti)
            pageHost.addView(nt, ti, FrameLayout.LayoutParams(MATCH, MATCH))
            nt.visibility = oldT.visibility
            navOverlay.removeView(navPopup)
            navItems.clear()
            navOverlay.addView(buildNav(), navPopupLp())
            showPage(page)
            restylePills(root)
            syncDlBtnHeights()
        } catch (e: Throwable) {
            logErr("واجهة", "تعذر تطبيق الأحجام: " + (e.message ?: ""), e)
        }
    }

    private fun showSizes() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(cCard)
        col.setPadding(dp(16), dp(10), dp(16), dp(10))
        sv.addView(col)
        val groups = LinkedHashMap<String, ArrayList<String>>()
        for ((id, gl) in sizeIds) groups.getOrPut(gl.first) { ArrayList() }.add(id)
        fun fill() {
            val keepY = sv.scrollY
            col.removeAllViews()
            col.addView(tv("اسحب الشريط للتحكم بالحجم من 30% إلى 300%. يُطبَّق فوراً. أزرار الشريط العلوي تتوقف عند أكبر حجم يُبقي مربع الرابط ظاهراً.", 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 0, 0, 4))
            labeledSeek(col, "عدد أعمدة بطاقات الأدوات", 2, 4, prefs().getInt("toolcols", 3).coerceIn(2, 4), { "" + it }) { v ->
                prefs().edit().putInt("toolcols", v).apply()
                applySizes()
            }
            for ((g, ids) in groups) {
                col.addView(tv(g, 15f, cAccent, true), lp(MATCH, WRAP, 0f, 0, 18, 0, 2))
                val first = ids[0]
                if (ids.size > 1) {
                    labeledSeek(col, "كل أزرار المجموعة", 30, 300, pctB(first), { "$it%" }) { v ->
                        val e = prefs().edit()
                        for (id in ids) e.putInt("szb_" + id, v)
                        e.apply()
                        applySizes()
                        fill()
                    }
                    labeledSeek(col, "كل أيقونات المجموعة", 30, 300, pctI(first), { "$it%" }) { v ->
                        val e = prefs().edit()
                        for (id in ids) e.putInt("szi_" + id, v)
                        e.apply()
                        applySizes()
                        fill()
                    }
                }
                for (id in ids) {
                    val label = sizeIds[id]?.second ?: id
                    val gi = LinearLayout(this)
                    gi.orientation = LinearLayout.VERTICAL
                    gi.background = bg(Color.TRANSPARENT, 12, cLine)
                    gi.setPadding(dp(10), dp(4), dp(10), dp(8))
                    gi.addView(tv("▪ " + label, 13f, cText, true), lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
                    labeledSeek(gi, "الزر", 30, 300, pctB(id), { "$it%" }) { v ->
                        prefs().edit().putInt("szb_" + id, v).apply()
                        applySizes()
                    }
                    if (id !in noIconIds) {
                        labeledSeek(gi, "الأيقونة", 30, 300, pctI(id), { "$it%" }) { v ->
                            prefs().edit().putInt("szi_" + id, v).apply()
                            applySizes()
                        }
                    }
                    col.addView(gi, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
                }
            }
            sv.post { sv.scrollTo(0, keepY) }
        }
        fill()
        StyledBuilder(this).setTitle("أحجام الأزرار والأيقونات").setView(sv)
            .setPositiveButton("تم", null)
            .setNeutralButton("إعادة الضبط") { _, _ ->
                val e = prefs().edit()
                for (k in prefs().all.keys) if (k.startsWith("szb_") || k.startsWith("szi_") || k == "toolcols") e.remove(k)
                e.apply()
                applySizes()
                toast("أُعيدت الأحجام الأصلية")
            }.show()
    }
}
