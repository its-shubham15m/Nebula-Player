package com.shubhamgupta.nebula_player.models

import android.net.Uri

data class Video(
    val id: Long,
    val title: String,
    val duration: Long,
    val size: Long,
    val path: String,
    val uri: Uri,
    val dateAdded: Long,
    val resolution: String?
)