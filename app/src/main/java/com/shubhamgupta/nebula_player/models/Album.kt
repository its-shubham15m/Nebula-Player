package com.shubhamgupta.nebula_player.models

import android.os.Parcel
import android.os.Parcelable

data class Album(
    val name: String,
    val artist: String,
    var songCount: Int,
    val songs: MutableList<Song>,
    val albumId: Long
) : Parcelable {

    constructor(parcel: Parcel) : this(
        name = parcel.readString() ?: "Unknown Album",
        artist = parcel.readString() ?: "Unknown Artist",
        songCount = parcel.readInt(),
        songs = parcel.createTypedArrayList(Song.CREATOR)?.toMutableList() ?: mutableListOf(),
        albumId = parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(artist)
        parcel.writeInt(songCount)
        parcel.writeTypedList(songs)
        parcel.writeLong(albumId)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Album> {
        override fun createFromParcel(parcel: Parcel): Album {
            return Album(parcel)
        }

        override fun newArray(size: Int): Array<Album?> {
            return arrayOfNulls(size)
        }
    }
}