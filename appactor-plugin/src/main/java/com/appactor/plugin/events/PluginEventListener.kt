package com.appactor.plugin.events

/** Receives SDK events as JSON strings. Flutter/RN plugins implement this. */
public fun interface PluginEventListener {
    public fun onEvent(eventName: String, jsonPayload: String)
}
