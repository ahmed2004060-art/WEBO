package com.webo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

// أدوات الواجهة المشتركة: الأبعاد والألوان والأزرار والصفوف وتنسيق الأحجام
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.fmtSize(b: Double): String = when {
    b >= 1048576.0 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1024.0 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
    else -> b.toLong().toString() + " B"
}

internal fun MainActivity.fmtTime(sec: Long): String {
    val m = sec / 60
    val x = sec % 60
    return if (m >= 60) String.format(java.util.Locale.US, "%d:%02d:%02d", m / 60, m % 60, x)
    else String.format(java.util.Locale.US, "%02d:%02d", m, x)
}

internal fun MainActivity.saver() = prefs().getBoolean("saver", false)

internal fun MainActivity.lowQ() = saver() || prefs().getString("quality", "high") == "low"

internal fun MainActivity.pageCache() = prefs().getBoolean("cachepages", true)

internal fun MainActivity.baseCache() = if (saver() || pageCache()) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT


internal fun MainActivity.dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

internal fun MainActivity.lp(w: Int, h: Int, wt: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, wt)
    p.setMargins(dp(l), dp(t), dp(r), dp(b))
    return p
}

internal fun MainActivity.bg(color: Int, r: Int, stroke: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(r).toFloat()
    if (stroke != 0) g.setStroke(dp(1), stroke)
    return g
}

internal fun MainActivity.tv(t: String, size: Float = 15f, color: Int = cText, bold: Boolean = false): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = size
    v.setTextColor(color)
    if (bold) v.setTypeface(null, Typeface.BOLD)
    return v
}

internal fun MainActivity.styleChip(t: TextView, on: Boolean) {
    t.setTextColor(if (on) Color.WHITE else cAccent)
    t.background = if (on) bg(cAccent, 18) else bg(Color.TRANSPARENT, 18, cAccent)
}

internal fun MainActivity.pill(text: String, filled: Boolean, onClick: () -> Unit): TextView {
    val t = tv(text, 13f, Color.WHITE, true)
    t.gravity = Gravity.CENTER
    t.tag = "pill"
    applyPillSize(t)
    styleChip(t, filled)
    t.setOnClickListener { onClick() }
    return t
}

// أزرار تبويب التنزيلات: مطابقة لأزرار معاينة html (ارتفاع 46، نصف قطر 22، حشو أفقي 14، خط 13، سطر واحد)
internal fun MainActivity.dlPill(text: String, filled: Boolean, onClick: () -> Unit): TextView {
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
internal fun MainActivity.applyPillSize(t: TextView) {
    val p = pctB("txt_pill")
    t.textSize = (13f * p / 100f).coerceIn(7f, 30f)
    t.setPadding(dp(14 * p / 100), dp(8 * p / 100), dp(14 * p / 100), dp(8 * p / 100))
}

// سماكة زر «عداد ملتقط الروابط» و«التحميلات» = سماكة الأزرار العلوية (تتبع حجم الأزرار النصية)
internal fun MainActivity.syncDlBtnHeights() {
    try {
        val h = dp(46)
        for (v in listOf<View>(badgeBox, tabDl)) {
            val p = v.layoutParams
            p.height = h
            v.layoutParams = p
        }
    } catch (e: Throwable) { Diag.swallow("UiKit.kt:118 syncDlBtnHeights", e);
    }
}

internal fun MainActivity.restylePills(v: View) {
    if (v is TextView && v.tag == "pill") applyPillSize(v)
    if (v is ViewGroup) for (i in 0 until v.childCount) restylePills(v.getChildAt(i))
}

internal fun MainActivity.iconBtn(res: Int, tint: Int = cText, onClick: () -> Unit): ImageView {
    val i = ImageView(this)
    i.setImageResource(res)
    i.setColorFilter(tint)
    i.setPadding(dp(8), dp(8), dp(8), dp(8))
    i.setOnClickListener { onClick() }
    return i
}

internal fun MainActivity.toast(s: String) {
    DlgStyle.toast(this, s, false)
}

internal fun MainActivity.copyText(s: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("url", s))
    toast("تم النسخ")
}

internal fun MainActivity.hideKb() {
    val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    im.hideSoftInputFromWindow(root.windowToken, 0)
}

internal fun MainActivity.initColors() {
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

internal fun MainActivity.prefs() = getSharedPreferences("tdl", Context.MODE_PRIVATE)

internal fun MainActivity.toolCard(res: Int, label: String, onClick: () -> Unit): LinearLayout {
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

internal fun MainActivity.grid(parent: LinearLayout, cards: List<View>) {
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

internal fun MainActivity.settingsCard(parent: LinearLayout): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.background = bg(cCard, 14, cLine)
    c.setPadding(dp(14), dp(10), dp(14), dp(10))
    parent.addView(c, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
    return c
}

internal fun MainActivity.switchRow(parent: LinearLayout, title: String, sub: String, key: String, def: Boolean, onChange: (Boolean) -> Unit) {
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

internal fun MainActivity.section(col: LinearLayout, title: String) {
    col.addView(tv(title, 13f, cAccent, true), lp(MATCH, WRAP, 0f, 4, 10, 0, 6))
}

internal fun MainActivity.actionRow(parent: LinearLayout, title: String, sub: String, btn: String, onClick: () -> Unit) {
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

internal fun MainActivity.hubRow(parent: LinearLayout, icon: Int, title: String, sub: String, id: String) {
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

internal fun MainActivity.hostOf(u: String): String = Uri.parse(u).host?.removePrefix("www.") ?: u.take(20)


internal fun MainActivity.clipText(): String = try {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val c = cm.primaryClip
    if (c != null && c.itemCount > 0) (c.getItemAt(0).coerceToText(this)?.toString() ?: "").trim() else ""
} catch (e: Throwable) { Diag.swallow("UiKit.kt:292 clipText", e);
    ""
}

// خانة رقم حرة: تكتب أي قيمة تريدها، والصفر يعني بلا حد (أو تلقائي حسب الوصف)
internal fun MainActivity.numRow(parent: LinearLayout, title: String, sub0: String, key: String, def: Int, zeroLabel: String = "بلا حد", onChange: (Int) -> Unit = {}) {
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

internal fun MainActivity.choiceRow(parent: LinearLayout, title: String, sub: String, key: String, labels: Array<String>, values: IntArray, def: Int, onChange: (Int) -> Unit = {}) {
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

internal fun MainActivity.fmtDur(sec: Double): String = fmtTime(sec.toLong())


internal fun MainActivity.labeledSeek(col: LinearLayout, title: String, min: Int, max: Int, start: Int, fmt: (Int) -> String, onStop: (Int) -> Unit): SeekBar {
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
