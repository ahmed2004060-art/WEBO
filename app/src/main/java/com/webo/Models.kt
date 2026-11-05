package com.webo

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.webo.MainActivity.Tab
import java.io.File

// الكلاسات المساعدة (بيانات الصفوف والمرشحات والروابط)

// تنبيهات التطبيق
internal class AlertItem(val time: Long, val kind: String, val msg: String, val detail: String)

internal class Variant(val label: String, val url: String, val bw: Long = 0L, val res: String = "")

// معلومات الفيديو الملتقط: مسجل (VOD) أم بث مباشر، المدة، الحجم، أعلى جودة، وروابط الجودات الفرعية
internal data class Meta(val vod: Boolean, val dur: Double, val size: Long, val sizeEst: Boolean, val res: String, val kbps: Long, val subs: List<String>, val conf: Int = 100, val why: String = "")

internal class Cand(val url: String, val owner: Tab?, var meta: Meta?, var title: String, val rw: Row, val view: View, val info: TextView, val hostTxt: String, val pageTitle: String)

internal class Excl(val u: String, val h: Map<String, String>, val meta: Meta?, val owner: Tab?, val title: String)

internal class Ctl {
    @Volatile var cancelled = false
    @Volatile var paused = false
    val bytes = java.util.concurrent.atomic.AtomicLong(0)
}

internal class CancelledException : Exception("أُلغي التحميل")

internal class ProtectedException(m: String) : Exception(m)

internal class Row(val bar: ProgressBar, val msg: TextView, val btn: View, val pause: TextView, val cancel: TextView, val ctl: Ctl, val view: View) {
    var work: File? = null
    var finalLoc: String? = null
    var finalIsUri = false
    var watch: View? = null
    var del: View? = null
    // 0 ملتقط، 1 جارٍ، 2 متوقف مؤقتاً، 3 مكتمل، 4 غير مكتمل (أُلغي أو فشل)
    var state = 0
    // ضُغط عليه تحميل من صفحة العداد وما زال في الانتظار/اختيار الجودة
    var started = false
    var removed = false
    var chip: TextView? = null
    // فيديو محمي رُفض تحميله: زر المشاهدة يجرّب تشغيله مباشرة بالمشغّل المدمج
    var protectedStream = false
    var srcUrl: String? = null
    var srcHdr: Map<String, String>? = null
    // معرّف العنصر المحمي المحفوظ للتشغيل دون اتصال (إن وُجد)
    var offlineId: String? = null
    // الصورة المصغّرة وحالة الحماية والصيغة المعروضة بجانب العنصر
    var thumb: ImageView? = null
    var thumbSet = false
    var thumbSaved = false
    @Volatile var thumbBusy = false
    @Volatile var thumbStop = false
    var thumbRetry: (() -> Unit)? = null
    // المحاولات المتبقية لجلب صورة المعاينة؛ لا تُؤخذ لقطة من الفيديو إلا بعد أن تصل إلى صفر
    @Volatile var thumbTries = 0
    var kindTv: TextView? = null
    var kindProt = 2
    var kindFmt = ""
    // هوية المهمة (من الرابط كاملاً) وبياناتها المستقلة المحفوظة
    var url = ""
    var id = ""
    var created = System.currentTimeMillis()
    var engine = 0
    var quiet = false
    // عنوان الصفحة التي التُقط منها الفيديو، لإعادة فتحها والالتقاط من جديد عند انتهاء صلاحية الرابط
    var pageUrl = ""
}

// الصفحات المغلقة مؤخراً (حالة محفوظة على القرص)
internal class Closed(val id: String, val title: String, val url: String)

// ================= شاشة التبويبات (النمط الجديد) =================

internal class SwClosed(val t: Tab, val i: Int)


internal class GImg(val u: String, var w: Int, var h: Int) {
    var sel = false
    var loaded = false
    var cell: FrameLayout? = null
    var iv: ImageView? = null
    var mark: TextView? = null
    // بعد الضغط على «تحميل المحدد» ينتقل إلى صفحة التحميلات: 1 جارٍ، 2 مكتمل، 3 فشل
    var started = false
    var state = 0
    var chip: TextView? = null
    var pbar: ProgressBar? = null
}

internal class HlsVar(val bw: Long, val avg: Long, val w: Int, val h: Int, val codecs: String, val fps: String, val audio: String, val url: String, val hasVideo: Boolean)


internal class Kind(val prot: Int, val fmt: String)

// حالة بناء المعاينة الحية: نضيف البيانات الجديدة فقط إلى ملف المعاينة بدل إعادة نسخ كل شيء
internal class Inc { var hdr = false; var copied = 0L; var next = 0 }

internal class LiveB(val dir: File) { val v = Inc(); val a = Inc() }


internal class DRep(val id: String, val bw: Long, val w: Int, val h: Int, val init: String?, val segs: List<String>, val single: String?,
                   val lang: String = "", val main: Boolean = true, val codecs: String = "", val fps: String = "")

internal class DashInfo(val live: Boolean, val dur: Double, val video: List<DRep>, val audio: List<DRep>, val drm: Boolean)

internal class DashJob(val v: File, val a: File?, val drm: Boolean = false)

internal class ProbeRes(val code: Int, val ct: String, val len: Long)

internal class Seg(val index: Int, val url: String, val key: ByteArray?, val iv: ByteArray, val rs: Long = -1L, val re: Long = -1L)

internal class HlsJob(val parts: List<File>, val init: File?, val mp4Family: Boolean, val audio: HlsJob? = null, val audioDir: File? = null)
