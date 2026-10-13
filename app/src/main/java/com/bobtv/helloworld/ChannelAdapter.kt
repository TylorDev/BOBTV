package com.bobtv.helloworld

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.bobtv.helloworld.data.Channel
import com.bobtv.helloworld.data.ChannelNumbers

class ChannelAdapter(context: Context) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private var channels: List<Channel> = emptyList()
    private var numbers = ChannelNumbers(emptyList())
    private var labels: Map<String, String> = emptyMap()
    var highlightedPosition = -1
        set(value) { field = value; notifyDataSetChanged() }

    fun setCatalogue(available: List<Channel>, numbers: ChannelNumbers, labels: Map<String, String> = emptyMap()) {
        this.labels = labels
        channels = available
        this.numbers = numbers
        notifyDataSetChanged()
    }

    override fun getCount() = channels.size
    override fun getItem(position: Int) = channels[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: inflater.inflate(R.layout.channel_row, parent, false)
        val channel = channels[position]
        val number = numbers.numberOf(channel.id)?.let(ChannelNumbers::format).orEmpty()
        row.isActivated = position == highlightedPosition
        row.findViewById<TextView>(R.id.row_number).text = number
        row.findViewById<TextView>(R.id.row_name).text = channel.name
        row.findViewById<TextView>(R.id.row_status).text = labels[channel.id].orEmpty()
        row.contentDescription = row.context.getString(R.string.channel_label, number, channel.name)
        return row
    }
}
