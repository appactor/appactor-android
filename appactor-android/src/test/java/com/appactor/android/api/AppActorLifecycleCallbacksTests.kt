package com.appactor.android.api

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Each test starts with host already started and resumed, as when configure() runs again after
// reset() while an activity shows. The first callback host gets reports the foreground.
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

        assertEquals(listOf("foreground"), transitions)
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

        assertEquals(listOf("foreground"), transitions)
    }

    @Test
    fun `recreating the only activity for a configuration change is not a background`() {
        val recreated = Activity()

        rotateHost()
        callbacks.onActivityStarted(recreated)
        callbacks.onActivityResumed(recreated)

        assertEquals(listOf("foreground"), transitions)

        callbacks.onActivityPaused(recreated)
        callbacks.onActivityStopped(recreated)
        callbacks.onActivityStarted(recreated)

        assertEquals(listOf("foreground", "background", "foreground"), transitions)
    }

    @Test
    fun `a recreated activity that finishes before it starts is a background`() {
        val recreated = Activity()

        rotateHost()
        callbacks.onActivityCreated(recreated, null)
        callbacks.onActivityDestroyed(recreated)

        assertEquals(listOf("foreground", "background"), transitions)
    }

    @Test
    fun `leaving the app from an activity started before registration is a background`() {
        callbacks.onActivityPaused(host)
        callbacks.onActivityStopped(host)
        callbacks.onActivityStarted(host)

        assertEquals(listOf("foreground", "background", "foreground"), transitions)
    }

    /** Host (a stand-in reporting a configuration change) stops to be recreated. */
    private fun rotateHost() {
        val rotating = object : Activity() {
            override fun isChangingConfigurations() = true
        }
        callbacks.onActivityPaused(rotating)
        callbacks.onActivityStopped(rotating)
        callbacks.onActivityDestroyed(rotating)
    }
}
