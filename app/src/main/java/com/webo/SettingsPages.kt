package com.webo

import android.content.ContentValues
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

// صفحات الإعدادات الفرعية
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.applyWebSettings(w: WebView) {
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

internal fun MainActivity.buildSettings(): View {
    settingsHost = FrameLayout(this)
    settingsHost.setBackgroundColor(cBg)
    openSettingsSub("")
    return settingsHost
}

internal fun MainActivity.openSettingsSub(id: String) {
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
            "net" to "التحميل والشبكة", "perf" to "الأداء والتخزين", "privacy" to "الخصوصية والبيانات", "diag" to "السجل والفحص الذاتي", "about" to "حول التطبيق"
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
            "diag" -> buildSetDiag(col)
            "about" -> buildSetAbout(col)
        }
    }
    val sv = ScrollView(this)
    sv.setBackgroundColor(cBg)
    sv.addView(col)
    settingsHost.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
}

internal fun MainActivity.buildSettingsHub(col: LinearLayout) {
    section(col, "التطبيق")
    hubRow(col, R.drawable.ic_resize, "المظهر والواجهة", "الوضع الداكن وأحجام الأزرار والأيقونات", "look")
    hubRow(col, R.drawable.ic_web, "التصفح والبحث", "الشاشة الرئيسية ومحرك البحث وحظر الإعلانات", "browse")
    section(col, "الفيديو والتحميل")
    hubRow(col, R.drawable.ic_batch, "التقاط الفيديو", "أزرار الفيديو وخطوات الالتقاط وما يظهر في القائمة", "capture")
    hubRow(col, R.drawable.ic_download, "التحميل والشبكة", "السرعة والاتصالات والمهل والرؤوس والجودة", "net")
    section(col, "النظام")
    hubRow(col, R.drawable.ic_speed, "الأداء والتخزين", "توفير البيانات وحفظ الصفحات والذاكرة", "perf")
    hubRow(col, R.drawable.ic_cookie, "الخصوصية والبيانات", "مسح السجل والكاش والكوكيز", "privacy")
    hubRow(col, R.drawable.ic_code, "السجل والفحص الذاتي", "سجل الأحداث والأخطاء الكامل وفحص وحدات التطبيق ومشاركة التقرير", "diag")
    hubRow(col, R.drawable.ic_alert, "حول التطبيق", "الإصدار والوصف", "about")
}

internal fun MainActivity.buildSetLook(col: LinearLayout) {
    section(col, "المظهر")
    val c = settingsCard(col)
    switchRow(c, "الوضع الداكن", "يتبدل المظهر فوراً مع بقاء التبويبات وهذه الصفحة كما هي", "dark", true) { recreate() }
    section(col, "الأحجام")
    val c2 = settingsCard(col)
    actionRow(c2, "أحجام الأزرار والأيقونات", "تحكم حر بحجم كل زر وكل أيقونة وعدد أعمدة الأدوات", "فتح") { showSizes() }
}

internal fun MainActivity.buildSetBrowse(col: LinearLayout) {
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

internal fun MainActivity.buildSetCapture(col: LinearLayout) {
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

internal fun MainActivity.buildSetNet(col: LinearLayout) {
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

internal fun MainActivity.buildSetPerf(col: LinearLayout) {
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
    choiceRow(cp, "حجم المخزن الأقصى", "الأصل: بلا حد. إن حددت حجماً فعند الامتلاء تُحذف الأقدم استخداماً أولاً", "rcmb", arrayOf("بلا حد", "500 م.ب", "1000 م.ب", "3000 م.ب"), intArrayOf(0, 500, 1000, 3000), 0) { diagThread("SettingsPages:295") { rcTrim() }.start() }
    actionRow(cp, "مسح مخزن الصور", "يحذف الصور المحفوظة فقط", "مسح") { rcClear() }

    section(col, "السرعة والذاكرة")
    val c3 = settingsCard(col)
    switchRow(c3, "إيقاف التبويبات الخلفية", "يوقف نشاط التبويبات المخفية لتسريع التبويب الحالي وتوفير الذاكرة والبطارية", "pausebg", true) {
        cur?.let { switchTo(it) }
    }
    actionRow(c3, "تقرير الأداء", "التبويبات والذاكرة والتحميلات", "عرض") { perfReport() }
    actionRow(c3, "تفريغ الذاكرة", "يمسح ذاكرة التبويبات الخلفية", "تفريغ") { freeMem() }
}

internal fun MainActivity.buildSetPrivacy(col: LinearLayout) {
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

internal fun MainActivity.buildSetAbout(col: LinearLayout) {
    val c4 = settingsCard(col)
    c4.gravity = Gravity.CENTER_HORIZONTAL
    val logo = ImageView(this)
    logo.setImageResource(R.drawable.logo)
    c4.addView(logo, lp(dp(72), dp(72), 0f, 0, 8, 0, 8))
    val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { Diag.swallow("SettingsPages.kt:334 buildSetAbout", e); "" }
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
