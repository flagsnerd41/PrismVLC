package com.example.myvlc

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.progressindicator.LinearProgressIndicator

data class VideoItem(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val series: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val size: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val folder: String = "",
    val added: Long = 0L
)

sealed class Row {
    data class Header(val text: String) : Row()
    data class Note(val text: String) : Row()
    object NewPlaylist : Row()
    object AddVideos : Row()
    data class PlaylistRow(val name: String, val count: Int, val cover: Uri?) : Row()
    data class SeriesRow(val name: String, val count: Int, val cover: Uri) : Row()
    data class FolderRow(val name: String, val count: Int, val cover: Uri) : Row()
    data class VideoRow(val item: VideoItem, val progress: Float = 0f) : Row()
    data class Hero(val item: VideoItem, val progress: Float) : Row()
}

/** Loads a video's cover; videos without one show the app icon on the icon's purple-blue. */
fun loadCover(view: ImageView, uri: Uri) {
    Glide.with(view).load(uri)
        .placeholder(R.drawable.cover_placeholder)
        .error(R.drawable.cover_placeholder)
        .fallback(R.drawable.cover_placeholder)
        .centerCrop()
        .into(view)
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}

class LibraryAdapter(
    private val onSeries: (String) -> Unit,
    private val onFolder: (String) -> Unit,
    private val onVideo: (VideoItem) -> Unit,
    private val onVideoLong: (VideoItem) -> Unit,
    private val onPlaylist: (String) -> Unit,
    private val onPlaylistLong: (String) -> Unit,
    private val onNewPlaylist: () -> Unit,
    private val onAddVideos: () -> Unit,
    private val onHeroRestart: (VideoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val T_HEADER = 0
        const val T_LIST = 1
        const val T_ACTION = 2
        const val T_CARD = 3
        const val T_NOTE = 4
        const val T_HERO = 5
    }

    private var rows: List<Row> = emptyList()
    private var listMode = false

    fun submit(list: List<Row>, asList: Boolean) {
        rows = list
        listMode = asList
        notifyDataSetChanged()
    }

    /** Headers, notes and action rows span the full width; video cards take one column. */
    fun isFullSpan(position: Int): Boolean = getItemViewType(position) != T_CARD

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.headerText)
    }

    class NoteVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.noteText)
    }

    class ActionVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.actionText)
    }

    class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val duration: TextView = v.findViewById(R.id.duration)
        val badge: TextView? = v.findViewById(R.id.badge)
        val progress: LinearProgressIndicator? = v.findViewById(R.id.progressBar)
    }

    class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.heroThumb)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val sub: TextView = v.findViewById(R.id.heroSub)
        val resume: TextView = v.findViewById(R.id.heroResume)
        val restart: TextView = v.findViewById(R.id.heroRestart)
        val progress: LinearProgressIndicator = v.findViewById(R.id.heroProgress)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> T_HEADER
        is Row.Note -> T_NOTE
        is Row.NewPlaylist, is Row.AddVideos -> T_ACTION
        is Row.Hero -> T_HERO
        is Row.PlaylistRow, is Row.SeriesRow, is Row.FolderRow, is Row.VideoRow ->
            if (listMode) T_LIST else T_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_HEADER -> HeaderVH(inflater.inflate(R.layout.item_header, parent, false))
            T_NOTE -> NoteVH(inflater.inflate(R.layout.item_note, parent, false))
            T_ACTION -> ActionVH(inflater.inflate(R.layout.item_action, parent, false))
            T_HERO -> HeroVH(inflater.inflate(R.layout.item_hero, parent, false))
            T_CARD -> ItemVH(inflater.inflate(R.layout.item_card, parent, false))
            else -> ItemVH(inflater.inflate(R.layout.item_video, parent, false))
        }
    }

    /** Small "press" animation, then run the action. */
    private fun press(v: View, action: () -> Unit) {
        v.animate().cancel()
        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            action()
        }.start()
    }

    private fun bindProgress(h: ItemVH, fraction: Float) {
        val bar = h.progress ?: return
        if (fraction > 0f) {
            bar.visibility = View.VISIBLE
            bar.setProgressCompat((fraction * 100).toInt(), false)
        } else {
            bar.visibility = View.GONE
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> {
                (holder as HeaderVH).text.text = row.text
            }
            is Row.Note -> {
                (holder as NoteVH).text.text = row.text
            }
            is Row.Hero -> {
                val h = holder as HeroVH
                val item = row.item
                h.title.text = item.title
                val leftMin = (item.durationMs * (1f - row.progress) / 60000f).toInt()
                val prefix = if (item.series != null)
                    String.format("S%02dE%02d · ", item.season, item.episode) else ""
                h.sub.text = prefix + (if (leftMin >= 1) leftMin.toString() + " min left" else "Almost done")
                h.progress.setProgressCompat((row.progress * 100).toInt(), false)
                loadCover(h.thumb, item.uri)
                h.resume.setOnClickListener { press(h.resume) { onVideo(item) } }
                h.restart.setOnClickListener { press(h.restart) { onHeroRestart(item) } }
                h.itemView.setOnClickListener { press(h.itemView) { onVideo(item) } }
                h.itemView.setOnLongClickListener { onVideoLong(item); true }
            }
            is Row.NewPlaylist -> {
                val h = holder as ActionVH
                h.text.text = "+  New playlist"
                h.itemView.setOnClickListener { onNewPlaylist() }
            }
            is Row.AddVideos -> {
                val h = holder as ActionVH
                h.text.text = "+  Add videos"
                h.itemView.setOnClickListener { onAddVideos() }
            }
            is Row.PlaylistRow -> {
                val h = holder as ItemVH
                h.title.text = row.name
                h.duration.text = row.count.toString() + " videos"
                h.badge?.visibility = View.GONE
                bindProgress(h, 0f)
                val cover = row.cover
                h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                if (cover != null) {
                    loadCover(h.thumb, cover)
                } else {
                    Glide.with(h.thumb).clear(h.thumb)
                    h.thumb.setImageResource(R.drawable.cover_placeholder)
                }
                h.itemView.setOnClickListener { press(h.itemView) { onPlaylist(row.name) } }
                h.itemView.setOnLongClickListener { onPlaylistLong(row.name); true }
            }
            is Row.SeriesRow -> {
                val h = holder as ItemVH
                h.title.text = row.name
                h.duration.text = row.count.toString() + " episodes"
                h.badge?.visibility = View.GONE
                bindProgress(h, 0f)
                h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                loadCover(h.thumb, row.cover)
                h.itemView.setOnClickListener { press(h.itemView) { onSeries(row.name) } }
                h.itemView.setOnLongClickListener(null)
                h.itemView.isLongClickable = false
            }
            is Row.FolderRow -> {
                val h = holder as ItemVH
                h.title.text = row.name
                h.duration.text = row.count.toString() + " videos"
                h.badge?.visibility = View.GONE
                bindProgress(h, 0f)
                h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                loadCover(h.thumb, row.cover)
                h.itemView.setOnClickListener { press(h.itemView) { onFolder(row.name) } }
                h.itemView.setOnLongClickListener(null)
                h.itemView.isLongClickable = false
            }
            is Row.VideoRow -> {
                val h = holder as ItemVH
                val item = row.item
                h.title.text = item.title
                if (listMode) {
                    h.duration.text = if (item.series != null)
                        String.format("S%02dE%02d · %s", item.season, item.episode, formatTime(item.durationMs))
                    else formatTime(item.durationMs)
                    h.badge?.visibility = View.GONE
                } else {
                    h.duration.text = if (item.series != null)
                        String.format("S%02dE%02d · %s", item.season, item.episode, item.series)
                    else ""
                    h.badge?.visibility = View.VISIBLE
                    h.badge?.text = formatTime(item.durationMs)
                }
                bindProgress(h, row.progress)
                h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                loadCover(h.thumb, item.uri)
                h.itemView.setOnClickListener { press(h.itemView) { onVideo(item) } }
                h.itemView.setOnLongClickListener { onVideoLong(item); true }
            }
        }
    }
}
