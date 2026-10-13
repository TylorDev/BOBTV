package com.bobtv.helloworld.controls

import com.bobtv.helloworld.data.Channel
import org.junit.Assert.*
import org.junit.Test

class ChannelTuningTest {
    private fun channel(id: String) = Channel(id, "Canal $id", "", "https://example.com/$id.m3u8")
    private val catalogue = (1..5).map { channel(it.toString()) }

    private class Fixture {
        private data class Scheduled(val at: Long, val action: Runnable)
        private var now = 0L
        private val queue = mutableListOf<Scheduled>()
        val posted = mutableListOf<Runnable>()
        val selections = mutableListOf<Channel>()
        val tuning = ChannelTuning(
            postDelayed = { action, delay -> queue.add(Scheduled(now + delay, action)); posted.add(action) },
            removeCallbacks = { action -> queue.removeAll { it.action === action } },
            onChanged = {},
            onSelect = selections::add,
        )

        fun advance(milliseconds: Long) {
            val until = now + milliseconds
            while (true) {
                val next = queue.filter { it.at <= until }.minByOrNull { it.at } ?: break
                queue.remove(next)
                now = next.at
                next.action.run()
            }
            now = until
        }
    }

    @Test fun timerOkAndThreeDigitsSelectTheSameChannelWithLeadingZeros() {
        for (digits in listOf("5", "05", "005")) {
            val fixture = Fixture()
            fixture.tuning.setCatalogue(catalogue)
            digits.forEach { fixture.tuning.append(it.digitToInt()) }
            if (digits.length < 3) fixture.advance(1500)
            assertEquals(listOf(catalogue[4]), fixture.selections)
            assertNull(fixture.tuning.notice)
            fixture.advance(5000)
            assertEquals(1, fixture.selections.size)
        }
        val fixture = Fixture()
        fixture.tuning.setCatalogue(catalogue)
        fixture.tuning.append(5)
        assertTrue(fixture.tuning.confirm())
        assertEquals(listOf(catalogue[4]), fixture.selections)
    }

    @Test fun missingAndUnavailableNumbersShowDifferentNoticesWithoutSelectingAnything() {
        for ((digits, kind) in listOf("0" to ChannelTuning.NoticeKind.MISSING,
            "000" to ChannelTuning.NoticeKind.MISSING,
            "999" to ChannelTuning.NoticeKind.MISSING,
            "05" to ChannelTuning.NoticeKind.UNAVAILABLE)) {
            val fixture = Fixture()
            fixture.tuning.setCatalogue(catalogue, catalogue.dropLast(1))
            digits.forEach { fixture.tuning.append(it.digitToInt()) }
            if (digits.length < 3) fixture.advance(1500)
            assertEquals(kind, fixture.tuning.notice?.kind)
            assertEquals(digits.toInt().toString().padStart(2, '0'), fixture.tuning.notice?.number)
            assertTrue(fixture.selections.isEmpty())
            fixture.advance(1999)
            assertNotNull(fixture.tuning.notice)
            fixture.advance(1)
            assertNull(fixture.tuning.notice)
            assertTrue(fixture.selections.isEmpty())
        }
    }

    @Test fun availabilityChangesDoNotRenumberOrCancelEntryAndAreCheckedAtConfirmation() {
        val fixture = Fixture()
        fixture.tuning.setCatalogue(catalogue)
        fixture.tuning.append(0)
        fixture.tuning.append(5)
        fixture.tuning.setCatalogue(catalogue, catalogue.dropLast(1))
        assertEquals("05", fixture.tuning.digits)
        assertEquals(5, fixture.tuning.numbers.numberOf("5"))
        fixture.advance(1500)
        assertEquals(ChannelTuning.NoticeKind.UNAVAILABLE, fixture.tuning.notice?.kind)
        assertTrue(fixture.selections.isEmpty())
        fixture.tuning.setCatalogue(catalogue)
        fixture.tuning.append(5)
        fixture.advance(1500)
        assertEquals(listOf(catalogue[4]), fixture.selections)
    }

    @Test fun reorderingOrRemovingChannelsCancelsEntryAndStaleCallbacksCannotSelect() {
        for (updated in listOf(catalogue.reversed(), catalogue.dropLast(1))) {
            val fixture = Fixture()
            fixture.tuning.setCatalogue(catalogue)
            fixture.tuning.append(5)
            fixture.tuning.setCatalogue(updated)
            assertFalse(fixture.tuning.isPending)
            fixture.posted.forEach(Runnable::run)
            fixture.advance(5000)
            assertTrue(fixture.selections.isEmpty())
        }
    }

    @Test fun renamingDuringEntryKeepsTheNumberAndSelectsTheUpdatedChannel() {
        val fixture = Fixture()
        fixture.tuning.setCatalogue(catalogue)
        fixture.tuning.append(5)
        val renamed = catalogue[4].copy(name = "Nombre actualizado")
        fixture.tuning.setCatalogue(catalogue.dropLast(1) + renamed)
        fixture.advance(1500)
        assertEquals(listOf(renamed), fixture.selections)
    }

    @Test fun cancellingForNavigationSelectionOrStopPreventsLateChannelChanges() {
        val fixture = Fixture()
        fixture.tuning.setCatalogue(catalogue)
        fixture.tuning.append(5)
        fixture.tuning.cancel()
        fixture.tuning.append(1)
        val pending = fixture.posted.last()
        fixture.tuning.cancel()
        pending.run()
        fixture.advance(5000)
        assertFalse(fixture.tuning.isPending)
        assertNull(fixture.tuning.notice)
        assertTrue(fixture.selections.isEmpty())
    }

    @Test fun startingAnotherEntryCancelsTheOldNoticeWithoutHidingTheNewOne() {
        val fixture = Fixture()
        fixture.tuning.setCatalogue(catalogue, emptyList())
        fixture.tuning.append(0)
        fixture.tuning.confirm()
        val oldHide = fixture.posted.last()
        fixture.tuning.append(5)
        assertNull(fixture.tuning.notice)
        fixture.tuning.confirm()
        oldHide.run()
        assertEquals(ChannelTuning.NoticeKind.UNAVAILABLE, fixture.tuning.notice?.kind)
        fixture.advance(2000)
        assertNull(fixture.tuning.notice)
    }

    @Test fun tuningCyclesAmongAvailableChannelsUsingOriginalCatalogueOrder() {
        val fixture = Fixture()
        fixture.tuning.setCatalogue(catalogue, listOf(catalogue[4], catalogue[0], catalogue[2]))
        assertEquals(listOf("1", "3", "5"), fixture.tuning.available.map { it.id })
        assertEquals("1", fixture.tuning.adjacent("5", 1)?.id)
        assertEquals("5", fixture.tuning.adjacent("1", -1)?.id)
        fixture.tuning.setCatalogue(catalogue, emptyList())
        assertNull(fixture.tuning.adjacent("5", 1))
    }
}
