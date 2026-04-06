package com.appactor.android.internal.logging

import android.util.Log
import com.appactor.android.models.AppActorLogLevel

internal object AppActorLogger {

    private const val TAG = "AppActor"

    @Volatile
    var level: AppActorLogLevel = AppActorLogLevel.Info
        private set

    @Volatile
    var logHandler: ((level: String, message: String, category: String, timestamp: String) -> Unit)? = null

    fun applyOverride(override: AppActorLogLevel?) {
        override ?: return
        if (override.ordinal < level.ordinal) {
            level = override
        }
    }

    fun setLevel(newLevel: AppActorLogLevel) {
        level = newLevel
    }

    fun debug(message: String) {
        if (shouldLog(AppActorLogLevel.Debug)) {
            Log.d(TAG, message)
            dispatchToHandler("debug", message)
        }
    }

    fun info(message: String) {
        if (shouldLog(AppActorLogLevel.Info)) {
            Log.i(TAG, message)
            dispatchToHandler("info", message)
        }
    }

    fun warn(message: String) {
        if (shouldLog(AppActorLogLevel.Warn)) {
            Log.w(TAG, message)
            dispatchToHandler("warn", message)
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (shouldLog(AppActorLogLevel.Error)) {
            Log.e(TAG, message, throwable)
            dispatchToHandler("error", message)
        }
    }

    private fun dispatchToHandler(level: String, message: String) {
        logHandler?.invoke(level, message, "sdk", java.time.Instant.now().toString())
    }

    private fun shouldLog(messageLevel: AppActorLogLevel): Boolean {
        return level.ordinal <= messageLevel.ordinal
    }
}
