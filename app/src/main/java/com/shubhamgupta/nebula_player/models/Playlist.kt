package com.shubhamgupta.nebula_player.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class Playlist(
    val id: Long,
    var name: String,
    val songIds: MutableList<Long>,
    val createdAt: Long
) : Parcelable, Serializable {

    // Add default constructor for serialization
    constructor() : this(0L, "", mutableListOf(), 0L)
}