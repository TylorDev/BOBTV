package com.bobtv.helloworld.player

/** A manual retry supersedes pending work even when channel and URL are unchanged. */
class PlaybackEpoch {
    class Ticket internal constructor(internal val generation: Long, internal val attempt: Long)
    var generation = 0L; private set
    var attempt = 0L; private set
    fun replace() { generation++; attempt++ }
    fun nextAttempt() { attempt++ }
    fun ticket() = Ticket(generation, attempt)
    fun accepts(ticket: Ticket) = ticket.generation == generation && ticket.attempt == attempt
}
