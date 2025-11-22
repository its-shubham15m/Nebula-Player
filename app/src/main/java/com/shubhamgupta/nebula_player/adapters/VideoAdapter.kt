package com.shubhamgupta.nebula_player.adapters

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Video
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.Locale

// Sealed class to handle both Videos and Folders in one list
sealed class VideoUiModel {
    data class VideoItem(val video: Video) : VideoUiModel()
    data class FolderItem(val name: String, val count: Int, val firstVideo: Video?) : VideoUiModel()
}

class VideoAdapter(
    private val context: Context,
    private val items: List<VideoUiModel>,
    private val onItemClick: (VideoUiModel) -> Unit,
    private val onDeleteRequest: (Video) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // We need the Container (CardView) to set the click listener specifically on it
        val cardContainer: View = itemView.findViewById(R.id.video_card_container)
        val thumbnail: ImageView = itemView.findViewById(R.id.video_thumbnail)
        val title: TextView = itemView.findViewById(R.id.video_title)
        val fileInfo: TextView = itemView.findViewById(R.id.video_file_info) // New TextView
        val duration: TextView = itemView.findViewById(R.id.video_duration)
        val resolution: TextView = itemView.findViewById(R.id.video_resolution)
        val options: ImageButton = itemView.findViewById(R.id.btn_options)
        val playIcon: ImageView? = itemView.findViewById(R.id.icon_play_overlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]

        when (item) {
            is VideoUiModel.VideoItem -> bindVideo(holder, item.video)
            is VideoUiModel.FolderItem -> bindFolder(holder, item)
        }
    }

    private fun bindVideo(holder: VideoViewHolder, video: Video) {
        holder.title.text = video.title
        holder.duration.text = formatDuration(video.duration)

        // --- FIX 1: Resolution Display (1080p, 1440p, etc) ---
        holder.resolution.text = formatResolution(video.resolution)

        // --- FIX 2: File Size and Path (e.g., "1.2 GB • WhatsApp Video") ---
        val file = File(video.path)
        val sizeString = formatFileSize(file.length())
        val folderName = file.parentFile?.name ?: "Unknown"
        holder.fileInfo.text = "$sizeString • $folderName"

        // Visibility checks
        holder.duration.isVisible = true
        holder.resolution.isVisible = true
        holder.options.isVisible = true
        holder.fileInfo.isVisible = true
        holder.playIcon?.isVisible = true

        Glide.with(context)
            .load(video.uri)
            .apply(RequestOptions().transform(CenterCrop(), RoundedCorners(16)))
            .placeholder(android.R.color.darker_gray)
            .into(holder.thumbnail)

        // Set Click Listeners
        val playListener = View.OnClickListener { onItemClick(VideoUiModel.VideoItem(video)) }

        holder.cardContainer.setOnClickListener(playListener)
        holder.title.setOnClickListener(playListener)
        // Optional: Make the info text clickable as well to play
        holder.fileInfo.setOnClickListener(playListener)

        // Remove listener from root to avoid conflicts
        holder.itemView.setOnClickListener(null)

        holder.options.setOnClickListener { showVideoOptions(it, video) }
    }

    private fun bindFolder(holder: VideoViewHolder, folder: VideoUiModel.FolderItem) {
        holder.title.text = folder.name
        holder.duration.text = "${folder.count} videos"

        // For folders, we can hide the detailed file info or show summary
        holder.fileInfo.text = "Folder"

        holder.resolution.isVisible = false
        holder.options.isVisible = false
        holder.playIcon?.isVisible = false
        holder.duration.isVisible = true
        holder.fileInfo.isVisible = true

        if (folder.firstVideo != null) {
            Glide.with(context)
                .load(folder.firstVideo.uri)
                .apply(RequestOptions().transform(CenterCrop(), RoundedCorners(16)))
                .placeholder(R.drawable.ic_playlist)
                .into(holder.thumbnail)
        } else {
            holder.thumbnail.setImageResource(R.drawable.ic_playlist)
        }

        // Set listeners for Folder navigation as well
        val openFolderListener = View.OnClickListener { onItemClick(folder) }
        holder.cardContainer.setOnClickListener(openFolderListener)
        holder.title.setOnClickListener(openFolderListener)
        holder.fileInfo.setOnClickListener(openFolderListener)
        holder.itemView.setOnClickListener(null)
    }

    private fun showVideoOptions(view: View, video: Video) {
        val popup = PopupMenu(context, view)
        popup.menu.add(0, 1, 0, "Play")
        popup.menu.add(0, 2, 0, "Add to Playlist")
        popup.menu.add(0, 3, 0, "Share")
        popup.menu.add(0, 4, 0, "Delete")
        popup.menu.add(0, 5, 0, "Properties")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> onItemClick(VideoUiModel.VideoItem(video))
                2 -> addToPlaylist(video)
                3 -> shareVideo(video)
                4 -> confirmDelete(video)
                5 -> showProperties(video)
            }
            true
        }
        popup.show()
    }

    private fun addToPlaylist(video: Video) {
        val options = arrayOf("+ Create New Playlist", "Favorites")

        AlertDialog.Builder(context)
            .setTitle("Add to Playlist")
            .setItems(options) { _, which ->
                if (which == 0) {
                    val input = EditText(context)
                    AlertDialog.Builder(context)
                        .setTitle("New Playlist")
                        .setView(input)
                        .setPositiveButton("Create") { _, _ ->
                            Toast.makeText(context, "Playlist '${input.text}' created", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                } else {
                    Toast.makeText(context, "Added to Favorites", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun shareVideo(video: Video) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, video.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Video"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error sharing video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(video: Video) {
        AlertDialog.Builder(context)
            .setTitle("Delete Video?")
            .setMessage("Delete '${video.title}' permanently?")
            .setPositiveButton("Delete") { _, _ -> onDeleteRequest(video) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProperties(video: Video) {
        val sizeMB = File(video.path).length() / (1024 * 1024).toFloat()
        val info = "File: ${video.title}\nSize: ${String.format("%.2f", sizeMB)} MB\nPath: ${video.path}"
        Toast.makeText(context, info, Toast.LENGTH_LONG).show()
    }

    override fun getItemCount() = items.size

    @SuppressLint("DefaultLocale")
    private fun formatDuration(durationMillis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun formatFileSize(sizeBytes: Long): String {
        val sizeMB = sizeBytes / (1024.0 * 1024.0)
        return if (sizeMB >= 1024) {
            String.format(Locale.US, "%.1f GB", sizeMB / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", sizeMB)
        }
    }

    /**
     * Robust resolution formatter.
     * Handles "2560x1440", "1920x1080", "1280 x 720", etc.
     */
    private fun formatResolution(resolutionString: String?): String {
        if (resolutionString.isNullOrEmpty()) return ""

        try {
            // Normalize string: remove spaces, convert to lowercase
            val cleaned = resolutionString.replace(" ", "").lowercase(Locale.US)

            // Split by common separators (x, *)
            val parts = cleaned.split("x", "*")

            // We need at least 2 parts (Width x Height)
            if (parts.size >= 2) {
                // Usually the second part is height in landscape video, but checking both is safer.
                // We usually label by the smaller dimension (e.g. 1920x1080 -> 1080p).
                val dim1 = parts[0].toIntOrNull() ?: 0
                val dim2 = parts[1].toIntOrNull() ?: 0

                // Get the smaller dimension (usually height in landscape)
                val height = if (dim1 < dim2) dim1 else dim2
                // Exception: if it's vertical video (1080x1920), smallest is 1080.

                return when {
                    height >= 2160 -> "4K"
                    height >= 1440 -> "1440p" // QHD
                    height >= 1080 -> "1080p" // FHD
                    height >= 720 -> "720p"   // HD
                    height >= 480 -> "480p"
                    height >= 360 -> "360p"
                    height >= 240 -> "240p"
                    else -> "${height}p"
                }
            }
        } catch (e: Exception) {
            return resolutionString // Fallback to original if parsing fails
        }
        return resolutionString
    }
}