package com.shubhamgupta.nebula_player.adapters

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.SectionIndexer
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Playlist
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils
import java.io.File
import java.util.UUID
import androidx.core.graphics.toColorInt

class SongAdapter(
    private val context: Context,
    private val songs: MutableList<Song>,
    private val onItemClick: (position: Int) -> Unit,
    private val onDataChanged: () -> Unit,
    private val onDeleteRequest: (song: Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongVH>(), SectionIndexer {

    private var sections: SparseArray<Int> = SparseArray()
    private var sectionLetters: MutableList<String> = mutableListOf()

    init {
        setupSections()
    }

    private fun setupSections() {
        sections.clear()
        sectionLetters.clear()

        if (songs.isNotEmpty()) {
            var sectionStart = 0
            var currentSection = songs[0].title[0].uppercaseChar()

            for (i in songs.indices) {
                val firstChar = songs[i].title[0].uppercaseChar()
                if (firstChar != currentSection) {
                    sections.put(currentSection.code, sectionStart)
                    sectionLetters.add(currentSection.toString())
                    currentSection = firstChar
                    sectionStart = i
                }
            }
            sections.put(currentSection.code, sectionStart)
            sectionLetters.add(currentSection.toString())
        }
    }

    override fun getSections(): Array<String> {
        return sectionLetters.toTypedArray()
    }

    override fun getPositionForSection(sectionIndex: Int): Int {
        return if (sectionIndex < sectionLetters.size) {
            val sectionChar = sectionLetters[sectionIndex][0]
            sections.get(sectionChar.code, 0)
        } else {
            0
        }
    }

    override fun getSectionForPosition(position: Int): Int {
        var section = 0
        for (i in sectionLetters.indices) {
            if (getPositionForSection(i) <= position) {
                section = i
            }
        }
        return section
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongVH(view)
    }

    override fun onBindViewHolder(holder: SongVH, position: Int) {
        val song = songs[position]
        holder.title.text = song.title
        holder.artist.text = song.artist ?: "Unknown"

        val albumUri = SongUtils.getAlbumArtUri(song.albumId)
        Glide.with(holder.itemView.context)
            .load(albumUri)
            .placeholder(R.drawable.default_album_art)
            .error(R.drawable.default_album_art)
            .into(holder.albumArt)

        holder.itemView.setOnClickListener { onItemClick(position) }

        holder.options.setOnClickListener { view ->
            showPopupMenu(view, position)
        }
    }

    override fun getItemCount(): Int = songs.size

    private fun showPopupMenu(view: View, position: Int) {
        if (position < 0 || position >= songs.size) {
            Log.e("SongAdapter", "Invalid position for popup menu: $position")
            return
        }
        val song = songs[position]
        val isFavorite = PreferenceManager.isFavorite(context, song.id)

        val popup = PopupMenu(view.context, view)
        popup.menuInflater.inflate(R.menu.song_options_menu, popup.menu)

        popup.menu.findItem(R.id.menu_title)?.title = song.title

        val favoriteItem = popup.menu.findItem(R.id.menu_toggle_favorite)
        if (isFavorite) {
            favoriteItem.title = "Remove from Favorites"
            favoriteItem.setIcon(R.drawable.ic_favorite_filled)
            val primaryColor = "#DE3163".toColorInt()
            favoriteItem.icon?.setTint(primaryColor)
        } else {
            favoriteItem.title = "Add to Favorites"
            favoriteItem.setIcon(R.drawable.ic_favorite_outline)
        }

        popup.setOnMenuItemClickListener { item ->
            if (position < 0 || position >= songs.size) {
                Log.e("SongAdapter", "Position $position became invalid during menu click.")
                return@setOnMenuItemClickListener true
            }
            when (item.itemId) {
                R.id.menu_play -> onItemClick(position)
                R.id.menu_add_to_queue -> addToQueue(song)
                R.id.menu_add_to_playlist -> showAddToPlaylistDialog(song)
                R.id.menu_delete -> confirmDelete(song, position)
                R.id.menu_share -> shareSongFile(song)
                R.id.menu_toggle_favorite -> toggleFavorite(song, position)
            }
            true
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(mPopup, true)
        } catch (e: Exception) {
            Log.e("SongAdapter", "Error showing menu icons.", e)
        }

        popup.show()
    }

    private fun addToQueue(song: Song) {
        Toast.makeText(context, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFavorite(song: Song, position: Int) {
        if (PreferenceManager.isFavorite(context, song.id)) {
            PreferenceManager.removeFavorite(context, song.id)
            Toast.makeText(context, "Removed from Favorites", Toast.LENGTH_SHORT).show()
        } else {
            PreferenceManager.addFavorite(context, song.id)
            Toast.makeText(context, "Added to Favorites", Toast.LENGTH_SHORT).show()
        }
        notifyItemChanged(position)
        onDataChanged()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val playlists = PreferenceManager.getPlaylists(context).toMutableList()
        val playlistNames = playlists.map { it.name }.toMutableList()
        playlistNames.add(0, "+ Create New Playlist")

        AlertDialog.Builder(context)
            .setTitle("Add to Playlist: ${song.title}")
            .setItems(playlistNames.toTypedArray()) { dialog, which ->
                if (which == 0) {
                    showCreateNewPlaylistDialog(song)
                } else {
                    val playlist = playlists[which - 1]
                    if (!playlist.songIds.contains(song.id)) {
                        PreferenceManager.addSongToPlaylist(context, playlist.id, song.id)
                        Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "${song.title} is already in ${playlist.name}", Toast.LENGTH_SHORT).show()
                    }
                }
                onDataChanged()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateNewPlaylistDialog(song: Song) {
        val editText = EditText(context)
        editText.hint = "Playlist Name"

        AlertDialog.Builder(context)
            .setTitle("Create New Playlist")
            .setView(editText)
            .setPositiveButton("Create") { dialog, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val newPlaylist = Playlist(
                        id = System.currentTimeMillis() + UUID.randomUUID().mostSignificantBits,
                        name = newName,
                        songIds = mutableListOf(song.id),
                        createdAt = System.currentTimeMillis()
                    )
                    val existingPlaylists = PreferenceManager.getPlaylists(context).toMutableList()
                    existingPlaylists.add(newPlaylist)
                    PreferenceManager.savePlaylists(context, existingPlaylists)
                    Toast.makeText(context, "Playlist '$newName' created and song added.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Playlist name cannot be empty.", Toast.LENGTH_SHORT).show()
                }
                onDataChanged()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareSongFile(song: Song) {
        try {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, song.uri)
                type = context.contentResolver.getType(song.uri) ?: "audio/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share ${song.title}"))
        } catch (e: Exception) {
            Log.e("SongAdapter", "Error sharing song file using content URI", e)
            Toast.makeText(context, "Could not share file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(song: Song, position: Int) {
        AlertDialog.Builder(context)
            .setTitle("Delete Song?")
            .setMessage("Are you sure you want to permanently delete '${song.title}' from your device? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleteSong(song, position)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSong(song: Song, position: Int) {
        onDeleteRequest(song)
    }

    class SongVH(view: View) : RecyclerView.ViewHolder(view) {
        val albumArt: ImageView = view.findViewById(R.id.item_album_art)
        val title: TextView = view.findViewById(R.id.item_title)
        val artist: TextView = view.findViewById(R.id.item_artist)
        val options: ImageView = view.findViewById(R.id.item_options)
    }
}