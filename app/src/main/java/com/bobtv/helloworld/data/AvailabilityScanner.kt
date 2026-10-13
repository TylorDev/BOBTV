package com.bobtv.helloworld.data

import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AvailabilityScanner(
    private val createProbe: (SourceKey) -> StreamProbe,
    private val deadlineMs: Long = HlsSourceProbe.DEADLINE_MS,
) : AutoCloseable {
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>())
    private val deadlines = Executors.newScheduledThreadPool(2)
    private val generation = AtomicLong()
    private val active = ConcurrentHashMap.newKeySet<StreamProbe>()
    private val jobs = CopyOnWriteArrayList<Future<*>>()

    fun scan(tickets: List<ProbeTicket>, onResult: (ProbeTicket, ProbeResult) -> Unit, onComplete: () -> Unit) {
        cancel()
        val token = generation.get()
        if (tickets.isEmpty()) { onComplete(); return }
        val remaining = AtomicInteger(tickets.size)
        for (ticket in tickets) jobs.add(workers.submit {
            if (token != generation.get()) return@submit
            var probe: StreamProbe? = null
            val timedOut = java.util.concurrent.atomic.AtomicBoolean()
            var deadline: java.util.concurrent.ScheduledFuture<*>? = null
            val startedAt = System.nanoTime()
            val value = try {
                val current = createProbe(ticket.key)
                probe = current
                active.add(current)
                // Cancellation must also catch a probe created while cancel() was running.
                if (token != generation.get()) return@submit
                deadline = deadlines.schedule({
                    timedOut.set(true)
                    current.cancel()
                }, deadlineMs, TimeUnit.MILLISECONDS)
                current.check()
            } catch (error: Exception) {
                ProbeResult(false, error.message ?: error.javaClass.simpleName,
                    (System.nanoTime() - startedAt) / 1_000_000, error)
            } finally {
                deadline?.cancel(false)
                probe?.let { active.remove(it); it.cancel() }
            }
            val result = if (timedOut.get() || (System.nanoTime() - startedAt) / 1_000_000 >= deadlineMs)
                ProbeResult(false, "Source deadline exceeded: " + deadlineMs + " ms",
                    deadlineMs, SocketTimeoutException("Source deadline exceeded")) else value
            if (token == generation.get()) {
                onResult(ticket, result)
                if (remaining.decrementAndGet() == 0) onComplete()
            }
        })
    }

    fun cancel() {
        generation.incrementAndGet()
        jobs.forEach { it.cancel(true) }
        jobs.clear()
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
