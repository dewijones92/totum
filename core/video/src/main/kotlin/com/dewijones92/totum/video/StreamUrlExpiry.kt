package com.dewijones92.totum.video

private val EXPIRE_IN_QUERY = Regex("""[?&]expire=(\d+)""")
private val EXPIRE_IN_PATH = Regex("""/expire/(\d+)(/|$)""")
private const val MILLIS_PER_SECOND = 1_000L

internal fun signedUrlExpiryMs(url: String): Long? =
    (EXPIRE_IN_QUERY.find(url) ?: EXPIRE_IN_PATH.find(url))
        ?.groupValues?.get(1)?.toLongOrNull()?.times(MILLIS_PER_SECOND)

internal fun VideoResolver.Resolved.streamUrls(): List<String> =
    listOfNotNull(item.mediaUrl?.value, audioOnlyUrl?.value) +
        qualities.flatMap { listOfNotNull(it.videoUrl.value, it.audioUrl?.value) }
