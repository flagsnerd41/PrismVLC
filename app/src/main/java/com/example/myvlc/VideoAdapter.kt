package com.example.myvlc

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

data class VideoItem(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val series: String? = null,
    val season: Int = 0,
    val episode: Int = 0
)

sealed class Row {
    data class Header(val text: String) : Row()
    data class SeriesRow(val name: String, val count: Int, val cover: Uri) : Row()
    data class VideoRow(val item: VideoItem) : Row()
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

class LibraryAdapter(
    private val onSeries: (String) -> Unit,
    private val onVideo: (VideoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.headerText)
    }

    class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val duration: TextView = v.findViewById(R.id.duration)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> 0
        is Row.SeriesRow -> 1
        is Row.VideoRow -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inflater.inflate(R.layout.item_header, parent, false))
        else ItemVH(inflater.inflate(R.layout.item_video, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> {
                (holder as HeaderVH).text.text = row.text
            }
            is Row.SeriesRow -> {
                val h = holder as ItemVH
                h.title.text = row.name
                h.duration.text = row.count.toString() + " episodes"
                Glide.with(h.thumb).load(row.cover).centerCrop().into(h.thumb)
                h.itemView.setOnClickListener { onSeries(row.name) }
            }
            is Row.VideoRow -> {
                val h = holder as ItemVH
                val item = row.item
                h.title.text = item.title
                h.duration.text = if (item.series != null)
                    String.format("S%02dE%02d · %s", item.season, item.episode, formatTime(item.durationMs))
                else formatTime(item.durationMs)
                Glide.with(h.thumb).load(item.uri).centerCrop().into(h.thumb)
                h.itemView.setOnClickListener { onVideo(item) }
            }
        }
    }
}
