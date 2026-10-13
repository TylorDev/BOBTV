package com.bobtv.helloworld.diagnostics

enum class ResourceLevel { NORMAL, LIMITED, CRITICAL, STOP }
data class ResourceSnapshot(val level: ResourceLevel, val heapUsed: Long, val heapMax: Long,
                            val pssKiB: Int, val availableBytes: Long, val totalBytes: Long, val thermalStatus: Int)

class ResourceGovernor {
    private var current = ResourceLevel.NORMAL
    private var improvingSince: Long? = null
    private var over100 = 0
    private var over128 = 0
    fun evaluate(now: Long, used: Long, max: Long, lowMemory: Boolean, belowThreshold: Boolean,
                 thermal: Int, pssKiB: Int?, trimPressure: Boolean): ResourceLevel {
        if (pssKiB != null) {
            over100 = if (pssKiB > 100 * 1024) over100 + 1 else 0
            over128 = if (pssKiB > 128 * 1024) over128 + 1 else 0
        }
        val ratio = if (max > 0) used.toDouble() / max else 1.0
        val next = when {
            thermal >= 4 || ratio >= .9 -> ResourceLevel.STOP
            thermal >= 3 || ratio >= .85 || lowMemory || over128 >= 2 -> ResourceLevel.CRITICAL
            thermal >= 2 || ratio >= .75 || belowThreshold || trimPressure || over100 >= 3 ||
                max - used < maxOf(16L * 1024 * 1024, max / 5) -> ResourceLevel.LIMITED
            else -> ResourceLevel.NORMAL
        }
        if (next.ordinal >= current.ordinal) { current = next; improvingSince = null }
        else {
            val since = improvingSince ?: now.also { improvingSince = it }
            if (now - since >= 120_000) { current = next; improvingSince = null }
        }
        return current
    }
}
