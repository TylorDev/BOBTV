package com.bobtv.helloworld.data

/** Numbers belong to the complete catalogue, independently of source availability. */
class ChannelNumbers(catalogue: List<Channel>) {
    private val channels = catalogue.toList()
    private val positions = channels.mapIndexed { index, channel -> channel.id to index }.toMap()
    val ids: List<String> = channels.map { it.id }

    init {
        require(channels.size <= MAX_CHANNELS) { "Playlist exceeds $MAX_CHANNELS channels" }
        require(positions.size == channels.size) { "Channel identifiers must be unique" }
    }

    fun numberOf(id: String?): Int? = positions[id]?.plus(1)

    fun channelAt(number: Int): Channel? =
        if (number in 1..MAX_CHANNELS) channels.getOrNull(number - 1) else null

    fun resolve(number: Int, available: List<Channel>): Target {
        val channel = channelAt(number) ?: return Target.Missing
        return if (available.any { it.id == channel.id }) Target.Available(channel)
        else Target.Unavailable
    }

    fun availableInOrder(available: List<Channel>): List<Channel> {
        val activeIds = available.mapTo(hashSetOf()) { it.id }
        return channels.filter { it.id in activeIds }
    }

    fun adjacent(currentId: String?, available: List<Channel>, direction: Int): Channel? {
        require(direction == 1 || direction == -1) { "Direction must be next or previous" }
        val active = availableInOrder(available)
        if (active.isEmpty()) return null
        val start = positions[currentId] ?: return if (direction > 0) active.first() else active.last()
        val activeIds = active.mapTo(hashSetOf()) { it.id }
        return (1..channels.size).asSequence()
            .map { offset -> channels[(start + offset * direction).mod(channels.size)] }
            .firstOrNull { it.id in activeIds }
    }

    sealed class Target {
        data class Available(val channel: Channel) : Target()
        object Unavailable : Target()
        object Missing : Target()
    }

    companion object {
        const val MAX_CHANNELS = 999

        fun format(number: Int): String {
            require(number in 1..MAX_CHANNELS) { "Channel number must be between 1 and $MAX_CHANNELS" }
            return number.toString().padStart(2, '0')
        }
    }
}
