package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelCatalogueLimitTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun playlist(size: Int): String = "#EXTM3U\n" + (1..size).joinToString("") { number ->
        "#EXTINF:-1 tvg-id=\"channel-$number\",Canal $number\nhttps://example.com/$number.m3u8\n"
    }

    @Test fun accepts999DistinctChannelsAndRejects1000() {
        assertEquals(999, M3uParser.parse(playlist(999)).size)
        assertThrows(IllegalArgumentException::class.java) { M3uParser.parse(playlist(1000)) }
    }

    @Test fun duplicateSourcesDoNotCountAsAdditionalChannels() {
        val text = "#EXTM3U\n" + (1..1000).joinToString("") { number ->
            "#EXTINF:-1 tvg-id=\"RedUno.bo@SD\",RED UNO\nhttps://example.com/$number.m3u8\n"
        }
        assertEquals(1, M3uParser.parse(text).size)
    }

    @Test fun oversizedRemoteCataloguePreservesThePreviousChannelsAndCache() {
        val cache = temporary.newFile("canales.m3u")
        val valid = playlist(5)
        cache.writeText(valid)
        val repository = PlaylistRepository(cache, { valid }, { playlist(1000) })
        val previous = repository.loadInitial().channels
        val result = repository.refresh() as PlaylistRepository.RefreshResult.Kept
        assertEquals(previous, result.channels)
        assertEquals(valid, cache.readText())
    }

    @Test fun oversizedCacheFallsBackToTheBundledCatalogue() {
        val cache = temporary.newFile("canales.m3u")
        cache.writeText(playlist(1000))
        val initial = PlaylistRepository(cache, { playlist(5) }).loadInitial()
        assertFalse(initial.fromCache)
        assertEquals(5, initial.channels.size)
    }
}
