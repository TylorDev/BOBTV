package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    private val channels = (1..4).map { Channel(it.toString(), "Channel " + it, "", "https://example.com/" + it) }

    @Test fun failureWaitsForAcknowledgementAndThenSkipsOfflineChannels() {
        val recovery = PlaybackRecovery()
        recovery.fail("2")
        val available = listOf(channels[0], channels[3])
        assertTrue(recovery.awaitingAcknowledgement)
        assertEquals("2", recovery.failedChannelId)
        assertEquals(channels[3], recovery.acknowledge(channels, available))
        assertFalse(recovery.awaitingAcknowledgement)
        assertNull(recovery.acknowledge(channels, available))
    }

    @Test fun wrapsAndDoesNotRetryTheFailedChannelEvenIfItRecovers() {
        val recovery = PlaybackRecovery()
        recovery.fail("4")
        assertEquals(channels[0], recovery.acknowledge(channels, listOf(channels[0], channels[3])))
        recovery.fail("1")
        assertNull(recovery.acknowledge(channels, listOf(channels[0])))
    }

    @Test fun noOnlineChannelsAndRemovedChannelsAreHandledWithoutCrashing() {
        val recovery = PlaybackRecovery()
        recovery.fail("2")
        assertNull(recovery.acknowledge(channels, emptyList()))
        recovery.fail("removed")
        assertEquals(channels[1], recovery.acknowledge(channels, listOf(channels[1])))
        recovery.fail("1")
        recovery.clear()
        assertNull(recovery.acknowledge(channels, channels))
    }
}
