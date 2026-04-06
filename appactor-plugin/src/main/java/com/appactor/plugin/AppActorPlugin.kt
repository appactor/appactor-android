package com.appactor.plugin

import android.app.Activity
import android.content.Context
import com.appactor.plugin.events.PluginEventBridge
import com.appactor.plugin.events.PluginEventListener
import com.appactor.plugin.infrastructure.PluginRequestFactory
import com.appactor.plugin.infrastructure.PluginRequestRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

public object AppActorPlugin {

    @Volatile
    internal var applicationContext: Context? = null
        private set

    @Volatile
    internal var activityRef: WeakReference<Activity>? = null
        private set

    @Volatile
    public var eventListener: PluginEventListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        PluginRequestRouter.registerDefaults()
    }

    /** Sets the application context. Call once from Application or FlutterPlugin.onAttachedToEngine. */
    public fun setContext(context: Context) {
        applicationContext = context.applicationContext
    }

    /** Sets the current Activity (weak reference). Call from onAttachedToActivity. */
    public fun setActivity(activity: Activity?) {
        activityRef = activity?.let { WeakReference(it) }
    }

    /** Async entry point. Decodes method + params, executes, returns JSON string. */
    public suspend fun execute(method: String, json: String): String {
        return PluginRequestRouter.route(method, json).jsonString
    }

    /** Callback-based entry point for platform channels. */
    @JvmStatic
    public fun execute(method: String, json: String, callback: (String) -> Unit) {
        scope.launch {
            val result = execute(method, json)
            callback(result)
        }
    }

    /** Start event listening (customer info changes, receipt pipeline events). */
    public fun startEventListening() {
        PluginEventBridge.startListening()
    }

    /** Stop event listening. */
    public fun stopEventListening() {
        PluginEventBridge.stopListening()
    }

    /** Register additional request handlers at runtime. */
    public fun register(requests: List<PluginRequestFactory>) {
        PluginRequestRouter.register(requests)
    }

    /** Remove request handlers by method name. */
    public fun remove(methods: List<String>) {
        PluginRequestRouter.remove(methods)
    }

    /** All currently registered method names. */
    public val registeredMethods: List<String>
        get() = PluginRequestRouter.availableMethods
}
