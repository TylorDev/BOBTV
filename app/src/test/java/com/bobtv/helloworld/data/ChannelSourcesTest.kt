package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Test

class ChannelSourcesTest {
    private fun entry(id: String, name: String, url: String) =
        "#EXTINF:-1 tvg-id=\"" + id + "\" group-title=\"Original\"," + name + "\n" + url + "\n"

    @Test fun mergesAlternativesForEverySupportedIdAndPreservesMetadataAndOrder() {
        for (id in OfficialChannels.ids) {
            val parsed = M3uParser.parse("#EXTM3U\n" +
                entry("other", "Other", "https://example.com/other.m3u8") +
                entry(id, "First", "https://example.com/first.m3u8") +
                entry(id, "Different", "https://example.com/second.m3u8") +
                entry(id, "Duplicate", "https://example.com/first.m3u8"))
            assertEquals(2, parsed.size)
            assertEquals("other", parsed[0].id)
            assertEquals("First", parsed[1].name)
            assertEquals("Original", parsed[1].group)
            assertEquals(listOf("https://example.com/first.m3u8", "https://example.com/second.m3u8"), parsed[1].sources)
        }
    }

    @Test fun neverMergesDistinctRegionalIds() {
        val channels = M3uParser.parse("#EXTM3U\n" +
            entry("UnitelSantaCruz.bo@SD", "SC", "https://example.com/sc.m3u8") +
            entry("UnitelLaPaz.bo@SD", "LP", "https://example.com/lp.m3u8") +
            entry("UnitelSantaCruz.bo@SD", "SC", "https://example.com/sc2.m3u8"))
        assertEquals(2, channels.size)
        assertEquals(2, channels[0].sources.size)
        assertEquals(listOf("https://example.com/lp.m3u8"), channels[1].sources)
    }

    @Test fun unsupportedDuplicateIdsKeepTheFirstEntry() {
        val channel = M3uParser.parse("#EXTM3U\n" +
            entry("ATBLaPaz.bo@SD", "ATB", "https://example.com/one.m3u8") +
            entry("ATBLaPaz.bo@SD", "Other", "https://example.com/two.m3u8")).single()
        assertEquals("ATB", channel.name)
        assertEquals(1, channel.sources.size)
    }

    @Test fun emptyAvailableListHasNoRestoredOrNextChannel() {
        assertNull(ChannelSelection.restore(emptyList(), "missing"))
        assertNull(ChannelSelection.nextAvailable(emptyList(), emptyList(), "missing"))
    }
}
