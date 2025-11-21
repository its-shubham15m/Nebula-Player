package com.shubhamgupta.nebula_player.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.imageview.ShapeableImageView
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.SongUtils
import java.util.concurrent.TimeUnit

class SongSelectionAdapter(
    private var songs: List<Song>,
    selectedSongIds: Set<Long>,
    private val onSongSelected: ((Long, Boolean) -> Unit)?
) : RecyclerView.Adapter<SongSelectionAdapter.SongSelectionViewHolder>() {

    private var filteredSongs: List<Song> = songs
    private val selectedSongs = selectedSongIds.toMutableSet()

    inner class SongSelectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkbox: MaterialCheckBox = itemView.findViewById(R.id.checkbox_selected)
        val albumArt: ShapeableImageView = itemView.findViewById(R.id.iv_album_art)
        val title: TextView = itemView.findViewById(R.id.tv_song_title)
        val artist: TextView = itemView.findViewById(R.id.tv_artist)
        val duration: TextView = itemView.findViewById(R.id.tv_duration)

        init {
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = filteredSongs[position]
                    if (isChecked) {
                        selectedSongs.add(song.id)
                    } else {
                        selectedSongs.remove(song.id)
                    }
                    onSongSelected?.invoke(song.id, isChecked)
                }
            }

            itemView.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }
        }

        fun bind(song: Song) {
            title.text = song.title
            artist.text = song.artist ?: "Unknown Artist"

            // Format duration
            val minutes = TimeUnit.MILLISECONDS.toMinutes(song.duration)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(song.duration) -
                    TimeUnit.MINUTES.toSeconds(minutes)
            duration.text = String.format("%d:%02d", minutes, seconds)

            // Load album art
            val albumUri = SongUtils.getAlbumArtUri(song.albumId)
            Glide.with(itemView.context)
                .load(albumUri)
                .placeholder(R.drawable.default_album_art)
                .error(R.drawable.default_album_art)
                .into(albumArt)

            // Set checkbox state
            checkbox.isChecked = selectedSongs.contains(song.id)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongSelectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song_selection, parent, false)
        return SongSelectionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongSelectionViewHolder, position: Int) {
        val song = filteredSongs[position]
        holder.bind(song)
    }

    override fun getItemCount(): Int = filteredSongs.size

    fun filterSongs(query: String) {
        filteredSongs = if (query.isBlank()) {
            songs
        } else {
            songs.filter { song ->
                song.title.contains(query, true) ||
                        song.artist?.contains(query, true) == true ||
                        song.album?.contains(query, true) == true
            }
        }
        notifyDataSetChanged()
    }

    fun getSelectedSongs(): List<Long> = selectedSongs.toList()

    fun selectAll() {
        selectedSongs.clear()
        selectedSongs.addAll(filteredSongs.map { it.id })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedSongs.clear()
        notifyDataSetChanged()
    }

    fun updateSongs(newSongs: List<Song>) {
        songs = newSongs
        filteredSongs = newSongs
        notifyDataSetChanged()
    }

    fun getTotalSongsCount(): Int = songs.size

    fun getFilteredSongsCount(): Int = filteredSongs.size

    fun getSelectedSongsCount(): Int = selectedSongs.size
}