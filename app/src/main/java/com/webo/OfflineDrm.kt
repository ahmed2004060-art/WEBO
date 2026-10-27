package com.webo

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

// الحفظ المحمي للاستخدام دون اتصال (البند ٦):
// - الترخيص يُطلب من خادم المنصة نفسها بالطريقة الرسمية (ترخيص دائم لنظام Widevine). إن رفضته المنصة لا يُحفظ شيء.
// - الأجزاء تُحفظ كما هي مشفّرة داخل مجلد التطبيق الخاص، ولا يُفك تشفيرها ولا تُحوَّل إلى ملف فيديو عادي.
// - التشغيل لا يتم إلا داخل مشغّل التطبيق وبترخيص صالح.
object OfflineDrm {
    class Rec(
        val id: String,
        val name: String,
        val url: String,
        val licenseUrl: String,
        val keySetId: String,
        val hdr: Map<String, String>,
        val savedAt: Long,
        val bytes: Long
    )

    private val lock = Any()
    private val mainH = Handler(Looper.getMainLooper())

    @Volatile private var cacheI: SimpleCache? = null
    @Volatile private var dbI: StandaloneDatabaseProvider? = null
    @Volatile private var dmI: DownloadManager? = null
    @Volatile private var curHdr: Map<String, String> = emptyMap()

    private fun root(ctx: Context): File {
        val d = File(ctx.applicationContext.filesDir, "offline_drm")
        d.mkdirs()
        return d
    }

    private fun db(ctx: Context): StandaloneDatabaseProvider = synchronized(lock) {
        dbI ?: StandaloneDatabaseProvider(ctx.applicationContext).also { dbI = it }
    }

    fun cache(ctx: Context): SimpleCache = synchronized(lock) {
        cacheI ?: SimpleCache(File(root(ctx), "cache"), NoOpCacheEvictor(), db(ctx)).also { cacheI = it }
    }

    // يشغّل الدالة على الخيط الرئيسي وينتظر نتيجتها (مدير التنزيل يُنشأ ويُدار من خيط التطبيق)
    private fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val latch = CountDownLatch(1)
        var res: Any? = null
        var err: Throwable? = null
        mainH.post {
            try {
                res = f()
            } catch (e: Throwable) {
                err = e
            }
            latch.countDown()
        }
        latch.await()
        val e = err
        if (e != null) throw e
        @Suppress("UNCHECKED_CAST")
        return res as T
    }

    fun http(h: Map<String, String>): DefaultHttpDataSource.Factory {
        val f = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val ua = h["User-Agent"]
        if (!ua.isNullOrBlank()) f.setUserAgent(ua)
        val rest = HashMap<String, String>()
        for ((k, v) in h) if (k != "User-Agent" && v.isNotBlank()) rest[k] = v
        f.setDefaultRequestProperties(rest)
        return f
    }

    private fun manager(ctx: Context): DownloadManager {
        dmI?.let { return it }
        return onMain {
            dmI ?: DownloadManager(
                ctx.applicationContext,
                db(ctx),
                cache(ctx),
                DataSource.Factory { http(curHdr).createDataSource() },
                Executors.newFixedThreadPool(4)
            ).also {
                it.resumeDownloads()
                dmI = it
            }
        }
    }

    // مصدر بيانات التشغيل: من المخزن المحلي فقط ولا يتصل بالشبكة أبداً
    fun playbackFactory(ctx: Context): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(ctx))
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)

    // ---------- سجل العناصر المحفوظة ----------

    private fun indexFile(ctx: Context) = File(root(ctx), "index.json")

    private fun readAll(ctx: Context): MutableList<Rec> = synchronized(lock) {
        val out = ArrayList<Rec>()
        try {
            val f = indexFile(ctx)
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val hj = o.optJSONObject("hdr")
                    val hm = HashMap<String, String>()
                    if (hj != null) for (k in hj.keys()) hm[k] = hj.optString(k)
                    out.add(
                        Rec(
                            o.getString("id"), o.optString("name"), o.getString("url"), o.getString("licenseUrl"),
                            o.getString("keySetId"), hm, o.optLong("savedAt"), o.optLong("bytes")
                        )
                    )
                }
            }
        } catch (e: Throwable) {
        }
        out
    }

    private fun writeAll(ctx: Context, list: List<Rec>) = synchronized(lock) {
        val a = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("name", r.name)
            o.put("url", r.url)
            o.put("licenseUrl", r.licenseUrl)
            o.put("keySetId", r.keySetId)
            val hj = JSONObject()
            for ((k, v) in r.hdr) hj.put(k, v)
            o.put("hdr", hj)
            o.put("savedAt", r.savedAt)
            o.put("bytes", r.bytes)
            a.put(o)
        }
        val f = indexFile(ctx)
        val tmp = File(f.parentFile, "index.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(a.toString())
            tmp.delete()
        }
    }

    fun list(ctx: Context): List<Rec> = readAll(ctx).sortedByDescending { it.savedAt }

    fun get(ctx: Context, id: String): Rec? = readAll(ctx).firstOrNull { it.id == id }

    private fun put(ctx: Context, r: Rec) = synchronized(lock) {
        val l = readAll(ctx)
        l.removeAll { it.id == r.id }
        l.add(r)
        writeAll(ctx, l)
    }

    fun idOf(url: String): String = "off_" + TaskStore.id(url)

    // ---------- الترخيص ----------

    private fun helper(licenseUrl: String, h: Map<String, String>): OfflineLicenseHelper =
        OfflineLicenseHelper.newWidevineInstance(licenseUrl, true, http(h), DrmSessionEventListener.EventDispatcher())

    private fun drmFormat(ds: DataSource, url: String): androidx.media3.common.Format {
        val mf = DashUtil.loadManifest(ds, Uri.parse(url))
        for (pi in 0 until mf.periodCount) {
            val f = DashUtil.loadFormatWithDrmInitData(ds, mf.getPeriod(pi))
            if (f != null && f.drmInitData != null) return f
        }
        throw Exception("لم يعلن الملف بيانات الحماية اللازمة لطلب الترخيص")
    }

    private fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private fun why(e: Throwable): String {
        var c: Throwable? = e
        var m = ""
        while (c != null) {
            val x = c.message
            if (!x.isNullOrBlank()) m = x
            c = c.cause
        }
        return m.ifBlank { e.javaClass.simpleName }
    }

    // الزمن المتبقي للترخيص المحفوظ بصيغة مفهومة (يُستدعى على خيط خلفي)
    fun remainingText(ctx: Context, r: Rec): String {
        var h: OfflineLicenseHelper? = null
        return try {
            h = helper(r.licenseUrl, r.hdr)
            val p = h.getLicenseDurationRemainingSec(dec(r.keySetId))
            val lic = p.first
            val play = p.second
            val sec = if (lic < play) lic else play
            when {
                sec <= 0L -> "منتهٍ"
                sec > 3650L * 86400L -> "بلا حد معلن"
                sec < 3600L -> (sec / 60 + 1).toString() + " دقيقة"
                sec < 86400L -> (sec / 3600).toString() + " ساعة"
                else -> (sec / 86400).toString() + " يوم"
            }
        } catch (e: Throwable) {
            "غير معروف"
        } finally {
            try { h?.release() } catch (e: Throwable) {}
        }
    }

    // تجديد الترخيص من خادم المنصة نفسها. إن رفضت المنصة فلا بديل، ويبقى العنصر غير قابل للتشغيل حتى يُقبل التجديد
    fun renew(ctx: Context, id: String): Rec {
        val r = get(ctx, id) ?: throw Exception("العنصر غير موجود")
        var h: OfflineLicenseHelper? = null
        try {
            h = helper(r.licenseUrl, r.hdr)
            val ks = try {
                h.renewLicense(dec(r.keySetId))
            } catch (e: Throwable) {
                h.downloadLicense(drmFormat(http(r.hdr).createDataSource(), r.url))
            }
            val n = Rec(r.id, r.name, r.url, r.licenseUrl, enc(ks), r.hdr, r.savedAt, r.bytes)
            put(ctx, n)
            return n
        } catch (e: Throwable) {
            throw Exception("رفضت المنصة تجديد الترخيص أو تعذّر الوصول إليها: " + why(e))
        } finally {
            try { h?.release() } catch (e: Throwable) {}
        }
    }

    // حذف العنصر وبياناته المشفّرة وترخيصه
    fun delete(ctx: Context, id: String) {
        val r = get(ctx, id)
        try {
            val dm = manager(ctx)
            onMain { dm.removeDownload(id) }
        } catch (e: Throwable) {
        }
        if (r != null) {
            var h: OfflineLicenseHelper? = null
            try {
                h = helper(r.licenseUrl, r.hdr)
                h.releaseLicense(dec(r.keySetId))
            } catch (e: Throwable) {
            } finally {
                try { h?.release() } catch (e: Throwable) {}
            }
        }
        synchronized(lock) {
            val l = readAll(ctx)
            l.removeAll { it.id == id }
            writeAll(ctx, l)
        }
    }

    // ---------- الاختيار والتنزيل ----------

    // يختار مساراً واحداً للصورة (الأعلى أو الأدنى في وضع التوفير، مع تفضيل H.264 للتوافق) ومساراً للصوت في كل فترة
    private fun pickKeys(m: DashManifest, low: Boolean): List<StreamKey> {
        val keys = ArrayList<StreamKey>()
        for (pi in 0 until m.periodCount) {
            val p = m.getPeriod(pi)
            var vDone = false
            var aDone = false
            for (ai in p.adaptationSets.indices) {
                val a = p.adaptationSets[ai]
                val isV = a.type == C.TRACK_TYPE_VIDEO
                val isA = a.type == C.TRACK_TYPE_AUDIO
                if (!isV && !isA) continue
                if ((isV && vDone) || (isA && aDone)) continue
                val reps = a.representations
                if (reps.isEmpty()) continue
                var best = 0
                for (ri in reps.indices) {
                    val f = reps[ri].format
                    val b = reps[best].format
                    val better = if (low) f.bitrate < b.bitrate else f.bitrate > b.bitrate
                    if (better) best = ri
                }
                if (isV && !low) {
                    val bf = reps[best].format
                    val avc = { c: String? -> c == null || c.lowercase(Locale.US).startsWith("avc") }
                    if (!avc(bf.codecs)) {
                        var alt = -1
                        for (ri in reps.indices) {
                            val f = reps[ri].format
                            if (avc(f.codecs) && f.height >= bf.height && (alt < 0 || f.bitrate > reps[alt].format.bitrate)) alt = ri
                        }
                        if (alt >= 0) best = alt
                    }
                }
                keys.add(StreamKey(pi, ai, best))
                if (isV) vDone = true else aDone = true
            }
        }
        return keys
    }

    // يحفظ الفيديو المحمي: ترخيص رسمي ثم أجزاء مشفّرة. يُستدعى على خيط خلفي. الإلغاء يرمي CancellationException
    fun download(
        ctx: Context,
        url: String,
        hdr: Map<String, String>,
        licenseUrl: String,
        low: Boolean,
        name: String,
        progress: (Int, Long) -> Unit,
        status: (String) -> Unit,
        cancelled: () -> Boolean,
        paused: () -> Boolean
    ): Rec {
        val id = idOf(url)
        val src = http(hdr)

        status("جاري طلب الترخيص الرسمي من المنصة للحفظ بدون اتصال...")
        val ds = src.createDataSource()
        val mf = try {
            DashUtil.loadManifest(ds, Uri.parse(url))
        } catch (e: Throwable) {
            throw Exception("تعذر قراءة قائمة التشغيل: " + why(e))
        }
        val lh = helper(licenseUrl, hdr)
        val ks: ByteArray = try {
            lh.downloadLicense(drmFormat(src.createDataSource(), url))
        } catch (e: Throwable) {
            throw Exception("لم تمنح المنصة ترخيصاً للتشغيل دون اتصال (قد لا تسمح بذلك لهذا الفيديو أو لحسابك): " + why(e))
        } finally {
            try { lh.release() } catch (e: Throwable) {}
        }
        if (cancelled()) {
            try {
                val h2 = helper(licenseUrl, hdr)
                try { h2.releaseLicense(ks) } finally { h2.release() }
            } catch (e: Throwable) {
            }
            throw CancellationException()
        }

        val keys = pickKeys(mf, low)
        if (keys.isEmpty()) throw Exception("لا توجد مسارات صورة أو صوت قابلة للحفظ")
        val req = DownloadRequest.Builder(id, Uri.parse(url))
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setStreamKeys(keys)
            .build()

        status("جاري حفظ الأجزاء المشفّرة...")
        curHdr = hdr
        val dm = manager(ctx)
        onMain { dm.addDownload(req, Download.STOP_REASON_NONE) }
        var wasPaused = false
        var bytes = 0L
        while (true) {
            if (cancelled()) {
                try { onMain { dm.removeDownload(id) } } catch (e: Throwable) {}
                try {
                    val h3 = helper(licenseUrl, hdr)
                    try { h3.releaseLicense(ks) } finally { h3.release() }
                } catch (e: Throwable) {
                }
                throw CancellationException()
            }
            val p = paused()
            if (p != wasPaused) {
                onMain { if (p) dm.pauseDownloads() else dm.resumeDownloads() }
                wasPaused = p
            }
            val d = try { dm.downloadIndex.getDownload(id) } catch (e: Throwable) { null }
            if (d != null) {
                bytes = d.bytesDownloaded
                val pc = d.percentDownloaded
                progress(if (pc >= 0f) pc.toInt() else 0, bytes)
                if (d.state == Download.STATE_COMPLETED) break
                if (d.state == Download.STATE_FAILED) {
                    try { onMain { dm.removeDownload(id) } } catch (e: Throwable) {}
                    throw Exception("فشل تنزيل الأجزاء المشفّرة")
                }
            }
            Thread.sleep(500)
        }
        val rec = Rec(id, name, url, licenseUrl, enc(ks), hdr, System.currentTimeMillis(), bytes)
        put(ctx, rec)
        return rec
    }

    // نص يوضح للمستخدم ماهية ما حُفظ وحدوده
    fun savedNote(): String =
        "تم الحفظ المحمي. المحتوى مشفّر ويعمل داخل التطبيق فقط وبترخيص صالح من المنصة، " +
            "ولا يظهر كملف فيديو عادي في التحميلات ولا يعمل في معرض الصور أو أي مشغّل آخر. " +
            "اضغط «▶ مشاهدة» للتشغيل، وإدارة التراخيص من زر «المحمية»."
}
