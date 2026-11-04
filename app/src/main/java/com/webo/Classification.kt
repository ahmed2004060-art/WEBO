package com.webo

import android.app.AlertDialog
import android.net.Uri
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout

// تصنيف الفيديوهات يدوياً والمستبعدات
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.ovrKey(u: String): String { val x = Uri.parse(u); return (x.host ?: "") + (x.path ?: "") }

// true = مسجل، false = بث مباشر، null = لا تصنيف يدوي (الأولوية للرابط ثم للموقع)
internal fun MainActivity.getOvr(u: String): Boolean? {
    val set = prefs().getStringSet("ovr", emptySet()) ?: emptySet()
    val k = ovrKey(u)
    val host = Uri.parse(u).host ?: ""
    if (set.contains("vod|" + k)) return true
    if (set.contains("live|" + k)) return false
    if (set.contains("hvod|" + host)) return true
    if (set.contains("hlive|" + host)) return false
    return null
}

// scope: "url" لهذا الرابط، "host" لكل روابط الموقع. vod = null يزيل التصنيف اليدوي
internal fun MainActivity.setOvr(u: String, scope: String, vod: Boolean?) {
    val set = HashSet(prefs().getStringSet("ovr", emptySet()) ?: emptySet())
    val k = ovrKey(u)
    val host = Uri.parse(u).host ?: ""
    if (vod == null) {
        set.remove("vod|" + k); set.remove("live|" + k); set.remove("hvod|" + host); set.remove("hlive|" + host)
    } else if (scope == "host") {
        set.remove("hvod|" + host); set.remove("hlive|" + host)
        set.add((if (vod) "hvod|" else "hlive|") + host)
    } else {
        set.remove("vod|" + k); set.remove("live|" + k)
        set.add((if (vod) "vod|" else "live|") + k)
    }
    prefs().edit().putStringSet("ovr", set).apply()
}

internal fun MainActivity.applyVerdict(c: Cand, vod: Boolean) {
    val m = c.meta ?: Meta(vod, -1.0, -1L, false, "", 0L, emptyList())
    c.meta = m.copy(vod = vod, conf = 100, why = "يدوي")
    refreshInfo(c)
    if (!vod && !prefs().getBoolean("showlive", true)) {
        excluded[c.url] = Excl(c.url, foundHdr[c.url] ?: emptyMap(), c.meta, c.owner, c.pageTitle)
        dropCand(c)
        updateCounts()
    }
}

internal fun MainActivity.classifyDialog(c: Cand) {
    val labels = arrayOf("مسجل (هذا الرابط)", "بث مباشر (هذا الرابط)", "كل روابط هذا الموقع مسجلة", "كل روابط هذا الموقع بث مباشر", "إزالة التصنيف اليدوي")
    val icons = intArrayOf(R.drawable.ic_video, R.drawable.ic_live, R.drawable.ic_web, R.drawable.ic_web, R.drawable.ic_refresh)
    fun apply(i: Int) {
        val u = c.url
        when (i) {
            0 -> { setOvr(u, "url", true); applyVerdict(c, true) }
            1 -> { setOvr(u, "url", false); applyVerdict(c, false) }
            2 -> {
                setOvr(u, "host", true)
                for (x in ArrayList(cands.values)) if (x.hostTxt == c.hostTxt) applyVerdict(x, true)
            }
            3 -> {
                setOvr(u, "host", false)
                for (x in ArrayList(cands.values)) if (x.hostTxt == c.hostTxt) applyVerdict(x, false)
            }
            else -> { setOvr(u, "url", null); toast("أُزيل التصنيف اليدوي، يُحكم على الرابط تلقائياً عند التقاطه ثانية") }
        }
        capNote("يدوي", u, "صُنّف: " + labels[i])
    }
    var dlg: AlertDialog? = null
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(12), dp(6), dp(12), dp(6))
    for (i in labels.indices) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(12), dp(8), dp(12))
        val ic = ImageView(this)
        ic.setImageResource(icons[i])
        ic.setColorFilter(cAccent)
        row.addView(ic, lp(dp(24), dp(24), 0f, 0, 0, 12, 0))
        row.addView(tv(labels[i], 14f, cText, true), lp(0, WRAP, 1f))
        row.setOnClickListener { dlg?.dismiss(); apply(i) }
        col.addView(row, lp(MATCH, WRAP))
    }
    dlg = StyledBuilder(this).setTitle("تصنيف الفيديو").setView(col).setNegativeButton("إغلاق", null).show()
}

// الروابط التي استبعدها التطبيق لأنها بدت بثاً مباشراً: تظهر هنا لتصحح الحكم يدوياً
internal fun MainActivity.excludedDialog() {
    val items = ArrayList(excluded.values).reversed()
    if (items.isEmpty()) { toast("لا توجد روابط مستبعدة"); return }
    val exHolder = arrayOfNulls<AlertDialog>(1)
    fun pick(i: Int) {
        val e = items[i]
        val acts = arrayOf("اعتباره مسجلاً وإضافته", "اعتبار كل روابط هذا الموقع مسجلة", "نسخ الرابط")
        StyledBuilder(this).setTitle("ماذا تريد؟").setItems(acts) { _, k ->
            if (k == 2) { copyText(e.u); return@setItems }
            setOvr(e.u, if (k == 1) "host" else "url", true)
            val targets = if (k == 1) items.filter { (Uri.parse(it.u).host ?: "") == (Uri.parse(e.u).host ?: "") } else listOf(e)
            for (x in targets) {
                excluded.remove(x.u)
                val base = x.meta ?: Meta(true, -1.0, -1L, false, "", 0L, emptyList())
                addFound(x.u, x.h, base.copy(vod = true, conf = 100, why = "يدوي"), x.owner, x.title)
            }
            updateCounts()
            toast("أُضيف إلى التنزيلات")
        }.show()
    }
    val exRows = items.mapIndexed { i, ex ->
        DlgStyle.thumbRow(
            this,
            if (ex.title.isNotBlank()) ex.title.take(40) else "فيديو",
            (Uri.parse(ex.u).host ?: "") + (ex.meta?.let { m -> " • " + (if (m.vod) "مسجل" else "بث") + " " + m.conf + "% • " + m.why } ?: ""),
            null
        ) { exHolder[0]?.dismiss(); pick(i) }
    }
    exHolder[0] = StyledBuilder(this).setTitle("الروابط المستبعدة (" + items.size + ")")
        .setView(DlgStyle.rowsList(this, exRows))
        .setNegativeButton("إغلاق", null).show()
}
