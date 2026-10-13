package com.bobtv.helloworld.data

data class SourceKey(val channelId: String, val url: String)
data class ProbeTicket(val key: SourceKey, val revision: Long)
enum class ProbeVerdict { ONLINE, INCONCLUSIVE, UNAVAILABLE }
data class ProbeResult(val online: Boolean, val detail: String, val elapsedMs: Long,
                       val error: Exception? = null,
                       val verdict: ProbeVerdict = if (online) ProbeVerdict.ONLINE else ProbeVerdict.INCONCLUSIVE)
enum class Availability { UNKNOWN, ONLINE, SUSPECT, OFFLINE_CONFIRMED }

/** Main-thread evidence. A playback failure is never a negative availability verdict. */
class AvailabilityState(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Health(var state: Availability = Availability.UNKNOWN, var verified: Boolean = false,
                              var positiveAt: Long = Long.MIN_VALUE, var negativeAt: Long? = null,
                              var revision: Long)
    private val sources = linkedMapOf<SourceKey, Health>()
    private val pending = mutableSetOf<SourceKey>()
    private var serial = 0L
    private var cursor = 0

    fun setCatalogue(channels: List<Channel>, reset: Boolean = false) {
        val keys = channels.flatMap { channel -> channel.sources.map { SourceKey(channel.id, it) } }.toSet()
        sources.keys.retainAll(keys)
        pending.retainAll(keys)
        for (key in keys) if (reset || key !in sources) sources[key] = Health(revision = ++serial)
        if (reset) pending.clear()
    }

    fun beginScan(channels: List<Channel>, preferredId: String?, playing: SourceKey?, limit: Int = 30): List<ProbeTicket> {
        pending.clear()
        val all = channels.flatMap { channel -> channel.sources.map { SourceKey(channel.id, it) } }
            .filter { it != playing && it in sources }
        if (all.isEmpty()) return emptyList()
        val preferred = all.filter { it.channelId == preferredId }.take(2)
        val rotated = all.indices.map { all[(cursor + it).mod(all.size)] }
        val batch = (preferred + rotated).distinct().take(limit)
        cursor = (cursor + maxOf(1, batch.size - preferred.size)).mod(all.size)
        return batch.map { key -> pending.add(key); ProbeTicket(key, sources.getValue(key).revision) }
    }

    fun apply(ticket: ProbeTicket, result: ProbeResult, networkHealthy: Boolean = false): Boolean {
        val health = sources[ticket.key] ?: return false
        if (health.revision != ticket.revision) return false
        pending.remove(ticket.key)
        when (result.verdict) {
            ProbeVerdict.ONLINE -> positive(health)
            ProbeVerdict.INCONCLUSIVE -> {
                health.negativeAt = null
                if (health.state != Availability.OFFLINE_CONFIRMED) health.state = Availability.SUSPECT
            }
            ProbeVerdict.UNAVAILABLE -> {
                if (!networkHealthy) {
                    health.negativeAt = null
                    if (health.state != Availability.OFFLINE_CONFIRMED) health.state = Availability.SUSPECT
                } else {
                    val first = health.negativeAt
                    if (first != null && now() - first >= CONFIRM_MS) health.state = Availability.OFFLINE_CONFIRMED
                    else { health.negativeAt = first ?: now(); health.state = Availability.SUSPECT }
                }
            }
        }
        health.revision = ++serial
        return true
    }

    fun recordPlayback(key: SourceKey, online: Boolean) {
        sources[key]?.let {
            if (online) positive(it)
            else { it.negativeAt = null; if (it.state != Availability.OFFLINE_CONFIRMED) it.state = Availability.SUSPECT }
            it.revision = ++serial
            pending.remove(key)
        }
    }
    private fun positive(health: Health) {
        health.state = Availability.ONLINE
        health.verified = true
        health.positiveAt = now()
        health.negativeAt = null
    }
    fun state(key: SourceKey): Availability = sources[key]?.let {
        if (it.state == Availability.ONLINE && now() - it.positiveAt > FRESH_MS) Availability.SUSPECT else it.state
    } ?: Availability.UNKNOWN
    fun confirmedOffline(channel: Channel) = channel.sources.all { state(SourceKey(channel.id, it)) == Availability.OFFLINE_CONFIRMED }
    fun onlineSources(channel: Channel) = channel.sources.filter { state(SourceKey(channel.id, it)) == Availability.ONLINE }
    fun available(channels: List<Channel>) = channels.filter { onlineSources(it).isNotEmpty() }
    fun navigable(channels: List<Channel>) = channels.filter { !confirmedOffline(it) && it.sources.any { url -> sources[SourceKey(it.id, url)]?.verified == true } }
    fun visible(channels: List<Channel>): List<Channel> {
        val navigableIds = navigable(channels).mapTo(hashSetOf()) { it.id }
        return channels.filter { it.id in ChannelVisibilityPolicy.alwaysVisibleIds || it.id in navigableIds }
    }
    fun label(channel: Channel): String = when {
        confirmedOffline(channel) -> "Sin señal confirmada"
        onlineSources(channel).isNotEmpty() -> "Disponible"
        channel.sources.any { sources[SourceKey(channel.id, it)]?.verified == true } -> "Señal intermitente · puedes reintentar"
        else -> "Señal sin confirmar"
    }
    fun isPending(channel: Channel) = channel.sources.any { SourceKey(channel.id, it) in pending }
    fun cancelScan() { pending.clear() }
    val pendingCount: Int get() = pending.size
    companion object { const val CONFIRM_MS = 15_000L; const val FRESH_MS = 10 * 60_000L }
}

object ChannelVisibilityPolicy {
    val alwaysVisibleIds = setOf("UnitelSantaCruz.bo@SD", "UnitelLaPaz.bo@SD", "UnitelCochabamba.bo@SD")
}
