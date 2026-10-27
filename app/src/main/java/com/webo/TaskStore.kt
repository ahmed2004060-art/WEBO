package com.webo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// بيانات مهمة تنزيل واحدة، تُحفظ مستقلة عن غيرها وتبقى بعد إغلاق التطبيق وإعادة فتحه
class TaskRec(
    val id: String,
    val url: String,
    val hdr: Map<String, String>,
    val pageTitle: String,
    val host: String,
    val meta: JSONObject?,
    val state: Int,
    val done: Int,
    val total: Int,
    val bytes: Long,
    val msg: String,
    val kindProt: Int,
    val kindFmt: String,
    val engine: Int,
    val finalLoc: String?,
    val offlineId: String?,
    val protectedStream: Boolean,
    val watchOk: Boolean,
    val fname: String?,
    val mime: String?,
    val created: Long,
    val updated: Long,
    val pageUrl: String = ""
) {
    var thumbBmp: Bitmap? = null
}

// كل مهمة في ملف خاص بها داخل مجلد التطبيق (معرّف المهمة مشتق من الرابط كاملاً بتجزئة قوية فلا يتصادم رابطان)
object TaskStore {
    private val lock = Any()

    fun id(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 10) sb.append(String.format("%02x", d[i]))
        return sb.toString()
    }

    private fun dir(ctx: Context): File {
        val d = File(ctx.applicationContext.filesDir, "tasks")
        d.mkdirs()
        return d
    }

    private fun jf(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    private fun tf(ctx: Context, id: String) = File(dir(ctx), "$id.jpg")

    fun save(ctx: Context, r: TaskRec) = synchronized(lock) {
        try {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("url", r.url)
            val hj = JSONObject()
            for ((k, v) in r.hdr) hj.put(k, v)
            o.put("hdr", hj)
            o.put("pageTitle", r.pageTitle)
            o.put("host", r.host)
            if (r.meta != null) o.put("meta", r.meta)
            o.put("state", r.state)
            o.put("done", r.done)
            o.put("total", r.total)
            o.put("bytes", r.bytes)
            o.put("msg", r.msg)
            o.put("kindProt", r.kindProt)
            o.put("kindFmt", r.kindFmt)
            o.put("engine", r.engine)
            o.put("finalLoc", r.finalLoc ?: JSONObject.NULL)
            o.put("offlineId", r.offlineId ?: JSONObject.NULL)
            o.put("protectedStream", r.protectedStream)
            o.put("watchOk", r.watchOk)
            o.put("fname", r.fname ?: JSONObject.NULL)
            o.put("mime", r.mime ?: JSONObject.NULL)
            o.put("created", r.created)
            o.put("updated", r.updated)
            o.put("pageUrl", r.pageUrl)
            val f = jf(ctx, r.id)
            val tmp = File(f.parentFile, r.id + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
        } catch (e: Throwable) {
        }
    }

    fun saveThumb(ctx: Context, id: String, b: Bitmap) = synchronized(lock) {
        try {
            val f = tf(ctx, id)
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Throwable) {
        }
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) {
        try { jf(ctx, id).delete() } catch (e: Throwable) {}
        try { tf(ctx, id).delete() } catch (e: Throwable) {}
    }

    private fun optS(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }

    // يقرأ كل المهام (الأقدم أولاً) ويحمّل صورها المصغّرة. يُستدعى على خيط خلفي
    fun load(ctx: Context): List<TaskRec> = synchronized(lock) {
        val out = ArrayList<TaskRec>()
        try {
            val fs = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
            for (f in fs) {
                try {
                    val o = JSONObject(f.readText())
                    val hj = o.optJSONObject("hdr")
                    val hm = HashMap<String, String>()
                    if (hj != null) for (k in hj.keys()) hm[k] = hj.optString(k)
                    val r = TaskRec(
                        o.getString("id"), o.getString("url"), hm,
                        o.optString("pageTitle"), o.optString("host"), o.optJSONObject("meta"),
                        o.optInt("state"), o.optInt("done"), o.optInt("total"), o.optLong("bytes"),
                        o.optString("msg"), o.optInt("kindProt", 2), o.optString("kindFmt"), o.optInt("engine"),
                        optS(o, "finalLoc"), optS(o, "offlineId"), o.optBoolean("protectedStream"), o.optBoolean("watchOk"),
                        optS(o, "fname"), optS(o, "mime"), o.optLong("created"), o.optLong("updated"), o.optString("pageUrl")
                    )
                    out.add(r)
                } catch (e: Throwable) {
                    try { f.delete() } catch (x: Throwable) {}
                }
            }
        } catch (e: Throwable) {
        }
        for (r in out) {
            try {
                val t = tf(ctx, r.id)
                if (t.exists()) r.thumbBmp = BitmapFactory.decodeFile(t.absolutePath)
            } catch (e: Throwable) {
            }
        }
        out.sortBy { it.created }
        out
    }

    fun metaToJson(vod: Boolean, dur: Double, size: Long, sizeEst: Boolean, res: String, kbps: Long, subs: List<String>, conf: Int, why: String): JSONObject {
        val o = JSONObject()
        o.put("vod", vod)
        o.put("dur", dur)
        o.put("size", size)
        o.put("sizeEst", sizeEst)
        o.put("res", res)
        o.put("kbps", kbps)
        val a = JSONArray()
        for (s in subs) a.put(s)
        o.put("subs", a)
        o.put("conf", conf)
        o.put("why", why)
        return o
    }
}
