package com.appactor.android.internal.runtime

import com.appactor.android.models.AppActorConfiguration

internal fun AppActorConfiguration.Options.shouldStartBootstrap(): Boolean = true

internal fun AppActorConfiguration.Options.shouldObserveLifecycle(): Boolean = true
