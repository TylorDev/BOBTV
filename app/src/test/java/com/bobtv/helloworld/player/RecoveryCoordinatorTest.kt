package com.bobtv.helloworld.player

import org.junit.Assert.*
import org.junit.Test

class RecoveryCoordinatorTest {
    @Test fun preparationBudgetCannotResetWithBriefReadyEvents() {
        var time = 0L
        val recovery = RecoveryCoordinator { time }
        assertTrue(recovery.begin(true))
        repeat(6) { assertTrue(recovery.permitPreparation()); recovery.progress(10_000, 2_000); time += 1_000 }
        assertFalse(recovery.permitPreparation())
        time = 60_000
        assertTrue(recovery.permitPreparation())
    }
    @Test fun boundedCyclesCoolDownAndStopAutomaticStorms() {
        var time = 0L
        val recovery = RecoveryCoordinator { time }
        repeat(3) {
            assertTrue(recovery.begin())
            recovery.exhausted()
            assertFalse(recovery.canAutoRecover())
            time += 60_000
        }
        assertFalse(recovery.canAutoRecover())
        assertFalse(recovery.begin())
        assertTrue(recovery.begin(manual = true))
    }
    @Test fun accumulatedPlaybackRenewsCycleWithoutConsecutiveThirtySeconds() {
        var time = 0L
        val recovery = RecoveryCoordinator { time }
        recovery.begin()
        repeat(8) { time += 17_000; recovery.progress(15_000, 1_000) }
        assertTrue(recovery.permitPreparation())
    }
    @Test fun reconnectHasOneReservedPreparationButFlappingCannotUseItRepeatedly() {
        var time = 0L
        val recovery = RecoveryCoordinator { time }
        recovery.begin()
        repeat(6) { assertTrue(recovery.permitPreparation()) }
        assertTrue(recovery.permitPreparation(reconnect = true))
        assertFalse(recovery.permitPreparation(reconnect = true))
        time += 30_000
        assertTrue(recovery.permitPreparation(reconnect = true))
    }
    @Test fun manualChannelChangesRemainResponsiveButCycleDurationIsBounded() {
        var time = 0L
        val recovery = RecoveryCoordinator { time }
        repeat(50) { recovery.begin(true); assertTrue(recovery.permitPreparation(manual = true)); time += 500 }
        time += 120_000
        assertFalse(recovery.permitPreparation(manual = true))
    }
    @Test fun retryAfterRespectsDatesAndBoundsMalformedOrHugeValues() {
        assertEquals(60_000L, RetryAfterDelay.parse(Long.MAX_VALUE.toString(), 0))
        assertEquals(1_000L, RetryAfterDelay.parse("-1", 0))
        assertEquals(5_000L, RetryAfterDelay.parse("invalid", 0))
        assertEquals(30_000L, RetryAfterDelay.parse("Thu, 1 Jan 1970 00:00:30 GMT", 0))
    }
}
