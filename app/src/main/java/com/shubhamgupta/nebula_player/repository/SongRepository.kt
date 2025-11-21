package com.shubhamgupta.nebula_player.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongCacheManager

object SongRepository {

    fun getAllSongs(context: Context): List<Song> {
        val songs = mutableListOf<Song>()

        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.GENRE,
                MediaStore.Audio.Media.DATA // For file path
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"

            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                MediaStore.Audio.Media.TITLE + " ASC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val yearIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val genreIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
                val dataIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (it.moveToNext()) {
                    val id = it.getLong(idIndex)
                    val title = it.getString(titleIndex) ?: "Unknown Title"
                    val artist = it.getString(artistIndex) ?: "Unknown Artist"
                    val album = it.getString(albumIndex)
                    val albumId = it.getLong(albumIdIndex)
                    val duration = it.getLong(durationIndex)
                    val dateAdded = it.getLong(dateAddedIndex) * 1000 // Convert to milliseconds
                    val year = it.getString(yearIndex)
                    val genre = it.getString(genreIndex)
                    val path = it.getString(dataIndex)

                    val uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    // Get favorite status from PreferenceManager
                    val isFavorite = PreferenceManager.isFavorite(context, id)

                    songs.add(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            albumId = albumId,
                            album = album,
                            year = year,
                            genre = genre,
                            uri = uri,
                            duration = duration,
                            dateAdded = dateAdded,
                            path = path,
                            isFavorite = isFavorite
                        )
                    )
                }
            }
            Log.d("SongRepository", "Loaded ${songs.size} songs")
        } catch (e: SecurityException) {
            Log.e("SongRepository", "Permission denied", e)
        } catch (e: Exception) {
            Log.e("SongRepository", "Error loading songs", e)
        }

        SongCacheManager.getAllSongs()
        return songs
    }
}