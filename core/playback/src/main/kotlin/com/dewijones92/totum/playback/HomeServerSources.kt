package com.dewijones92.totum.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import com.dewijones92.totum.common.HomeServerAuth

@UnstableApi
internal fun homeServerAware(upstream: DataSource.Factory): DataSource.Factory =
    ResolvingDataSource.Factory(upstream) { spec ->
        val headers = HomeServerAuth.headersFor(spec.uri.host)
        if (headers.isEmpty()) spec else spec.withAdditionalHeaders(headers)
    }
