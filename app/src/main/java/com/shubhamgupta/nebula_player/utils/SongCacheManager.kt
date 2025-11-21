package com.shubhamgupta.nebula_player.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shubhamgupta.nebula_player.models.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object SongCacheManager {
    private const val TAG = "SongCacheManager"
    private const val SONG_CACHE_FILENAME = "song_cache.json"

    private val songsCache = ConcurrentHashMap<Long, Song>()
    @Volatile
    private var allSongsCache: List<Song> = emptyList()

    @Volatile
    private var isCacheInitialized = false

    // Properties for time-based cache updates
    @Volatile
    private var lastCacheUpdateTime = 0L
    private const val CACHE_UPDATE_INTERVAL = 5 * 60 * 1000L // 5 minutes

    fun initializeCache(context: Context) {
        if (isCacheInitialized) return

        CoroutineScope(Dispatchers.IO).launch {
            val songsFromFile = loadCacheFromFile(context.applicationContext)
            if (!songsFromFile.isNullOrEmpty()) {
                Log.d(TAG, "Cache initialized from file with ${songsFromFile.size} songs.")
                updateCache(songsFromFile)
                isCacheInitialized = true
                lastCacheUpdateTime = System.currentTimeMillis() // Set time on load
            } else {
                Log.d(TAG, "Cache file not found. Initializing from MediaStore...")
                val songsFromMediaStore = loadSongsFromMediaStore(context.applicationContext)
                if (songsFromMediaStore.isNotEmpty()) {
                    updateCache(songsFromMediaStore)
                    isCacheInitialized = true
                    lastCacheUpdateTime = System.currentTimeMillis() // Set time on new scan
                    saveCacheToFile(context.applicationContext, songsFromMediaStore)
                    Log.d(TAG, "Cache initialized from MediaStore with ${songsFromMediaStore.size} songs and saved to file.")
                }
            }
        }
    }

    fun refreshCache(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Refreshing cache from MediaStore...")
            val songs = loadSongsFromMediaStore(context.applicationContext)
            updateCache(songs)
            lastCacheUpdateTime = System.currentTimeMillis() // Update time on refresh
            saveCacheToFile(context.applicationContext, songs)
            Log.d(TAG, "Cache refreshed with ${songs.size} songs and saved to file.")
        }
    }

    // THIS IS THE FUNCTION THAT WAS MISSING OR NOT SAVED CORRECTLY
    /**
     * Checks if the cache is stale and needs to be updated based on a time interval.
     */
    fun shouldUpdateCache(): Boolean {
        return !isCacheInitialized || (System.currentTimeMillis() - lastCacheUpdateTime > CACHE_UPDATE_INTERVAL)
    }

    private fun saveCacheToFile(context: Context, songs: List<Song>) {
        try {
            val gson = Gson()
            val jsonString = gson.toJson(songs)
            val cacheFile = File(context.filesDir, SONG_CACHE_FILENAME)
            cacheFile.writeText(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cache to file", e)
        }
    }

    private fun loadCacheFromFile(context: Context): List<Song>? {
        try {
            val cacheFile = File(context.filesDir, SONG_CACHE_FILENAME)
            if (!cacheFile.exists() || cacheFile.readText().isBlank()) return null
            val jsonString = cacheFile.readText()
            val type = object : TypeToken<List<Song>>() {}.type
            return Gson().fromJson(jsonString, type)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache from file", e)
            return null
        }
    }

    private fun loadSongsFromMediaStore(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.GENRE,
                MediaStore.Audio.Media.DATA
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
            val cursor = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, MediaStore.Audio.Media.TITLE + " ASC")
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
                    val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    songs.add(Song(id, it.getString(titleIndex) ?: "Unknown Title", it.getString(artistIndex) ?: "Unknown Artist", it.getLong(albumIdIndex),
                        it.getString(albumIndex), it.getString(yearIndex), it.getString(genreIndex), uri, it.getLong(durationIndex),
                        it.getLong(dateAddedIndex) * 1000, it.getString(dataIndex), PreferenceManager.isFavorite(context, id))
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading songs from MediaStore", e)
        }
        return songs
    }

    fun getAllSongs(): List<Song> = allSongsCache
    fun getSongById(id: Long): Song? = songsCache[id]

    private suspend fun updateCache(songs: List<Song>) {
        val newMap = ConcurrentHashMap<Long, Song>()
        songs.forEach { song -> newMap[song.id] = song }
        withContext(Dispatchers.Main) {
            songsCache.clear()
            songsCache.putAll(newMap)
            allSongsCache = songs
        }
    }
}