package com.shubhamgupta.nebula_music.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SearchHistoryManager {
    private const val PREF_NAME = "search_history_pref"
    private const val KEY_HISTORY = "history_list"
    private const val MAX_HISTORY_SIZE = 20

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveHistory(context: Context, query: String) {
        if (query.isBlank()) return

        val history = getHistory(context).toMutableList()
        // Remove if exists to move to top
        history.removeAll { it.equals(query, ignoreCase = true) }
        history.add(0, query)

        if (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(history.lastIndex)
        }

        saveList(context, history)
    }

    fun removeHistory(context: Context, query: String) {
        val history = getHistory(context).toMutableList()
        history.removeAll { it.equals(query, ignoreCase = true) }
        saveList(context, history)
    }

    fun getHistory(context: Context): List<String> {
        val json = getPrefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return Gson().fromJson(json, type) ?: emptyList()
    }

    private fun saveList(context: Context, list: List<String>) {
        val json = Gson().toJson(list)
        getPrefs(context).edit().putString(KEY_HISTORY, json).apply()
    }
}