package com.shubhamgupta.nebula_player.models

import com.google.gson.annotations.SerializedName

data class LrcLibLyrics(
    @SerializedName("id") val id: Int,
    @SerializedName("trackName") val trackName: String,
    @SerializedName("artistName") val artistName: String,
    @SerializedName("albumName") val albumName: String?,
    @SerializedName("duration") val duration: Int,
    @SerializedName("instrumental") val instrumental: Boolean,
    @SerializedName("plainLyrics") val plainLyrics: String?,
    @SerializedName("syncedLyrics") val syncedLyrics: String?
)