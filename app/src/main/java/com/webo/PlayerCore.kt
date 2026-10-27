package com.webo

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.text.TextUtils
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

// PlayerCore: كشف نوع الوسائط وحالة الحماية، واختيار محرك التشغيل المناسب
object PlayerCore {
    const val ENGINE_MEDIA3 = 1

    private const val WIDEVINE = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    // نتيجة لقطة التشغيل أثناء التنزيل: path ملف قابل للتشغيل (أو null إن لم يتغير شيء)، done = لا بيانات جديدة بعد الآن
    class Live(val path: String?, val isUri: Boolean, val done: Boolean, val sig: Long, val note: String, val audio: String? = null)

    // تسجّله الشاشة الرئيسية؛ يُستدعى على خيط خلفي فقط
    @Volatile var liveHook: ((String, Long) -> Live)? = null

    // prot: 0 غير محمي، 1 محمي، 2 غير محدد
    class Probe(val kind: String, val prot: Int, val widevine: Boolean, val licenseUrl: String?, val note: String, val codecs: String = "")

    fun extOf(s: String): String =
        s.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.US)

    fun kindOf(url: String, hint: String): String {
        if (hint == "HLS" || hint == "DASH") return hint
        val p = url.substringBefore("#tdlrep=").substringBefore('?').lowercase(Locale.US)
        return when {
            p.endsWith(".m3u8") -> "HLS"
            p.endsWith(".mpd") -> "DASH"
            else -> "FILE"
        }
    }

    fun choose(p: Probe, ext: String): Int = ENGINE_MEDIA3

    private fun fetchText(u: String, hdr: Map<String, String>): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            for ((k, v) in hdr) if (v.isNotBlank()) c.setRequestProperty(k, v)
            try {
                if (c.responseCode !in 200..299) null
                else c.inputStream.use { s ->
                    val b = ByteArray(1000000)
                    var n = 0
                    while (n < b.size) {
                        val r = s.read(b, n, b.size - n)
                        if (r < 0) break
                        n += r
                    }
                    String(b, 0, n, Charsets.UTF_8)
                }
            } finally {
                try { c.disconnect() } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {
            null
        }
    }

    // يقرأ القائمة نفسها (m3u8 / mpd) ليعرف هل الفيديو محمي، وهل فيه رابط ترخيص معلن. يعمل على خيط خلفي
    fun probe(url0: String, hdr: Map<String, String>, hint: String): Probe {
        val url = url0.substringBefore("#tdlrep=")
        val kind = kindOf(url, hint)
        if (kind == "FILE") return Probe(kind, 2, false, null, "")
        val t = fetchText(url, hdr) ?: return Probe(kind, 2, false, null, "")
        return if (kind == "DASH") probeDash(t) else probeHls(url, t, hdr)
    }

    private fun probeDash(t: String): Probe {
        if (!t.contains("<MPD")) return Probe("DASH", 2, false, null, "")
        val has = t.contains("<ContentProtection")
        val wv = t.contains(WIDEVINE, true)
        val la = Regex("<(?:[A-Za-z0-9_]+:)?[Ll]aurl[^>]*>\\s*(?:<!\\[CDATA\\[)?\\s*([^<\\]\\s]+)")
            .find(t)?.groupValues?.get(1)?.replace("&amp;", "&")
        val cod = Regex("codecs=\"([^\"]+)\"").findAll(t).map { it.groupValues[1] }.distinct().joinToString(",")
        return Probe("DASH", if (has) 1 else 0, wv, la, "", cod)
    }

    private fun probeHls(url: String, t: String, hdr: Map<String, String>): Probe {
        if (!t.contains("#EXTM3U")) return Probe("HLS", 2, false, null, "")
        val lines = ArrayList<String>()
        lines.addAll(t.lines())
        if (t.contains("#EXT-X-STREAM-INF")) {
            val ls = t.lines()
            for (i in ls.indices) {
                if (ls[i].startsWith("#EXT-X-STREAM-INF")) {
                    val nx = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (nx != null) {
                        try {
                            val vu = URL(URL(url), nx.trim()).toString()
                            fetchText(vu, hdr)?.let { lines.addAll(it.lines()) }
                        } catch (e: Throwable) {
                        }
                    }
                    break
                }
            }
        }
        var prot = 0
        var wv = false
        var fair = false
        for (raw in lines) {
            val l = raw.trim()
            if (!(l.startsWith("#EXT-X-KEY") || l.startsWith("#EXT-X-SESSION-KEY"))) continue
            val m = Regex("METHOD=([A-Z0-9-]+)").find(l)?.groupValues?.get(1) ?: "NONE"
            val kf = Regex("KEYFORMAT=\"([^\"]+)\"").find(l)?.groupValues?.get(1) ?: "identity"
            if (m == "NONE") continue
            // AES-128 بمفتاح عادي تشفير بسيط وليس DRM، والمشغّل يفكه بنفسه
            if (m == "AES-128" && kf == "identity") continue
            prot = 1
            if (kf.contains("edef8ba9", true)) wv = true
            if (kf.contains("streamingkeydelivery", true)) fair = true
        }
        val note = if (fair && !wv) "هذا الفيديو محمي بنظام FairPlay الخاص بأبل وغير مدعوم على أندرويد" else ""
        val cod = Regex("CODECS=\"([^\"]+)\"").findAll(t).map { it.groupValues[1] }.distinct().joinToString(",")
        return Probe("HLS", prot, wv, null, note, cod)
    }
}

// المشغّل المدمج: Media3 ExoPlayer. يُفتح من تبويب التحميلات فقط
class VideoPlayerActivity : Activity() {
    companion object {
        const val X_SRC = "src"
        const val X_URI = "isUri"
        const val X_REMOTE = "remote"
        const val X_HDR = "hdr"
        const val X_KIND = "kind"
        const val X_LIVE = "live"
        const val X_OFFLINE = "offline"
        const val X_ENGINE = "engine"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var src = ""
    private var isUri = false
    private var remote = false
    private var kindHint = ""
    private val hdr = HashMap<String, String>()
    private var probe: PlayerCore.Probe? = null
    private var engine = 0
    private var destroyed = false
    private var dragging = false

    // التشغيل أثناء التنزيل
    private var liveKey: String? = null
    private var live = false
    private var waiting = false
    private var liveBusy = false
    private var lastSig = -1L
    private var lastPos = 0L
    private var resumeAt = 0L
    private val liveFiles = ArrayList<String>()
    private var audioPath: String? = null

    // التشغيل دون اتصال لمحتوى محمي محفوظ (ترخيص دائم + أجزاء مشفّرة في مخزن التطبيق)
    private var offId: String? = null
    private var offRec: OfflineDrm.Rec? = null
    private var renewTried = false

    private var exo: ExoPlayer? = null
    private var surface: SurfaceView? = null
    private var vsW = 0
    private var vsH = 0
    private var vsPar = 1f


    private lateinit var host: FrameLayout
    private lateinit var status: TextView
    private lateinit var engLabel: TextView
    private lateinit var playBtn: TextView
    private lateinit var qBtn: TextView
    private lateinit var rootV: FrameLayout
    private lateinit var topV: LinearLayout
    private lateinit var barV: LinearLayout
    private var dlgLayer: View? = null
    private var qSel = 0
    private var uiHidden = false
    private lateinit var seek: SeekBar
    private lateinit var tCur: TextView
    private lateinit var tTot: TextView

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (!destroyed) ui.postDelayed(this, 500)
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private val ACC = Color.parseColor("#4da3ff")

    // خلفية «زجاجية» شبه شفافة بحدّ أبيض خفيف
    private fun glass(circle: Boolean, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        if (circle) g.shape = GradientDrawable.OVAL else g.cornerRadius = dp(radiusDp).toFloat()
        g.setColor(Color.argb(140, 24, 24, 28))
        g.setStroke(dp(1), Color.argb(51, 255, 255, 255))
        return g
    }

    // زر دائري زجاجي
    private fun iconBtn(t: String, size: Int, sp: Float, onClick: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.WHITE)
        v.textSize = sp
        v.gravity = Gravity.CENTER
        v.width = dp(size)
        v.height = dp(size)
        v.background = glass(true, 0)
        v.setOnClickListener { onClick() }
        return v
    }

    // رمز التشغيل/الإيقاف مرسوم (مثلث / عمودان) ومتوسط داخل الدائرة، ولا يتأثر بخط الجهاز
    private inner class PlayGlyph(val playing: Boolean) : Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).also { it.color = Color.WHITE }
        override fun getIntrinsicWidth(): Int = dp(20)
        override fun getIntrinsicHeight(): Int = dp(20)
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            val u = b.width() / 24f
            val l = b.left.toFloat()
            val t = b.top.toFloat()
            if (playing) {
                c.drawRoundRect(l + 6.5f * u, t + 5f * u, l + 10.5f * u, t + 19f * u, 1.2f * u, 1.2f * u, paint)
                c.drawRoundRect(l + 13.5f * u, t + 5f * u, l + 17.5f * u, t + 19f * u, 1.2f * u, 1.2f * u, paint)
            } else {
                val path = android.graphics.Path()
                path.moveTo(l + 7.6f * u, t + 5.5f * u)
                path.lineTo(l + 7.6f * u, t + 18.5f * u)
                path.lineTo(l + 18.2f * u, t + 12f * u)
                path.close()
                c.drawPath(path, paint)
            }
        }
        override fun setAlpha(a: Int) { paint.alpha = a }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun setPlayIcon(playing: Boolean) {
        playBtn.text = ""
        playBtn.translationX = 0f
        val ld = LayerDrawable(arrayOf<Drawable>(glass(true, 0), PlayGlyph(playing)))
        ld.setLayerGravity(1, Gravity.CENTER)
        playBtn.background = ld
    }

    // حبّة زجاجية (للمحرك والجودة)
    private fun pillBtn(t: String, onClick: (() -> Unit)?): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.WHITE)
        v.textSize = 14f
        v.gravity = Gravity.CENTER
        v.minHeight = dp(40)
        v.setPadding(dp(14), 0, dp(14), 0)
        v.background = glass(false, 20)
        if (onClick != null) v.setOnClickListener { onClick() }
        return v
    }

    private fun timeText(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.WHITE)
        v.textSize = 12f
        v.gravity = Gravity.CENTER
        v.minWidth = dp(38)
        v.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        v.fontFeatureSettings = "tnum"
        return v
    }

    // شريط تقدم بمسار رفيع ولون أزرق ومقبض أبيض دائري
    private fun styleSeek(sb: SeekBar) {
        val track = GradientDrawable()
        track.setColor(Color.argb(77, 255, 255, 255))
        track.cornerRadius = dp(2).toFloat()
        val prog = GradientDrawable()
        prog.setColor(ACC)
        prog.cornerRadius = dp(2).toFloat()
        val clip = ClipDrawable(prog, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        val ld = LayerDrawable(arrayOf<Drawable>(track, clip))
        ld.setId(0, android.R.id.background)
        ld.setId(1, android.R.id.progress)
        for (i in 0..1) {
            ld.setLayerHeight(i, dp(4))
            ld.setLayerGravity(i, Gravity.CENTER_VERTICAL)
        }
        sb.progressDrawable = ld
        val th = GradientDrawable()
        th.shape = GradientDrawable.OVAL
        th.setColor(Color.WHITE)
        th.setSize(dp(16), dp(16))
        sb.thumb = th
        sb.splitTrack = false
        sb.setPadding(dp(8), 0, dp(8), 0)
    }

    private fun radioDrawable(sel: Boolean): Drawable {
        val ring = GradientDrawable()
        ring.shape = GradientDrawable.OVAL
        ring.setColor(Color.TRANSPARENT)
        ring.setStroke(dp(2), if (sel) ACC else Color.parseColor("#6b7078"))
        if (!sel) return ring
        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(ACC)
        val ld = LayerDrawable(arrayOf<Drawable>(ring, dot))
        ld.setLayerInset(1, dp(5), dp(5), dp(5), dp(5))
        return ld
    }

    private fun optRow(title: String, sub: String, sel: Boolean, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(8), dp(12), dp(8))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(18).toFloat()
        bg.setColor(Color.argb(153, 0, 0, 0))
        bg.setStroke(dp(1), if (sel) ACC else Color.argb(102, 255, 255, 255))
        row.background = bg
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title
        t.textSize = 15f
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.setTextColor(if (sel) ACC else Color.WHITE)
        col.addView(t)
        if (sub.isNotEmpty()) {
            val st = TextView(this)
            st.text = sub
            st.textSize = 12f
            st.setTextColor(Color.parseColor("#9aa0a8"))
            col.addView(st)
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val rad = View(this)
        rad.background = radioDrawable(sel)
        row.addView(rad, LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginStart = dp(8) })
        row.setOnClickListener { onClick() }
        return row
    }

    private fun closeDlg() {
        dlgLayer?.let { try { rootV.removeView(it) } catch (e: Throwable) {} }
        dlgLayer = null
    }

    // نافذة اختيار الجودة: صندوق زجاجي تحت زر الجودة مع شرح كل خيار
    private fun showQualityDialog(titles: List<String>, subs: List<String>, onPick: (Int) -> Unit) {
        closeDlg()
        val r = rootV
        val layer = FrameLayout(this)
        layer.setOnClickListener { closeDlg() }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10), dp(10), dp(10), dp(10))
        box.background = glass(false, 18)
        box.isClickable = true

        val hd = LinearLayout(this)
        hd.orientation = LinearLayout.HORIZONTAL
        hd.gravity = Gravity.CENTER_VERTICAL
        val ttl = TextView(this)
        ttl.text = "اختر الجودة"
        ttl.textSize = 15f
        ttl.setTypeface(ttl.typeface, android.graphics.Typeface.BOLD)
        ttl.setTextColor(Color.WHITE)
        hd.addView(ttl, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(16) })
        hd.addView(iconBtn("✕", 32, 14f) { closeDlg() })
        box.addView(hd, LinearLayout.LayoutParams(-1, -2).also { it.setMargins(dp(2), 0, dp(2), dp(6)) })

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        for (i in titles.indices) {
            list.addView(optRow(titles[i], subs[i], i == qSel) { closeDlg(); onPick(i) },
                LinearLayout.LayoutParams(-1, -2).also { it.setMargins(0, dp(4), 0, dp(4)) })
        }
        val sc = ScrollView(this)
        sc.addView(list)
        box.addView(sc, LinearLayout.LayoutParams(-1, -2))

        val rw = r.width
        val rh = r.height
        val loc = IntArray(2)
        qBtn.getLocationInWindow(loc)
        val topPos = loc[1] + qBtn.height + dp(6)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(rw - dp(24), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(rh, View.MeasureSpec.AT_MOST)
        )
        val avail = rh - topPos - dp(12)
        if (box.measuredHeight > avail && avail > dp(120)) {
            sc.layoutParams = LinearLayout.LayoutParams(-1, sc.measuredHeight - (box.measuredHeight - avail))
        }
        val leftPos = Math.max(dp(12), Math.min(loc[0], rw - box.measuredWidth - dp(12)))
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT)
        lp.leftMargin = leftPos
        lp.topMargin = topPos
        layer.addView(box, lp)
        r.addView(layer, FrameLayout.LayoutParams(-1, -1))
        dlgLayer = layer
    }

    // إخفاء/إظهار الواجهة بنقرتين متتاليتين على الشاشة
    private fun toggleUi() {
        closeDlg()
        uiHidden = !uiHidden
        for (v in listOf<View>(topV, barV)) {
            if (uiHidden) {
                v.animate().alpha(0f).setDuration(200).withEndAction { if (uiHidden) v.visibility = View.INVISIBLE }.start()
            } else {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    // نص زر الجودة في وضع «تلقائي» مع الدقة الجارية فعلاً
    private fun refreshQ() {
        if (qSel != 0) return
        val h = exo?.videoSize?.height ?: 0
        val t = "⚙ تلقائي" + (if (h > 0) " · " + h + "p" else "")
        if (qBtn.text.toString() != t) qBtn.text = t
    }

    override fun onBackPressed() {
        if (dlgLayer != null) closeDlg() else super.onBackPressed()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        src = intent.getStringExtra(X_SRC) ?: ""
        isUri = intent.getBooleanExtra(X_URI, false)
        remote = intent.getBooleanExtra(X_REMOTE, false)
        kindHint = intent.getStringExtra(X_KIND) ?: ""
        val hb = intent.getBundleExtra(X_HDR)
        if (hb != null) for (k in hb.keySet()) hb.getString(k)?.let { hdr[k] = it }
        liveKey = intent.getStringExtra(X_LIVE)
        live = liveKey != null
        offId = intent.getStringExtra(X_OFFLINE)
        if (offId != null) {
            val rec = OfflineDrm.get(this, offId!!)
            if (rec == null) {
                toast("العنصر المحمي غير موجود")
                finish()
                return
            }
            offRec = rec
            src = rec.url
            remote = true
            kindHint = "DASH"
            hdr.clear()
            hdr.putAll(rec.hdr)
        }
        if (src.isEmpty() && !live) {
            finish()
            return
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        host = FrameLayout(this)
        root.addView(host, FrameLayout.LayoutParams(-1, -1))
        host.addOnLayoutChangeListener { _, a, bb, c, d, e, f, g, h ->
            if (c - a != g - e || d - bb != h - f) fitSurface()
        }

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 15f
        status.gravity = Gravity.CENTER
        status.setPadding(dp(24), dp(24), dp(24), dp(24))
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        // الشريط العلوي: إغلاق + اسم المحرك + الجودة (بنفس تصميم المعاينة)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(12), dp(14), dp(12), dp(30))
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(166, 0, 0, 0), Color.TRANSPARENT))
        top.addView(iconBtn("✕", 40, 18f) { finish() })
        engLabel = pillBtn("", null)
        engLabel.textSize = 13f
        engLabel.maxLines = 2
        engLabel.visibility = View.GONE
        engLabel.ellipsize = TextUtils.TruncateAt.END
        engLabel.maxWidth = (resources.displayMetrics.widthPixels * 0.5f).toInt()
        top.addView(engLabel, LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(8) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        qBtn = pillBtn("⚙ تلقائي") { chooseQuality() }
        top.addView(qBtn)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // الشريط السفلي: زر التشغيل + الوقت + شريط التقدم + المدة
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(12), dp(8), dp(12), dp(8))
        playBtn = iconBtn("▶", 40, 18f) { togglePlay() }
        setPlayIcon(false)
        tCur = timeText("00:00")
        tTot = timeText("00:00")
        seek = SeekBar(this)
        seek.max = 0
        styleSeek(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) tCur.text = fmt(p.toLong())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                seekTo((s?.progress ?: 0).toLong())
            }
        })
        bar.addView(playBtn)
        bar.addView(tCur, LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(10) })
        bar.addView(seek, LinearLayout.LayoutParams(0, dp(24), 1f).also { it.marginStart = dp(10) })
        bar.addView(tTot, LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(10) })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).also { it.bottomMargin = dp(12) })

        rootV = root
        topV = top
        barV = bar
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleUi()
                return true
            }
        })
        root.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }

        // المسافات حسب شاشة الهاتف (الشق وشريط الحالة وشريط التنقل) مثل max(14px, safe-area) في المعاينة
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            val t = ins.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bt = ins.systemWindowInsetBottom
            top.setPadding(dp(12), Math.max(dp(14), t), dp(12), dp(30))
            val blp = bar.layoutParams as FrameLayout.LayoutParams
            blp.bottomMargin = Math.max(dp(12), bt)
            bar.layoutParams = blp
            ins
        }

        setContentView(root)
        root.requestApplyInsets()
        ui.post(ticker)

        if (offRec != null) {
            val rec = offRec!!
            begin(PlayerCore.Probe("DASH", 1, true, rec.licenseUrl, ""))
            pool.execute {
                val t = OfflineDrm.remainingText(this, rec)
                ui.post { if (!destroyed) { engLabel.text = "محمي دون اتصال • الترخيص: " + t; engLabel.visibility = View.VISIBLE } }
            }
        } else if (live) {
            probe = PlayerCore.Probe("FILE", 2, false, null, "")
            waiting = true
            showStatus("جاري تجهيز الجزء المحمّل... سيستمر التشغيل مع استمرار التنزيل")
            liveStep()
        } else if (remote) {
            showStatus("جاري تحضير الفيديو...")
            val h = HashMap<String, String>(hdr)
            pool.execute {
                val p = try {
                    PlayerCore.probe(src, h, kindHint)
                } catch (e: Throwable) {
                    PlayerCore.Probe(PlayerCore.kindOf(src, kindHint), 2, false, null, "")
                }
                ui.post { if (!destroyed) begin(p) }
            }
        } else {
            begin(PlayerCore.Probe("FILE", 2, false, null, ""))
        }
    }

    // ---------- التشغيل أثناء التنزيل ----------

    // هل المشغّل ينتظر بيانات؟ (انتهى ما هو محمّل أو يحمّل مؤقتاً). التحديث لا يحدث إلا إذا وُجدت بيانات جديدة فعلاً
    private fun nearEnd(): Boolean {
        val e = exo
        if (e != null) return e.playbackState == Player.STATE_ENDED || e.playbackState == Player.STATE_BUFFERING
        return false
    }

    private fun liveStep() {
        val key = liveKey ?: return
        if (liveBusy) return
        if (engine != 0 && !waiting && !nearEnd()) return
        val hook = PlayerCore.liveHook
        if (hook == null) {
            fail("تعذر الاتصال بمدير التنزيلات")
            live = false
            return
        }
        liveBusy = true
        val sigIn = lastSig
        try {
            pool.execute {
                val r = try { hook(key, sigIn) } catch (e: Throwable) { null }
                ui.post {
                    liveBusy = false
                    if (!destroyed && r != null) applyLive(r)
                }
            }
        } catch (e: Throwable) {
            liveBusy = false
        }
    }

    private fun applyLive(r: PlayerCore.Live) {
        lastSig = r.sig
        val path = r.path
        if (path != null) {
            if (r.done && r.isUri) {
                src = path
                isUri = true
            } else {
                src = path
                isUri = false
                if (!liveFiles.contains(path)) liveFiles.add(path)
            }
            audioPath = if (r.done && r.isUri) null else r.audio
            audioPath?.let { if (!liveFiles.contains(it)) liveFiles.add(it) }
            remote = false
            resumeAt = if (engine == 0) 0L else lastPos
            waiting = false
            hideStatus()
            val ext = PlayerCore.extOf(src)
            if (engine == 0) startEngine(PlayerCore.choose(probe!!, ext))
            else startEngine(engine)
        } else if (engine == 0) {
            showStatus(if (r.done) "لم يُحمَّل جزء كافٍ للمشاهدة" else "بانتظار تنزيل جزء كافٍ للبدء...")
        }
        if (r.done) {
            live = false
            waiting = false
            if (path == null && engine == 0) fail("لم يُحمَّل جزء كافٍ للمشاهدة")
        }
    }

    // نهاية البيانات المتاحة أثناء التنزيل: ننتظر ثم نعيد التحميل من نفس الموضع
    private fun waitMore() {
        waiting = true
        showStatus("بانتظار تنزيل المزيد... سيكمل التشغيل تلقائياً")
    }

    private fun begin(p: PlayerCore.Probe) {
        probe = p
        if (p.prot == 1) {
            if (!p.widevine) {
                fail(p.note.ifBlank { "هذا الفيديو محمي بنظام حماية غير Widevine، وهو غير مدعوم في التطبيق" })
                return
            }
            if (p.licenseUrl == null) {
                fail("هذا الفيديو محمي بنظام Widevine، لكن المنصة لم تعلن رابط الترخيص داخل الملف. تشغيله يحتاج ترخيص المنصة الرسمي")
                return
            }
        }
        hideStatus()
        startEngine(PlayerCore.ENGINE_MEDIA3)
    }

    private fun uriOf(): Uri {
        val s = src.substringBefore("#tdlrep=")
        return if (remote || isUri) Uri.parse(s) else Uri.fromFile(File(s))
    }

    private fun showStatus(t: String) {
        status.text = t
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        status.visibility = View.GONE
    }

    private fun toast(s: String) {
        try { DlgStyle.toast(this, s, false) } catch (e: Throwable) {}
    }

    private fun fmt(ms: Long): String {
        val s = (if (ms < 0) 0L else ms) / 1000
        val m = s / 60
        val x = s % 60
        return if (m >= 60) String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, x)
        else String.format(Locale.US, "%02d:%02d", m, x)
    }

    // ---------- اختيار المحرك والتبديل ----------

    private fun startEngine(e: Int) {
        engine = e
        startExo()
    }

    // قائمة المسارات الفعلية التي يعلنها البث (الدقة ومعدل البت وعدد الإطارات) ليختار المستخدم أحدها، أو «تلقائي»
    private fun chooseQuality() {
        val e = exo
        if (e == null) {
            toast("قائمة الجودة غير متاحة الآن")
            return
        }
        class Opt(val g: androidx.media3.common.Tracks.Group, val idx: Int, val h: Int, val br: Int, val fps: Float)
        val opts = ArrayList<Opt>()
        for (g in e.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                opts.add(Opt(g, i, f.height, f.bitrate, f.frameRate))
            }
        }
        if (opts.isEmpty()) {
            toast("لم تتوفر قائمة الجودة بعد")
            return
        }
        opts.sortWith(compareByDescending<Opt> { it.h }.thenByDescending { it.br })
        val titles = ArrayList<String>()
        val subs = ArrayList<String>()
        val curH = e.videoSize.height
        titles.add("تلقائي")
        subs.add(if (curH > 0) "حالياً " + curH + "p" else "")
        for (o in opts) {
            titles.add(if (o.h > 0) o.h.toString() + "p" else "جودة")
            val parts = ArrayList<String>()
            if (o.br > 0) parts.add((o.br / 1000).toString() + " kbps")
            if (o.fps > 0f) parts.add(Math.round(o.fps).toString() + "fps")
            subs.add(parts.joinToString(" • "))
        }
        showQualityDialog(titles, subs) { i ->
            try {
                val b = e.trackSelectionParameters.buildUpon()
                if (i == 0) b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                else b.setOverrideForType(TrackSelectionOverride(opts[i - 1].g.mediaTrackGroup, opts[i - 1].idx))
                e.trackSelectionParameters = b.build()
                qSel = i
                if (i == 0) refreshQ() else qBtn.text = "⚙ " + titles[i]
            } catch (x: Throwable) {
                toast("تعذر تغيير الجودة")
            }
        }
    }

    private fun drmMsg(code: Int): String = when (code) {
        6001 -> "جهازك لا يدعم نظام الحماية Widevine المطلوب"
        6002 -> "فشل تجهيز نظام الحماية (Provisioning) على جهازك"
        6004 -> "تعذر الحصول على الترخيص: رفضه الخادم أو لم يمكن الوصول إليه"
        6007 -> "تم إلغاء ترخيص جهازك لدى نظام الحماية"
        6008 -> "انتهت صلاحية الترخيص"
        else -> "خطأ في نظام الحماية (كود $code)"
    }

    // يُستدعى عند فشل المحرك: الحماية تُشرح، وغيرها يظهر له خطأ واضح
    private fun onEngineError(which: Int, code: Int) {
        if (destroyed || which != engine) return
        if (live && !(which == PlayerCore.ENGINE_MEDIA3 && code in 6000..6999)) {
            // خطأ أثناء التنزيل غالباً سببه نفاد الجزء المحمّل: ننتظر المزيد بدل تغيير المحرك
            // إن كان التنزيل قد انتهى فسيُشغَّل الملف النهائي، وأي خطأ بعدها يُعالج كالمعتاد
            waitMore()
            return
        }
        if (offRec != null && which == PlayerCore.ENGINE_MEDIA3) {
            // ترخيص محفوظ منتهٍ أو مرفوض: محاولة تجديد واحدة من المنصة نفسها
            if (!renewTried && (code == 6003 || code == 6004 || code == 6005 || code == 6008)) {
                renewTried = true
                releaseExo()
                host.removeAllViews()
                showStatus("الترخيص المحفوظ منتهٍ أو مرفوض، جاري طلب تجديده من المنصة...")
                val id = offId!!
                pool.execute {
                    val ok = try {
                        offRec = OfflineDrm.renew(this, id)
                        null
                    } catch (e: Throwable) {
                        e.message ?: "تعذر التجديد"
                    }
                    ui.post {
                        if (!destroyed) {
                            if (ok == null) {
                                hideStatus()
                                startEngine(PlayerCore.ENGINE_MEDIA3)
                            } else {
                                fail(ok + "\nلا يمكن تشغيل هذا الفيديو دون ترخيص صالح من المنصة.")
                            }
                        }
                    }
                }
                return
            }
            fail(if (code in 6000..6999) drmMsg(code) else "تعذر تشغيل المحتوى المحفوظ (كود $code)")
            return
        }
        if (which == PlayerCore.ENGINE_MEDIA3 && code in 6000..6999) {
            fail(drmMsg(code))
            return
        }
        if (probe?.prot == 1) {
            fail("تعذر تشغيل المحتوى المحمي. قد لا يدعم جهازك Widevine أو لم يقبل الخادم الترخيص (كود $code)")
            return
        }
        fail("تعذر تشغيل الملف. قد تكون بياناته مشفّرة أو غير مكتملة أو بصيغة غير مدعومة.")
    }

    private fun fail(msg: String) {
        releaseExo()
        host.removeAllViews()
        setPlayIcon(false)
        showStatus(msg)
    }

    // ---------- Media3 ExoPlayer ----------

    private fun startExo() {
        try {
            releaseExo()
            host.removeAllViews()
            val sv = SurfaceView(this)
            surface = sv
            host.addView(sv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

            val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            val ua = hdr["User-Agent"]
            if (!ua.isNullOrBlank()) http.setUserAgent(ua)
            val rest = HashMap<String, String>()
            for ((k, v) in hdr) if (k != "User-Agent" && v.isNotBlank()) rest[k] = v
            http.setDefaultRequestProperties(rest)
            val off = offRec
            val ds: androidx.media3.datasource.DataSource.Factory =
                if (off != null) OfflineDrm.playbackFactory(this) else DefaultDataSource.Factory(this, http)
            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
                .build()
            exo = player

            val p = probe
            val mb = MediaItem.Builder().setUri(uriOf())
            if (p != null && p.kind == "HLS") mb.setMimeType(MimeTypes.APPLICATION_M3U8)
            if (p != null && p.kind == "DASH") mb.setMimeType(MimeTypes.APPLICATION_MPD)
            if (p != null && p.prot == 1 && p.licenseUrl != null) {
                val lh = HashMap<String, String>()
                for (k in listOf("Referer", "Origin")) hdr[k]?.let { if (it.isNotBlank()) lh[k] = it }
                val db = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(p.licenseUrl)
                    .setLicenseRequestHeaders(lh)
                    .setMultiSession(true)
                if (off != null) db.setKeySetId(android.util.Base64.decode(off.keySetId, android.util.Base64.NO_WRAP))
                mb.setDrmConfiguration(db.build())
            }

            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onEngineError(PlayerCore.ENGINE_MEDIA3, error.errorCode)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    vsW = videoSize.width
                    vsH = videoSize.height
                    vsPar = videoSize.pixelWidthHeightRatio
                    fitSurface()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        hideStatus()
                        waiting = false
                    }
                    if (state == Player.STATE_ENDED && live && engine == PlayerCore.ENGINE_MEDIA3) waitMore()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    setPlayIcon(isPlaying)
                }
            })
            player.setVideoSurfaceView(sv)
            val ap = audioPath
            if (ap != null && !remote) {
                // صورة وصوت من ملفين متزايدين: يُدمجان أثناء التشغيل بلا نسخ
                val pf = ProgressiveMediaSource.Factory(ds)
                val vs = pf.createMediaSource(mb.build())
                val au = pf.createMediaSource(MediaItem.fromUri(Uri.fromFile(File(ap))))
                val ms = MergingMediaSource(vs, au)
                if (resumeAt > 0) player.setMediaSource(ms, resumeAt) else player.setMediaSource(ms)
            } else if (resumeAt > 0) player.setMediaItem(mb.build(), resumeAt) else player.setMediaItem(mb.build())
            resumeAt = 0
            player.prepare()
            player.playWhenReady = true
        } catch (e: Throwable) {
            onEngineError(PlayerCore.ENGINE_MEDIA3, 0)
        }
    }

    // يعرض الفيديو بنسبته الأصلية داخل الشاشة (SurfaceView لا يحافظ على النسبة وحده)
    private fun fitSurface() {
        val sv = surface ?: return
        val hw = host.width
        val hh = host.height
        if (vsW <= 0 || vsH <= 0 || hw <= 0 || hh <= 0) return
        val ar = vsW * (if (vsPar > 0f) vsPar else 1f) / vsH
        var tw = hw
        var th = (hw / ar).toInt()
        if (th > hh) {
            th = hh
            tw = (hh * ar).toInt()
        }
        sv.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.CENTER)
    }

    private fun releaseExo() {
        try { exo?.release() } catch (e: Throwable) {}
        exo = null
        surface = null
        vsW = 0
        vsH = 0
    }

    // ---------- التحكم المشترك ----------

    private fun togglePlay() {
        val e = exo
        if (e != null) {
            if (e.isPlaying) e.pause()
            else {
                if (e.playbackState == Player.STATE_ENDED) e.seekTo(0)
                e.play()
            }
        }
    }

    private fun seekTo(ms: Long) {
        try {
            exo?.seekTo(ms)
        } catch (e: Throwable) {
        }
    }

    private fun tick() {
        var pos = 0L
        var dur = 0L
        try {
            val e = exo
            if (e != null) {
                pos = e.currentPosition
                dur = e.duration
            }
        } catch (e: Throwable) {
        }
        if (dur < 0) dur = 0
        if (pos < 0) pos = 0
        if (pos > 0 && !waiting) lastPos = pos
        if (live) liveStep()
        seek.max = dur.toInt()
        if (!dragging) {
            seek.progress = pos.toInt()
            tCur.text = fmt(pos)
        }
        tTot.text = fmt(dur)
        refreshQ()
    }

    override fun onPause() {
        super.onPause()
        try { exo?.pause() } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        destroyed = true
        ui.removeCallbacksAndMessages(null)
        releaseExo()
        pool.shutdownNow()
        for (f in liveFiles) try { File(f).delete() } catch (e: Throwable) {}
        super.onDestroy()
    }
}
