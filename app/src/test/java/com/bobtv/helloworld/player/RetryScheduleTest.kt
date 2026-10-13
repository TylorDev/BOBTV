package com.bobtv.helloworld.player

import org.junit.Assert.*
import org.junit.Test

class RetryScheduleTest {
    @Test fun stopsAutomaticRetriesAfterTwoAndAllowsManualRestart() {
        val retries = RetrySchedule()
        assertEquals(2_000L, retries.nextDelayMs())
        assertEquals(5_000L, retries.nextDelayMs())
        assertNull(retries.nextDelayMs())
        assertNull(retries.nextDelayMs())
        retries.reset()
        assertEquals(2_000L, retries.nextDelayMs())
    }
}
