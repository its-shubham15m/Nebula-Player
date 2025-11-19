package com.shubhamgupta.nebula_music.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.models.Video
import java.util.concurrent.TimeUnit

class VideoAdapter(
    private val context: Context,
    private val videos: List<Video>,
    private val onVideoClick: (Video) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.video_thumbnail)
        val title: TextView = itemView.findViewById(R.id.video_title)
        val duration: TextView = itemView.findViewById(R.id.video_duration)
        val resolution: TextView = itemView.findViewById(R.id.video_resolution)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]

        holder.title.text = video.title
        holder.duration.text = formatDuration(video.duration)
        holder.resolution.text = video.resolution ?: "HD"

        Glide.with(context)
            .load(video.uri)
            .apply(RequestOptions().transform(CenterCrop(), RoundedCorners(16)))
            .placeholder(android.R.color.darker_gray)
            .into(holder.thumbnail)

        holder.itemView.setOnClickListener {
            onVideoClick(video)
        }
    }

    override fun getItemCount() = videos.size

    @SuppressLint("DefaultLocale")
    private fun formatDuration(durationMillis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}