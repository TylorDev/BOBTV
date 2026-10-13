package com.bobtv.helloworld.controls

import org.junit.Assert.*
import org.junit.Test

class NumericChannelInputTest {
    private class Fixture {
        private data class Scheduled(val at: Long, val action: Runnable)
        private var now = 0L
        private val queue = mutableListOf<Scheduled>()
        val posted = mutableListOf<Runnable>()
        val changes = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        val input = NumericChannelInput(
            postDelayed = { action, delay -> queue.add(Scheduled(now + delay, action)); posted.add(action) },
            removeCallbacks = { action -> queue.removeAll { it.action === action } },
            onChanged = changes::add,
            onConfirmed = confirmed::add,
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

        val pendingCallbacks: Int get() = queue.size
    }

    @Test fun waits1500MillisecondsAfterTheLastDigit() {
        val fixture = Fixture()
        fixture.input.append(5)
        fixture.advance(1499)
        assertTrue(fixture.confirmed.isEmpty())
        fixture.advance(1)
        assertEquals(listOf("5"), fixture.confirmed)
        assertFalse(fixture.input.isPending)
        assertEquals(listOf("5", ""), fixture.changes)
    }

    @Test fun addingAnotherDigitRestartsTheTimeout() {
        val fixture = Fixture()
        fixture.input.append(0)
        fixture.advance(1000)
        fixture.input.append(5)
        fixture.advance(500)
        assertTrue(fixture.confirmed.isEmpty())
        fixture.advance(999)
        assertTrue(fixture.confirmed.isEmpty())
        fixture.advance(1)
        assertEquals(listOf("05"), fixture.confirmed)
    }

    @Test fun thirdDigitConfirmsImmediatelyIncludingLeadingZerosAndRangeEdges() {
        for (digits in listOf("005", "100", "999", "000")) {
            val fixture = Fixture()
            digits.forEach { fixture.input.append(it.digitToInt()) }
            assertEquals(listOf(digits), fixture.confirmed)
            assertFalse(fixture.input.isPending)
            assertEquals(0, fixture.pendingCallbacks)
            fixture.advance(5000)
            assertEquals(1, fixture.confirmed.size)
        }
    }

    @Test fun okConfirmsOneOrTwoDigitsWithoutASecondDelayedSelection() {
        for (digits in listOf("5", "05", "0")) {
            val fixture = Fixture()
            digits.forEach { fixture.input.append(it.digitToInt()) }
            assertTrue(fixture.input.confirm())
            assertEquals(listOf(digits), fixture.confirmed)
            assertFalse(fixture.input.confirm())
            fixture.posted.forEach(Runnable::run)
            fixture.advance(5000)
            assertEquals(listOf(digits), fixture.confirmed)
        }
    }

    @Test fun cancellationClearsDigitsAndRejectsEvenAlreadyDequeuedCallbacks() {
        val fixture = Fixture()
        fixture.input.append(0)
        fixture.input.append(5)
        fixture.input.cancel()
        assertFalse(fixture.input.isPending)
        assertEquals("", fixture.changes.last())
        assertEquals(0, fixture.pendingCallbacks)
        fixture.posted.forEach(Runnable::run)
        fixture.advance(5000)
        assertTrue(fixture.confirmed.isEmpty())
    }

    @Test fun staleCallbackCannotConfirmTheNextEntryEarly() {
        val fixture = Fixture()
        fixture.input.append(5)
        val stale = fixture.posted.last()
        fixture.input.cancel()
        fixture.input.append(1)
        stale.run()
        assertTrue(fixture.confirmed.isEmpty())
        assertEquals("1", fixture.input.digits)
        fixture.advance(1500)
        assertEquals(listOf("1"), fixture.confirmed)
    }

    @Test fun supersededTimeoutCannotCommitAnIncompleteMultiDigitNumber() {
        val fixture = Fixture()
        fixture.input.append(1)
        val stale = fixture.posted.last()
        fixture.input.append(0)
        stale.run()
        assertTrue(fixture.confirmed.isEmpty())
        fixture.input.append(0)
        assertEquals(listOf("100"), fixture.confirmed)
    }

    @Test fun longPressRepeatsDoNotAddDigitsOrPostponeConfirmation() {
        val fixture = Fixture()
        fixture.input.append(5)
        fixture.advance(1000)
        fixture.input.append(5, repeatCount = 1)
        fixture.input.append(5, repeatCount = 2)
        assertEquals("5", fixture.input.digits)
        fixture.advance(500)
        assertEquals(listOf("5"), fixture.confirmed)
        fixture.input.append(5, repeatCount = 3)
        assertFalse(fixture.input.isPending)
    }

    @Test fun aFourthDigitStartsANewEntryAfterTheThreeDigitSelection() {
        val fixture = Fixture()
        "1005".forEach { fixture.input.append(it.digitToInt()) }
        assertEquals(listOf("100"), fixture.confirmed)
        assertEquals("5", fixture.input.digits)
        fixture.advance(1500)
        assertEquals(listOf("100", "5"), fixture.confirmed)
    }
}
