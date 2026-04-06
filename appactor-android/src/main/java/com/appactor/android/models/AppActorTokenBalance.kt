package com.appactor.android.models

public data class AppActorTokenBalance(
    val renewable: Int,
    val nonRenewable: Int,
    val total: Int = renewable + nonRenewable,
)
