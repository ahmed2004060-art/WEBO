package com.webo

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.webo.MainActivity.Tab

// شاشة مبدّل التبويبات (البطاقات واللمس والتحديد)
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.swC2() = Color.parseColor(if (dark) "#2B2E33" else "#E6EAF0")

internal fun MainActivity.swCs() = Color.parseColor(if (dark) "#A8C7FA" else "#CFE0FB")

internal fun MainActivity.swOns() = Color.parseColor("#0B1B33")

internal fun MainActivity.swOnAcc() = Color.parseColor(if (dark) "#0B1B33" else "#FFFFFF")

internal fun MainActivity.swAcc() = Color.parseColor(if (dark) "#5B9BFF" else "#2F6FE0")

internal fun MainActivity.nAr(n: Int): String {
    val sb = StringBuilder()
    for (ch in n.toString()) sb.append(if (ch in '0'..'9') ('٠' + (ch - '0')) else ch)
    return sb.toString()
}

internal fun MainActivity.swGd(color: Int, r: Int, sw: Int = 0, sc: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(r).toFloat()
    if (sw > 0) g.setStroke(dp(sw), sc)
    return g
}

internal fun MainActivity.swOval(color: Int): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(color)
    return g
}

internal fun MainActivity.swPress(normal: android.graphics.drawable.Drawable, pressed: Int, r: Int, oval: Boolean = false): android.graphics.drawable.StateListDrawable {
    val s = android.graphics.drawable.StateListDrawable()
    val p = GradientDrawable()
    p.setColor(pressed)
    if (oval) p.shape = GradientDrawable.OVAL else p.cornerRadius = dp(r).toFloat()
    s.addState(intArrayOf(android.R.attr.state_pressed), p)
    s.addState(intArrayOf(), normal)
    return s
}

internal fun MainActivity.swHit(v: View, x: Float, y: Float): Boolean {
    val l = IntArray(2)
    v.getLocationOnScreen(l)
    return x >= l[0] && x <= l[0] + v.width && y >= l[1] && y <= l[1] + v.height
}

internal fun MainActivity.buildSwitcher(): View {
    swRoot = object : FrameLayout(this@buildSwitcher) {
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

internal fun MainActivity.swOpen() = swShown

internal fun MainActivity.openSw() {
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

internal fun MainActivity.closeSw() {
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

internal fun MainActivity.swCenterCur() {
    val e = swCells.firstOrNull { tabs.getOrNull(it.second) === cur } ?: return
    val cell = e.first
    val row = cell.parent as? View ?: return
    val y = row.top + cell.top - (swScroll.height - cell.height) / 2
    swScroll.scrollTo(0, maxOf(0, y))
}

internal fun MainActivity.swPick(i: Int) {
    val t = tabs.getOrNull(i) ?: return
    closeSw()
    switchTo(t)
    showPage(0)
}

internal fun MainActivity.swTitle(t: Tab): String {
    if (t.start) return "تبويب جديد"
    val u = t.web.url ?: t.pendingUrl ?: return "تبويب جديد"
    return if (t.title.isNotBlank()) t.title else u.replace(Regex("^https?://"), "")
}

internal fun MainActivity.swKey(t: Tab): String {
    if (t.start) return ""
    return (t.web.url ?: t.pendingUrl ?: "").replace(Regex("^https?://"), "")
}

internal fun MainActivity.swHost(t: Tab): String {
    if (t.start) return ""
    return try { Uri.parse(t.web.url ?: t.pendingUrl ?: "").host ?: "" } catch (e: Throwable) { Diag.swallow("TabSwitcher.kt:300 swHost", e); "" }
}

// الدائرة الصغيرة = شعار الموقع المفتوح
internal fun MainActivity.swFav(t: Tab): View {
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

internal fun MainActivity.swUpdCount() {
    swCount.text = nAr(tabs.size)
}

internal fun MainActivity.swSelUI() {
    swSelBar.visibility = if (swSelMode) View.VISIBLE else View.GONE
    swHint.text = if (swSelMode) "اضغط على البطاقات لتحديدها أو إلغاء تحديدها ثم اضغط حذف" else SW_HINT
    swDel.text = "حذف (" + nAr(swSel.size) + ")"
    swDel.isEnabled = swSel.isNotEmpty()
    swDel.alpha = if (swSel.isNotEmpty()) 1f else 0.4f
    swAll.text = if (tabs.isNotEmpty() && swSel.size == tabs.size) "إلغاء تحديد الكل" else "تحديد الكل"
}

internal fun MainActivity.swDraw(keep: Boolean = true) {
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

internal fun MainActivity.swCard(i: Int, cw: Int): View {
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
internal fun MainActivity.swTouch(t: Tab, i: Int, cell: View, card: View): View.OnTouchListener {
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


internal fun MainActivity.swDetach(t: Tab) {
    // الخروج من التبويب: يُحذف كل ما التُقط منه (صور وروابط فيديو ومعاينات) قبل فصله
    pruneTab(t)
    tabs.remove(t)
    try { container.removeView(t.web) } catch (e: Throwable) { Diag.swallow("TabSwitcher.kt:572 swDetach", e);}
    try { tabsRow.removeView(t.chip) } catch (e: Throwable) { Diag.swallow("TabSwitcher.kt:573 swDetach", e);}
    try { t.web.onPause() } catch (e: Throwable) { Diag.swallow("TabSwitcher.kt:574 swDetach", e);}
}

internal fun MainActivity.swReattach(c: SwClosed) {
    val at = minOf(c.i, tabs.size)
    tabs.add(at, c.t)
    container.addView(c.t.web, FrameLayout.LayoutParams(MATCH, MATCH))
    tabsRow.addView(c.t.chip, minOf(at, maxOf(0, tabsRow.childCount - 1)))
}

internal fun MainActivity.swFinalize() {
    if (swClosed.isEmpty()) return
    val l = ArrayList(swClosed)
    swClosed.clear()
    for (c in l) {
        try { snapshot(c.t) } catch (e: Throwable) { Diag.swallow("TabSwitcher.kt:589 swFinalize", e);}
        pages.remove(c.t.web)
        try { c.t.web.destroy() } catch (e: Throwable) { Diag.swallow("TabSwitcher.kt:591 swFinalize", e);}
    }
}

internal fun MainActivity.swSnackShow(msg: String, lbl: String?, fn: (() -> Unit)?) {
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

internal fun MainActivity.swCloseAt(i: Int) {
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

internal fun MainActivity.swCloseOthers() {
    val keep = cur ?: return
    for (t in ArrayList(tabs)) if (t !== keep) removeTab(t)
    relabel()
    swDraw()
    swSnackShow("أُغلقت كل التبويبات الأخرى", null, null)
    saveTabsPrefs()
}

internal fun MainActivity.swCloseAll() {
    for (t in ArrayList(tabs)) removeTab(t)
    cur = null
    relabel()
    swDraw()
    swSnackShow("أُغلقت كل التبويبات", null, null)
    saveTabsPrefs()
}

internal fun MainActivity.swStartSel() {
    if (tabs.isEmpty()) {
        swSnackShow("لا توجد تبويبات", null, null)
        return
    }
    swSelMode = true
    swSel.clear()
    swDraw()
}

internal fun MainActivity.swEndSel() {
    swSelMode = false
    swSel.clear()
    swDraw()
}

internal fun MainActivity.swDelSel() {
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


internal fun MainActivity.swCapture(t: Tab) {
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
    } catch (e: Throwable) { Diag.swallow("TabSwitcher.kt:715 swCapture", e);
    }
}
