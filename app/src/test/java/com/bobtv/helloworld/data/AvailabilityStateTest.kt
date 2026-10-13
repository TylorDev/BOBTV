package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Test

class AvailabilityStateTest {
    private val a = Channel("a", "A", "", listOf("https://example.com/a1", "https://example.com/a2"))
    private val b = Channel("b", "B", "", "https://example.com/b")
    private val catalogue = listOf(a, b)
    private val live = ProbeResult(true, "Live", 20)
    private val offline = ProbeResult(false, "Manifest HTTP 404", 10, verdict = ProbeVerdict.UNAVAILABLE)
    private val timeout = ProbeResult(false, "Timeout", 10)

    @Test fun repeatedConclusiveFailuresAreNeededAndRecoveryPreservesSources() {
        var clock = 0L
        val state = AvailabilityState { clock }
        state.setCatalogue(catalogue)
        val tickets = state.beginScan(catalogue, "b", null)
        assertEquals("b", tickets.first().key.channelId)
        assertEquals(catalogue, state.visible(catalogue))
        assertEquals(catalogue, state.navigable(catalogue))
        tickets.forEach { state.apply(it, live) }
        assertEquals(catalogue, state.available(catalogue))
        state.beginScan(catalogue, null, null).forEach { state.apply(it, offline, true) }
        assertEquals(catalogue, state.visible(catalogue))
        clock = 15_000
        state.beginScan(catalogue, null, null).forEach { state.apply(it, offline, true) }
        assertEquals(catalogue, state.visible(catalogue))
        assertTrue(state.navigable(catalogue).isEmpty())
        state.apply(state.beginScan(catalogue, "a", null).first(), live)
        assertEquals(listOf(a), state.available(catalogue))
        assertEquals(2, a.sources.size)
    }

    @Test fun playbackFailureNeverHidesVerifiedChannelAndInvalidatesOldResults() {
        val state = AvailabilityState()
        state.setCatalogue(catalogue)
        val key = SourceKey(a.id, a.sources[0])
        state.recordPlayback(key, true)
        val old = state.beginScan(catalogue, null, null).first { it.key == key }
        state.recordPlayback(key, false)
        assertFalse(state.apply(old, offline, true))
        assertEquals(catalogue, state.visible(catalogue))
        assertEquals(catalogue, state.navigable(catalogue))
        val next = state.beginScan(catalogue, null, null).first { it.key == key }
        state.recordPlayback(key, true)
        assertFalse(state.apply(next, offline, true))
        assertEquals(listOf(a), state.available(catalogue))
    }

    @Test fun uncertainErrorsAndDisconnectedChecksCannotConfirmOffline() {
        var clock = 0L
        val state = AvailabilityState { clock }
        state.setCatalogue(catalogue)
        state.recordPlayback(SourceKey(a.id, a.url), true)
        repeat(20) {
            state.beginScan(catalogue, null, null).forEach { state.apply(it, if (it.key.url == a.url) timeout else offline, false) }
            clock += 30_000
        }
        assertEquals(catalogue, state.visible(catalogue))
        assertFalse(state.confirmedOffline(a))
    }

    @Test fun allChannelsRemainManuallySelectableOfflineAndNeverCountAsOnline() {
        var clock = 0L
        val state = AvailabilityState { clock }
        val unitel = ChannelVisibilityPolicy.alwaysVisibleIds.map { Channel(it, it, "", "https://example.com/$it") }
        val others = listOf(Channel("RedUno.bo@SD", "Red Uno", "", b.url), Channel("Bolivision.bo@SD", "B", "", a.url))
        val all = unitel + others
        state.setCatalogue(all)
        repeat(2) {
            state.beginScan(all, null, null).forEach { state.apply(it, offline, true) }
            clock += 15_000
        }
        assertEquals(all, state.visible(all))
        assertTrue(state.available(all).isEmpty())
        assertTrue(state.navigable(all).isEmpty())
        all.forEach { assertTrue(state.confirmedOffline(it)) }
    }

    @Test fun catalogueChangesDiscardRemovedSourcesAndDoNotResurrectOldTickets() {
        val state = AvailabilityState()
        state.setCatalogue(catalogue)
        val tickets = state.beginScan(catalogue, null, null)
        tickets.forEach { state.apply(it, live) }
        val updated = a.copy(name = "Renamed", sources = listOf(a.sources[1], "https://example.com/new"))
        state.setCatalogue(listOf(updated))
        assertFalse(state.apply(tickets.first { it.key.url == a.url }, live))
        assertEquals(listOf(updated), state.available(listOf(updated)))
        state.setCatalogue(catalogue, reset = true)
        assertFalse(state.apply(tickets.first(), live))
    }

    @Test fun playingSourceIsNotProbedAndPendingIsCancelled() {
        val state = AvailabilityState()
        state.setCatalogue(catalogue)
        val playing = SourceKey(a.id, a.url)
        state.recordPlayback(playing, true)
        val tickets = state.beginScan(catalogue, a.id, playing)
        assertFalse(tickets.any { it.key == playing })
        assertTrue(state.isPending(a))
        state.apply(tickets.first { it.key.channelId == a.id }, timeout)
        assertFalse(state.isPending(a))
        state.cancelScan()
        assertEquals(0, state.pendingCount)
    }

    @Test fun batchesAreBoundedAndEventuallyVisitTheEntireCatalogue() {
        val state = AvailabilityState()
        val channels = (1..100).map { Channel("$it", "$it", "", "https://example.com/$it") }
        state.setCatalogue(channels)
        val visited = mutableSetOf<SourceKey>()
        repeat(5) { visited += state.beginScan(channels, "1", null).map { it.key } }
        assertEquals(100, visited.size)
    }
    @Test fun localFailureDoesNotClaimSignalInstability() {
        val state = AvailabilityState()
        state.setCatalogue(catalogue)
        val key = SourceKey(a.id, a.url)
        state.recordPlayback(key, true)
        state.recordPlayback(key, false)
        assertFalse(state.label(a).contains("intermitente"))
        state.recordStability(key, true)
        assertTrue(state.label(a).contains("intermitente"))
    }

    @Test fun bundledCatalogueIsSelectableBeforeAnyProbeAndAfterInconclusiveResults() {
        val channels = M3uParser.parse(javaClass.classLoader!!.getResource("canales.m3u")!!.readText())
        val state = AvailabilityState()
        state.setCatalogue(channels)
        assertEquals(13, state.visible(channels).size)
        assertEquals(channels, state.navigable(channels))
        assertTrue(state.available(channels).isEmpty())
        state.beginScan(channels, null, null).forEach { state.apply(it, timeout) }
        assertEquals(channels, state.visible(channels))
        assertEquals(channels, state.navigable(channels))
        assertTrue(state.available(channels).isEmpty())
    }

    @Test fun interruptedRoundsPrioritizeSourcesThatHaveNotBeenChecked() {
        val channels = (1..13).map { Channel("$it", "$it", "", "https://example.com/$it") }
        val state = AvailabilityState()
        state.setCatalogue(channels)
        val visited = mutableSetOf<SourceKey>()
        repeat(13) {
            val tickets = state.beginScan(channels, "1", null).take(2)
            tickets.forEach { ticket -> visited.add(ticket.key); state.apply(ticket, timeout) }
            state.cancelScan()
        }
        assertEquals(13, visited.size)
    }
}
