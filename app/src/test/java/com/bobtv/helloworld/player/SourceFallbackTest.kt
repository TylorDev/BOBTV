package com.bobtv.helloworld.player

import org.junit.Assert.*
import org.junit.Test

class SourceFallbackTest {
    @Test fun refreshedSourcesPreservePlaybackAndRemoveDeletedSourcesFromLaterPasses() {
        val plan = SourceFallback(listOf("a", "b"))
        plan.updateSources(listOf("b", "c"))
        assertEquals("a", plan.current().url)
        assertEquals("b", plan.afterFailure()!!.url)
        assertEquals("c", plan.afterFailure()!!.url)
        val retry = plan.afterFailure()!!
        assertEquals("b", retry.url)
        assertEquals(2_000L, retry.delayMs)
    }
    @Test fun triesAlternativesImmediatelyAndStopsAfterThreePasses() {
        val plan = SourceFallback(listOf("a", "b", "c"), "b")
        val attempts = mutableListOf(plan.current())
        while (true) attempts.add(plan.afterFailure() ?: break)
        assertEquals(listOf("b", "a", "c", "b", "a", "c", "b", "a", "c"), attempts.map { it.url })
        assertEquals(listOf(0L, 0L, 0L, 2_000L, 0L, 0L, 5_000L, 0L, 0L), attempts.map { it.delayMs })
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 3, 3, 3), attempts.map { it.round })
        assertNull(plan.afterFailure())
    }

    @Test fun oneSourceRetainsTheOriginalRetrySchedule() {
        val plan = SourceFallback(listOf("a", "a"), "absent")
        assertEquals("a", plan.current().url)
        assertEquals(2_000L, plan.afterFailure()!!.delayMs)
        assertEquals(5_000L, plan.afterFailure()!!.delayMs)
        assertNull(plan.afterFailure())
    }

    @Test fun stablePlaybackStartsAFreshPassWithTheWorkingSourceFirst() {
        val plan = SourceFallback(listOf("a", "b", "c"))
        plan.afterFailure()
        plan.afterFailure()
        plan.markStable()
        assertEquals("c", plan.current().url)
        assertEquals("a", plan.afterFailure()!!.url)
        assertEquals("b", plan.afterFailure()!!.url)
        assertEquals(2_000L, plan.afterFailure()!!.delayMs)
    }
}
