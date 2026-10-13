package com.bobtv.helloworld.player

import java.util.ArrayDeque
private const val SAMPLE_MIB = 1024 * 1024

enum class BufferProfile(val minMs: Long, val maxMs: Long, val rebufferMs: Long, val bytes: Int) {
    NORMAL(5_000, 20_000, 3_000, 16 * SAMPLE_MIB),
    STABILIZING(10_000, 30_000, 5_000, 24 * SAMPLE_MIB),
    REINFORCED(15_000, 40_000, 8_000, 32 * SAMPLE_MIB),
    PRESSURE(3_000, 10_000, 3_000, 8 * SAMPLE_MIB);
    companion object { const val MIB = SAMPLE_MIB }
}

data class PlaybackPolicy(val profile: BufferProfile = BufferProfile.NORMAL,
                          val targetBytes: Int = profile.bytes,
                          val maxBufferMs: Long = profile.maxMs,
                          val targetOffsetMs: Long? = null) {
    fun boundedByHeap(maxHeap: Long, usedHeap: Long, previousBytes: Int): PlaybackPolicy {
        val reserve = maxOf(16L * BufferProfile.MIB, maxHeap / 5)
        val expansion = (maxHeap - usedHeap - reserve).coerceAtLeast(0)
        val cap = minOf(profile.bytes.toLong(), maxHeap / 4, previousBytes.toLong() + expansion)
        return copy(targetBytes = cap.coerceIn(1L * BufferProfile.MIB, profile.bytes.toLong()).toInt())
    }
    fun continueLoading(bufferMs: Long, bytes: Int, wasLoading: Boolean): Boolean {
        if (bytes >= targetBytes || bufferMs >= maxBufferMs) return false
        return bufferMs < minOf(profile.minMs, maxBufferMs) || wasLoading
    }
    fun startPlayback(bufferMs: Long, bytes: Int, rebuffer: Boolean): Boolean {
        val threshold = minOf(if (rebuffer) profile.rebufferMs else 1_500L, maxBufferMs.coerceAtLeast(500))
        val liveThreshold = targetOffsetMs?.let { minOf(threshold, it / 2) } ?: threshold
        return bufferMs >= liveThreshold || (bytes >= targetBytes && bufferMs > 0)
    }
}

/** Per-source history survives retries; no consecutive READY interval is required. */
class SourceStability {
    private data class Cut(val at: Long, val duration: Long)
    private val cuts = ArrayDeque<Cut>()
    var profile = BufferProfile.NORMAL; private set
    var extraOffsetMs = 0L; private set
    private var lastIncrement: Long? = null
    private var healthyPlayedMs = 0L
    private var recentStallMs = 0L
    fun onCut(durationMs: Long, now: Long) {
        if (durationMs < 500) return
        while (cuts.isNotEmpty() && now - cuts.first.at > 300_000) cuts.removeFirst()
        cuts.addLast(Cut(now, durationMs))
        recentStallMs += durationMs
        val recent = cuts.filter { now - it.at <= 120_000 }
        val level = when {
            cuts.size >= 4 || recent.sumOf { it.duration } >= 15_000 -> BufferProfile.REINFORCED
            recent.size >= 2 || durationMs >= 5_000 -> BufferProfile.STABILIZING
            else -> profile
        }
        if (level.ordinal > profile.ordinal) profile = level
        if (profile != BufferProfile.NORMAL && (lastIncrement == null || now - lastIncrement!! >= 30_000)) {
            extraOffsetMs = minOf(30_000, extraOffsetMs + 5_000)
            lastIncrement = now
        }
    }
    fun played(durationMs: Long) {
        healthyPlayedMs += durationMs
        if (healthyPlayedMs >= 300_000) {
            if (recentStallMs * 100 < healthyPlayedMs) {
                profile = when (profile) {
                    BufferProfile.REINFORCED -> BufferProfile.STABILIZING
                    else -> BufferProfile.NORMAL
                }
                extraOffsetMs = if (profile == BufferProfile.NORMAL) 0 else (extraOffsetMs - 5_000).coerceAtLeast(0)
                cuts.clear()
            }
            healthyPlayedMs = 0
            recentStallMs = 0
        }
    }
    fun requestedOffset(normalMs: Long): Long? = if (extraOffsetMs == 0L) null
        else maxOf(normalMs, minOf(30_000, normalMs + extraOffsetMs))
}

data class LiveWindow(val durationMs: Long, val edgeLagMs: Long, val segmentMs: Long,
                      val seekable: Boolean, val minOffsetMs: Long? = null, val maxOffsetMs: Long? = null)
data class LiveDecision(val targetMs: Long?, val positionMs: Long?, val reason: String)
object LiveWindowPolicy {
    fun resolve(requestedMs: Long?, window: LiveWindow): LiveDecision {
        if (requestedMs == null) return LiveDecision(null, null, "NORMAL_LATENCY")
        if (!window.seekable || window.durationMs <= 0) return LiveDecision(null, null, "LIVE_WINDOW_UNAVAILABLE")
        val segment = window.segmentMs.coerceAtLeast(1_000)
        val lower = maxOf(window.minOffsetMs ?: 0, window.edgeLagMs + maxOf(2_000, segment))
        val upper = minOf(window.maxOffsetMs ?: Long.MAX_VALUE,
            window.edgeLagMs + window.durationMs - maxOf(5_000, 2 * segment))
        if (upper < lower) return LiveDecision(null, null, "LIVE_WINDOW_TOO_SHORT")
        val effective = requestedMs.coerceIn(lower, upper)
        val position = (window.durationMs + window.edgeLagMs - effective).coerceIn(0, window.durationMs)
        return LiveDecision(effective, position, if (effective != requestedMs) "LIVE_WINDOW_LIMIT" else "LIVE_TARGET")
    }
}
