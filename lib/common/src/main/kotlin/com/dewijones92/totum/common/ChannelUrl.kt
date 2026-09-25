package com.dewijones92.totum.common

public fun youTubeChannelUrl(channelId: String): HttpUrl? = HttpUrl.parse("https://www.youtube.com/channel/$channelId")
