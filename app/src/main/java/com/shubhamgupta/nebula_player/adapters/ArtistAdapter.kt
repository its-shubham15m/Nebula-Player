package com.shubhamgupta.nebula_player.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Artist

class ArtistAdapter(
    private val artists: List<Artist>,
    private val onArtistClick: (position: Int) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.ArtistVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_artist, parent, false)
        return ArtistVH(view)
    }

    override fun onBindViewHolder(holder: ArtistVH, position: Int) {
        val artist = artists[position]
        holder.artistName.text = artist.name
        holder.songCount.text = "${artist.songCount} songs"

        holder.itemView.setOnClickListener {
            onArtistClick(position)
        }
    }

    override fun getItemCount(): Int = artists.size

    class ArtistVH(view: View) : RecyclerView.ViewHolder(view) {
        val artistName: TextView = view.findViewById(R.id.artist_name)
        val songCount: TextView = view.findViewById(R.id.artist_song_count)
    }
}