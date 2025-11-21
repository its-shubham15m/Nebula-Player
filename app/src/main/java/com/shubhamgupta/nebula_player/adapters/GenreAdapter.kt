package com.shubhamgupta.nebula_player.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Genre

class GenreAdapter(
    private val genres: List<Genre>,
    private val onGenreClick: (position: Int) -> Unit
) : RecyclerView.Adapter<GenreAdapter.GenreVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GenreVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_genre, parent, false)
        return GenreVH(view)
    }

    override fun onBindViewHolder(holder: GenreVH, position: Int) {
        val genre = genres[position]
        holder.genreName.text = genre.name
        holder.songCount.text = "${genre.songCount} songs"

        holder.itemView.setOnClickListener {
            onGenreClick(position)
        }
    }

    override fun getItemCount(): Int = genres.size

    class GenreVH(view: View) : RecyclerView.ViewHolder(view) {
        val genreName: TextView = view.findViewById(R.id.genre_name)
        val songCount: TextView = view.findViewById(R.id.genre_song_count)
    }
}