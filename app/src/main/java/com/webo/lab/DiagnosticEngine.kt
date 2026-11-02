package com.webo.lab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticEvent(
    val id: Long, val sessionId: String, val correlationId: String,
    val wallTime: Long, val elapsedNanos: Long, val level: String,
    val component: String, val operation: String, val result: String,
    val durationMs: Long, val detail: String, val stackTrace: String
)

private class DiagnosticDatabase(context: Context) : SQLiteOpenHelper(context, "webo_lab_diagnostics.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, correlation_id TEXT NOT NULL, wall_time INTEGER NOT NULL, elapsed_ns INTEGER NOT NULL, level TEXT NOT NULL, component TEXT NOT NULL, operation TEXT NOT NULL, result TEXT NOT NULL, duration_ms INTEGER NOT NULL, detail TEXT NOT NULL, stack_trace TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_events_time ON events(wall_time DESC)")
        db.execSQL("CREATE INDEX idx_events_corr ON events(correlation_id)")
        db.execSQL("CREATE INDEX idx_events_component ON events(component)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/** A durable local event recorder. Sensitive header/token values are redacted before persistence. */
object DiagnosticEngine {
    private const val TAG = "WEBO-Lab-Diag"
    private const val MAX_ROWS = 25000
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "webo-diagnostic-writer").apply { isDaemon = true } }
    private val pruneCounter = AtomicInteger(0)
    @Volatile private var database: DiagnosticDatabase? = null
    @Volatile private var sessionId: String = UUID.randomUUID().toString()
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            database = DiagnosticDatabase(context.applicationContext)
            sessionId = UUID.randomUUID().toString()
            initialized = true
            record("INFO", "diagnostics", "session_start", "OK", "Diagnostic engine initialized")
        }
    }

    fun newCorrelationId(): String = UUID.randomUUID().toString().take(12)

    fun record(
        level: String = "INFO", component: String, operation: String,
        result: String = "OK", detail: String = "", durationMs: Long = 0L,
        correlationId: String = "", error: Throwable? = null
    ) {
        val safeDetail = redact(detail).take(6000)
        val stack = error?.let { redact(Log.getStackTraceString(it)).take(12000) } ?: ""
        val sid = sessionId
        val cid = correlationId.ifBlank { newCorrelationId() }
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        Log.println(when (level.uppercase(Locale.US)) { "ERROR", "FATAL" -> Log.ERROR; "WARN", "WARNING" -> Log.WARN; "DEBUG" -> Log.DEBUG; else -> Log.INFO }, TAG, "$component/$operation [$result] $safeDetail")
        try {
            writer.execute {
                val db = database?.writableDatabase ?: return@execute
                val values = ContentValues().apply {
                    put("session_id", sid); put("correlation_id", cid); put("wall_time", wall); put("elapsed_ns", elapsed)
                    put("level", level.uppercase(Locale.US)); put("component", component.take(100)); put("operation", operation.take(160))
                    put("result", result.take(40)); put("duration_ms", durationMs.coerceAtLeast(0L)); put("detail", safeDetail); put("stack_trace", stack)
                }
                val inserted = db.insert("events", null, values)
                if (inserted != -1L && pruneCounter.incrementAndGet() >= 250) {
                    pruneCounter.set(0)
                    db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT $MAX_ROWS)")
                }
            }
        } catch (e: Throwable) { Log.e(TAG, "Unable to queue diagnostic event", e) }
    }

    private fun redact(value: String): String {
        var s = value
        s = s.replace(Regex("(?i)(cookie|set-cookie|authorization|proxy-authorization|password|token|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"), "\$1=[REDACTED]")
        s = s.replace(Regex("(?i)([?&](?:token|key|password|auth|sig|signature|session)=)[^&\\s]+"), "\$1[REDACTED]")
        return s
    }

    fun flush(timeoutMs: Long = 4000L): Boolean = try { writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS); true } catch (_: Throwable) { false }

    fun count(context: Context): Int {
        initialize(context); flush()
        return try { database!!.readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { if (it.moveToFirst()) it.getInt(0) else 0 } } catch (_: Throwable) { 0 }
    }

    fun recent(context: Context, limit: Int = 200): List<DiagnosticEvent> {
        initialize(context); flush()
        val out = ArrayList<DiagnosticEvent>()
        try {
            database!!.readableDatabase.rawQuery("SELECT id,session_id,correlation_id,wall_time,elapsed_ns,level,component,operation,result,duration_ms,detail,stack_trace FROM events ORDER BY id DESC LIMIT ?", arrayOf(limit.coerceIn(1, MAX_ROWS).toString())).use { c ->
                while (c.moveToNext()) out.add(DiagnosticEvent(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getLong(9), c.getString(10), c.getString(11)))
            }
        } catch (e: Throwable) { record("ERROR", "diagnostics", "read_events", "FAIL", e.message ?: e.javaClass.simpleName, error = e) }
        return out
    }

    fun clear(context: Context) {
        initialize(context); flush()
        try { database!!.writableDatabase.delete("events", null, null) } catch (e: Throwable) { record("ERROR", "diagnostics", "clear_events", "FAIL", e.message ?: "", error = e) }
        record("INFO", "diagnostics", "clear_events", "OK", "Event history cleared")
    }

    fun exportReport(context: Context): File {
        initialize(context); flush()
        val dir = File(context.cacheDir, "diagnostic_reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val report = File(dir, "webo_lab_report_$stamp.zip")
        val events = recent(context, MAX_ROWS).asReversed()
        ZipOutputStream(FileOutputStream(report)).use { zip ->
            zip.putNextEntry(ZipEntry("events.csv"))
            zip.write("id,session,correlation,time,elapsed_ns,level,component,operation,result,duration_ms,detail,stack_trace\n".toByteArray(StandardCharsets.UTF_8))
            for (e in events) {
                val cols = listOf(e.id.toString(), e.sessionId, e.correlationId, e.wallTime.toString(), e.elapsedNanos.toString(), e.level, e.component, e.operation, e.result, e.durationMs.toString(), e.detail, e.stackTrace)
                zip.write((cols.joinToString(",") { "\"" + it.replace("\"", "\"\"").replace("\n", "\\n") + "\"" } + "\n").toByteArray(StandardCharsets.UTF_8))
            }
            zip.closeEntry(); zip.putNextEntry(ZipEntry("environment.txt"))
            val appInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Throwable) { null }
            val environment = buildString {
                appendLine("WEBO Lab diagnostic report"); appendLine("Package: ${context.packageName}"); appendLine("Version: ${appInfo?.versionName ?: "unknown"}")
                appendLine("Version code: ${if (Build.VERSION.SDK_INT >= 28) appInfo?.longVersionCode else appInfo?.versionCode}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"); appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}"); appendLine("Events exported: ${events.size}")
                appendLine("Cookies, authorization values and private page contents are not intentionally included.")
            }
            zip.write(environment.toByteArray(StandardCharsets.UTF_8)); zip.closeEntry()
            val scanLog = File(context.getExternalFilesDir(null) ?: context.filesDir, "scan_live.txt")
            if (scanLog.isFile) {
                zip.putNextEntry(ZipEntry("full_ui_scan.txt"))
                scanLog.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        record("INFO", "diagnostics", "export_report", "OK", "Exported ${events.size} events")
        return report
    }
}
