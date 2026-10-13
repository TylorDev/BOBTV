package com.bobtv.helloworld.data

import java.io.File
import com.bobtv.helloworld.network.BoundedHttpSession
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PlaylistRepository(
    private val cacheFile: File,
    private val readBundled: () -> String,
    private val download: (() -> String)? = null,
    private val log: (String) -> Unit = {},
) {
    private var current = emptyList<Channel>()
    @Volatile private var activeRequest: BoundedHttpSession? = null

    fun loadInitial(): InitialCatalogue {
        val cached = runCatching { M3uParser.parse(cacheFile.readText(Charsets.UTF_8)) }.getOrNull()
        current = cached ?: M3uParser.parse(readBundled())
        return InitialCatalogue(current, cached != null)
    }

    fun refresh(): RefreshResult = try {
        val text = download?.invoke() ?: downloadPlaylist()
        val parsed = M3uParser.parse(text)
        saveCache(text)
        current = parsed
        RefreshResult.Updated(parsed)
    } catch (error: Exception) {
        RefreshResult.Kept(current, error)
    }

    fun cancel() { activeRequest?.cancel() }

    private fun downloadPlaylist(): String {
        val request = BoundedHttpSession(25_000, log)
        activeRequest = request
        return try {
            val url = PLAYLIST_URL + "?updated=" + System.currentTimeMillis()
            request.fetch(url, MAX_PLAYLIST_BYTES).bytes.toString(Charsets.UTF_8)
        } finally {
            request.close()
            activeRequest = null
        }
    }

    private fun saveCache(text: String) {
        cacheFile.parentFile?.mkdirs()
        val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        try {
            temporary.writeText(text, Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    data class InitialCatalogue(val channels: List<Channel>, val fromCache: Boolean)
    sealed class RefreshResult {
        data class Updated(val channels: List<Channel>) : RefreshResult()
        data class Kept(val channels: List<Channel>, val error: Exception) : RefreshResult()
    }

    companion object {
        const val PLAYLIST_URL = "https://raw.githubusercontent.com/TylorDev/bobtv-canales/main/canales.m3u"
        const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
        private const val MAX_PLAYLIST_BYTES = 1024 * 1024

    }
}
