package com.appactor.android.api

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlin.math.max

internal class AppActorLifecycleCallbacks(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private var startedCount: Int = 0

    override fun onActivityStarted(activity: Activity) {
        val wasBackground = startedCount == 0
        startedCount += 1
        if (wasBackground) {
            onForeground()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = max(0, startedCount - 1)
        if (startedCount == 0) {
            onBackground()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
