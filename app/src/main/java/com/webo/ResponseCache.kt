package com.webo

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File

// مخزن الصور والاستجابات الخاص
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.rcKey(u: String): String {
    val d = java.security.MessageDigest.getInstance("SHA-1").digest(u.toByteArray())
    return d.joinToString("") { String.format("%02x", it) }
}

internal fun MainActivity.rcWanted(r: WebResourceRequest, low: String, accept: String): Boolean {
    if (!prefs().getBoolean("rescache", true)) return false
    if (r.method != "GET" || r.isForMainFrame) return false
    val sc = r.url.scheme ?: return false
    if (sc != "http" && sc != "https") return false
    if (hdr(r.requestHeaders, "Range") != null) return false
    val path = low.substringBefore('?').substringBefore('#')
    if (rcExt.any { path.endsWith(it) }) return true
    return accept.contains("image/") && !accept.contains("text/html")
}

internal fun MainActivity.rcResponse(ct: String, st: java.io.InputStream): WebResourceResponse {
    val hd = HashMap<String, String>()
    hd["Access-Control-Allow-Origin"] = "*"
    hd["Cache-Control"] = "max-age=86400"
    return WebResourceResponse(ct.ifEmpty { "image/jpeg" }, null, 200, "OK", hd, st)
}

// يعمل على خيط الشبكة؛ أي فشل يعيد null فيتولى المتصفح الطلب بنفسه
internal fun MainActivity.rcServe(u: String, h: Map<String, String>, stop: () -> Boolean = { false }): WebResourceResponse? {
    val key = rcKey(u)
    val bin = File(rcDir, key + ".bin")
    val meta = File(rcDir, key + ".meta")
    if (bin.exists() && meta.exists()) {
        try {
            val m = meta.readText().split("\n")
            bin.setLastModified(System.currentTimeMillis())
            return rcResponse(m[0], java.io.FileInputStream(bin))
        } catch (e: Throwable) { Diag.swallow("ResponseCache.kt:43 meta", e);
        }
    }
    return try {
        val c = conn(u, h)
        val ct = (c.contentType ?: "").substringBefore(';').trim()
        val tmp = File(rcDir, key + "_" + Thread.currentThread().id + ".tmp")
        var size = 0L
        c.inputStream.use { i ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(16384)
                while (true) {
                    if (stop()) break
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    size += n
                }
            }
        }
        if (stop()) {
            tmp.delete()
            return null
        }
        val okType = ct.startsWith("image/") || ct.startsWith("font/") || ct.contains("font")
        if (okType && size > 0) {
            meta.writeText(ct + "\n" + System.currentTimeMillis())
            bin.delete()
            if (tmp.renameTo(bin)) {
                if (rcWrites.incrementAndGet() % 40 == 0) rcTrim()
                return rcResponse(ct, java.io.FileInputStream(bin))
            }
        }
        val st = java.io.FileInputStream(tmp)
        tmp.delete()
        rcResponse(ct, st)
    } catch (e: Throwable) { Diag.swallow("ResponseCache.kt:79 rcServe", e);
        null
    }
}

internal fun MainActivity.rcLocal(u: String): Pair<File, String>? {
    return try {
        if (!prefs().getBoolean("rescache", true)) return null
        val key = rcKey(u)
        val bin = File(rcDir, key + ".bin")
        val meta = File(rcDir, key + ".meta")
        if (!bin.exists() || !meta.exists()) return null
        Pair(bin, meta.readText().split("\n")[0])
    } catch (e: Throwable) { Diag.swallow("ResponseCache.kt:92 rcLocal", e);
        null
    }
}

internal fun MainActivity.rcTrim() {
    try {
        val maxB = prefs().getInt("rcmb", 0) * 1048576L
        if (maxB <= 0) return
        val fs = rcDir.listFiles() ?: return
        var total = 0L
        for (f in fs) total += f.length()
        if (total <= maxB) return
        val bins = fs.filter { it.name.endsWith(".bin") }.sortedBy { it.lastModified() }
        for (b in bins) {
            if (total <= maxB * 8 / 10) break
            val m = File(rcDir, b.name.removeSuffix(".bin") + ".meta")
            total -= b.length() + m.length()
            b.delete()
            m.delete()
        }
    } catch (e: Throwable) { Diag.swallow("ResponseCache.kt:113 rcTrim", e);
    }
}

internal fun MainActivity.rcSizeMb(): Long {
    var total = 0L
    try { rcDir.listFiles()?.forEach { total += it.length() } } catch (e: Throwable) { Diag.swallow("ResponseCache.kt:119 rcSizeMb", e);}
    return total / 1048576
}

internal fun MainActivity.rcClear() {
    try { rcDir.listFiles()?.forEach { it.delete() } } catch (e: Throwable) { Diag.swallow("ResponseCache.kt:124 rcClear", e);}
    toast("تم مسح مخزن الصور")
}
