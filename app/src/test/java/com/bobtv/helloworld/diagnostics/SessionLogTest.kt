package com.bobtv.helloworld.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionLogTest {
    @Test fun redactsCredentialsParametersAndExceptionUrlsInBothOutputs() {
        val sink = mutableListOf<String>()
        val log = SessionLog("Version 2.1", sink = { _, _, value -> sink.add(value) })
        log.warn("Test", "https://user:password@example.com/live.m3u8?token=secret&key=value",
            IllegalStateException("https://example.com/live?access_token=private"))
        val report = log.export() + sink.joinToString()
        for (secret in listOf("password", "user:", "secret", "key=value", "private")) assertFalse(report.contains(secret))
        assertTrue(report.contains("example.com/live"))
        assertTrue(report.contains("REDACTED"))
        assertTrue(report.contains("IllegalStateException"))
    }

    @Test fun limitsEventCountAndKeepsTheMostRecentEvents() {
        val log = SessionLog("Session", maxEvents = 3, now = { Instant.EPOCH })
        (1..5).forEach { log.info("Test", "event=" + it) }
        assertEquals(3, log.eventCount())
        assertFalse(log.export().contains("event=1"))
        assertTrue(log.export().contains("event=5"))
        assertTrue(log.export().contains("se omitieron"))
    }

    @Test fun longUnicodeEventsRemainValidAndWithinTheByteLimit() {
        val log = SessionLog("Session", maxBytes = 512)
        log.error("Test", "Señal 😀".repeat(1000))
        val report = log.export()
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= 512)
        assertFalse(report.contains('\uFFFD'))
        assertTrue(report.contains("Evento truncado"))
    }

    @Test fun concurrentProbesCanLogWithoutCorruptingTheSession() {
        val log = SessionLog("Session")
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { index -> pool.submit { log.info("Probe", "request=" + index) } }
            pool.shutdown()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(100, log.eventCount())
        } finally { pool.shutdownNow() }
    }
}
