package com.appactor.android.api

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.Collections
import java.util.WeakHashMap

internal class AppActorLifecycleCallbacks(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {

    // The started activities (between onStart and onStop), not a count. These callbacks are
    // registered at configure(), often while an activity is already started; a count would miss
    // it, reach 0 when it stops behind the next one, and report a background while that one shows.
    // A paused or resumed activity is started too, which is how those get in (not a saved one:
    // in apps targeting API 28+ onSaveInstanceState runs after onStop). Weak, so an activity this
    // instance outlives isn't kept.
    private val startedActivities: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    // Set while the last started activity is recreated for a configuration change (rotation, dark
    // mode, locale): the app stays in the foreground, and its new instance's start is no return.
    private var recreatingLastActivity = false

    override fun onActivityStarted(activity: Activity) {
        val wasBackground = startedActivities.isEmpty() && !recreatingLastActivity
        recreatingLastActivity = false
        startedActivities += activity
        if (wasBackground) {
            onForeground()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        // An activity never seen started is left alone: without it the set can't tell whether
        // another one it missed is still showing.
        if (!startedActivities.remove(activity) || startedActivities.isNotEmpty()) return
        if (activity.isChangingConfigurations) {
            recreatingLastActivity = true
        } else {
            onBackground()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        startedActivities += activity
    }

    override fun onActivityPaused(activity: Activity) {
        startedActivities += activity
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        startedActivities -= activity
    }
}
