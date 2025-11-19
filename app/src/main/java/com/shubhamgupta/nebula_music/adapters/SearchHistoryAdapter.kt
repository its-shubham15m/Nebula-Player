package com.shubhamgupta.nebula_music.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_music.R

class SearchHistoryAdapter(
    private var historyList: MutableList<String>,
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.HistoryViewHolder>() {

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvQuery: TextView = itemView.findViewById(R.id.tv_history_query)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btn_history_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val query = historyList[position]
        holder.tvQuery.text = query

        holder.itemView.setOnClickListener { onItemClick(query) }

        holder.btnDelete.setOnClickListener {
            val actualPosition = holder.bindingAdapterPosition
            if (actualPosition != RecyclerView.NO_POSITION) {
                onDeleteClick(query)
                historyList.removeAt(actualPosition)
                notifyItemRemoved(actualPosition)
            }
        }
    }

    override fun getItemCount(): Int = historyList.size

    fun updateData(newList: List<String>) {
        historyList.clear()
        historyList.addAll(newList)
        notifyDataSetChanged()
    }
}