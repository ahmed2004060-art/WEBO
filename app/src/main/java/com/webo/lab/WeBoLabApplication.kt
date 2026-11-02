package com.webo.lab

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

/** Global instrumentation starts before the first Activity and observes the real app lifecycle. */
class WeBoLabApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val activityStarts = ConcurrentHashMap<String, Long>()
    private val resumedCount = AtomicInteger(0)
    private val samplerHandler = Handler(Looper.getMainLooper())
    @Volatile private var foregroundActivity = ""
    private var lastCpuMs = 0L
    private var lastSampleElapsed = 0L
    private val sampler = object : Runnable {
        override fun run() {
            if (resumedCount.get() <= 0) return
            try {
                val now = android.os.SystemClock.elapsedRealtime()
                val cpu = Process.getElapsedCpuTime()
                val elapsed = (now - lastSampleElapsed).coerceAtLeast(1L)
                val cpuDelta = (cpu - lastCpuMs).coerceAtLeast(0L)
                val cpuPercent = (cpuDelta * 100.0 / elapsed).coerceAtMost(1000.0)
                val runtime = Runtime.getRuntime()
                val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val pss = try { Debug.getPss() / 1024 } catch (_: Throwable) { -1L }
                DiagnosticEngine.record("DEBUG", "performance", "sample", "OK", "activity=$foregroundActivity cpuPercent=${"%.1f".format(java.util.Locale.US, cpuPercent)} processCpuMs=$cpu heapUsedMB=$heapUsed pssMB=$pss threads=${Thread.getAllStackTraces().size}")
                lastCpuMs = cpu
                lastSampleElapsed = now
            } catch (e: Throwable) {
                DiagnosticEngine.record("WARN", "performance", "sample", "FAIL", e.message ?: e.javaClass.simpleName)
            }
            samplerHandler.postDelayed(this, 10_000L)
        }
    }
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun onCreate() {
        super.onCreate()
        DiagnosticEngine.initialize(this)
        registerActivityLifecycleCallbacks(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DiagnosticEngine.record("FATAL", "application", "uncaught_exception", "CRASH", "thread=${thread.name}", error = error)
            previousHandler?.uncaughtException(thread, error)
        }
        DiagnosticEngine.record("INFO", "application", "process_start", "OK", "pid=${Process.myPid()} package=$packageName")
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        val name = activity.javaClass.simpleName
        activityStarts[name] = android.os.SystemClock.elapsedRealtime()
        DiagnosticEngine.record("INFO", "activity_lifecycle", "created", "OK", "activity=$name restored=${state != null}")
    }

    override fun onActivityStarted(activity: Activity) {
        DiagnosticEngine.record("DEBUG", "activity_lifecycle", "started", "OK", "activity=${activity.javaClass.simpleName}")
    }

    override fun onActivityResumed(activity: Activity) {
        foregroundActivity = activity.javaClass.simpleName
        if (resumedCount.incrementAndGet() == 1) {
            lastCpuMs = Process.getElapsedCpuTime()
            lastSampleElapsed = android.os.SystemClock.elapsedRealtime()
            samplerHandler.removeCallbacks(sampler)
            samplerHandler.postDelayed(sampler, 10_000L)
        }
        val runtime = Runtime.getRuntime()
        val used = ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
        val pss = try { Debug.getPss() / 1024 } catch (_: Throwable) { -1L }
        DiagnosticEngine.record("INFO", "activity_lifecycle", "resumed", "OK", "activity=${activity.javaClass.simpleName} heapUsedMB=$used pssMB=$pss")
    }

    override fun onActivityPaused(activity: Activity) {
        DiagnosticEngine.record("DEBUG", "activity_lifecycle", "paused", "OK", "activity=${activity.javaClass.simpleName}")
        if (resumedCount.decrementAndGet() <= 0) {
            resumedCount.set(0)
            samplerHandler.removeCallbacks(sampler)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        DiagnosticEngine.record("DEBUG", "activity_lifecycle", "stopped", "OK", "activity=${activity.javaClass.simpleName}")
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        DiagnosticEngine.record("DEBUG", "activity_lifecycle", "save_state", "OK", "activity=${activity.javaClass.simpleName} keys=${outState.keySet().size}")
    }

    override fun onActivityDestroyed(activity: Activity) {
        val name = activity.javaClass.simpleName
        val started = activityStarts.remove(name)
        val duration = if (started != null) (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) else 0L
        DiagnosticEngine.record("INFO", "activity_lifecycle", "destroyed", "OK", "activity=$name durationSinceCreateMs=$duration changingConfig=${activity.isChangingConfigurations}", duration)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DiagnosticEngine.record("WARN", "resources", "low_memory", "WARNING", "Android reported low memory")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DiagnosticEngine.record("INFO", "resources", "trim_memory", "LEVEL_$level", "Android memory trim callback")
    }
}
