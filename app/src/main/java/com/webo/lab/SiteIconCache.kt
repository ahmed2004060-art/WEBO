package com.webo.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Durable favicon cache. filesDir keeps icons across normal cache cleanups and restarts. */
object SiteIconCache {
    private const val MAX_BYTES = 4L * 1024L * 1024L
    private fun key(host: String): String = MessageDigest.getInstance("SHA-256").digest(host.lowercase().toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    fun file(context: Context, host: String): File = File(File(context.applicationContext.filesDir, "site_icons").apply { mkdirs() }, key(host) + ".png")
    fun exists(context: Context, host: String): Boolean = file(context, host).isFile
    fun load(context: Context, host: String): Bitmap? {
        val f = file(context, host)
        if (!f.isFile) return null
        return try { BitmapFactory.decodeFile(f.absolutePath)?.also { f.setLastModified(System.currentTimeMillis()) } } catch (_: Throwable) { null }
    }
    fun save(context: Context, host: String, bitmap: Bitmap): Boolean {
        val target = file(context, host)
        val oldWidth = if (target.exists()) BitmapFactory.Options().let { o -> o.inJustDecodeBounds = true; BitmapFactory.decodeFile(target.absolutePath, o); o.outWidth } else 0
        if (oldWidth >= bitmap.width) return true
        val temp = File(target.parentFile, target.name + ".tmp")
        return try {
            FileOutputStream(temp).use { out -> if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IllegalStateException("PNG compression failed"); out.fd.sync() }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
            evict(target.parentFile); true
        } catch (e: Throwable) {
            try { temp.delete() } catch (_: Throwable) {}
            DiagnosticEngine.record("WARN", "icon_cache", "save", "FAIL", e.message ?: e.javaClass.simpleName, error = e); false
        }
    }
    private fun evict(dir: File?) {
        if (dir == null) return
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) { if (total <= MAX_BYTES) break; val n = f.length(); if (f.delete()) total -= n }
        } catch (_: Throwable) {}
    }
}
