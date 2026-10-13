package com.bobtv.helloworld.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun stableSourceKeepsOriginalStartupBufferAndLatency() {
        val source = SourceStability()
        val policy = PlaybackPolicy()
        assertNull(source.requestedOffset(6_000))
        assertFalse(policy.startPlayback(1_499, 0, false))
        assertTrue(policy.startPlayback(1_500, 0, false))
        assertFalse(policy.startPlayback(2_999, 0, true))
        assertEquals(20_000L, policy.maxBufferMs)
    }
    @Test fun repeatedShortCutsAdaptWithoutThirtySecondsOfContinuousPlayback() {
        val source = SourceStability()
        repeat(8) { i -> source.played(15_000); source.onCut(2_000, i * 32_000L) }
        assertEquals(BufferProfile.REINFORCED, source.profile)
        assertEquals(30_000L, source.requestedOffset(5_000))
    }
    @Test fun smallOrUserBufferingDoesNotTriggerStabilization() {
        val source = SourceStability()
        repeat(20) { source.onCut(100, it * 1_000L) }
        assertEquals(BufferProfile.NORMAL, source.profile)
    }
    @Test fun sourceHistoriesAreIndependentAndHealthyPlaybackReducesOneLevel() {
        val unstable = SourceStability()
        val stable = SourceStability()
        repeat(4) { unstable.onCut(5_000, it * 32_000L) }
        assertEquals(BufferProfile.REINFORCED, unstable.profile)
        assertEquals(BufferProfile.NORMAL, stable.profile)
        unstable.played(300_000) // First interval includes the previous stalls.
        unstable.played(300_000)
        assertEquals(BufferProfile.STABILIZING, unstable.profile)
        unstable.played(300_000)
        assertEquals(BufferProfile.NORMAL, unstable.profile)
        assertNull(unstable.requestedOffset(5_000))
    }
    @Test fun byteLimitedHighBitrateStartsWithUsefulSamplesInsteadOfWaitingForever() {
        val policy = PlaybackPolicy(BufferProfile.REINFORCED)
        assertFalse(policy.startPlayback(1_000, policy.targetBytes - 1, true))
        assertTrue(policy.startPlayback(1_000, policy.targetBytes, true))
        assertFalse(policy.startPlayback(0, policy.targetBytes, true))
        assertFalse(policy.continueLoading(1_000, policy.targetBytes, true))
    }
    @Test fun heapBudgetCapsExpansionAndReservesHeadroom() {
        val mib = 1024L * 1024
        val policy = PlaybackPolicy(BufferProfile.REINFORCED)
        assertEquals(16 * 1024 * 1024, policy.boundedByHeap(64 * mib, 24 * mib, 16 * 1024 * 1024).targetBytes)
        assertEquals(16 * 1024 * 1024, policy.boundedByHeap(128 * mib, 110 * mib, 16 * 1024 * 1024).targetBytes)
        assertEquals(32 * 1024 * 1024, policy.boundedByHeap(256 * mib, 64 * mib, 16 * 1024 * 1024).targetBytes)
    }
    @Test fun thirtySecondDelayFitsLongWindowWithoutThirtySecondsInRam() {
        val decision = LiveWindowPolicy.resolve(30_000, LiveWindow(120_000, 2_000, 6_000, true))
        assertEquals(30_000L, decision.targetMs)
        assertEquals(92_000L, decision.positionMs)
        assertTrue(PlaybackPolicy(targetOffsetMs = decision.targetMs).startPlayback(1_500, 0, false))
    }
    @Test fun shortWindowsClampOrDisableTargetAndSenderLimitsWin() {
        assertEquals(20_000L, LiveWindowPolicy.resolve(30_000, LiveWindow(30_000, 2_000, 6_000, true)).targetMs)
        assertNull(LiveWindowPolicy.resolve(30_000, LiveWindow(12_000, 0, 6_000, true)).targetMs)
        assertEquals(40_000L, LiveWindowPolicy.resolve(30_000, LiveWindow(120_000, 0, 6_000, true, 40_000)).targetMs)
        assertEquals(15_000L, LiveWindowPolicy.resolve(30_000, LiveWindow(120_000, 0, 6_000, true, maxOffsetMs = 15_000)).targetMs)
        assertNull(LiveWindowPolicy.resolve(30_000, LiveWindow(120_000, 0, 6_000, false)).targetMs)
    }
    @Test fun stableSourceDoesNotSeekOrOverrideEvenWithLargeWindow() {
        val decision = LiveWindowPolicy.resolve(null, LiveWindow(120_000, 0, 6_000, true))
        assertNull(decision.targetMs)
        assertNull(decision.positionMs)
    }
    @Test fun shortWindowCannotDeadlockOnAnUnreachableRebufferThreshold() {
        val policy = PlaybackPolicy(BufferProfile.REINFORCED, maxBufferMs = 3_000, targetOffsetMs = 6_000)
        assertFalse(policy.startPlayback(2_999, 1_000, true))
        assertTrue(policy.startPlayback(3_000, 1_000, true))
    }
}
