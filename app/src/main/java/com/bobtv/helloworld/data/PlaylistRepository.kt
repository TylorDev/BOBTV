package com.bobtv.helloworld.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PlaylistRepository(
    private val cacheFile: File,
    private val readBundled: () -> String,
    private val download: () -> String = { downloadPlaylist() },
) {
    private var current = emptyList<Channel>()

    fun loadInitial(): InitialCatalogue {
        val cached = runCatching { M3uParser.parse(cacheFile.readText(Charsets.UTF_8)) }.getOrNull()
        current = cached ?: M3uParser.parse(readBundled())
        return InitialCatalogue(current, cached != null)
    }

    fun refresh(): RefreshResult = try {
        val text = download()
        val parsed = M3uParser.parse(text)
        saveCache(text)
        current = parsed
        RefreshResult.Updated(parsed)
    } catch (error: Exception) {
        RefreshResult.Kept(current, error)
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

        private fun downloadPlaylist(): String {
            // A fresh query avoids GitHub's CDN serving an older list after a manual update.
            val connection = URL("$PLAYLIST_URL?updated=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "BOBTV/2.0")
            connection.setRequestProperty("Cache-Control", "no-cache")
            // The small catalogue does not need compression; request the fresh plain-text CDN variant.
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                return connection.inputStream.use { input ->
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        check(result.size() + count <= MAX_PLAYLIST_BYTES) { "Playlist too large" }
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray().toString(Charsets.UTF_8)
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
