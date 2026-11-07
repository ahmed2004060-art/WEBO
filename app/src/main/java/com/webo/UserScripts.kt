package com.webo

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

// سكربتات JavaScript للمستخدم
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)


internal fun MainActivity.loadScripts(): org.json.JSONArray =
    try { org.json.JSONArray(prefs().getString("scripts", "[]")) } catch (e: Exception) { Diag.swallow("UserScripts.kt:19 loadScripts", e); org.json.JSONArray() }

internal fun MainActivity.saveScripts(a: org.json.JSONArray) {
    prefs().edit().putString("scripts", a.toString()).apply()
}

internal fun MainActivity.showScripts() {
    val a = loadScripts()
    if (a.length() == 0) {
        toast("لا توجد سكربتات بعد، أضف أول سكربت")
        editScript(-1)
        return
    }
    val names = Array(a.length()) { i ->
        val o = a.getJSONObject(i)
        (if (o.optBoolean("on", true)) "✔ " else "✖ ") + o.optString("name", "بدون اسم")
    }
    StyledBuilder(this)
        .setTitle("سكربتات JavaScript")
        .setItems(names) { _, i -> editScript(i) }
        .setPositiveButton("إضافة") { _, _ -> editScript(-1) }
        .setNegativeButton("إغلاق", null)
        .show()
}

internal fun MainActivity.editScript(idx: Int) {
    val a = loadScripts()
    val o = if (idx >= 0) a.getJSONObject(idx) else org.json.JSONObject()
    val lay = LinearLayout(this)
    lay.orientation = LinearLayout.VERTICAL
    lay.setPadding(dp(16), dp(8), dp(16), dp(8))
    val nameE = EditText(this)
    nameE.hint = "اسم السكربت"
    nameE.setText(o.optString("name", ""))
    val matchE = EditText(this)
    matchE.hint = "المواقع (اختياري) مثل https://*.tango.me/*"
    matchE.setText(o.optString("match", ""))
    matchE.layoutDirection = View.LAYOUT_DIRECTION_LTR
    matchE.textSize = 13f
    val codeE = EditText(this)
    codeE.hint = "كود JavaScript"
    codeE.setText(o.optString("code", ""))
    codeE.layoutDirection = View.LAYOUT_DIRECTION_LTR
    codeE.typeface = Typeface.MONOSPACE
    codeE.textSize = 12f
    codeE.minLines = 8
    codeE.gravity = Gravity.TOP or Gravity.START
    codeE.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    lay.addView(nameE)
    lay.addView(matchE)
    lay.addView(codeE)
    lay.addView(dlPill("استيراد ملف JavaScript من الجهاز", false) {
        jsImportCb = { u ->
            var fname = ""
            try {
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    val ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (ix >= 0 && c.moveToFirst()) fname = c.getString(ix) ?: ""
                }
            } catch (e: Throwable) { Diag.swallow("UserScripts.kt importJs name", e) }
            if (!(fname.endsWith(".js", true) || fname.endsWith(".mjs", true))) {
                toast("ملف غير مدعوم، اختر ملف .js فقط")
            } else {
                try {
                    val t = contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: ""
                    codeE.setText(t)
                    toast("تم استيراد: " + fname)
                } catch (e: Throwable) {
                    Diag.swallow("UserScripts.kt importJs read", e)
                    toast("تعذر قراءة الملف")
                }
            }
        }
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            startActivityForResult(i, 80)
        } catch (e: Throwable) {
            Diag.swallow("UserScripts.kt importJs picker", e)
            toast("تعذر فتح منتقي الملفات")
        }
    }, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
    val sv = ScrollView(this)
    sv.addView(lay)
    val on = o.optBoolean("on", true)
    val b = StyledBuilder(this)
        .setTitle(if (idx >= 0) "تعديل السكربت" else "سكربت جديد")
        .setView(sv)
        .setPositiveButton("حفظ") { _, _ ->
            val code = codeE.text.toString()
            if (code.isBlank()) {
                toast("الكود فاضي")
            } else {
                val n = org.json.JSONObject()
                n.put("name", nameE.text.toString().ifBlank { "سكربت" })
                n.put("match", matchE.text.toString().trim())
                n.put("code", code)
                n.put("on", on)
                if (idx >= 0) a.put(idx, n) else a.put(n)
                saveScripts(a)
                toast("تم حفظ السكربت، يعمل عند فتح الصفحة")
            }
        }
    if (idx >= 0) {
        b.setNeutralButton(if (on) "تعطيل" else "تفعيل") { _, _ ->
            o.put("on", !on)
            a.put(idx, o)
            saveScripts(a)
        }
        b.setNegativeButton("حذف") { _, _ ->
            val n = org.json.JSONArray()
            for (i in 0 until a.length()) if (i != idx) n.put(a.get(i))
            saveScripts(n)
        }
    } else {
        b.setNeutralButton("لصق من الحافظة", null)
        b.setNegativeButton("إلغاء", null)
    }
    val dlg = b.show()
    if (idx < 0) {
        dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (t.isBlank()) toast("الحافظة فاضية") else codeE.setText(t)
        }
    }
}

internal fun MainActivity.scriptMatches(match: String, code: String, url: String): Boolean {
    val pats = ArrayList<String>()
    if (match.isNotBlank()) {
        for (x in match.split(Regex("[\\s,]+"))) if (x.isNotBlank()) pats.add(x)
    } else {
        val re = Regex("^\\s*//\\s*@(?:match|include)\\s+(\\S+)", RegexOption.MULTILINE)
        for (m in re.findAll(code)) pats.add(m.groupValues[1])
    }
    if (pats.isEmpty()) return true
    for (pt in pats) {
        if (pt == "*" || pt == "<all_urls>") return true
        val rx = pt.split("*").joinToString(".*") { Regex.escape(it) }
        try {
            if (Regex(rx).matches(url)) return true
        } catch (e: Exception) { Diag.swallow("UserScripts.kt:130 scriptMatches", e);
        }
    }
    return false
}

internal fun MainActivity.runScripts(v: WebView, url: String) {
    try {
        val a = loadScripts()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (!o.optBoolean("on", true)) continue
            val code = o.optString("code", "")
            if (code.isBlank() || !scriptMatches(o.optString("match", ""), code, url)) continue
            val nm = o.optString("name", "")
            // new Function يجعل أخطاء الصياغة قابلة للالتقاط وتظهر في تبويب التنبيهات
            val js = "(function(){var n=" + org.json.JSONObject.quote(nm) + ";try{(new Function(" +
                org.json.JSONObject.quote(code) + "))();}catch(e){try{TDL.dg('UserScripts.kt:147 runScripts.js '+(e&&e.stack||e))}catch(_q){}try{TDL.err('سكربت '+n+': '+e);}catch(x){try{TDL.dg('UserScripts.kt:147 runScripts.js '+(x&&x.stack||x))}catch(_q){}}}})();"
            v.evaluateJavascript(js, null)
        }
    } catch (e: Throwable) { Diag.swallow("UserScripts.kt:150 runScripts", e);
        logErr("سكربت", "تعذر تشغيل السكربتات: " + (e.message ?: e.javaClass.simpleName), e)
    }
}
