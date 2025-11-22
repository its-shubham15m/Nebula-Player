package com.shubhamgupta.nebula_player.api

import com.shubhamgupta.nebula_player.models.LrcLibLyrics
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface LrcLibApi {

    // Strict match endpoint - requires reasonably accurate duration
    @GET("api/get")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("album_name") albumName: String?,
        @Query("duration") duration: Int?
    ): LrcLibLyrics

    // Search endpoint - useful as fallback if strict match fails
    @GET("api/search")
    suspend fun searchLyrics(
        @Query("q") query: String
    ): List<LrcLibLyrics>
}

object LrcLibApiClient {
    private const val BASE_URL = "https://lrclib.net/"

    val api: LrcLibApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LrcLibApi::class.java)
    }
}