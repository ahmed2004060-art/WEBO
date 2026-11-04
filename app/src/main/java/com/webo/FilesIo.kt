package com.webo

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

// حفظ الملفات وفتح التخزين والعارض
// (دوال امتداد على MainActivity — منقولة تلقائياً بلا تغيير في الأجسام)

// ينقل الملف النهائي إلى مجلد التحميلات (Download)
internal fun MainActivity.saveToDownloads(f: File, mime: String): String = saveToDownloadsEx(f, mime).first

// يرجع (وصف المكان، رابط/مسار الملف النهائي للمشاهدة)
internal fun MainActivity.saveToDownloadsEx(f: File, mime: String): Pair<String, String> {
    Diag.i("تحميل", "حفظ في مجلد التحميلات | ملف=" + f.absolutePath + " | حجم=" + f.length() + " | mime=" + mime)
    if (Build.VERSION.SDK_INT >= 29) {
        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, f.name)
        v.put(MediaStore.Downloads.MIME_TYPE, mime)
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        v.put(MediaStore.Downloads.IS_PENDING, 1)
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw Exception("تعذر إنشاء الملف في مجلد التحميلات")
        contentResolver.openOutputStream(uri)!!.use { o ->
            f.inputStream().use { it.copyTo(o) }
        }
        val done = ContentValues()
        done.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(uri, done, null, null)
        f.delete()
        return Pair("Download/" + f.name, uri.toString())
    }
    val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    d.mkdirs()
    val out = File(d, f.name)
    f.copyTo(out, true)
    f.delete()
    return Pair(out.absolutePath, out.absolutePath)
}

internal fun MainActivity.openStorage() {
    startActivity(Intent(this, StorageActivity::class.java))
}

internal fun MainActivity.openViewer(u: Uri) {
    val i = Intent(this, ViewerActivity::class.java)
    i.data = u
    startActivity(i)
}

internal fun MainActivity.openHtmlFile() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
    i.addCategory(Intent.CATEGORY_OPENABLE)
    i.type = "*/*"
    try {
        startActivityForResult(i, 79)
    } catch (e: Throwable) { Diag.swallow("FilesIo.kt:60 openHtmlFile", e);
        toast("تعذر فتح منتقي الملفات")
    }
}
