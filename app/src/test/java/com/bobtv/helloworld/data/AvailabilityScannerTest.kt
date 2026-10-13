package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AvailabilityScannerTest {
    private fun tickets(count: Int) = (1..count).map { ProbeTicket(SourceKey(it.toString(), "https://example.com/" + it), 0) }

    @Test fun probesUseAtMostTwoWorkersAndAllComplete() {
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val firstTwo = CountDownLatch(2)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val results = AtomicInteger()
        val scanner = AvailabilityScanner(createProbe = {
            object : StreamProbe {
                override fun check(): ProbeResult {
                    val count = running.incrementAndGet()
                    maxRunning.accumulateAndGet(count, ::maxOf)
                    firstTwo.countDown()
                    try { check(release.await(2, TimeUnit.SECONDS)) }
                    finally { running.decrementAndGet() }
                    return ProbeResult(true, "Live", 1)
                }
                override fun cancel() {}
            }
        })
        try {
            scanner.scan(tickets(5), { _, _ -> results.incrementAndGet() }, { completed.countDown() })
            assertTrue(firstTwo.await(2, TimeUnit.SECONDS))
            assertEquals(2, maxRunning.get())
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(5, results.get())
            assertTrue(maxRunning.get() <= 2)
        } finally { release.countDown(); scanner.close() }
    }

    @Test fun replacingAScanSuppressesOldResults() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val oldResults = AtomicInteger()
        val newResults = AtomicInteger()
        val scanner = AvailabilityScanner(createProbe = { key ->
            object : StreamProbe {
                override fun check(): ProbeResult {
                    if (key.channelId == "old") {
                        started.countDown()
                        try { cancelled.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                    }
                    return ProbeResult(true, "Live", 1)
                }
                override fun cancel() { cancelled.countDown() }
            }
        })
        try {
            scanner.scan(listOf(ProbeTicket(SourceKey("old", "https://example.com/old"), 0)),
                { _, _ -> oldResults.incrementAndGet() }, {})
            assertTrue(started.await(2, TimeUnit.SECONDS))
            scanner.scan(tickets(1), { _, _ -> newResults.incrementAndGet() }, { completed.countDown() })
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(0, oldResults.get())
            assertEquals(1, newResults.get())
        } finally { scanner.close() }
    }

    @Test fun deadlineCancelsAProbeAndCannotReportItAsOnline() {
        val cancelled = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<ProbeResult>()
        val scanner = AvailabilityScanner(createProbe = {
            object : StreamProbe {
                override fun check(): ProbeResult {
                    cancelled.await(2, TimeUnit.SECONDS)
                    return ProbeResult(true, "Late success", 1)
                }
                override fun cancel() { cancelled.countDown() }
            }
        }, deadlineMs = 50)
        try {
            scanner.scan(tickets(1), { _, value -> result.set(value) }, { completed.countDown() })
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertFalse(result.get().online)
            assertTrue(result.get().detail.contains("deadline"))
        } finally { scanner.close() }
    }

    @Test fun factoryFailureStillCompletesTheScan() {
        val completed = CountDownLatch(1)
        val result = AtomicReference<ProbeResult>()
        val scanner = AvailabilityScanner(createProbe = { throw IllegalStateException("Cannot create probe") })
        try {
            scanner.scan(tickets(1), { _, value -> result.set(value) }, { completed.countDown() })
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertFalse(result.get().online)
            assertTrue(result.get().detail.contains("Cannot create"))
        } finally { scanner.close() }
    }
}
