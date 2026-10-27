package com.webo

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// AlertDialog.Builder يُظهر النافذة بستايل التطبيق تلقائياً (إطار بطاقة، أزرار pill، نصوص بألوان التطبيق)
class StyledBuilder(private val ctx: Context) : AlertDialog.Builder(ctx) {
    override fun show(): AlertDialog {
        val d = create()
        d.show()
        try {
            DlgStyle.style(ctx, d)
        } catch (e: Throwable) {
        }
        return d
    }
}

object DlgStyle {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    class Pal(ctx: Context) {
        val dark = ctx.getSharedPreferences("tdl", Context.MODE_PRIVATE).getBoolean("dark", true)
        val bg = Color.parseColor(if (dark) "#0F1318" else "#F3F5F8")
        val card = Color.parseColor(if (dark) "#1E252E" else "#FFFFFF")
        val text = Color.parseColor(if (dark) "#ECEFF4" else "#1B1F24")
        val muted = Color.parseColor(if (dark) "#93A0B1" else "#5B6470")
        val line = Color.parseColor(if (dark) "#2A323D" else "#D8DCE2")
        val accent = Color.parseColor(if (dark) "#5B9BFF" else "#0B6BCB")
        val danger = Color.parseColor(if (dark) "#FF6B6B" else "#C62828")
    }

    private fun dp(ctx: Context, n: Int): Int = (n * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(ctx: Context, color: Int, r: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(ctx, r).toFloat()
        if (stroke != 0) g.setStroke(dp(ctx, 1), stroke)
        return g
    }

    private fun lp(w: Int, h: Int, wt: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, wt)

    // ---------- إطار النافذة والأزرار ----------

    fun style(ctx: Context, d: AlertDialog) {
        val p = Pal(ctx)
        d.window?.setBackgroundDrawable(InsetDrawable(shape(ctx, p.card, 18, p.line), dp(ctx, 12)))

        val tid = ctx.resources.getIdentifier("alertTitle", "id", "android")
        if (tid != 0) {
            val t = d.findViewById<View>(tid)
            if (t is TextView) {
                t.setTextColor(p.text)
                t.textSize = 16f
                t.setTypeface(null, Typeface.BOLD)
            }
        }
        val did = ctx.resources.getIdentifier("titleDivider", "id", "android")
        if (did != 0) d.findViewById<View>(did)?.visibility = View.GONE

        val msg = d.findViewById<TextView>(android.R.id.message)
        if (msg != null) {
            msg.setTextColor(p.muted)
            msg.textSize = 13f
            msg.setLineSpacing(0f, 1.25f)
        }

        styleBtn(ctx, p, d.getButton(AlertDialog.BUTTON_POSITIVE), true)
        styleBtn(ctx, p, d.getButton(AlertDialog.BUTTON_NEGATIVE), false)
        styleBtn(ctx, p, d.getButton(AlertDialog.BUTTON_NEUTRAL), false)
        fixButtonBar(ctx, d)

        var lv: ListView? = null
        try {
            lv = d.listView
        } catch (e: Throwable) {
        }
        if (lv != null) {
            lv.divider = ColorDrawable(p.line)
            lv.dividerHeight = 1
            lv.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) {
                    styleItem(ctx, p, child)
                }

                override fun onChildViewRemoved(parent: View, child: View) {}
            })
            for (i in 0 until lv.childCount) styleItem(ctx, p, lv.getChildAt(i))
        }

        val dv = d.window?.decorView
        if (dv != null) styleEdits(ctx, p, dv)
    }

    // منع قص أزرار النافذة: إلغاء القص في كل الحاويات فوق الأزرار + مساحة كافية لصف الأزرار + إعادة القياس بعد التنسيق
    private fun fixButtonBar(ctx: Context, d: AlertDialog) {
        val anyBtn = d.getButton(AlertDialog.BUTTON_POSITIVE) ?: d.getButton(AlertDialog.BUTTON_NEGATIVE) ?: d.getButton(AlertDialog.BUTTON_NEUTRAL) ?: return
        var par: View? = anyBtn.parent as? View
        var lvl = 0
        while (par != null && lvl < 8) {
            if (par is ViewGroup) {
                par.clipChildren = false
                par.clipToPadding = false
            }
            par = par.parent as? View
            lvl++
        }
        val bid = ctx.resources.getIdentifier("buttonPanel", "id", "android")
        val panel = if (bid != 0) d.findViewById<View>(bid) else null
        if (panel != null) {
            panel.minimumHeight = 0
            panel.setPadding(panel.paddingLeft, dp(ctx, 8), panel.paddingRight, dp(ctx, 10))
        } else {
            (anyBtn.parent as? View)?.let { it.setPadding(it.paddingLeft, dp(ctx, 8), it.paddingRight, dp(ctx, 10)) }
        }
        val root = d.window?.decorView
        root?.post {
            root.requestLayout()
            root.postDelayed({ root.requestLayout() }, 60)
        }
    }

    // ---------- تلوين المفاتيح وخانات الاختيار بلون التطبيق (لا يعتمد على ثيم الجهاز) ----------

    fun styleSwitch(ctx: Context, sw: android.widget.Switch) {
        val p = Pal(ctx)
        val checked = intArrayOf(android.R.attr.state_checked)
        val none = intArrayOf()
        val thumbOn = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.accent); setSize(dp(ctx, 22), dp(ctx, 22)) }
        val thumbOff = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.muted); setSize(dp(ctx, 22), dp(ctx, 22)) }
        val trackOn = GradientDrawable().apply { setColor((p.accent and 0x00FFFFFF) or 0x66000000); cornerRadius = dp(ctx, 8).toFloat(); setSize(dp(ctx, 40), dp(ctx, 16)) }
        val trackOff = GradientDrawable().apply { setColor((p.muted and 0x00FFFFFF) or 0x44000000); cornerRadius = dp(ctx, 8).toFloat(); setSize(dp(ctx, 40), dp(ctx, 16)) }
        val thumb = android.graphics.drawable.StateListDrawable()
        thumb.addState(checked, thumbOn)
        thumb.addState(none, thumbOff)
        val track = android.graphics.drawable.StateListDrawable()
        track.addState(checked, trackOn)
        track.addState(none, trackOff)
        sw.thumbTintList = null
        sw.trackTintList = null
        sw.thumbDrawable = thumb
        sw.trackDrawable = track
    }

    fun styleCheck(ctx: Context, cb: android.widget.CheckBox) {
        val p = Pal(ctx)
        cb.buttonTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(p.accent, p.muted)
        )
    }

    private fun styleItem(ctx: Context, p: Pal, v: View) {
        if (v is TextView) {
            v.setTextColor(p.text)
            v.textSize = 14f
            v.setTypeface(null, Typeface.BOLD)
        }
    }

    private fun styleEdits(ctx: Context, p: Pal, v: View) {
        if (v is EditText) {
            v.setTextColor(p.text)
            v.setHintTextColor(p.muted)
            v.textSize = 13f
            v.background = shape(ctx, p.bg, 10, p.line)
            v.setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
            val lp = v.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.setMargins(lp.leftMargin, dp(ctx, 4), lp.rightMargin, dp(ctx, 4))
                v.layoutParams = lp
            }
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) styleEdits(ctx, p, v.getChildAt(i))
    }

    private fun styleBtn(ctx: Context, p: Pal, b: Button?, positive: Boolean) {
        if (b == null) return
        val t = b.text.toString()
        val destructive = t == "حذف" || t.startsWith("مسح")
        val col = if (destructive) p.danger else p.accent
        b.setAllCaps(false)
        b.textSize = 13f
        b.setTypeface(null, Typeface.BOLD)
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = dp(ctx, 38)
        b.minimumHeight = dp(ctx, 38)
        b.gravity = Gravity.CENTER
        b.stateListAnimator = null
        b.setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
        if (positive) {
            b.setTextColor(Color.WHITE)
            b.background = shape(ctx, col, 18)
        } else {
            b.setTextColor(col)
            b.background = shape(ctx, Color.TRANSPARENT, 18, col)
        }
        val lp = b.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.setMargins(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 2))
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            b.layoutParams = lp
        }
    }

    // رسالة Toast بستايل التطبيق: بطاقة بإطار بلون التأكيد. تعود للرسالة العادية إن تعذر العرض
    fun toast(ctx: Context, msg: String, long: Boolean = false) {
        try {
            val p = Pal(ctx)
            val t = TextView(ctx)
            t.text = msg
            t.textSize = 13f
            t.setTypeface(null, Typeface.BOLD)
            t.setTextColor(p.text)
            t.gravity = Gravity.CENTER
            t.maxLines = 4
            t.background = shape(ctx, p.card, 18, p.accent)
            t.setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
            val ts = Toast(ctx)
            ts.view = t
            ts.duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ts.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(ctx, 90))
            ts.show()
        } catch (e: Throwable) {
            try {
                Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            } catch (e2: Throwable) {
            }
        }
    }

    // ---------- محتوى منظم داخل النوافذ ----------

    private fun txt(ctx: Context, s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun sep(ctx: Context, p: Pal): View {
        val v = View(ctx)
        v.setBackgroundColor(p.line)
        v.layoutParams = lp(MATCH, 1)
        return v
    }

    private fun ripple(ctx: Context, v: View) {
        val tv = TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0) {
            v.setBackgroundResource(tv.resourceId)
        }
    }

    private fun scroll(ctx: Context, col: View): ScrollView {
        val sv = ScrollView(ctx)
        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        return sv
    }

    private fun column(ctx: Context, hp: Int = 16): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(ctx, hp), dp(ctx, 2), dp(ctx, hp), dp(ctx, 4))
        return c
    }

    // سطور «الاسم: القيمة» تتحول إلى صفوف اسم ← قيمة. سطر ينتهي بنقطتين تأخذ قيمته السطر التالي
    fun kvText(ctx: Context, text: String): View {
        val p = Pal(ctx)
        val col = column(ctx, 20)
        val lines = text.split("\n").filter { it.isNotBlank() }
        var i = 0
        var first = true
        while (i < lines.size) {
            val ln = lines[i]
            var k = ""
            var v = ln
            val ix = ln.indexOf(": ")
            if (ix > 0) {
                k = ln.substring(0, ix)
                v = ln.substring(ix + 2)
            } else if (ln.endsWith(":") && i + 1 < lines.size) {
                k = ln.dropLast(1)
                v = lines[i + 1]
                i++
            }
            i++
            if (!first) col.addView(sep(ctx, p))
            first = false
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            if (k.isNotEmpty()) {
                row.addView(txt(ctx, k, 12.5f, p.muted), lp(0, WRAP, 1f))
                val vt = txt(ctx, v, 14f, p.text, true)
                vt.gravity = Gravity.END
                if (v.startsWith("http")) vt.textDirection = View.TEXT_DIRECTION_LTR
                row.addView(vt, lp(0, WRAP, 1.4f))
            } else {
                row.addView(txt(ctx, v, 13f, p.muted), lp(MATCH, WRAP))
            }
            col.addView(row, lp(MATCH, WRAP))
        }
        return scroll(ctx, col)
    }

    // فقرة تعريفية ثم نقاط بنقاط ملونة (الأسطر التي تبدأ بـ «• »)
    fun bulletText(ctx: Context, text: String): View {
        val p = Pal(ctx)
        val col = column(ctx, 20)
        for (ln in text.split("\n")) {
            if (ln.isBlank()) continue
            if (ln.startsWith("• ")) {
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
                val dot = View(ctx)
                dot.background = shape(ctx, p.accent, 3)
                val dl = lp(dp(ctx, 6), dp(ctx, 6))
                dl.setMargins(0, dp(ctx, 8), dp(ctx, 10), 0)
                row.addView(dot, dl)
                row.addView(txt(ctx, ln.substring(2), 13f, p.text), lp(0, WRAP, 1f))
                col.addView(row, lp(MATCH, WRAP))
            } else {
                val t = txt(ctx, ln, 13f, p.muted)
                t.setLineSpacing(0f, 1.25f)
                t.setPadding(0, 0, 0, dp(ctx, 6))
                col.addView(t, lp(MATCH, WRAP))
            }
        }
        return scroll(ctx, col)
    }

    // قائمة صفوف مفصولة بخط خفيف
    fun rowsList(ctx: Context, rows: List<View>): View {
        val p = Pal(ctx)
        val col = column(ctx, 12)
        for ((i, r) in rows.withIndex()) {
            if (i > 0) col.addView(sep(ctx, p))
            col.addView(r, lp(MATCH, WRAP))
        }
        return scroll(ctx, col)
    }

    private fun initial(name: String): String {
        val s = name.trim()
        return if (s.isEmpty()) "•" else s.substring(0, 1).uppercase()
    }

    // صف موقع: شعار الموقع (أو أول حرف) + الاسم + الرابط
    fun siteRow(ctx: Context, name: String, sub: String, icon: Bitmap?, onClick: () -> Unit): View {
        val p = Pal(ctx)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(ctx, 6), dp(ctx, 9), dp(ctx, 6), dp(ctx, 9))
        val size = dp(ctx, 30)
        if (icon != null) {
            val iv = ImageView(ctx)
            iv.setImageBitmap(icon)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.background = shape(ctx, p.bg, 15, p.line)
            iv.clipToOutline = true
            iv.outlineProvider = ViewOutlineProvider.BACKGROUND
            row.addView(iv, lp(size, size))
        } else {
            // الموقع لا يعرض أيقونته: أيقونة التطبيق
            val iv = ImageView(ctx)
            iv.setImageResource(R.drawable.logo)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3))
            iv.background = shape(ctx, Color.WHITE, 15, p.line)
            iv.clipToOutline = true
            iv.outlineProvider = ViewOutlineProvider.BACKGROUND
            row.addView(iv, lp(size, size))
        }
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val n = txt(ctx, name, 14f, p.text, true)
        n.maxLines = 1
        n.ellipsize = TextUtils.TruncateAt.END
        col.addView(n)
        if (sub.isNotEmpty()) {
            val s = txt(ctx, sub, 11.5f, p.muted)
            s.maxLines = 1
            s.ellipsize = TextUtils.TruncateAt.END
            s.textDirection = View.TEXT_DIRECTION_LTR
            s.gravity = Gravity.START
            col.addView(s)
        }
        val cl = lp(0, WRAP, 1f)
        cl.setMargins(dp(ctx, 12), 0, 0, 0)
        row.addView(col, cl)
        ripple(ctx, row)
        row.setOnClickListener { onClick() }
        return row
    }

    // صف فيديو: مربع مصغّر 96×60 (أو أيقونة الفيديو) + العنوان + سطر ثانوي
    fun thumbRow(ctx: Context, title: String, sub: String, thumb: Bitmap?, onClick: () -> Unit): View {
        val p = Pal(ctx)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(ctx, 6), dp(ctx, 9), dp(ctx, 6), dp(ctx, 9))
        val iv = ImageView(ctx)
        iv.background = shape(ctx, p.bg, 10, p.line)
        iv.clipToOutline = true
        iv.outlineProvider = ViewOutlineProvider.BACKGROUND
        if (thumb != null) {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(thumb)
        } else {
            iv.scaleType = ImageView.ScaleType.CENTER
            iv.setImageResource(R.drawable.ic_video)
            iv.setColorFilter(p.muted)
        }
        row.addView(iv, lp(dp(ctx, 96), dp(ctx, 60)))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val n = txt(ctx, title, 14f, p.text, true)
        n.maxLines = 2
        n.ellipsize = TextUtils.TruncateAt.END
        col.addView(n)
        if (sub.isNotEmpty()) {
            val s = txt(ctx, sub, 11.5f, p.muted)
            s.maxLines = 2
            s.ellipsize = TextUtils.TruncateAt.END
            col.addView(s)
        }
        val cl = lp(0, WRAP, 1f)
        cl.setMargins(dp(ctx, 12), 0, 0, 0)
        row.addView(col, cl)
        ripple(ctx, row)
        row.setOnClickListener { onClick() }
        return row
    }

    // صف جودة: الاسم + الحجم التقريبي في شارة
    fun qualityRow(ctx: Context, label: String, size: String, onClick: () -> Unit): View {
        val p = Pal(ctx)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(ctx, 8), dp(ctx, 12), dp(ctx, 8), dp(ctx, 12))
        row.addView(txt(ctx, label, 14f, p.text, true), lp(0, WRAP, 1f))
        if (size.isNotEmpty()) {
            val b = txt(ctx, size, 12f, p.muted)
            b.background = shape(ctx, p.bg, 12, p.line)
            b.setPadding(dp(ctx, 9), dp(ctx, 3), dp(ctx, 9), dp(ctx, 3))
            row.addView(b, lp(WRAP, WRAP))
        }
        ripple(ctx, row)
        row.setOnClickListener { onClick() }
        return row
    }

    // نص عادي بسيط للقوائم التي كل بند فيها سطر واحد (ماذا تريد؟ ...)
    fun textRow(ctx: Context, label: String, onClick: () -> Unit): View {
        val p = Pal(ctx)
        val row = LinearLayout(ctx)
        row.setPadding(dp(ctx, 8), dp(ctx, 13), dp(ctx, 8), dp(ctx, 13))
        row.addView(txt(ctx, label, 14f, p.text, true), lp(0, WRAP, 1f))
        ripple(ctx, row)
        row.setOnClickListener { onClick() }
        return row
    }

    // سجل الالتقاط: كل سجل بطاقة (الوقت + شارة النوع + السبب + الرابط)
    fun logCards(ctx: Context, entries: List<String>): View {
        val p = Pal(ctx)
        val col = column(ctx, 14)
        for ((i, e) in entries.withIndex()) {
            if (i > 0) col.addView(sep(ctx, p))
            val nl = e.indexOf('\n')
            val head = if (nl >= 0) e.substring(0, nl) else e
            val url = if (nl >= 0) e.substring(nl + 1) else ""
            val m = Regex("^(\\S+)\\s+\\[(.+?)\\]\\s*(.*)$").find(head)
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10))
            if (m != null) {
                val time = m.groupValues[1]
                val kind = m.groupValues[2]
                val why = m.groupValues[3]
                val top = LinearLayout(ctx)
                top.orientation = LinearLayout.HORIZONTAL
                top.gravity = Gravity.CENTER_VERTICAL
                val tt = txt(ctx, time, 11.5f, p.muted)
                tt.typeface = Typeface.MONOSPACE
                tt.textDirection = View.TEXT_DIRECTION_LTR
                top.addView(tt, lp(WRAP, WRAP))
                val badge = txt(ctx, kind, 11f, p.muted, true)
                badge.setPadding(dp(ctx, 9), dp(ctx, 2), dp(ctx, 9), dp(ctx, 2))
                if (kind == "قُبل") {
                    badge.setTextColor(Color.WHITE)
                    badge.background = shape(ctx, p.accent, 12)
                } else if (kind == "أُهمل") {
                    badge.setTextColor(p.danger)
                    badge.background = shape(ctx, Color.TRANSPARENT, 12, p.danger)
                } else {
                    badge.background = shape(ctx, Color.TRANSPARENT, 12, p.muted)
                }
                val bl = lp(WRAP, WRAP)
                bl.setMargins(dp(ctx, 8), 0, 0, 0)
                top.addView(badge, bl)
                box.addView(top, lp(MATCH, WRAP))
                if (why.isNotEmpty()) {
                    val w = txt(ctx, why, 13f, p.text)
                    w.setLineSpacing(0f, 1.2f)
                    box.addView(w, lp(MATCH, WRAP))
                }
            } else {
                box.addView(txt(ctx, head, 13f, p.text), lp(MATCH, WRAP))
            }
            if (url.isNotEmpty()) {
                val u = txt(ctx, url, 11f, p.muted)
                u.typeface = Typeface.MONOSPACE
                u.textDirection = View.TEXT_DIRECTION_LTR
                u.gravity = Gravity.START
                u.maxLines = 3
                u.ellipsize = TextUtils.TruncateAt.END
                box.addView(u, lp(MATCH, WRAP))
            }
            col.addView(box, lp(MATCH, WRAP))
        }
        return scroll(ctx, col)
    }
}
