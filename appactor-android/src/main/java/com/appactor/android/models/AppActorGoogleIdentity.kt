package com.appactor.android.models

import java.security.MessageDigest

internal fun appActorGoogleObfuscatedAccountId(appUserId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(appUserId.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
