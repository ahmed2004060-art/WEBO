package com.webo

import android.net.Uri
import android.view.View
import java.io.File

// حفظ مهام التنزيل واستعادتها
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.engineFor(prot: Int, fmt: String): Int {
    val kind = when (fmt) {
        "HLS/M3U8" -> "HLS"
        "DASH/MPD" -> "DASH"
        else -> "FILE"
    }
    return PlayerCore.choose(PlayerCore.Probe(kind, prot, false, null, ""), fmt.lowercase(java.util.Locale.US))
}

// يحفظ حالة المهمة الآن (يُستدعى من الخيط الرئيسي)
internal fun MainActivity.saveTask(r: Row) {
    if (r.quiet || r.removed || r.id.isEmpty()) return
    // الملتقط الذي لم يُبدأ تحميله لا يُحفظ أبداً
    if (isCapOnly(r)) return
    val c = cands[r.url] ?: return
    val m = c.meta
    val mj = if (m != null) TaskStore.metaToJson(m.vod, m.dur, m.size, m.sizeEst, m.res, m.kbps, m.subs, m.conf, m.why) else null
    val rec = TaskRec(
        r.id, r.url, foundHdr[r.url] ?: emptyMap(), c.pageTitle, c.hostTxt, mj,
        r.state, r.bar.progress, r.bar.max, r.ctl.bytes.get(), r.msg.text.toString(),
        r.kindProt, r.kindFmt, r.engine, r.finalLoc, r.offlineId, r.protectedStream,
        r.watch?.visibility == View.VISIBLE, foundName[r.url], foundMime[r.url], r.created, System.currentTimeMillis(), r.pageUrl
    )
    try { taskIo.execute { TaskStore.save(this, rec) } } catch (e: Throwable) { Diag.swallow("TaskPersistence.kt:31 saveTask", e);}
    if (r.thumbSet && !r.thumbSaved) {
        val b = synchronized(videoThumbs) { videoThumbs[r.url] }
        if (b != null) {
            r.thumbSaved = true
            val id = r.id
            try { taskIo.execute { TaskStore.saveThumb(this, id, b) } } catch (e: Throwable) { Diag.swallow("TaskPersistence.kt saveTask thumb", e);}
        }
    }
}

internal fun MainActivity.forgetTask(id: String) {
    if (id.isEmpty()) return
    try { taskIo.execute { TaskStore.delete(this, id) } } catch (e: Throwable) { Diag.swallow("TaskPersistence.kt:36 forgetTask", e);}
}

internal fun MainActivity.metaFromJson(o: org.json.JSONObject?): Meta? {
    if (o == null) return null
    val subs = ArrayList<String>()
    val a = o.optJSONArray("subs")
    if (a != null) for (i in 0 until a.length()) subs.add(a.optString(i))
    return Meta(o.optBoolean("vod", true), o.optDouble("dur", -1.0), o.optLong("size"), o.optBoolean("sizeEst"),
        o.optString("res"), o.optLong("kbps"), subs, o.optInt("conf", 100), o.optString("why"))
}

// يقرأ المهام المحفوظة في الخلفية ثم يعيدها إلى القائمة بحالاتها
internal fun MainActivity.restoreTasks() {
    diagThread("TaskPersistence:50") {
        val all = try { TaskStore.load(this) } catch (e: Throwable) { Diag.swallow("TaskPersistence.kt:51 restoreTasks", e); emptyList<TaskRec>() }
        // الملتقط القديم (غير المبدوء تحميله) يُحذف ولا يُستعاد؛ تُستعاد فقط التنزيلات المستأنفة والمكتملة
        for (x in all) if (x.state == 0) TaskStore.delete(this, x.id)
        val recs = all.filter { it.state != 0 }
        ui.post { pendingRecs = recs; if (page == 1) restoreSavedNow() }
    }.start()
}

// تُستدعى عند فتح صفحة التنزيلات فقط
internal fun MainActivity.restoreSavedNow() {
    val recs = pendingRecs ?: return
    pendingRecs = null
    for (rec in recs) try {
        restoreOne(rec)
    } catch (e: Throwable) { Diag.swallow("TaskPersistence.kt restoreSavedNow", e); logErr("استعادة", "تعذر استعادة مهمة: " + (e.message ?: e.javaClass.simpleName), e) }
}

internal fun MainActivity.finalExists(loc: String): Boolean = try {
    if (loc.startsWith("content:")) {
        contentResolver.openFileDescriptor(Uri.parse(loc), "r")?.use { true } ?: false
    } else File(loc).exists()
} catch (e: Throwable) { Diag.swallow("TaskPersistence.kt:60 finalExists", e);
    false
}

internal fun MainActivity.restoreOne(rec: TaskRec) {
    val u = rec.url
    if (found.contains(u)) return
    rec.fname?.let { foundName[u] = it }
    rec.mime?.let { foundMime[u] = it }
    addFound(u, rec.hdr, metaFromJson(rec.meta), null, rec.pageTitle, rec)
    val c = cands[u] ?: return
    val r = c.rw
    r.quiet = true
    r.created = rec.created
    rec.thumbBmp?.let { setThumb(r, it); r.thumbSaved = true }
    if (rec.kindFmt.isNotEmpty()) setKind(r, rec.kindProt, rec.kindFmt)
    r.engine = if (rec.engine != 0) rec.engine else if (rec.kindFmt.isNotEmpty()) engineFor(rec.kindProt, rec.kindFmt) else 0
    var st = rec.state
    var msg = rec.msg
    when (st) {
        3 -> {
            val oid = rec.offlineId
            val fl = rec.finalLoc
            if (oid != null && OfflineDrm.get(this, oid) != null) {
                r.offlineId = oid
            } else if (oid == null && fl != null && finalExists(fl)) {
                r.finalLoc = fl
                r.finalIsUri = fl.startsWith("content:")
            } else {
                st = 4
                msg = "الملف المحفوظ لم يعد موجوداً. اضغط «تحميل» لإعادة التنزيل."
            }
        }
        // المتوقف مؤقتاً والمستأنف: تبقى حالتهما ورسالتهما كما حُفظتا دون إعادة تنشيط
        1, 2 -> r.started = true
    }
    if (rec.protectedStream && st == 4) {
        r.protectedStream = true
        r.srcUrl = u
        r.srcHdr = rec.hdr
    }
    if (st == 1 || st == 2 || st == 3 || st == 4) {
        r.msg.text = msg
        if (rec.total > 0) {
            r.bar.max = rec.total
            r.bar.progress = if (st == 3) rec.total else rec.done
            r.bar.visibility = View.VISIBLE
        }
    }
    if (st == 3) {
        r.btn.isEnabled = false
        r.btn.alpha = 0.5f
    }
    // البيانات الخام المشفّرة تبقى مكتملة لكن زر المشاهدة مخفي كما كان قبل الإغلاق
    r.watch?.visibility = if ((st == 3 && rec.watchOk && (r.finalLoc != null || r.offlineId != null)) || (st == 4 && r.protectedStream)) View.VISIBLE else View.GONE
    val wk = File(getExternalFilesDir(null) ?: filesDir, "work_" + r.id)
    if (st == 4 && wk.exists()) {
        r.work = wk
        r.watch?.visibility = View.VISIBLE
    }
    setState(r, st)
    r.quiet = false
    saveTask(r)
    // مهمة لم تُفحص صيغتها قبل الإغلاق: نفحصها الآن
    if (rec.kindFmt.isEmpty()) classifyCand(c, rec.hdr)
}
