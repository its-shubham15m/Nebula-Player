package com.shubhamgupta.nebula_player.utils

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

object ThemeManager {

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private const val PREF_THEME_KEY = "app_theme"

    fun getCurrentTheme(context: Context): Int {
        val prefs = context.getSharedPreferences("app_theme", Context.MODE_PRIVATE)
        return prefs.getInt(PREF_THEME_KEY, THEME_SYSTEM)
    }

    fun setTheme(context: Context, theme: Int) {
        val prefs = context.getSharedPreferences("app_theme", Context.MODE_PRIVATE)
        prefs.edit { putInt(PREF_THEME_KEY, theme) }
    }

    fun applyTheme(theme: Int) {
        when (theme) {
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun applySavedTheme(context: Context) {
        val savedTheme = getCurrentTheme(context)
        applyTheme(savedTheme)
    }

    fun getThemeName(theme: Int): String {
        return when (theme) {
            THEME_SYSTEM -> "System"
            THEME_LIGHT -> "Light"
            THEME_DARK -> "Dark"
            else -> "System"
        }
    }
}