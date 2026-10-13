package com.bobtv.helloworld.data

import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One worker and one queued round. No executor job per catalogue source. */
class AvailabilityScanner(private val createProbe: (SourceKey) -> StreamProbe,
                          private val deadlineMs: Long = HlsSourceProbe.DEADLINE_MS,
                          private val roundMs: Long = 60_000) : AutoCloseable {
    private val workers = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1))
    private val deadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val generation = AtomicLong()
    private val active = ConcurrentHashMap.newKeySet<StreamProbe>()
    private var job: Future<*>? = null

    fun scan(tickets: List<ProbeTicket>, onResult: (ProbeTicket, ProbeResult) -> Unit, onComplete: () -> Unit) {
        cancel()
        val token = generation.get()
        if (tickets.isEmpty()) { onComplete(); return }
        job = workers.submit {
            val roundStarted = System.nanoTime()
            for (ticket in tickets.take(30)) {
                val remaining = roundMs - (System.nanoTime() - roundStarted) / 1_000_000
                if (token != generation.get() || remaining <= 0) break
                var probe: StreamProbe? = null
                var deadline: java.util.concurrent.ScheduledFuture<*>? = null
                val timedOut = AtomicBoolean()
                val started = System.nanoTime()
                val limit = minOf(deadlineMs, remaining)
                val value = try {
                    val current = createProbe(ticket.key)
                    probe = current
                    active.add(current)
                    if (token != generation.get()) break
                    deadline = deadlines.schedule({ timedOut.set(true); current.cancel() }, limit, TimeUnit.MILLISECONDS)
                    current.check()
                } catch (error: Exception) {
                    ProbeResult(false, error.message ?: error.javaClass.simpleName,
                        (System.nanoTime() - started) / 1_000_000, error)
                } finally {
                    deadline?.cancel(false)
                    probe?.let { active.remove(it); it.cancel() }
                }
                val result = if (timedOut.get() || (System.nanoTime() - started) / 1_000_000 >= limit)
                    ProbeResult(false, "Source deadline exceeded: " + limit + " ms", limit,
                        SocketTimeoutException("Source deadline exceeded")) else value
                if (token != generation.get()) break
                onResult(ticket, result)
            }
            if (token == generation.get()) onComplete()
        }
    }
    fun cancel() {
        generation.incrementAndGet()
        job?.cancel(true)
        job = null
        active.forEach(StreamProbe::cancel)
        workers.purge()
    }
    override fun close() {
        cancel()
        workers.shutdownNow()
        deadlines.shutdownNow()
    }
    companion object { const val INTERVAL_MS = 2 * 60 * 1000L }
}
