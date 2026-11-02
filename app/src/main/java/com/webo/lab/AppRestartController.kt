package com.webo.lab

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock

/** Schedules the system to relaunch the in-app lab, then ends this process. */
object AppRestartController {
    fun restartIntoLab(context: Context, delayMs: Long = 2500L): Boolean {
        return try {
            val app = context.applicationContext
            DiagnosticEngine.flush(5000L)
            val intent = Intent(app, DiagnosticLabActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(DiagnosticLabActivity.EXTRA_RESTART_MARKER, true)
                putExtra(DiagnosticLabActivity.EXTRA_RESTART_SCHEDULED_AT, System.currentTimeMillis())
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pending = PendingIntent.getActivity(app, 74120, intent, flags)
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val fireAt = System.currentTimeMillis() + delayMs.coerceIn(1500L, 10000L)
            alarm.set(AlarmManager.RTC_WAKEUP, fireAt, pending)
            DiagnosticEngine.record("WARN", "lifecycle", "process_restart", "SCHEDULED", "System relaunch scheduled in ${delayMs}ms")
            Thread.sleep(500L)
            Process.killProcess(Process.myPid())
            true
        } catch (e: Throwable) {
            DiagnosticEngine.record("ERROR", "lifecycle", "process_restart", "FAIL", e.message ?: e.javaClass.simpleName, error = e)
            false
        }
    }
}
