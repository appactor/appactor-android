package com.appactor.android.models

public fun interface AppActorCompletionCallback {
    public fun onComplete()
}

public fun interface AppActorSuccessCallback<T> {
    public fun onSuccess(value: T)
}

public fun interface AppActorErrorCallback {
    public fun onError(error: AppActorError)
}
