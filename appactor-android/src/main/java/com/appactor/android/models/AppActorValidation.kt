package com.appactor.android.models

internal object AppActorValidation {

    fun validateAppUserId(appUserId: String) {
        require(appUserId.isNotEmpty()) {
            throw AppActorError.InvalidConfiguration("appUserId must not be empty.")
        }
        require(appUserId.length <= 255) {
            throw AppActorError.InvalidConfiguration("appUserId must be at most 255 characters.")
        }
        require(appUserId.lowercase() != "nan") {
            throw AppActorError.InvalidConfiguration("appUserId must not be 'nan'.")
        }
    }
}
