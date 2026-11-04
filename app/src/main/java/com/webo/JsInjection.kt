package com.webo



// حقن سكربتات الالتقاط في الصفحات
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

// يُحقن عند بداية كل مستند وفي كل الإطارات: يلتقط الفيديو من fetch/XHR/عناصر الوسائط، ويتعرف على m3u8/mpd من محتواها ولو كان نوعها عاماً
internal fun MainActivity.hookJs(): String = HOOK_JS.replace("__READCAP__", prefs().getInt("readcap", 0).toString())
    .replace("__STREAM__", if (prefs().getBoolean("scanstream", true)) "1" else "0")
    .replace("__AUDIO__", if (capAudio()) "1" else "0")

internal fun MainActivity.w2rescan() {
    try { cur?.web?.evaluateJavascript(RESCAN_JS, null) } catch (e: Throwable) { Diag.swallow("JsInjection.kt:14 w2rescan", e);}
}

// يمرر الصفحة تدريجياً حتى نهاية القائمة، ويستخرج كل روابط الفيديو من العناصر والسكربتات وطلبات الشبكة دفعة واحدة
internal fun MainActivity.capturePage() {
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

internal fun MainActivity.overlayJs(): String = OVERLAY_JS.replace("__MAXN__", prefs().getInt("maxoverlay", 0).toString())
