package com.bobtv.helloworld

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.bobtv.helloworld.data.Channel

class ChannelAdapter(context: Context) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    var channels: List<Channel> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    var highlightedPosition = -1
        set(value) { field = value; notifyDataSetChanged() }

    override fun getCount() = channels.size
    override fun getItem(position: Int) = channels[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: inflater.inflate(R.layout.channel_row, parent, false)
        row.isActivated = position == highlightedPosition
        row.findViewById<TextView>(R.id.row_number).text =
            row.context.getString(R.string.channel_number, position + 1)
        row.findViewById<TextView>(R.id.row_name).text = channels[position].name
        row.contentDescription = channels[position].name
        return row
    }
}
