package com.bobtv.helloworld.player

class RetrySchedule {
    private var attempts = 0
    private val delays = listOf(2_000L, 5_000L)
    fun nextDelayMs(): Long? = delays.getOrNull(attempts)?.also { attempts++ }
    fun reset() { attempts = 0 }
}
