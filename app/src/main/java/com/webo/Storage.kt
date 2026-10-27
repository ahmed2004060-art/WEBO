package com.webo

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.InputStream

// أدوات مشتركة للملفات
object FileUtil {
    fun mimeOf(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "application/octet-stream"
    }

    fun fmtSize(b: Long): String = when {
        b >= 1073741824L -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1048576L -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> b.toString() + " B"
    }

    fun fmtDate(t: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(t))

    // يحفظ نسخة في مجلد التحميلات ويرجع رابط الملف الجديد (أو مساره)
    fun saveToDownloads(ctx: Context, input: InputStream, name0: String, mime: String): String {
        val name = name0.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "ملف" }
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues()
            v.put(MediaStore.Downloads.DISPLAY_NAME, name)
            v.put(MediaStore.Downloads.MIME_TYPE, mime)
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            v.put(MediaStore.Downloads.IS_PENDING, 1)
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw Exception("تعذر إنشاء الملف في مجلد التحميلات")
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> input.use { it.copyTo(o, 256 * 1024) } }
            val done = ContentValues()
            done.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, done, null, null)
            return uri.toString()
        }
        val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        d.mkdirs()
        var out = File(d, name)
        var k = 1
        while (out.exists()) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            out = File(d, base + " (" + k + ")" + (if (ext.isEmpty() || ext == name) "" else ".$ext"))
            k++
        }
        out.outputStream().use { o -> input.use { it.copyTo(o, 256 * 1024) } }
        return out.absolutePath
    }

    fun nameOf(ctx: Context, u: Uri): String {
        if (u.scheme == "content") {
            try {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val n = it.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            } catch (e: Throwable) {
            }
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: "ملف"
    }

    fun open(ctx: Context, u: Uri, mime: String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(u, mime)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "فتح بواسطة"))
    }
}

private class Pal(ctx: Context) {
    val dark = ctx.getSharedPreferences("tdl", Context.MODE_PRIVATE).getBoolean("dark", true)
    val bg = Color.parseColor(if (dark) "#0F1318" else "#F3F5F8")
    val bar = Color.parseColor(if (dark) "#171C23" else "#FFFFFF")
    val card = Color.parseColor(if (dark) "#1E252E" else "#FFFFFF")
    val text = Color.parseColor(if (dark) "#ECEFF4" else "#1B1F24")
    val muted = Color.parseColor(if (dark) "#93A0B1" else "#5B6470")
    val line = Color.parseColor(if (dark) "#2A323D" else "#D8DCE2")
    val accent = Color.parseColor(if (dark) "#5B9BFF" else "#0B6BCB")
}

private fun mkBg(color: Int, r: Int, stroke: Int, density: Float): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = r * density
    if (stroke != 0) g.setStroke((1 * density + 0.5f).toInt(), stroke)
    return g
}

// ---------------- عارض الملفات: html وما يشبهه والنصوص والصور والفيديو ----------------

class ViewerActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var info: TextView
    private lateinit var p: Pal
    private var src: Uri? = null
    private var name = ""
    private val ui = Handler(Looper.getMainLooper())

    private val pageExt = setOf("html", "htm", "xhtml", "svg", "mhtml")
    private val textExt = setOf("txt", "log", "tsv", "csv", "json", "md", "ini", "xml", "css", "js", "kt", "java", "py", "yml", "yaml", "srt", "vtt", "m3u8", "mpd")
    private val mediaExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "avif", "mp4", "webm", "m4v", "mov", "3gp", "mp3", "m4a", "ogg", "wav", "aac", "opus")

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun pill(t: String, onClick: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.setTextColor(p.accent)
        v.setTypeface(null, Typeface.BOLD)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(12), dp(7), dp(12), dp(7))
        v.background = mkBg(Color.TRANSPARENT, 18, p.accent, resources.displayMetrics.density)
        v.setOnClickListener { onClick() }
        return v
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = Pal(this)
        window.statusBarColor = p.bar
        window.navigationBarColor = p.bar
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(p.bar)
        bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        val title = TextView(this)
        title.setTextColor(p.text)
        title.textSize = 14f
        title.setTypeface(null, Typeface.BOLD)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        title.layoutDirection = View.LAYOUT_DIRECTION_LTR
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(dp(4), 0, dp(4), 0) } }
        bar.addView(pill("حفظ نسخة") { saveCopy() }, lp())
        bar.addView(pill("فتح بتطبيق آخر") { openOther() }, lp())
        bar.addView(pill("إغلاق") { finish() }, lp())
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        info = TextView(this)
        info.setTextColor(p.muted)
        info.textSize = 14f
        info.gravity = Gravity.CENTER
        info.setPadding(dp(20), dp(40), dp(20), dp(20))
        info.visibility = View.GONE
        root.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        web = WebView(this)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.allowFileAccessFromFileURLs = true
        s.allowUniversalAccessFromFileURLs = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        web.setBackgroundColor(if (p.dark) Color.parseColor("#FFFFFF") else Color.WHITE)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val u = intent.data
        if (u == null) {
            finish()
            return
        }
        src = u
        name = intent.getStringExtra("name") ?: FileUtil.nameOf(this, u)
        title.text = name
        show(u)
    }

    private fun read(u: Uri, max: Long): String {
        val st: InputStream = if (u.scheme == "file") File(u.path ?: "").inputStream() else contentResolver.openInputStream(u)!!
        return st.use { ins ->
            val bo = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var tot = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                bo.write(buf, 0, n)
                tot += n
                if (tot > max) break
            }
            bo.toString("UTF-8")
        }
    }

    private fun show(u: Uri) {
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            ext in pageExt -> {
                if (u.scheme == "file") web.loadUrl(u.toString())
                else Thread {
                    val html = try { read(u, 30L * 1024 * 1024) } catch (e: Throwable) { "" }
                    ui.post {
                        if (html.isEmpty()) fail("تعذر قراءة الملف") else web.loadDataWithBaseURL(u.toString(), html, "text/html", "utf-8", null)
                    }
                }.start()
            }
            ext in textExt -> Thread {
                val t = try { read(u, 4L * 1024 * 1024) } catch (e: Throwable) { "" }
                val esc = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                    "<body style='margin:0;padding:12px'><pre style='white-space:pre-wrap;word-break:break-all;font-size:13px'>" + esc + "</pre></body></html>"
                ui.post { web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
            }.start()
            ext in mediaExt -> web.loadUrl(u.toString())
            else -> fail("هذا النوع من الملفات لا يُعرض داخل التطبيق.\nيمكنك فتحه بتطبيق آخر أو حفظ نسخة منه في التحميلات.")
        }
    }

    private fun fail(msg: String) {
        web.visibility = View.GONE
        info.visibility = View.VISIBLE
        info.text = msg
    }

    private fun saveCopy() {
        val u = src ?: return
        Thread {
            val r = try {
                val st: InputStream = if (u.scheme == "file") File(u.path ?: "").inputStream() else contentResolver.openInputStream(u)!!
                FileUtil.saveToDownloads(this, st, name, FileUtil.mimeOf(name))
                "تم حفظ نسخة في مجلد التحميلات"
            } catch (e: Throwable) {
                "تعذر الحفظ: " + (e.message ?: "")
            }
            ui.post { DlgStyle.toast(this, r, true) }
        }.start()
    }

    private fun openOther() {
        val u = src ?: return
        if (u.scheme != "file") {
            try { FileUtil.open(this, u, FileUtil.mimeOf(name)) } catch (e: Throwable) { DlgStyle.toast(this, "لا يوجد تطبيق يفتح هذا الملف", false) }
            return
        }
        // ملفات التطبيق الداخلية: نحفظ نسخة في التحميلات ثم نفتحها
        Thread {
            try {
                val r = FileUtil.saveToDownloads(this, File(u.path ?: "").inputStream(), name, FileUtil.mimeOf(name))
                ui.post {
                    try {
                        val o = if (r.startsWith("content:")) Uri.parse(r) else Uri.fromFile(File(r))
                        FileUtil.open(this, o, FileUtil.mimeOf(name))
                    } catch (e: Throwable) {
                        DlgStyle.toast(this, "لا يوجد تطبيق يفتح هذا الملف", false)
                    }
                }
            } catch (e: Throwable) {
                ui.post { DlgStyle.toast(this, "تعذر الفتح: " + (e.message ?: ""), true) }
            }
        }.start()
    }

    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        try { web.destroy() } catch (e: Throwable) {}
        super.onDestroy()
    }
}

// ---------------- مخزن التطبيق: كل ما حُفظ وبيانات التطبيق ----------------

class StorageActivity : Activity() {
    private class Item(val name: String, val size: Long, val time: Long, val file: File?, val uri: Uri?)

    private lateinit var p: Pal
    private lateinit var list: LinearLayout
    private lateinit var head: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var mode = 0 // 0 الرئيسية، 1 التحميلات المحفوظة، 2 مجلد
    private var dir: File? = null
    private var rootDir: File? = null
    private var density = 1f

    private fun dp(n: Int): Int = (n * density + 0.5f).toInt()

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    private fun pill(t: String, filled: Boolean, onClick: () -> Unit): TextView {
        val v = tv(t, 13f, if (filled) Color.WHITE else p.accent, true)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(12), dp(7), dp(12), dp(7))
        v.background = if (filled) mkBg(p.accent, 18, 0, density) else mkBg(Color.TRANSPARENT, 18, p.accent, density)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun lp(w: Int, h: Int, wt: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        val x = LinearLayout.LayoutParams(w, h, wt)
        x.setMargins(dp(l), dp(t), dp(r), dp(b))
        return x
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        density = resources.displayMetrics.density
        p = Pal(this)
        window.statusBarColor = p.bar
        window.navigationBarColor = p.bar
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(p.bar)
        bar.setPadding(dp(10), dp(8), dp(10), dp(8))
        head = tv("مخزن التطبيق", 17f, p.text, true)
        head.maxLines = 1
        head.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        bar.addView(head, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill("تحديث", false) { refresh() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 4, 0, 4, 0))
        bar.addView(pill("رجوع", true) { onBackPressed() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(12), dp(12), dp(12), dp(24))
        val sv = ScrollView(this)
        sv.addView(list)
        root.addView(sv, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showRoots()
    }

    private fun refresh() {
        when (mode) {
            0 -> showRoots()
            1 -> showDownloads()
            else -> dir?.let { showDir(it) }
        }
    }

    override fun onBackPressed() {
        if (mode == 2) {
            val d = dir
            val r = rootDir
            if (d != null && r != null && d.absolutePath != r.absolutePath && d.parentFile != null) showDir(d.parentFile!!) else showRoots()
        } else if (mode == 1) showRoots() else super.onBackPressed()
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = mkBg(p.card, 14, p.line, density)
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        list.addView(c, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 0, 10))
        return c
    }

    private fun dirInfo(f: File): Pair<Int, Long> {
        var n = 0
        var s = 0L
        val st = ArrayList<File>()
        st.add(f)
        while (st.isNotEmpty() && n < 200000) {
            val d = st.removeAt(st.size - 1)
            val fs = d.listFiles() ?: continue
            for (x in fs) {
                if (x.isDirectory) st.add(x) else {
                    n++
                    s += x.length()
                }
            }
        }
        return Pair(n, s)
    }

    private fun showRoots() {
        mode = 0
        dir = null
        head.text = "مخزن التطبيق"
        list.removeAllViews()
        val note = tv("كل ما حفظه التطبيق على جهازك. اختر قسماً لاستعراضه وفتحه وتحميله.", 12f, p.muted)
        list.addView(note, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 0, 10))

        val dl = card()
        dl.addView(tv("التحميلات المحفوظة", 15f, p.text, true))
        dl.addView(tv("الفيديوهات والملفات والصور في مجلد التحميلات", 12f, p.muted))
        dl.addView(pill("استعراض", true) { showDownloads() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0))

        val roots = ArrayList<Triple<String, String, File?>>()
        roots.add(Triple("تحميلات غير مكتملة", "أجزاء تحميلات متوقفة يمكن إكمالها من صفحة التحميلات", getExternalFilesDir(null)))
        roots.add(Triple("بيانات التطبيق", "السجل والتنبيهات والصفحات المغلقة وإعدادات داخلية", filesDir))
        roots.add(Triple("ذاكرة الصور المؤقتة", "صور وخطوط الصفحات المخزنة لتسريع التصفح", File(cacheDir, "rc")))
        for (r in roots) {
            val f = r.third ?: continue
            val c = card()
            c.addView(tv(r.first, 15f, p.text, true))
            c.addView(tv(r.second, 12f, p.muted))
            val sz = tv("جاري الحساب...", 12f, p.accent)
            c.addView(sz, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 4, 0, 0))
            c.addView(pill("استعراض", true) { rootDir = f; showDir(f) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0))
            Thread {
                val i = dirInfo(f)
                ui.post { sz.text = i.first.toString() + " ملف  •  " + FileUtil.fmtSize(i.second) }
            }.start()
        }
    }

    private fun showDownloads() {
        mode = 1
        head.text = "التحميلات المحفوظة"
        list.removeAllViews()
        list.addView(tv("جاري القراءة...", 13f, p.muted))
        Thread {
            val items = ArrayList<Item>()
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val c = contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED),
                        null, null, MediaStore.Downloads.DATE_ADDED + " DESC"
                    )
                    c?.use {
                        while (it.moveToNext()) {
                            items.add(Item(it.getString(1) ?: "", it.getLong(2), it.getLong(3) * 1000L, null,
                                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0))))
                        }
                    }
                } else {
                    val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    d.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach {
                        items.add(Item(it.name, it.length(), it.lastModified(), it, null))
                    }
                }
            } catch (e: Throwable) {
            }
            ui.post {
                if (mode != 1) return@post
                list.removeAllViews()
                if (items.isEmpty()) {
                    list.addView(tv("لا توجد ملفات محفوظة بعد", 14f, p.muted))
                    return@post
                }
                for (x in items.take(600)) addItem(x, true)
                if (items.size > 600) list.addView(tv("يُعرض أحدث 600 ملف فقط", 12f, p.muted))
            }
        }.start()
    }

    private fun showDir(d: File) {
        mode = 2
        dir = d
        val r = rootDir
        head.text = if (r != null && d.absolutePath.startsWith(r.absolutePath)) d.absolutePath.removePrefix(r.parentFile?.absolutePath ?: "") else d.name
        list.removeAllViews()
        val fs = d.listFiles()
        if (fs == null || fs.isEmpty()) {
            list.addView(tv("المجلد فارغ", 14f, p.muted))
            return
        }
        val sorted = fs.sortedWith(Comparator { a, b ->
            if (a.isDirectory != b.isDirectory) (if (a.isDirectory) -1 else 1) else b.lastModified().compareTo(a.lastModified())
        })
        for (f in sorted.take(500)) {
            if (f.isDirectory) {
                val c = card()
                c.addView(tv("📁 " + f.name, 14f, p.text, true))
                c.setOnClickListener { showDir(f) }
            } else addItem(Item(f.name, f.length(), f.lastModified(), f, null), false)
        }
        if (sorted.size > 500) list.addView(tv("يُعرض أول 500 عنصر فقط", 12f, p.muted))
    }

    private fun addItem(item: Item, downloads: Boolean) {
        val c = card()
        val n = tv(item.name, 14f, p.text, true)
        n.layoutDirection = View.LAYOUT_DIRECTION_LTR
        n.gravity = Gravity.START
        c.addView(n)
        c.addView(tv(FileUtil.fmtSize(item.size) + "  •  " + FileUtil.fmtDate(item.time), 12f, p.muted))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(pill("فتح", true) { openItem(item) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 6, 0))
        if (item.file != null && !downloads) {
            row.addView(pill("تحميل على الجهاز", false) { exportItem(item) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 6, 0))
        }
        if (item.uri != null) {
            row.addView(pill("مشاركة", false) { shareItem(item) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 6, 0))
        }
        row.addView(pill("حذف", false) { deleteItem(item) })
        c.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0))
        c.setOnClickListener { openItem(item) }
    }

    private fun openItem(item: Item) {
        val u = item.uri ?: Uri.fromFile(item.file)
        val i = Intent(this, ViewerActivity::class.java)
        i.data = u
        i.putExtra("name", item.name)
        startActivity(i)
    }

    private fun exportItem(item: Item) {
        val f = item.file ?: return
        Thread {
            val r = try {
                FileUtil.saveToDownloads(this, f.inputStream(), item.name, FileUtil.mimeOf(item.name))
                "تم حفظ نسخة من «" + item.name + "» في مجلد التحميلات"
            } catch (e: Throwable) {
                "تعذر الحفظ: " + (e.message ?: "")
            }
            ui.post { DlgStyle.toast(this, r, true) }
        }.start()
    }

    private fun shareItem(item: Item) {
        val u = item.uri ?: return
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = FileUtil.mimeOf(item.name)
            i.putExtra(Intent.EXTRA_STREAM, u)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "مشاركة"))
        } catch (e: Throwable) {
            DlgStyle.toast(this, "تعذرت المشاركة", false)
        }
    }

    private fun deleteItem(item: Item) {
        StyledBuilder(this).setTitle("حذف الملف").setMessage(item.name)
            .setPositiveButton("حذف") { _, _ ->
                try {
                    if (item.uri != null) contentResolver.delete(item.uri, null, null) else item.file?.delete()
                } catch (e: Throwable) {
                    DlgStyle.toast(this, "تعذر الحذف", false)
                }
                refresh()
            }.setNegativeButton("إلغاء", null).show()
    }
}
