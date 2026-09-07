package com.dewijones92.totum.innertube.player

/**
 * Which InnerTube client a `/player` response was asked as. It matters after the response: a SABR
 * endpoint is bound to the client that requested it, and the ~1MB ceiling measured on 2026-09-06 belongs
 * to the ANDROID and WEB endpoints while the EMBEDDED one streams freely — so the stream must declare
 * the client its endpoint came from (docs/todos/sabr-stops-at-one-megabyte.md).
 */
public enum class PlayerClient { ANDROID, WEB, TV, EMBEDDED }
