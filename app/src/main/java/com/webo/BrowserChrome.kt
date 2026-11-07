package com.webo

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.webo.MainActivity.Tab

// الشريط العلوي والقائمة الجانبية وتبديل الصفحات
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

// تحديث حقيقي من الإنترنت (يتجاهل النسخة المخزنة مرة واحدة ثم يعود للوضع المعتاد بعد انتهاء التحميل)
internal fun MainActivity.hardReload(t: Tab) {
    t.restoring = true
    t.web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    t.web.reload()
}


internal fun MainActivity.navPopupLp(): FrameLayout.LayoutParams {
    val p = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.LEFT)
    p.topMargin = dp(52)
    p.leftMargin = dp(8)
    return p
}

internal fun MainActivity.openNavMenu() {
    hideKb()
    navOverlay.visibility = View.VISIBLE
}

internal fun MainActivity.closeNavMenu() {
    navOverlay.visibility = View.GONE
}

// تحديث الصفحة، أو إيقاف تحميلها إن كانت تُحمَّل الآن
internal fun MainActivity.reloadOrStop() {
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
internal fun MainActivity.buildNav(): View {
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

internal fun MainActivity.showPage(i: Int) {
    page = i
    hideKb()
    for (k in pagesList.indices) pagesList[k].visibility = if (k == i) View.VISIBLE else View.GONE
    for (k in navItems.indices) {
        val c = if (k == i) cAccent else cMuted
        navItems[k].first.setColorFilter(c)
        navItems[k].second.setTextColor(c)
    }
    if (i == 1) restoreSavedNow()
    updateCounts()
}

internal fun MainActivity.buildBrowser(): View {
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
    val urlHidden = ArrayList<View>()
    val urlRestore = Runnable {
        android.transition.TransitionManager.beginDelayedTransition(bar)
        for (v in urlHidden) v.visibility = View.VISIBLE
        urlHidden.clear()
        val ulp = urlBox.layoutParams as LinearLayout.LayoutParams
        ulp.leftMargin = dp(4)
        ulp.rightMargin = dp(4)
        urlBox.layoutParams = ulp
    }
    urlBox.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (!urlBox.hasFocus()) return
            urlBox.removeCallbacks(urlRestore)
            if (urlHidden.isEmpty()) {
                android.transition.TransitionManager.beginDelayedTransition(bar)
                for (i in 0 until bar.childCount) {
                    val v = bar.getChildAt(i)
                    if (v !== urlBox && v.visibility == View.VISIBLE) { urlHidden.add(v); v.visibility = View.GONE }
                }
                val ulp = urlBox.layoutParams as LinearLayout.LayoutParams
                ulp.leftMargin = 0
                ulp.rightMargin = 0
                urlBox.layoutParams = ulp
            }
            urlBox.postDelayed(urlRestore, 500)
        }
    })
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


internal fun MainActivity.hasTab(v: WebView): Tab? = tabs.firstOrNull { it.web === v }

internal fun MainActivity.updateLoadUi() {
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
