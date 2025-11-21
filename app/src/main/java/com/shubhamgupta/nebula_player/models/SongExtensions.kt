package com.shubhamgupta.nebula_player.models

import android.content.Context
import com.shubhamgupta.nebula_player.utils.PreferenceManager

// Add this extension function to the Song class
fun Song.updateFavoriteStatus(context: Context) {
    if (this.isFavorite) {
        PreferenceManager.addFavorite(context, this.id)
    } else {
        PreferenceManager.removeFavorite(context, this.id)
    }
}

// Optional: You can also add this toggle function for convenience
fun Song.toggleFavorite(context: Context) {
    this.isFavorite = !this.isFavorite
    this.updateFavoriteStatus(context)
}