package com.appactor.android.api

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Each test starts with host already started and resumed, as when configure() registers the
// callbacks from an activity or from Flutter/React Native/Capacitor.
@RunWith(RobolectricTestRunner::class)
class AppActorLifecycleCallbacksTests {

    private val transitions = mutableListOf<String>()
    private val callbacks = AppActorLifecycleCallbacks(
        onForeground = { transitions += "foreground" },
        onBackground = { transitions += "background" },
    )
    private val host = Activity()

    @Test
    fun `opening another activity over one started before registration is not a background`() {
        val next = Activity()

        callbacks.onActivityPaused(host)
        callbacks.onActivityStarted(next)
        callbacks.onActivityResumed(next)
        callbacks.onActivityStopped(host)

        assertEquals(emptyList<String>(), transitions)
    }

    @Test
    fun `the purchase sheet over an activity started before registration is not a background`() {
        // ProxyBillingActivity is translucent, so host is only paused while it shows.
        val proxy = Activity()

        callbacks.onActivityPaused(host)
        callbacks.onActivityStarted(proxy)
        callbacks.onActivityResumed(proxy)
        callbacks.onActivityPaused(proxy)
        callbacks.onActivityResumed(host)
        callbacks.onActivityStopped(proxy)

        assertEquals(emptyList<String>(), transitions)
    }

    @Test
    fun `leaving the app from an activity started before registration is a background`() {
        callbacks.onActivityPaused(host)
        callbacks.onActivityStopped(host)
        callbacks.onActivityStarted(host)

        assertEquals(listOf("background", "foreground"), transitions)
    }
}
