package com.shubhamgupta.nebula_player.models

import android.os.Parcel
import android.os.Parcelable

data class Artist(
    val name: String,
    var songCount: Int,
    val songs: MutableList<Song>
) : Parcelable {

    constructor(parcel: Parcel) : this(
        name = parcel.readString() ?: "Unknown Artist",
        songCount = parcel.readInt(),
        songs = parcel.createTypedArrayList(Song.CREATOR)?.toMutableList() ?: mutableListOf()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeInt(songCount)
        parcel.writeTypedList(songs)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Artist> {
        override fun createFromParcel(parcel: Parcel): Artist {
            return Artist(parcel)
        }

        override fun newArray(size: Int): Array<Artist?> {
            return arrayOfNulls(size)
        }
    }
}