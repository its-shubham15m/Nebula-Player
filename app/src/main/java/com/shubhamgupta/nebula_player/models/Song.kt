package com.shubhamgupta.nebula_player.models

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.core.net.toUri

data class Song(
    val id: Long,
    val title: String,
    val artist: String?,
    val albumId: Long,
    val album: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val uri: Uri,
    val duration: Long = 0,
    val dateAdded: Long = 0,
    val path: String? = null,
    var isFavorite: Boolean = false,
    val embeddedArtBytes: ByteArray? = null // Field for embedded art
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readLong(),
        title = parcel.readString() ?: "Unknown Title",
        artist = parcel.readString(),
        albumId = parcel.readLong(),
        album = parcel.readString(),
        year = parcel.readString(),
        genre = parcel.readString(),
        uri = (parcel.readString() ?: "").toUri(),
        duration = parcel.readLong(),
        dateAdded = parcel.readLong(),
        path = parcel.readString(),
        isFavorite = parcel.readByte() != 0.toByte(),
        // FIX: Replaced parcel.readByteArray() with parcel.createByteArray()
        embeddedArtBytes = parcel.createByteArray()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(title)
        parcel.writeString(artist)
        parcel.writeLong(albumId)
        parcel.writeString(album)
        parcel.writeString(year)
        parcel.writeString(genre)
        parcel.writeString(uri.toString()) // Convert Uri to String for parceling
        parcel.writeLong(duration)
        parcel.writeLong(dateAdded)
        parcel.writeString(path)
        parcel.writeByte(if (isFavorite) 1 else 0)
        parcel.writeByteArray(embeddedArtBytes) // This remains correct
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Song> {
        override fun createFromParcel(parcel: Parcel): Song {
            return Song(parcel)
        }

        override fun newArray(size: Int): Array<Song?> {
            return arrayOfNulls(size)
        }
    }

    // equals and hashCode for ByteArray comparison
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Song

        if (id != other.id) return false
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (albumId != other.albumId) return false
        if (album != other.album) return false
        if (genre != other.genre) return false
        if (year != other.year) return false
        if (uri != other.uri) return false
        if (duration != other.duration) return false
        if (dateAdded != other.dateAdded) return false
        if (path != other.path) return false
        if (isFavorite != other.isFavorite) return false
        if (embeddedArtBytes != null) {
            if (other.embeddedArtBytes == null) return false
            if (!embeddedArtBytes.contentEquals(other.embeddedArtBytes)) return false
        } else if (other.embeddedArtBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + albumId.hashCode()
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (genre?.hashCode() ?: 0)
        result = 31 * result + (year?.hashCode() ?: 0)
        result = 31 * result + uri.hashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + dateAdded.hashCode()
        result = 31 * result + (path?.hashCode() ?: 0)
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (embeddedArtBytes?.contentHashCode() ?: 0)
        return result
    }
}