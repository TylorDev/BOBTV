package com.bobtv.helloworld.data

import java.net.URI

object M3uParser {
    private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    fun parse(text: String): List<Channel> {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Playlist exceeds 1 MiB" }
        val iterator = text.removePrefix("\uFEFF").lineSequence()
            .map(String::trim).filter(String::isNotEmpty).iterator()
        require(iterator.hasNext()) { "Empty playlist" }
        val header = iterator.next()
        require(header == "#EXTM3U" || header.startsWith("#EXTM3U ")) { "Missing M3U header" }
        val channels = linkedMapOf<String, Channel>()
        var pending: Pending? = null
        for (line in iterator) {
            require(line.length <= MAX_LINE_CHARS && line.toByteArray(Charsets.UTF_8).size <= MAX_LINE_CHARS) { "Playlist line exceeds 8 KiB" }
            when {
                line.startsWith("#EXTINF:") -> pending = parseInfo(line)
                line.startsWith("#") -> Unit
                else -> {
                    val info = pending
                    pending = null
                    if (info != null && isWebUrl(line)) {
                        val id = info.id.ifBlank { line }
                        val existing = channels[id]
                        if (existing == null) {
                            require(channels.size < ChannelNumbers.MAX_CHANNELS) { "Playlist exceeds 999 channels" }
                            channels[id] = Channel(id, info.name, info.group, line)
                        }
                        else if (id in OfficialChannels.ids && line !in existing.sources) {
                            require(existing.sources.size < MAX_SOURCES_PER_CHANNEL) { "Channel exceeds 16 sources" }
                            channels[id] = existing.copy(sources = existing.sources + line)
                        }
                    }
                }
            }
        }
        require(channels.isNotEmpty()) { "Playlist has no valid channels" }
        require(channels.size <= ChannelNumbers.MAX_CHANNELS) { "Playlist exceeds ${ChannelNumbers.MAX_CHANNELS} channels" }
        require(channels.values.sumOf { it.sources.size } <= MAX_SOURCES) { "Playlist exceeds 4096 sources" }
        return channels.values.toList()
    }

    private fun parseInfo(line: String): Pending? {
        var quote: Char? = null
        val separator = line.indexOfFirst { char ->
            when {
                quote == char -> { quote = null; false }
                quote == null && (char == '"' || char == '\'') -> { quote = char; false }
                quote == null && char == ',' -> true
                else -> false
            }
        }
        if (separator < 0) return null
        val name = line.substring(separator + 1).trim()
        if (name.isEmpty()) return null
        val metadata = attributes.findAll(line.substring(0, separator)).associate {
            it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] }.trim()
        }
        return Pending(metadata["tvg-id"].orEmpty(), name, metadata["group-title"].orEmpty())
    }

    private fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private data class Pending(val id: String, val name: String, val group: String)
    const val MAX_BYTES = 1024 * 1024
    const val MAX_LINE_CHARS = 8192
    const val MAX_SOURCES_PER_CHANNEL = 16
    const val MAX_SOURCES = 4096
}
