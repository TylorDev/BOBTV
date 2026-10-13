package com.bobtv.helloworld.player

import java.util.ArrayDeque

enum class FailureCategory { NETWORK, HTTP_TRANSIENT, ACCESS, MISSING_RESOURCE, PROTOCOL, CODEC, LIVE_WINDOW, ENDED, MEMORY, THERMAL, TIMEOUT, CANCELLED, UNKNOWN }
data class PlaybackFailure(val category: FailureCategory, val detail: String, val recoverable: Boolean = true,
                           val media3Code: Int? = null, val httpCode: Int? = null)

object RetryAfterDelay {
    fun parse(value: String?, wallTimeMs: Long): Long {
        val seconds = value?.trim()?.toLongOrNull()
        if (seconds != null) return seconds.coerceIn(1, 60) * 1000
        val dateDelay = runCatching {
            java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - wallTimeMs
        }.getOrNull()
        return (dateDelay ?: 5_000).coerceIn(1_000, 60_000)
    }
}

/** A bounded cycle and rolling global quota, independent of READY notifications. */
class RecoveryCoordinator(private val now: () -> Long) {
    private val preparations = ArrayDeque<Long>()
    private val cycles = ArrayDeque<Long>()
    private var cycleStarted = 0L
    private var attempts = 0
    private var playedMs = 0L
    private var stalledMs = 0L
    private var cooldownUntil = 0L
    private var lastReconnect = Long.MIN_VALUE
    private var lastRenewal = 0L
    fun begin(manual: Boolean = false): Boolean {
        trim()
        if (!manual && (now() < cooldownUntil || cycles.size >= 3)) return false
        if (cycles.size >= 16) cycles.removeFirst()
        cycles.addLast(now())
        cycleStarted = now(); attempts = 0; playedMs = 0; stalledMs = 0; lastRenewal = now()
        return true
    }
    fun permitPreparation(reconnect: Boolean = false, manual: Boolean = false): Boolean {
        trim()
        if (attempts >= 9 || now() - cycleStarted >= 120_000) return false
        val reservedReconnect = reconnect && (lastReconnect == Long.MIN_VALUE || now() - lastReconnect >= 30_000)
        if (preparations.size >= 6 && !reservedReconnect && !manual) return false
        if (reservedReconnect) lastReconnect = now()
        if (preparations.size >= 128) preparations.removeFirst()
        preparations.addLast(now()); attempts++
        return true
    }
    fun exhausted() { cooldownUntil = now() + 60_000 }
    fun progress(played: Long, stalled: Long): Boolean {
        if (now() - lastRenewal > 300_000) { playedMs = 0; stalledMs = 0; lastRenewal = now() }
        playedMs += played; stalledMs += stalled
        if (playedMs >= 120_000 && stalledMs * 10 <= playedMs) {
            cycleStarted = now(); attempts = 0; playedMs = 0; stalledMs = 0; lastRenewal = now()
            cycles.clear()
            return true
        }
        return false
    }
    fun canAutoRecover(): Boolean { trim(); return now() >= cooldownUntil && cycles.size < 3 }
    private fun trim() {
        while (preparations.isNotEmpty() && now() - preparations.first >= 60_000) preparations.removeFirst()
        while (cycles.isNotEmpty() && now() - cycles.first >= 600_000) cycles.removeFirst()
    }
}
