package com.dewijones92.totum.common

import java.net.URI

public object SponsorLink {
    public fun of(configured: String?): HttpUrl? =
        configured?.takeIf { it.isNotBlank() }
            ?.let(HttpUrl::parse)
            ?.takeIf { it.value.startsWith("https://", ignoreCase = true) && URI(it.value).userInfo == null }
}
