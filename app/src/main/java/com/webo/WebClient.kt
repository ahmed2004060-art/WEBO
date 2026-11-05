package com.webo

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.webo.MainActivity.Bridge
import com.webo.MainActivity.Tab
import java.io.ByteArrayInputStream

// إعداد WebView وWebViewClient/ChromeClient والصلاحيات وملء الشاشة
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.makeWeb(): WebView {
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
    } catch (e: Throwable) { Diag.swallow("WebClient.kt:41 makeWeb", e);
        logErr("التقاط", "تعذر تفعيل الحقن المبكر: " + (e.message ?: e.javaClass.simpleName), e)
    }
    w.setOnLongClickListener { v -> onWebLongPress(v as WebView) }
    w.setFindListener { active, count, _ ->
        if (cur?.web === w) findCount.text = if (count > 0) "" + (active + 1) + "/" + count else "0"
    }
    w.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
            if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) DiagWatch.consoleError(m.message(), m.sourceId(), m.lineNumber())
            return false
        }

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
            val h = try { Uri.parse(v.url ?: "").host } catch (e: Throwable) { Diag.swallow("WebClient.kt:68 onReceivedIcon", e); null }
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
            } catch (e: Throwable) { Diag.swallow("WebClient.kt:91 onShowFileChooser", e);
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
            val tmp = WebView(this@makeWeb)
            tmp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(x: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url.toString()
                    ui.post { newTab(u, true) }
                    try { x.destroy() } catch (e: Throwable) { Diag.swallow("WebClient.kt:130 shouldOverrideUrlLoading", e);}
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
            } catch (e: Throwable) { Diag.swallow("WebClient.kt:146 shouldInterceptRequest", e);
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
                ui.post { val t0 = hasTab(v); if (t0 != null) imagesChanged(t0) }
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
            } catch (e: Throwable) { Diag.swallow("WebClient.kt:198 shouldOverrideUrlLoading", e);
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
                        try { t.web.stopLoading() } catch (e: Throwable) { Diag.swallow("WebClient.kt:223 onPageStarted", e);}
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
                pruneTab(t)
                if (cur === t) captureImagesAuto(t)
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
            if (cur === t) captureImagesAuto(t)
            runScripts(v, url)
        }

        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            Diag.i("WebView-شبكة", "خطأ تحميل " + (if (r.isForMainFrame) "(الإطار الرئيسي) " else "(مورد فرعي) ") + r.method + " " + r.url + " | كود " + e.errorCode + " | " + e.description)
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

        override fun onReceivedSslError(v: WebView, h: android.webkit.SslErrorHandler, err: android.net.http.SslError) {
            Diag.i("WebView-SSL", "خطأ شهادة SSL: " + err.url + " | " + err.toString())
            h.cancel()
        }

        override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, resp: WebResourceResponse) {
            if (resp.statusCode >= 400) Diag.i("WebView-HTTP", "HTTP " + resp.statusCode + " " + (if (r.isForMainFrame) "(الإطار الرئيسي) " else "(مورد فرعي) ") + r.method + " " + r.url)
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
internal fun MainActivity.recoverTab(t: Tab, dead: WebView, url: String) {
    if (t.web !== dead || !tabs.contains(t)) return
    val wasCur = cur === t
    val idx = container.indexOfChild(dead)
    pages.remove(dead)
    try { container.removeView(dead) } catch (e: Throwable) { Diag.swallow("WebClient.kt:307 recoverTab", e);}
    try { dead.destroy() } catch (e: Throwable) { Diag.swallow("WebClient.kt:308 recoverTab", e);}
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


internal fun MainActivity.askWebPermission(r: PermissionRequest) {
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
        try { r.grant(r.resources) } catch (e: Throwable) { Diag.swallow("WebClient.kt:345 askWebPermission", e);}
        return
    }
    StyledBuilder(this).setTitle("إذن الموقع")
        .setMessage((r.origin.host ?: "الموقع") + " يطلب استخدام " + names.joinToString(" و "))
        .setPositiveButton("سماح") { _, _ ->
            val miss = need.filter { Build.VERSION.SDK_INT >= 23 && checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (miss.isEmpty()) {
                try { r.grant(r.resources) } catch (e: Throwable) { Diag.swallow("WebClient.kt:353 askWebPermission", e);}
            } else {
                permReq = r
                requestPermissions(miss.toTypedArray(), 80)
            }
        }
        .setNegativeButton("منع") { _, _ -> try { r.deny() } catch (e: Throwable) { Diag.swallow("WebClient.kt:359 askWebPermission", e);} }
        .setOnCancelListener { try { r.deny() } catch (e: Throwable) { Diag.swallow("WebClient.kt:360 askWebPermission", e);} }
        .show()
}

internal fun MainActivity.showFull(v: View, cb: WebChromeClient.CustomViewCallback) {
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

internal fun MainActivity.hideFull() {
    val v = fullView ?: return
    fullView = null
    try { (window.decorView as ViewGroup).removeView(v) } catch (e: Throwable) { Diag.swallow("WebClient.kt:381 hideFull", e);}
    window.decorView.systemUiVisibility = fullFlags
    try { fullCb?.onCustomViewHidden() } catch (e: Throwable) { Diag.swallow("WebClient.kt:383 hideFull", e);}
    fullCb = null
}

// أي ملف يطلب الموقع تنزيله: يدخل محرك التحميل السريع ويبدأ فوراً
internal fun MainActivity.onWebDownload(url: String, ua: String?, cd: String?, mime: String?, len: Long) {
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

internal fun MainActivity.fetchBlob(url: String, name: String, mime: String) {
    val w = cur?.web ?: return
    val js = "(function(){var u=" + org.json.JSONObject.quote(url) + ";fetch(u).then(function(r){return r.blob();}).then(function(b){" +
        "var id=String(Date.now());var size=b.size;var ch=393216;var i=0;" +
        "function nx(){if(i>=size){TDL.blobEnd(id," + org.json.JSONObject.quote(name) + "," + org.json.JSONObject.quote(mime) + ");return;}" +
        "var fr=new FileReader();fr.onload=function(){var d=fr.result;TDL.blobPart(id,d.substring(d.indexOf(',')+1));i+=ch;nx();};" +
        "fr.onerror=function(){TDL.err('blob read');};fr.readAsDataURL(b.slice(i,i+ch));}" +
        "if(size==0){TDL.err('blob empty');return;}nx();}).catch(function(e){try{TDL.dg('WebClient.kt:417 fetchBlob '+(e&&e.stack||e))}catch(_q){}TDL.err('blob: '+e);});})();"
    w.evaluateJavascript(js, null)
    toast("جاري حفظ الملف...")
}
