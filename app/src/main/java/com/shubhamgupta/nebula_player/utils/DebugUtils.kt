package com.shubhamgupta.nebula_player.utils

import android.util.Log

object DebugUtils {
    private const val TAG = "NebulaMusic"
    private var isDebug = false

    fun initialize(isDebugBuild: Boolean) {
        isDebug = isDebugBuild
    }

    fun logDebug(message: String) {
        if (isDebug) {
            Log.d(TAG, message)
        }
    }

    fun logError(message: String, exception: Exception? = null) {
        if (isDebug) {
            Log.e(TAG, message, exception)
        } else {
            // In release, you might want to log to crash reporting service
            Log.e(TAG, message)
        }
    }

    fun logInfo(message: String) {
        if (isDebug) {
            Log.i(TAG, message)
        }
    }
}