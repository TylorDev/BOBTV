package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    @Test fun readsAllInitialChannelsWithoutChangingTheirIdsOrUrls() {
        val channels = M3uParser.parse(javaClass.classLoader!!.getResource("canales.m3u")!!.readText())
        assertEquals(13, channels.size)
        assertEquals("UnitelSantaCruz.bo@SD", channels.first().id)
        assertEquals("https://mdstrm.com/live-stream-playlist/692b7e7ac84183fcf9e3462d.m3u8", channels.first().url)
        assertEquals(1, channels.count { it.url.startsWith("http://") })
        assertEquals("http://190.181.18.82:4111/play/a006/index.m3u8", channels[4].url)
        assertEquals("BOLIVISIÓN Bolivia", channels[5].name)
    }

    @Test fun handlesBomSpanishCommentsAndCommasInNamesAndMetadata() {
        val text = "\uFEFF\n#EXTM3U\n# Comentario\n" +
            "#EXTINF:-1 tvg-id=\"canal-uno\" group-title=\"Bolivia, noticias\",Señal, edición nacional\n" +
            "# Otro comentario\nhttps://example.com/live.m3u8\n"
        val channel = M3uParser.parse(text).single()
        assertEquals("Señal, edición nacional", channel.name)
        assertEquals("Bolivia, noticias", channel.group)
    }

    @Test fun incompleteEntriesDoNotStealTheNextChannelUrl() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="incompleto",Incompleto
            #EXTINF:-1 tvg-id="sin-nombre",
            https://example.com/invalid.m3u8
            #EXTINF:-1,Conservado
            https://example.com/good.m3u8
            #EXTINF:-1,Inseguro
            file:///private/file
        """.trimIndent()
        val channel = M3uParser.parse(text).single()
        assertEquals("Conservado", channel.name)
        assertEquals(channel.url, channel.id)
    }

    @Test fun refusesHtmlAndEmptyCatalogues() {
        for (text in listOf("", "<html>Not found</html>", "#EXTM3U\n", "#EXTM3U\n#EXTINF:-1,Canal\nnot a URL")) {
            assertThrows(IllegalArgumentException::class.java) { M3uParser.parse(text) }
        }
    }

    @Test fun restoresByIdAfterRenameAndReorderAndFallsBackWhenRemoved() {
        val first = Channel("a", "Primero", "", "https://example.com/a.m3u8")
        val saved = Channel("b", "Renombrado", "", "https://example.com/b-new.m3u8")
        assertEquals(saved, ChannelSelection.restore(listOf(saved, first), "b"))
        assertEquals(first, ChannelSelection.restore(listOf(first), "b"))
    }
}
