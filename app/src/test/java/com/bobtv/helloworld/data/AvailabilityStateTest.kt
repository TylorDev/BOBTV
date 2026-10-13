package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Test

class AvailabilityStateTest {
    private val a = Channel("a", "A", "", listOf("https://example.com/a1", "https://example.com/a2"))
    private val b = Channel("b", "B", "", "https://example.com/b")
    private val catalogue = listOf(a, b)
    private val live = ProbeResult(true, "Live", 20)
    private val offline = ProbeResult(false, "HTTP 404", 10)

    @Test fun onlyVerifiedChannelsAppearAndRecoverWithoutLosingSources() {
        val state = AvailabilityState()
        state.setCatalogue(catalogue)
        val tickets = state.beginScan(catalogue, "b", null)
        assertEquals("b", tickets.first().key.channelId)
        assertTrue(state.available(catalogue).isEmpty())
        state.apply(tickets.first { it.key.url.endsWith("a1") }, offline)
        state.apply(tickets.first { it.key.url.endsWith("a2") }, live)
        assertEquals(listOf(a), state.available(catalogue))
        assertEquals(listOf("https://example.com/a2"), state.onlineSources(a))
        state.apply(tickets.first(), live)
        assertEquals(catalogue, state.available(catalogue))
        val second = state.beginScan(catalogue, null, null)
        second.forEach { state.apply(it, offline) }
        assertTrue(state.available(catalogue).isEmpty())
        assertEquals(2, a.sources.size)
        state.apply(state.beginScan(catalogue, null, null).first(), live)
        assertEquals(listOf(a), state.available(catalogue))
    }

    @Test fun playbackErrorsAndRecoveryInvalidateOldProbeResults() {
        val state = AvailabilityState()
        state.setCatalogue(catalogue)
        val ticket = state.beginScan(catalogue, null, null).first()
        state.recordPlayback(ticket.key, false)
        assertFalse(state.apply(ticket, live))
        assertTrue(state.available(catalogue).isEmpty())
        val next = state.beginScan(catalogue, null, null).first()
        state.recordPlayback(next.key, true)
        assertFalse(state.apply(next, offline))
        assertEquals(listOf(a), state.available(catalogue))
        state.markChannelOffline(a)
        assertTrue(state.available(catalogue).isEmpty())
    }

    @Test fun catalogueChangesDiscardRemovedSourcesAndPreserveUnchangedOnes() {
        val state = AvailabilityState()
        state.setCatalogue(catalogue)
        val tickets = state.beginScan(catalogue, null, null)
        tickets.forEach { state.apply(it, live) }
        val updated = a.copy(name = "Renamed", sources = listOf(a.sources[1], "https://example.com/new"))
        state.setCatalogue(listOf(updated))
        assertFalse(state.apply(tickets.first(), live))
        assertEquals(listOf(updated), state.available(listOf(updated)))
        assertEquals(listOf(a.sources[1]), state.onlineSources(updated))
        state.setCatalogue(listOf(updated), reset = true)
        assertTrue(state.available(listOf(updated)).isEmpty())
    }

    @Test fun currentlyPlayingSourceIsNotProbedAndSavedPendingStatusIsTracked() {
        val state = AvailabilityState()
        state.setCatalogue(catalogue)
        val playing = SourceKey(a.id, a.sources[0])
        state.recordPlayback(playing, true)
        val tickets = state.beginScan(catalogue, a.id, playing)
        assertFalse(tickets.any { it.key == playing })
        assertTrue(state.isPending(a))
        state.apply(tickets.first { it.key.channelId == a.id }, offline)
        assertFalse(state.isPending(a))
        state.cancelScan()
        assertEquals(0, state.pendingCount)
    }
}
