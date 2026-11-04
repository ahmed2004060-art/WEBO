package com.webo

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

// البحث داخل الصفحة
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)


internal fun MainActivity.buildFindBar(): View {
    findBar = LinearLayout(this)
    findBar.orientation = LinearLayout.HORIZONTAL
    findBar.gravity = Gravity.CENTER_VERTICAL
    findBar.setBackgroundColor(cBar)
    findBar.setPadding(dp(8), dp(4), dp(8), dp(4))
    findBar.visibility = View.GONE
    findBox = EditText(this)
    findBox.setTextColor(cText)
    findBox.setHintTextColor(cMuted)
    findBox.hint = "بحث داخل الصفحة"
    findBox.textSize = 14f
    findBox.setSingleLine()
    findBox.background = bg(cCard, 18, cLine)
    findBox.setPadding(dp(12), dp(6), dp(12), dp(6))
    findBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
    findBox.setOnEditorActionListener { _, _, _ ->
        cur?.web?.findNext(true)
        true
    }
    findBox.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val q = s?.toString() ?: ""
            val w = cur?.web
            if (q.isEmpty()) {
                w?.clearMatches()
                findCount.text = ""
            } else w?.findAllAsync(q)
        }
    })
    findCount = tv("", 12f, cMuted)
    findCount.minWidth = dp(34)
    findCount.gravity = Gravity.CENTER
    val up = pill("▲", false) { cur?.web?.findNext(false) }
    val dn = pill("▼", false) { cur?.web?.findNext(true) }
    up.setPadding(dp(10), dp(6), dp(10), dp(6))
    dn.setPadding(dp(10), dp(6), dp(10), dp(6))
    findBar.addView(findBox, lp(0, WRAP, 1f))
    findBar.addView(findCount)
    findBar.addView(up, lp(WRAP, WRAP, 0f, 2, 0, 2, 0))
    findBar.addView(dn, lp(WRAP, WRAP, 0f, 2, 0, 2, 0))
    findBar.addView(iconBtn(R.drawable.ic_close) { closeFind() }, lp(dp(36), dp(36)))
    return findBar
}

internal fun MainActivity.openFind() {
    showPage(0)
    findBar.visibility = View.VISIBLE
    findBox.requestFocus()
    findBox.postDelayed({
        val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        im.showSoftInput(findBox, 0)
    }, 150)
}

internal fun MainActivity.closeFind() {
    findBox.setText("")
    findCount.text = ""
    cur?.web?.clearMatches()
    findBar.visibility = View.GONE
    hideKb()
}
