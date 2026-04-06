package com.appactor.android.billing

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.storage.AppActorIdentityStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class AppActorInstallReferrerManager(
    private val context: Context,
    private val identityStore: AppActorIdentityStore,
) {

    suspend fun fetchReferrerOnce(): String? {
        if (!identityStore.installReferrer.isNullOrBlank()) {
            return identityStore.installReferrer
        }

        val referrer = connectAndFetchReferrer() ?: return null
        identityStore.setInstallReferrer(referrer)
        // TODO: send referrer to backend when endpoint is available
        return referrer
    }

    private suspend fun connectAndFetchReferrer(): String? {
        return suspendCancellableCoroutine { continuation ->
            val client = InstallReferrerClient.newBuilder(context).build()
            continuation.invokeOnCancellation {
                runCatching { client.endConnection() }
            }
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val result = when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            runCatching {
                                client.installReferrer?.installReferrer
                            }.getOrNull()
                        }
                        else -> {
                            AppActorLogger.debug(
                                "Install referrer setup failed with code: $responseCode"
                            )
                            null
                        }
                    }
                    runCatching { client.endConnection() }
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            })
        }
    }
}
