package com.appactor.android.backend.client

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun buildAppActorUrl(
    baseUrl: String,
    vararg pathSegments: String,
): String {
    return buildAppActorUrl(baseUrl, pathSegments = pathSegments, queryParameters = emptyList())
}

internal fun buildAppActorUrl(
    baseUrl: String,
    pathSegments: Array<out String>,
    queryParameters: List<Pair<String, String>> = emptyList(),
): String {
    val baseHttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) {
        "Invalid AppActor base URL: $baseUrl"
    }

    val builder = baseHttpUrl.newBuilder()
    pathSegments.forEach(builder::addPathSegment)
    queryParameters.forEach { (key, value) ->
        builder.addQueryParameter(key, value)
    }
    return builder.build().toString()
}
