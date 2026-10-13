package com.bobtv.helloworld.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/** Only own-process metrics. PSS may omit graphics/codec service allocations. */
class ResourceMonitor(context: Context, private val log: SessionLog,
                      private val onSample: (ResourceSnapshot) -> Unit) {
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val governor = ResourceGovernor()
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var generation = 0L
    private var active = false
    private var registered = false
    private var thermal = -1
    private var trimUntil = 0L
    private var lastPssAt = Long.MIN_VALUE
    private var pss = 0
    private var maxPss = 0
    private var lastCpu = 0L
    private var lastAt = 0L
    private val samples = java.util.ArrayDeque<Int>()
    private val tick = object : Runnable {
        override fun run() { if (active) { sample(); handler.postDelayed(this, 15_000) } }
    }
    fun start() {
        if (active) return
        active = true
        val token = ++generation
        lastPssAt = Long.MIN_VALUE
        lastAt = SystemClock.elapsedRealtime(); lastCpu = Process.getElapsedCpuTime()
        runCatching {
            thermal = power.currentThermalStatus
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                if (active && token == generation) { thermal = status; sample() }
            }
            thermalListener = listener
            power.addThermalStatusListener(contextExecutor, listener)
            registered = true
        }.onFailure { log.warn("Resources", "Thermal reporting unavailable", it) }
        handler.post(tick)
    }
    private val contextExecutor = java.util.concurrent.Executor { command -> handler.post(command) }
    fun onTrimMemory(level: Int) {
        // UI_HIDDEN is lifecycle, not a foreground memory shortage.
        if (level == 5 || level == 10 || level == 15 || level >= 60) trimUntil = SystemClock.elapsedRealtime() + 120_000
        if (active) sample()
    }
    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val measurePss = lastPssAt == Long.MIN_VALUE || now - lastPssAt >= 60_000
        var newPss: Int? = null
        if (measurePss) {
            runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss }.onSuccess {
                pss = it; maxPss = maxOf(maxPss, it); newPss = it
                if (samples.size >= 60) samples.removeFirst()
                samples.addLast(it)
            }
            lastPssAt = now
        }
        val level = governor.evaluate(now, used, runtime.maxMemory(), memory.lowMemory,
            memory.availMem < memory.threshold, thermal, newPss, now < trimUntil)
        if (measurePss) {
            val cpu = Process.getElapsedCpuTime()
            val cpuPercent = if (now > lastAt) 100.0 * (cpu - lastCpu) / (now - lastAt) else 0.0
            val median = samples.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
            log.info("Resources", "level=$level heap=$used/${runtime.maxMemory()} PSS_KiB=$pss max=$maxPss median=$median " +
                "systemAvailable=${memory.availMem} total=${memory.totalMem} threshold=${memory.threshold} " +
                "thermal=$thermal cpuOneCorePercent=$cpuPercent (PSS excludes some graphics/services)")
            lastCpu = cpu; lastAt = now
        }
        onSample(ResourceSnapshot(level, used, runtime.maxMemory(), pss, memory.availMem, memory.totalMem, thermal))
    }
    fun stop() {
        active = false
        generation++
        handler.removeCallbacksAndMessages(null)
        if (registered) thermalListener?.let { runCatching { power.removeThermalStatusListener(it) } }
        thermalListener = null
        registered = false
    }
}
