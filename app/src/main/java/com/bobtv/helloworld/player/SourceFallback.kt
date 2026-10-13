package com.bobtv.helloworld.player

/** A bounded playback pass across sources, followed by retries after 2 and 5 seconds. */
class SourceFallback(sources: List<String>, preferred: String? = null) {
    data class Attempt(val url: String, val round: Int, val delayMs: Long)
    private var configured = sources.distinct()
    private var sources = (listOfNotNull(preferred?.takeIf { it in sources }) + sources).distinct()
    private var index = 0
    private var round = 1
    private val retries = RetrySchedule()

    init { require(this.sources.isNotEmpty()) }
    fun current(): Attempt = Attempt(sources[index], round, 0)
    fun afterFailure(): Attempt? {
        if (index + 1 < sources.size) {
            index++
            return current()
        }
        val delay = retries.nextDelayMs() ?: return null
        sources = sources.filter { it in configured }
        index = 0
        round++
        return Attempt(sources[index], round, delay)
    }

    fun updateSources(updated: List<String>) {
        val distinct = updated.distinct()
        require(distinct.isNotEmpty())
        if (distinct == configured) return
        val working = sources[index]
        configured = distinct
        sources = (listOf(working) + distinct).distinct()
        index = 0
        round = 1
        retries.reset()
    }

    fun markStable() {
        val working = sources[index]
        sources = listOf(working) + sources.filter { it != working }
        index = 0
        round = 1
        retries.reset()
    }
}
