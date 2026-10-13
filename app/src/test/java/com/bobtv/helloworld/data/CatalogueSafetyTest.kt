package com.bobtv.helloworld.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CatalogueSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    private val initial = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",One\nhttps://example.com/live.m3u8\n"
    @Test fun oversizedCacheIsRejectedBeforeReadingAndBundledListIsUsed() {
        val cache = temp.newFile("canales.m3u")
        java.io.RandomAccessFile(cache, "rw").use { it.setLength(M3uParser.MAX_BYTES + 1L) }
        val result = PlaylistRepository(cache, { initial }).loadInitial()
        assertFalse(result.fromCache)
        assertEquals("one", result.channels.single().id)
    }
    @Test fun oversizedRemoteOrLineCannotReplaceGoodCache() {
        val cache = temp.newFile("canales.m3u").apply { writeText(initial) }
        for (remote in listOf(initial + "#".repeat(M3uParser.MAX_BYTES), initial + "#".repeat(8193))) {
            val repository = PlaylistRepository(cache, { initial }, { remote })
            repository.loadInitial()
            assertTrue(repository.refresh() is PlaylistRepository.RefreshResult.Kept)
            assertEquals(initial, cache.readText())
        }
    }
    @Test fun seventeenthAlternativeRejectsWholeCatalogueWithoutRenumbering() {
        val text = "#EXTM3U\n" + (1..17).joinToString("") {
            "#EXTINF:-1 tvg-id=\"UnitelSantaCruz.bo@SD\",Unitel\nhttps://example.com/$it.m3u8\n"
        }
        assertThrows(IllegalArgumentException::class.java) { M3uParser.parse(text) }
        assertEquals(1, M3uParser.parse(initial).size)
    }
    @Test fun cancelledDownloadCannotWriteOrPublishALateCatalogue() {
        val cache = temp.newFile("canales.m3u").apply { writeText(initial) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = PlaylistRepository(cache, { initial }, {
            started.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            initial.replace("One", "Late rename")
        })
        repository.loadInitial()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<PlaylistRepository.RefreshResult> { repository.refresh() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repository.cancel()
            release.countDown()
            val result = future.get(2, TimeUnit.SECONDS)
            assertTrue(result is PlaylistRepository.RefreshResult.Kept)
            assertEquals(initial, cache.readText())
            assertFalse(File(cache.parentFile, cache.name + ".tmp").exists())
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun failedAtomicReplacementKeepsCurrentCatalogue() {
        val cache = File(temp.root, "missing-parent/canales.m3u")
        File(temp.root, "missing-parent").writeText("blocks directory creation")
        val repository = PlaylistRepository(cache, { initial }, { initial.replace("One", "Remote") })
        repository.loadInitial()
        val result = repository.refresh() as PlaylistRepository.RefreshResult.Kept
        assertEquals("One", result.channels.single().name)
    }
}
