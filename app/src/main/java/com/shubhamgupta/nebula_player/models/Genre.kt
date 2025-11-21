package com.shubhamgupta.nebula_player.models

import android.os.Parcel
import android.os.Parcelable

data class Genre(
    val name: String,
    var songCount: Int,
    val songs: MutableList<Song>
) : Parcelable {

    constructor(parcel: Parcel) : this(
        name = parcel.readString() ?: "Unknown Genre",
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

    companion object CREATOR : Parcelable.Creator<Genre> {
        override fun createFromParcel(parcel: Parcel): Genre {
            return Genre(parcel)
        }

        override fun newArray(size: Int): Array<Genre?> {
            return arrayOfNulls(size)
        }
    }
}