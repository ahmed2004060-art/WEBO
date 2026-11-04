package com.webo

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

// أحجام الأزرار والأيقونات
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)


internal fun MainActivity.regStatic() {
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

internal fun MainActivity.pctB(id: String): Int = prefs().getInt("szb_" + id, 100).coerceIn(30, 300)

internal fun MainActivity.pctI(id: String): Int = prefs().getInt("szi_" + id, 100).coerceIn(30, 300)

internal fun MainActivity.applyBarSizes() {
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
    } catch (e: Throwable) { Diag.swallow("ButtonSizes.kt:64 applyBarSizes", e);
        logErr("واجهة", "تعذر تطبيق أحجام الشريط العلوي: " + (e.message ?: e.javaClass.simpleName), e)
    }
}

// يعيد بناء الأدوات والقائمة الجانبية بالأحجام الجديدة
internal fun MainActivity.applySizes() {
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
    } catch (e: Throwable) { Diag.swallow("ButtonSizes.kt:86 applySizes", e);
        logErr("واجهة", "تعذر تطبيق الأحجام: " + (e.message ?: ""), e)
    }
}

internal fun MainActivity.showSizes() {
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
