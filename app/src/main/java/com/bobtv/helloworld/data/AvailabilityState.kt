package com.bobtv.helloworld.data

data class SourceKey(val channelId: String, val url: String)
data class ProbeTicket(val key: SourceKey, val revision: Long)
data class ProbeResult(val online: Boolean, val detail: String, val elapsedMs: Long, val error: Exception? = null)

/** Main-thread state; playback verdicts invalidate any probe already in flight. */
class AvailabilityState {
    private data class Health(var online: Boolean? = null, var revision: Long = 0)
    private val sources = linkedMapOf<SourceKey, Health>()
    private val pending = mutableSetOf<SourceKey>()

    fun setCatalogue(channels: List<Channel>, reset: Boolean = false) {
        val keys = channels.flatMap { channel -> channel.sources.map { SourceKey(channel.id, it) } }.toSet()
        sources.keys.retainAll(keys)
        pending.retainAll(keys)
        for (key in keys) {
            if (reset || key !in sources) sources[key] = Health()
        }
        if (reset) pending.clear()
    }

    fun beginScan(channels: List<Channel>, preferredId: String?, playing: SourceKey?): List<ProbeTicket> {
        pending.clear()
        return channels.sortedBy { if (it.id == preferredId) 0 else 1 }
            .flatMap { channel -> channel.sources.map { SourceKey(channel.id, it) } }
            .filter { it != playing && it in sources }
            .map { key ->
                pending.add(key)
                ProbeTicket(key, sources.getValue(key).revision)
            }
    }

    fun apply(ticket: ProbeTicket, result: ProbeResult): Boolean {
        pending.remove(ticket.key)
        val health = sources[ticket.key] ?: return false
        if (health.revision != ticket.revision) return false
        health.online = result.online
        return true
    }

    fun recordPlayback(key: SourceKey, online: Boolean) {
        sources[key]?.let {
            it.revision++
            it.online = online
            pending.remove(key)
        }
    }

    fun markChannelOffline(channel: Channel) {
        channel.sources.forEach { recordPlayback(SourceKey(channel.id, it), false) }
    }

    fun available(channels: List<Channel>): List<Channel> =
        channels.filter { onlineSources(it).isNotEmpty() }

    fun onlineSources(channel: Channel): List<String> =
        channel.sources.filter { sources[SourceKey(channel.id, it)]?.online == true }

    fun isPending(channel: Channel): Boolean =
        channel.sources.any { SourceKey(channel.id, it) in pending }

    fun cancelScan() { pending.clear() }
    val pendingCount: Int get() = pending.size
}
