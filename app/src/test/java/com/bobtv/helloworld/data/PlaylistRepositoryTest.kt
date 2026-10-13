package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PlaylistRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val initial = "#EXTM3U\n#EXTINF:-1 tvg-id=\"uno\",Uno\nhttps://example.com/uno.m3u8\n"

    @Test fun firstLaunchUsesBundledListAndLaterLaunchUsesValidatedCache() {
        val cache = java.io.File(temporary.root, "channels.m3u")
        val remote = initial + "#EXTINF:-1 tvg-id=\"dos\",Dos\nhttp://example.com/dos.m3u8\n"
        val repository = PlaylistRepository(cache, { initial }, { remote })
        assertFalse(repository.loadInitial().fromCache)
        assertEquals(2, (repository.refresh() as PlaylistRepository.RefreshResult.Updated).channels.size)
        val reopened = PlaylistRepository(cache, { initial }, { throw IOException("offline") })
        assertTrue(reopened.loadInitial().fromCache)
        assertEquals(2, (reopened.refresh() as PlaylistRepository.RefreshResult.Kept).channels.size)
    }

    @Test fun disconnection404HtmlAndEmptyListsNeverReplaceGoodCache() {
        val cache = temporary.newFile("channels.m3u")
        cache.writeText(initial)
        for (bad in listOf("", "#EXTM3U\n", "<html>404</html>")) {
            val repository = PlaylistRepository(cache, { initial }, { bad })
            repository.loadInitial()
            assertEquals("uno", (repository.refresh() as PlaylistRepository.RefreshResult.Kept).channels.single().id)
            assertEquals(initial, cache.readText())
        }
        val repository = PlaylistRepository(cache, { initial }, { throw IOException("HTTP 404") })
        repository.loadInitial()
        assertEquals(1, (repository.refresh() as PlaylistRepository.RefreshResult.Kept).channels.size)
        assertEquals(initial, cache.readText())
    }

    @Test fun brokenCacheFallsBackToBundledList() {
        val cache = temporary.newFile("channels.m3u")
        cache.writeText("<html>broken</html>")
        assertFalse(PlaylistRepository(cache, { initial }).loadInitial().fromCache)
    }

    @Test fun supportsAddingRenamingReorderingAndRemovingWithoutChangingTheApp() {
        val cache = java.io.File(temporary.root, "channels.m3u")
        var remote = initial
        val repository = PlaylistRepository(cache, { initial }, { remote })
        repository.loadInitial()
        remote += "#EXTINF:-1 tvg-id=\"dos\",Dos\nhttps://example.com/dos.m3u8\n"
        assertEquals(2, (repository.refresh() as PlaylistRepository.RefreshResult.Updated).channels.size)
        remote = "#EXTM3U\n#EXTINF:-1 tvg-id=\"dos\",Dos renombrado\nhttps://example.com/dos-new.m3u8\n"
        val updated = (repository.refresh() as PlaylistRepository.RefreshResult.Updated).channels
        assertEquals("Dos renombrado", updated.single().name)
        assertEquals("dos", ChannelSelection.restore(updated, "uno").id)
    }
}
