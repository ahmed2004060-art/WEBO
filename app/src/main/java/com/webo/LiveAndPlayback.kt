package com.webo

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import java.io.File
import java.io.FileOutputStream

// البث المباشر والمعاينة والتشغيل
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

internal fun MainActivity.playOffline(id: String) {
    val i = Intent(this, VideoPlayerActivity::class.java)
    i.putExtra(VideoPlayerActivity.X_OFFLINE, id)
    try {
        startActivity(i)
    } catch (e: Throwable) { Diag.swallow("LiveAndPlayback.kt:18 playOffline", e);
        toast("تعذر فتح المشغّل")
    }
}

// إدارة المحتوى المحمي المحفوظ: عرض حالة الترخيص، تجديده، التشغيل، الحذف
internal fun MainActivity.showOfflineManager() {
    val items = OfflineDrm.list(this)
    if (items.isEmpty()) {
        toast("لا توجد فيديوهات محمية محفوظة")
        return
    }
    toast("جاري قراءة حالة التراخيص...")
    previewPool.execute {
        val st = items.map { OfflineDrm.remainingText(this, it) }
        ui.post {
            val ofHolder = arrayOfNulls<AlertDialog>(1)
            val ofRows = items.mapIndexed { i, it ->
                DlgStyle.thumbRow(
                    this,
                    if (it.name.isBlank()) "فيديو محمي" else it.name,
                    fmtSize(it.bytes.toDouble()) + "  •  الترخيص: " + st[i],
                    null
                ) { ofHolder[0]?.dismiss(); offlineItemDialog(items[i], st[i]) }
            }
            ofHolder[0] = StyledBuilder(this)
                .setTitle("المحمية — دون اتصال")
                .setView(DlgStyle.rowsList(this, ofRows))
                .setNegativeButton("إغلاق", null)
                .show()
        }
    }
}

internal fun MainActivity.offlineItemDialog(it: OfflineDrm.Rec, status: String) {
    val msg = "هذا المحتوى محفوظ مشفّراً كما أرسلته المنصة، ولا يمكن فتحه خارج التطبيق.\n\n" +
        "• يعمل داخل مشغّل التطبيق فقط وبترخيص صالح.\n" +
        "• حالة الترخيص الآن: " + status + "\n" +
        "• عند انتهاء الترخيص يلزم اتصال وقبول من المنصة لتجديده، وإلا لا يعمل."
    StyledBuilder(this)
        .setTitle(if (it.name.isBlank()) "فيديو محمي" else it.name)
        .setView(DlgStyle.bulletText(this, msg))
        .setPositiveButton("تشغيل") { _, _ -> playOffline(it.id) }
        .setNeutralButton("تجديد الترخيص") { _, _ ->
            toast("جاري طلب التجديد من المنصة...")
            previewPool.execute {
                val err = try {
                    OfflineDrm.renew(this, it.id)
                    null
                } catch (e: Throwable) { Diag.swallow("LiveAndPlayback.kt:67 offlineItemDialog", e);
                    e.message ?: "تعذر التجديد"
                }
                ui.post { toast(err ?: "تم تجديد الترخيص") }
            }
        }
        .setNegativeButton("حذف") { _, _ ->
            previewPool.execute {
                OfflineDrm.delete(this, it.id)
                ui.post {
                    for (rw in allRows()) if (rw.offlineId == it.id) {
                        rw.offlineId = null
                        rw.msg.text = "حُذف المحتوى المحمي"
                        rw.watch?.visibility = View.GONE
                        setState(rw, 4)
                    }
                    toast("تم الحذف")
                }
            }
        }
        .show()
}

internal fun MainActivity.playLive(workPath: String) {
    val i = Intent(this, VideoPlayerActivity::class.java)
    i.putExtra(VideoPlayerActivity.X_LIVE, workPath)
    try {
        startActivity(i)
    } catch (e: Throwable) { Diag.swallow("LiveAndPlayback.kt:95 playLive", e);
        toast("تعذر فتح المشغّل")
    }
}

internal fun MainActivity.copyRange(f: File, from: Long, count: Long, o: java.io.OutputStream) {
    java.io.FileInputStream(f).use { i ->
        i.channel.position(from)
        val buf = ByteArray(1 shl 16)
        var left = count
        while (left > 0) {
            val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            o.write(buf, 0, n)
            left -= n
        }
    }
}

internal fun MainActivity.countSegs(seg: (Int) -> File): Int {
    var k = 0
    while (true) {
        val f = seg(k)
        if (f.exists() && f.length() > 0) k++ else break
    }
    return k
}

// يضيف الأجزاء المتتالية الجديدة فقط (من inc.next حتى limit) إلى نهاية ملف المعاينة
internal fun MainActivity.appendSegs(inc: Inc, out: File, ini: File?, seg: (Int) -> File, limit: Int): Boolean {
    if (inc.hdr && !out.exists()) { inc.hdr = false; inc.copied = 0L; inc.next = 0 }
    if (!inc.hdr) {
        out.delete()
        out.outputStream().buffered().use { o ->
            if (ini != null && ini.exists() && ini.length() > 0) {
                ini.inputStream().use { it.copyTo(o) }
            }
            o.flush()
        }
        inc.hdr = true
        inc.next = 0
    }
    var k = inc.next
    if (k < limit) {
        java.io.FileOutputStream(out, true).buffered().use { o ->
            while (k < limit) {
                val f = seg(k)
                if (f.exists() && f.length() > 0) { f.inputStream().use { it.copyTo(o) }; k++ } else break
            }
        }
        inc.next = k
    }
    return inc.next > 0
}

// تنزيل مباشر لملف mp4: يكتب ftyp + moov المعدّل مرة واحدة ثم يضيف بايتات mdat الجديدة فقط
internal fun MainActivity.appendDirect(inc: Inc, out: File, dir: File): Boolean {
    if (inc.hdr && !out.exists()) { inc.hdr = false; inc.copied = 0L; inc.next = 0 }
    val mf = File(dir, "moovmeta.txt")
    val mb = File(dir, "moov.bin")
    if (!(mf.exists() && mb.exists())) {
        val c0 = File(dir, "chunk_0.part")
        val src = if (c0.exists() && c0.length() > 0) c0
        else dir.listFiles()?.filter { it.name.endsWith(".mp4") && it.length() > 0 }?.maxByOrNull { it.length() }
        if (src == null) return false
        if (out.length() != src.length()) src.copyTo(out, true)
        return out.length() > 0
    }
    val meta = mf.readText().split(",")
    val mdatStart = meta[0].toLong()
    val n = meta[1].toInt()
    val chunk = meta[2].toLong()
    val files = ArrayList<File>()
    val lens = ArrayList<Long>()
    var avail = 0L
    for (k in 0 until n) {
        val f = File(dir, "chunk_" + k + ".part")
        if (!f.exists()) break
        val l = f.length()
        files.add(f)
        lens.add(l)
        avail += l
        if (l < chunk) break
    }
    if (!inc.hdr) {
        if (avail <= mdatStart + 64) return false
        val moov = mb.readBytes()
        val ftyp = File(dir, "ftyp.bin").readBytes()
        patchMoov(moov, (ftyp.size + moov.size).toLong() - mdatStart)
        out.delete()
        out.outputStream().buffered().use { o -> o.write(ftyp); o.write(moov) }
        inc.hdr = true
        inc.copied = mdatStart
    }
    if (avail > inc.copied) {
        java.io.FileOutputStream(out, true).buffered().use { o ->
            var pos = 0L
            for (i in files.indices) {
                val len = lens[i]
                val st = maxOf(pos, inc.copied)
                val en = pos + len
                if (en > st) copyRange(files[i], st - pos, en - st, o)
                pos += len
            }
        }
        inc.copied = avail
    }
    return true
}

// يُستدعى من المشغّل المدمج على خيط خلفي: يحدّث ملف المعاينة بالبيانات الجديدة فقط، أو يعيد الملف النهائي إن اكتمل
internal fun MainActivity.liveSnapshot(key: String, lastSig: Long): PlayerCore.Live {
    val r = liveRows[key] ?: return PlayerCore.Live(null, false, true, 0L, "")
    val fl = r.finalLoc
    if (fl != null) {
        liveRows.remove(key)
        liveB.remove(key)
        return PlayerCore.Live(fl, fl.startsWith("content:"), true, 0L, "")
    }
    val w = r.work ?: return PlayerCore.Live(null, false, true, 0L, "")
    if (!w.exists()) return PlayerCore.Live(null, false, true, 0L, "")
    // حالة 4 = أُلغي أو فشل: لا بيانات جديدة بعد الآن
    val over = r.state == 4
    var sig = 0L
    try {
        w.walkTopDown().forEach { f -> if (f.isFile && f.parentFile?.name != "preview") sig += f.length() }
    } catch (e: Throwable) { Diag.swallow("LiveAndPlayback.kt:221 liveSnapshot", e);}
    if (sig == lastSig) return PlayerCore.Live(null, false, over, sig, "")
    val b = liveB.getOrPut(key) {
        val d = File(cacheDir, "live_preview/" + TaskStore.id(key))
        d.mkdirs()
        LiveB(d)
    }
    b.dir.mkdirs()
    var vOut: File? = null
    var aOut: File? = null
    try {
        if (File(w, "dv_00000.seg").exists()) {
            // DASH بأجزاء: صورة وصوت في ملفين، يجمعهما المشغّل معاً بلا دمج ولا إعادة نسخ
            val vc = countSegs { File(w, "dv_%05d.seg".format(it)) }
            val ac = countSegs { File(w, "da_%05d.seg".format(it)) }
            val n = if (ac > 0) minOf(vc, ac) else vc
            val vo = File(b.dir, "live_v.mp4")
            if (appendSegs(b.v, vo, File(w, "dv_init.mp4"), { File(w, "dv_%05d.seg".format(it)) }, n)) vOut = vo
            if (vOut != null && ac > 0 && n > 0) {
                val ao = File(b.dir, "live_a.mp4")
                if (appendSegs(b.a, ao, File(w, "da_init.mp4"), { File(w, "da_%05d.seg".format(it)) }, n)) aOut = ao
            }
        } else if (File(w, "dv").isDirectory) {
            val vo = File(b.dir, "live_v.mp4")
            if (appendDirect(b.v, vo, File(w, "dv"))) vOut = vo
            if (vOut != null && File(w, "da").isDirectory) {
                val ao = File(b.dir, "live_a.mp4")
                if (appendDirect(b.a, ao, File(w, "da"))) aOut = ao
            }
        } else if (File(w, "part_00000.seg").exists()) {
            val fam = File(w, "fam_mp4").exists()
            val ini = File(w, "init.mp4").takeIf { it.exists() && it.length() > 0 }
            if (fam && ini == null) {
                // أجزاء mp4 مستقلة تحتاج دمجاً فعلياً: لا مفر من إعادة البناء
                val pv = buildPreview(w)
                if (pv != null && pv.exists() && pv.length() > 0) {
                    val vo = File(b.dir, "live_v.mp4")
                    if (pv.parentFile?.name == "preview" && pv.renameTo(vo)) vOut = vo
                    else { pv.copyTo(vo, true); vOut = vo }
                }
            } else {
                val vo = File(b.dir, if (fam) "live_v.mp4" else "live_v.ts")
                val n = countSegs { File(w, "part_%05d.seg".format(it)) }
                if (appendSegs(b.v, vo, ini, { File(w, "part_%05d.seg".format(it)) }, n)) vOut = vo
            }
        } else {
            val vo = File(b.dir, "live_v.mp4")
            if (appendDirect(b.v, vo, w)) vOut = vo
        }
    } catch (e: Throwable) { Diag.swallow("LiveAndPlayback.kt:270 liveSnapshot", e);
        logErr("مشاهدة", "تعذر تحديث المعاينة الحية: " + (e.message ?: e.javaClass.simpleName), e)
    }
    val vf = vOut
    if (vf == null || !vf.exists() || vf.length() == 0L) return PlayerCore.Live(null, false, over, sig, "")
    return PlayerCore.Live(vf.absolutePath, false, over, sig, "", aOut?.absolutePath)
}

// يجمع الأجزاء المتتالية من البداية (بدون ثغرات) في ملف قابل للتشغيل
internal fun MainActivity.buildPreview(work: File): File? {
    val pdir0 = File(work, "preview")
    // DASH: مسار الصورة فقط (بدون صوت) من الأجزاء المتتالية
    if (File(work, "dv_00000.seg").exists()) {
        val dp = ArrayList<File>()
        var k = 0
        while (true) {
            val f = File(work, "dv_%05d.seg".format(k))
            if (f.exists() && f.length() > 0) { dp.add(f); k++ } else break
        }
        if (dp.isEmpty()) return null
        val di = File(work, "dv_init.mp4").takeIf { it.exists() && it.length() > 0 }
        pdir0.deleteRecursively()
        pdir0.mkdirs()
        val dout = File(pdir0, "preview.mp4")
        dout.outputStream().buffered().use { o ->
            if (di != null) di.inputStream().use { it.copyTo(o) }
            for (p in dp) p.inputStream().use { it.copyTo(o) }
        }
        return dout
    }
    if (File(work, "dv").isDirectory) {
        pdir0.deleteRecursively()
        return previewDirect(File(work, "dv"), pdir0)
    }
    val first = File(work, "part_00000.seg")
    if (!first.exists()) {
        // تحميل مباشر: يعمل حتى لو كان moov في آخر الملف
        pdir0.deleteRecursively()
        return previewDirect(work, pdir0)
    }
    val parts = ArrayList<File>()
    var i = 0
    while (true) {
        val f = File(work, "part_%05d.seg".format(i))
        if (f.exists() && f.length() > 0) { parts.add(f); i++ } else break
    }
    if (parts.isEmpty()) return null
    val ini = File(work, "init.mp4").takeIf { it.exists() && it.length() > 0 }
    val fam = File(work, "fam_mp4").exists()
    val pdir = File(work, "preview")
    pdir.deleteRecursively()
    pdir.mkdirs()
    if (fam && ini == null) {
        // أجزاء mp4 مستقلة: ندمجها فعلياً
        return merge(HlsJob(parts, null, true), pdir, "preview")
    }
    val out = File(pdir, if (fam) "preview.mp4" else "preview.ts")
    out.outputStream().buffered().use { o ->
        if (ini != null) ini.inputStream().use { it.copyTo(o) }
        for (p in parts) p.inputStream().use { it.copyTo(o) }
    }
    return out
}

// التشغيل عبر المشغّل المدمج (Media3) المخصص لتبويب التنزيلات
internal fun MainActivity.playVideo(src: String, isUri: Boolean, engine: Int = 0) {
    val i = Intent(this, VideoPlayerActivity::class.java)
    if (engine != 0) i.putExtra(VideoPlayerActivity.X_ENGINE, engine)
    i.putExtra(VideoPlayerActivity.X_SRC, src)
    i.putExtra(VideoPlayerActivity.X_URI, isUri)
    try {
        startActivity(i)
    } catch (e: Throwable) { Diag.swallow("LiveAndPlayback.kt:342 playVideo", e);
        toast("تعذر فتح المشغّل")
    }
}

// تشغيل مباشر من الرابط (للفيديو المحمي) بنفس ترويسات الالتقاط
internal fun MainActivity.playStream(u: String, h: Map<String, String>) {
    val i = Intent(this, VideoPlayerActivity::class.java)
    i.putExtra(VideoPlayerActivity.X_SRC, u)
    i.putExtra(VideoPlayerActivity.X_REMOTE, true)
    i.putExtra(VideoPlayerActivity.X_KIND, if (isHls(u)) "HLS" else if (isDash(u)) "DASH" else "")
    val b = Bundle()
    for ((k, v) in h) b.putString(k, v)
    i.putExtra(VideoPlayerActivity.X_HDR, b)
    try {
        startActivity(i)
    } catch (e: Throwable) { Diag.swallow("LiveAndPlayback.kt:358 playStream", e);
        toast("تعذر فتح المشغّل")
    }
}
