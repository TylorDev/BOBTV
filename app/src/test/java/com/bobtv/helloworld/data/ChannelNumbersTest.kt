package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Test

class ChannelNumbersTest {
    private fun channel(id: String) = Channel(id, "Canal $id", "", "https://example.com/$id.m3u8")
    private val catalogue = listOf("a", "b", "c", "d", "e").map(::channel)

    @Test fun assignsNumbersFromTheCompleteCatalogueAndFormatsTwoOrThreeDigits() {
        val numbers = ChannelNumbers((1..999).map { channel(it.toString()) })
        for (number in listOf(1, 5, 9, 10, 99, 100, 999)) {
            assertEquals(number, numbers.numberOf(number.toString()))
            assertEquals(number.toString(), numbers.channelAt(number)?.id)
        }
        assertEquals("01", ChannelNumbers.format(1))
        assertEquals("05", ChannelNumbers.format(5))
        assertEquals("99", ChannelNumbers.format(99))
        assertEquals("100", ChannelNumbers.format(100))
        assertEquals("999", ChannelNumbers.format(999))
    }

    @Test fun unavailableChannelsLeaveGapsWithoutRenumberingTheOthers() {
        val numbers = ChannelNumbers(catalogue)
        val visible = numbers.availableInOrder(listOf(catalogue[4], catalogue[0], catalogue[2]))
        assertEquals(listOf("a", "c", "e"), visible.map { it.id })
        assertEquals(listOf(1, 3, 5), visible.map { numbers.numberOf(it.id) })
        assertEquals("b", numbers.channelAt(2)?.id)
    }

    @Test fun reorderingChangesNumbersButRenamingAndSourceChangesDoNot() {
        val renamed = catalogue[2].copy(name = "Renombrado", sources = listOf("https://example.com/new.m3u8"))
        val sameOrder = ChannelNumbers(catalogue.map { if (it.id == "c") renamed else it })
        assertEquals(3, sameOrder.numberOf("c"))
        assertEquals(renamed, sameOrder.channelAt(3))
        assertEquals(ChannelNumbers(catalogue).ids, sameOrder.ids)
        val reordered = ChannelNumbers(listOf(renamed) + catalogue.filter { it.id != "c" })
        assertEquals(1, reordered.numberOf("c"))
        assertNotEquals(sameOrder.ids, reordered.ids)
    }

    @Test fun missingOrRemovedChannelsNeverBorrowAnotherChannelsNumber() {
        val numbers = ChannelNumbers(catalogue.drop(1))
        assertNull(numbers.numberOf("a"))
        assertNull(numbers.numberOf(null))
        for (number in listOf(-1, 0, 5, 999, 1000)) assertNull(numbers.channelAt(number))
    }

    @Test fun resolvesLeadingZerosAndDistinguishesMissingFromUnavailableChannels() {
        val numbers = ChannelNumbers((1..999).map { channel(it.toString()) })
        val available = listOf(5, 100, 999).map { numbers.channelAt(it)!! }
        for (digits in listOf("5", "05", "005", "100", "999")) {
            val result = numbers.resolve(digits.toInt(), available) as ChannelNumbers.Target.Available
            assertEquals(digits.toInt().toString(), result.channel.id)
        }
        assertSame(ChannelNumbers.Target.Unavailable, numbers.resolve(1, available))
        assertSame(ChannelNumbers.Target.Unavailable, numbers.resolve(5, emptyList()))
        for (digits in listOf("0", "000")) {
            assertSame(ChannelNumbers.Target.Missing, numbers.resolve(digits.toInt(), available))
        }
        val shortCatalogue = ChannelNumbers(catalogue)
        assertSame(ChannelNumbers.Target.Missing, shortCatalogue.resolve(999, catalogue))
        assertSame(ChannelNumbers.Target.Missing, numbers.resolve(1000, available))
    }

    @Test fun cyclesBothWaysAcrossTheEndsAndSkipsUnavailableChannels() {
        val numbers = ChannelNumbers(catalogue)
        val available = listOf(catalogue[0], catalogue[2], catalogue[4])
        assertEquals("a", numbers.adjacent("e", available, 1)?.id)
        assertEquals("e", numbers.adjacent("a", available, -1)?.id)
        assertEquals("c", numbers.adjacent("a", available, 1)?.id)
        assertEquals("c", numbers.adjacent("e", available, -1)?.id)
    }

    @Test fun navigatesFromAnUnavailableCurrentChannelInCatalogueOrder() {
        val numbers = ChannelNumbers(catalogue)
        val available = listOf(catalogue[0], catalogue[2], catalogue[4])
        assertEquals("c", numbers.adjacent("b", available, 1)?.id)
        assertEquals("a", numbers.adjacent("b", available, -1)?.id)
        assertEquals("a", numbers.adjacent("removed", available, 1)?.id)
        assertEquals("e", numbers.adjacent("removed", available, -1)?.id)
    }

    @Test fun emptyAndSingleChannelListsHaveSafeCircularNavigation() {
        val numbers = ChannelNumbers(catalogue)
        for (direction in listOf(-1, 1)) {
            assertNull(numbers.adjacent("a", emptyList(), direction))
            assertNull(ChannelNumbers(emptyList()).adjacent(null, emptyList(), direction))
            assertEquals("c", numbers.adjacent("c", listOf(catalogue[2]), direction)?.id)
            assertEquals("c", numbers.adjacent("a", listOf(catalogue[2]), direction)?.id)
        }
    }

    @Test fun rejectsMoreThan999ChannelsAndDuplicateIdentifiers() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelNumbers((1..1000).map { channel(it.toString()) })
        }
        assertThrows(IllegalArgumentException::class.java) { ChannelNumbers(listOf(catalogue[0], catalogue[0])) }
    }
}
