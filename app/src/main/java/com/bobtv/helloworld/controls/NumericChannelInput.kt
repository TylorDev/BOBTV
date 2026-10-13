package com.bobtv.helloworld.controls

/** The UI supplies its main-thread scheduler; tests can advance time without sleeping. */
class NumericChannelInput(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onChanged: (String) -> Unit,
    private val onConfirmed: (String) -> Unit,
) {
    var digits: String = ""
        private set
    val isPending: Boolean get() = digits.isNotEmpty()
    private var generation = 0L
    private var timeout: Runnable? = null

    fun append(digit: Int, repeatCount: Int = 0) {
        require(digit in 0..9) { "Expected a single digit" }
        if (repeatCount != 0) return
        cancelTimeout()
        digits += digit
        onChanged(digits)
        if (digits.length == MAX_DIGITS) {
            confirm()
        } else {
            val token = generation
            timeout = Runnable {
                if (token == generation && isPending) confirm()
            }.also { postDelayed(it, TIMEOUT_MS) }
        }
    }

    fun confirm(): Boolean {
        if (!isPending) return false
        val confirmed = digits
        cancel()
        onConfirmed(confirmed)
        return true
    }

    fun cancel() {
        cancelTimeout()
        if (isPending) {
            digits = ""
            onChanged(digits)
        }
    }

    private fun cancelTimeout() {
        generation++
        timeout?.let(removeCallbacks)
        timeout = null
    }

    companion object {
        const val MAX_DIGITS = 3
        const val TIMEOUT_MS = 1_500L
    }
}
