package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.HomeServerAuth

internal fun publicCastRoute(): PublicCastRoute = PublicCastRoute(
    enabled = { true },
    homeHost = HomeServerAuth::homeHost,
    signedIn = HomeServerAuth::signedIn,
    onHomeLan = HomeLanProbe({ HomeLan.address() })::onHomeLan,
    api = HttpPublicCastApi(
        host = HomeServerAuth::homeHost,
        headers = { HomeServerAuth.headersFor(HomeServerAuth.homeHost()) },
    ),
)
