package com.bobtv.helloworld.data

data class Channel(val id: String, val name: String, val group: String, val url: String)

object ChannelSelection {
    fun restore(channels: List<Channel>, savedId: String?): Channel =
        channels.firstOrNull { it.id == savedId } ?: channels.first()
}
