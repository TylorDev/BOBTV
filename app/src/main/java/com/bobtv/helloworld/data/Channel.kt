package com.bobtv.helloworld.data

data class Channel(val id: String, val name: String, val group: String, val sources: List<String>) {
    init { require(sources.isNotEmpty()) { "Channel requires a source" } }
    constructor(id: String, name: String, group: String, url: String) : this(id, name, group, listOf(url))
    val url: String get() = sources.first()
}

object OfficialChannels {
    val ids = setOf(
        "UnitelSantaCruz.bo@SD", "UnitelLaPaz.bo@SD", "UnitelCochabamba.bo@SD",
        "RedUno.bo@SD", "RedUnoSantaCruz.bo@SD",
        "Bolivision.bo@SD", "BolivisionSantaCruz.bo@SD",
    )
}

object ChannelSelection {
    fun restore(channels: List<Channel>, savedId: String?): Channel? =
        channels.firstOrNull { it.id == savedId } ?: channels.firstOrNull()

    fun nextAvailable(catalogue: List<Channel>, available: List<Channel>, failedId: String): Channel? {
        val activeIds = available.mapTo(hashSetOf()) { it.id }
        val start = catalogue.indexOfFirst { it.id == failedId }
        return (1..catalogue.size).asSequence()
            .map { catalogue[(start + it).mod(catalogue.size)] }
            .firstOrNull { it.id != failedId && it.id in activeIds }
    }
}

class PlaybackRecovery {
    var failedChannelId: String? = null
        private set
    val awaitingAcknowledgement: Boolean get() = failedChannelId != null
    fun fail(id: String) { failedChannelId = id }
    fun clear() { failedChannelId = null }
    fun acknowledge(catalogue: List<Channel>, available: List<Channel>): Channel? {
        val failed = failedChannelId ?: return null
        clear()
        return ChannelSelection.nextAvailable(catalogue, available, failed)
    }
}
