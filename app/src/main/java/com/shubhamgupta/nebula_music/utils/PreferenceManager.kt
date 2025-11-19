package com.shubhamgupta.nebula_music.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.models.Playlist
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.service.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PreferenceManager {

    private const val PREFS_NAME = "NebulaMusicPrefs"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_RECENT_SONGS = "recent_songs"
    private const val KEY_PLAYLISTS = "playlists"
    private const val MAX_RECENT_SONGS = 50

    // Playback State Keys
    private const val KEY_LAST_PLAYED_SONG_ID = "last_played_song_id"
    private const val KEY_LAST_SEEKBAR_POSITION = "last_seekbar_position"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_SHUFFLE_MODE = "shuffle_mode"
    private const val KEY_QUEUE_SONGS = "queue_songs"
    private const val KEY_CURRENT_QUEUE_POSITION = "current_queue_position"
    private const val KEY_ORIGINAL_QUEUE_SONGS = "original_queue_songs"
    private const val KEY_QUEUE_HASHMAP = "queue_hashmap"
    private const val KEY_LAST_SONG_DETAILS = "last_song_details"

    // Sorting Keys
    private const val KEY_SORT_SONGS = "sort_songs"
    private const val KEY_SORT_ARTISTS = "sort_artists"
    private const val KEY_SORT_ALBUMS = "sort_albums"
    private const val KEY_SORT_GENRES = "sort_genres"

    // Professional Audio Settings
    private const val KEY_CROSSFADE_ENABLED = "crossfade_enabled"
    private const val KEY_CROSSFADE_DURATION = "crossfade_duration"
    private const val KEY_GAPLESS_PLAYBACK = "gapless_playback"
    private const val KEY_FILTER_SHORT_AUDIO = "filter_short_audio"

    // Future Video Player Settings
    private const val KEY_VIDEO_HW_ACCELERATION = "video_hw_acceleration"
    private const val KEY_VIDEO_BG_PLAYBACK = "video_bg_playback"

    // Cache
    private var cachedSongsMap: Map<Long, Song> = emptyMap()
    private var isCacheDirty = true

    fun init(context: Context) {
        val prefs = getPreferences(context)
        if (!prefs.contains(KEY_CROSSFADE_DURATION)) {
            prefs.edit().putInt(KEY_CROSSFADE_DURATION, 5).apply() // Default 5s
        }
    }

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Audio Settings ---
    fun isCrossfadeEnabled(context: Context): Boolean = getPreferences(context).getBoolean(KEY_CROSSFADE_ENABLED, false)
    fun setCrossfadeEnabled(context: Context, enabled: Boolean) = getPreferences(context).edit().putBoolean(KEY_CROSSFADE_ENABLED, enabled).apply()

    fun getCrossfadeDuration(context: Context): Int = getPreferences(context).getInt(KEY_CROSSFADE_DURATION, 5)
    fun setCrossfadeDuration(context: Context, durationSeconds: Int) = getPreferences(context).edit().putInt(KEY_CROSSFADE_DURATION, durationSeconds).apply()

    fun isGaplessPlaybackEnabled(context: Context): Boolean = getPreferences(context).getBoolean(KEY_GAPLESS_PLAYBACK, true)
    fun setGaplessPlaybackEnabled(context: Context, enabled: Boolean) = getPreferences(context).edit().putBoolean(KEY_GAPLESS_PLAYBACK, enabled).apply()

    fun isFilterShortAudioEnabled(context: Context): Boolean = getPreferences(context).getBoolean(KEY_FILTER_SHORT_AUDIO, true)
    fun setFilterShortAudioEnabled(context: Context, enabled: Boolean) = getPreferences(context).edit().putBoolean(KEY_FILTER_SHORT_AUDIO, enabled).apply()

    // --- Video Settings ---
    fun isVideoHwAccelerationEnabled(context: Context): Boolean = getPreferences(context).getBoolean(KEY_VIDEO_HW_ACCELERATION, true)
    fun setVideoHwAccelerationEnabled(context: Context, enabled: Boolean) = getPreferences(context).edit().putBoolean(KEY_VIDEO_HW_ACCELERATION, enabled).apply()

    fun isVideoBgPlaybackEnabled(context: Context): Boolean = getPreferences(context).getBoolean(KEY_VIDEO_BG_PLAYBACK, false)
    fun setVideoBgPlaybackEnabled(context: Context, enabled: Boolean) = getPreferences(context).edit().putBoolean(KEY_VIDEO_BG_PLAYBACK, enabled).apply()

    // --- Sorting ---
    fun saveSortPreference(context: Context, category: String, sortType: MainActivity.SortType) {
        val key = when (category) {
            "songs" -> KEY_SORT_SONGS
            "artists" -> KEY_SORT_ARTISTS
            "albums" -> KEY_SORT_ALBUMS
            "genres" -> KEY_SORT_GENRES
            else -> KEY_SORT_SONGS
        }
        getPreferences(context).edit().putInt(key, sortType.ordinal).apply()
    }

    fun getSortPreferenceWithDefault(context: Context, category: String): MainActivity.SortType {
        val key = when (category) {
            "songs" -> KEY_SORT_SONGS
            "artists" -> KEY_SORT_ARTISTS
            "albums" -> KEY_SORT_ALBUMS
            "genres" -> KEY_SORT_GENRES
            else -> KEY_SORT_SONGS
        }
        val defaultSort = when (category) {
            "songs" -> MainActivity.SortType.DATE_ADDED_DESC.ordinal
            else -> MainActivity.SortType.NAME_ASC.ordinal
        }
        val sortOrdinal = getPreferences(context).getInt(key, defaultSort)
        return MainActivity.SortType.entries.toTypedArray().getOrElse(sortOrdinal) {
            MainActivity.SortType.entries.toTypedArray()[defaultSort]
        }
    }

    // --- Favorites ---
    fun addFavorite(context: Context, songId: Long) {
        val favorites = getFavorites(context).toMutableSet()
        favorites.add(songId)
        getPreferences(context).edit().putString(KEY_FAVORITES, favorites.joinToString(",")).apply()
    }

    fun removeFavorite(context: Context, songId: Long) {
        val favorites = getFavorites(context).toMutableSet()
        favorites.remove(songId)
        getPreferences(context).edit().putString(KEY_FAVORITES, favorites.joinToString(",")).apply()
    }

    fun getFavorites(context: Context): Set<Long> {
        val favoritesString = getPreferences(context).getString(KEY_FAVORITES, "") ?: ""
        return if (favoritesString.isEmpty()) emptySet()
        else favoritesString.split(",").mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun isFavorite(context: Context, songId: Long): Boolean {
        return getFavorites(context).contains(songId)
    }

    // --- Recent Songs ---
    fun addRecentSong(context: Context, songId: Long) {
        val recentSongs = getRecentSongs(context).toMutableList()
        recentSongs.remove(songId)
        recentSongs.add(0, songId)
        if (recentSongs.size > MAX_RECENT_SONGS) {
            recentSongs.subList(MAX_RECENT_SONGS, recentSongs.size).clear()
        }
        getPreferences(context).edit().putString(KEY_RECENT_SONGS, recentSongs.joinToString(",")).apply()
    }

    fun removeRecentSong(context: Context, songId: Long) {
        val recentSongs = getRecentSongs(context).toMutableList()
        recentSongs.remove(songId)
        getPreferences(context).edit().putString(KEY_RECENT_SONGS, recentSongs.joinToString(",")).apply()
    }

    fun getRecentSongs(context: Context): List<Long> {
        val recentString = getPreferences(context).getString(KEY_RECENT_SONGS, "") ?: ""
        return if (recentString.isEmpty()) emptyList()
        else recentString.split(",").mapNotNull { it.toLongOrNull() }
    }

    // --- Playlists (RESTORED METHODS) ---
    fun savePlaylists(context: Context, playlists: List<Playlist>) {
        val gson = Gson()
        val json = gson.toJson(playlists)
        getPreferences(context).edit().putString(KEY_PLAYLISTS, json).apply()
    }

    fun getPlaylists(context: Context): List<Playlist> {
        val json = getPreferences(context).getString(KEY_PLAYLISTS, null)
        return if (json == null) {
            emptyList()
        } else {
            val type = object : TypeToken<List<Playlist>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        }
    }

    // These were missing in the previous version
    fun addSongToPlaylist(context: Context, playlistId: Long, songId: Long) {
        val playlists = getPlaylists(context).toMutableList()
        playlists.find { it.id == playlistId }?.songIds?.add(songId)
        savePlaylists(context, playlists)
    }

    fun removeSongFromPlaylist(context: Context, playlistId: Long, songId: Long) {
        val playlists = getPlaylists(context).toMutableList()
        playlists.find { it.id == playlistId }?.songIds?.remove(songId)
        savePlaylists(context, playlists)
    }

    // --- Playback State ---
    fun savePlaybackState(
        context: Context,
        currentSongId: Long?,
        seekPosition: Int,
        repeatMode: MusicService.RepeatMode,
        isShuffleMode: Boolean,
        queueSongs: List<Song>,
        currentQueuePosition: Int,
        originalQueueSongs: List<Song>
    ) {
        val editor = getPreferences(context).edit()
        editor.putLong(KEY_LAST_PLAYED_SONG_ID, currentSongId ?: -1L)
        editor.putInt(KEY_LAST_SEEKBAR_POSITION, seekPosition)
        editor.putInt(KEY_REPEAT_MODE, repeatMode.ordinal)
        editor.putBoolean(KEY_SHUFFLE_MODE, isShuffleMode)
        editor.putInt(KEY_CURRENT_QUEUE_POSITION, currentQueuePosition)

        val gson = Gson()
        val queueJson = gson.toJson(queueSongs.map { it.id })
        val originalQueueJson = gson.toJson(originalQueueSongs.map { it.id })

        editor.putString(KEY_QUEUE_SONGS, queueJson)
        editor.putString(KEY_ORIGINAL_QUEUE_SONGS, originalQueueJson)

        val songsMap = hashMapOf<String, Map<String, String>>()
        (queueSongs + originalQueueSongs).forEach { song ->
            songsMap[song.id.toString()] = mapOf(
                "title" to song.title,
                "artist" to (song.artist ?: "Unknown Artist"),
                "album" to (song.album ?: "Unknown Album"),
                "albumId" to song.albumId.toString(),
                "duration" to song.duration.toString(),
                "uri" to song.uri.toString()
            )
        }
        val hashMapJson = gson.toJson(songsMap)
        editor.putString(KEY_QUEUE_HASHMAP, hashMapJson)

        currentSongId?.let { songId ->
            val currentSong = queueSongs.getOrNull(currentQueuePosition) ?: return@let
            val lastSongDetails = mapOf(
                "title" to currentSong.title,
                "artist" to (currentSong.artist ?: "Unknown Artist"),
                "album" to (currentSong.album ?: "Unknown Album"),
                "albumId" to currentSong.albumId.toString()
            )
            editor.putString(KEY_LAST_SONG_DETAILS, gson.toJson(lastSongDetails))
        }

        editor.apply()
    }

    fun savePlaybackStateWithQueueValidation(
        context: Context,
        currentSongId: Long?,
        seekPosition: Int,
        repeatMode: MusicService.RepeatMode,
        isShuffleMode: Boolean,
        queueSongs: List<Song>,
        currentQueuePosition: Int,
        originalQueueSongs: List<Song>
    ) {
        try {
            val validQueueSongs = if (queueSongs.isNotEmpty()) queueSongs else originalQueueSongs
            val validOriginalQueueSongs = if (originalQueueSongs.isNotEmpty()) originalQueueSongs else queueSongs
            val validQueuePosition = if (validQueueSongs.isNotEmpty()) {
                currentQueuePosition.coerceIn(0, validQueueSongs.size - 1)
            } else {
                0
            }

            savePlaybackState(
                context = context,
                currentSongId = currentSongId,
                seekPosition = seekPosition,
                repeatMode = repeatMode,
                isShuffleMode = isShuffleMode,
                queueSongs = validQueueSongs,
                currentQueuePosition = validQueuePosition,
                originalQueueSongs = validOriginalQueueSongs
            )
        } catch (e: Exception) {
            Log.e("PreferenceManager", "Error saving playback state with validation", e)
        }
    }

    fun loadPlaybackState(context: Context): PlaybackState? {
        val prefs = getPreferences(context)
        val lastSongId = prefs.getLong(KEY_LAST_PLAYED_SONG_ID, -1L)

        if (lastSongId == -1L) return null

        val lastSeekPosition = prefs.getInt(KEY_LAST_SEEKBAR_POSITION, 0)
        val repeatMode = prefs.getInt(KEY_REPEAT_MODE, MusicService.RepeatMode.ALL.ordinal)
        val isShuffleMode = prefs.getBoolean(KEY_SHUFFLE_MODE, false)
        val currentQueuePosition = prefs.getInt(KEY_CURRENT_QUEUE_POSITION, 0)

        val queueSongIds = try {
            val queueJson = prefs.getString(KEY_QUEUE_SONGS, null)
            if (queueJson != null) {
                val type = object : TypeToken<List<Long>>() {}.type
                Gson().fromJson<List<Long>>(queueJson, type) ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val originalQueueSongIds = try {
            val originalQueueJson = prefs.getString(KEY_ORIGINAL_QUEUE_SONGS, null)
            if (originalQueueJson != null) {
                val type = object : TypeToken<List<Long>>() {}.type
                Gson().fromJson<List<Long>>(originalQueueJson, type) ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val songsHashMap = try {
            val hashMapJson = prefs.getString(KEY_QUEUE_HASHMAP, null)
            if (hashMapJson != null) {
                val type = object : TypeToken<Map<String, Map<String, String>>>() {}.type
                Gson().fromJson<Map<String, Map<String, String>>>(hashMapJson, type) ?: emptyMap()
            } else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val lastSongDetails = try {
            val detailsJson = prefs.getString(KEY_LAST_SONG_DETAILS, null)
            if (detailsJson != null) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                Gson().fromJson<Map<String, String>>(detailsJson, type) ?: emptyMap()
            } else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        return PlaybackState(
            lastPlayedSongId = if (lastSongId != -1L) lastSongId else null,
            lastSeekPosition = lastSeekPosition,
            repeatMode = repeatMode,
            isShuffleMode = isShuffleMode,
            queueSongIds = queueSongIds,
            originalQueueSongIds = originalQueueSongIds,
            currentQueuePosition = currentQueuePosition,
            songsHashMap = songsHashMap,
            lastSongDetails = lastSongDetails
        )
    }

    fun getLastSongDetails(context: Context): Map<String, String> {
        return try {
            val detailsJson = getPreferences(context).getString(KEY_LAST_SONG_DETAILS, null)
            if (detailsJson != null) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                Gson().fromJson<Map<String, String>>(detailsJson, type) ?: emptyMap()
            } else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun clearPlaybackState(context: Context) {
        val editor = getPreferences(context).edit()
        editor.remove(KEY_LAST_PLAYED_SONG_ID)
        editor.remove(KEY_LAST_SEEKBAR_POSITION)
        editor.remove(KEY_REPEAT_MODE)
        editor.remove(KEY_SHUFFLE_MODE)
        editor.remove(KEY_QUEUE_SONGS)
        editor.remove(KEY_ORIGINAL_QUEUE_SONGS)
        editor.remove(KEY_CURRENT_QUEUE_POSITION)
        editor.remove(KEY_QUEUE_HASHMAP)
        editor.remove(KEY_LAST_SONG_DETAILS)
        editor.apply()
    }

    data class PlaybackState(
        val lastPlayedSongId: Long?,
        val lastSeekPosition: Int,
        val repeatMode: Int,
        val isShuffleMode: Boolean,
        val queueSongIds: List<Long>,
        val originalQueueSongIds: List<Long>,
        val currentQueuePosition: Int,
        val songsHashMap: Map<String, Map<String, String>>,
        val lastSongDetails: Map<String, String>
    )

    suspend fun getCachedSongsMap(context: Context): Map<Long, Song> {
        if (isCacheDirty || cachedSongsMap.isEmpty()) {
            // In a real app, access cache manager here
            // For now, assume cache is handled externally or via SongRepository
        }
        return cachedSongsMap
    }

    suspend fun getSongsForQueue(context: Context, songIds: List<Long>): List<Song> {
        return emptyList()
    }
}