package com.dewijones92.totum.video

import com.dewijones92.totum.data.download.DownloadStrategy
import com.dewijones92.totum.data.download.EngineDownloadStrategy
import com.dewijones92.totum.data.download.FallbackDownloadStrategy
import com.dewijones92.totum.data.download.HttpDownloadStrategy
import com.dewijones92.totum.data.download.RoutedDownloadStrategy
import com.dewijones92.totum.domain.deservesAnotherRoute
import com.dewijones92.totum.ytdlp.YtDlpEngine
import okhttp3.OkHttpClient

fun videoAndPodcastDownloads(
    engine: YtDlpEngine,
    preferredAudioLanguages: () -> List<String>,
    resolver: VideoResolver,
    transferClient: OkHttpClient,
    sabr: SabrRoute,
    codecs: VideoCodecSupport = resolver.codecSupport,
): DownloadStrategy =
    RoutedDownloadStrategy(
        // yt-dlp first, because it handles everything. Its one blind spot is anything YouTube
        // only serves to an ACCOUNT —
        // members-only uploads, which it is refused and the app is not — so a permanent
        // refusal falls back to the app's own signed-in resolution.
        video = FallbackDownloadStrategy(
            primary = EngineDownloadStrategy(
                engine = engine,
                // The SAME preference playback resolves with. Without it, choosing German and
                // downloading gave the English original -- and a downloaded file is precisely
                // the one you cannot re-pick a track for.
                preferredAudioLanguages = preferredAudioLanguages,
                videoCodecsToAvoid = codecs::codecsToAvoid,
            ),
            secondary = PlayerBackedDownloadStrategy(
                // The SAME resolution playback uses, so anything watchable is fetchable.
                resolveAudioUrl = { item -> resolver.resolve(item, "download")?.audioOnlyUrl },
                // NOT resumable: this route re-resolves the URL on every attempt and can be
                // handed a different audio format each time, so continuing a part-fetched
                // file would splice two formats into one that never plays.
                http = HttpDownloadStrategy(transferClient, resumable = false),
                resolveVideo = signedInVideo(resolver),
                fetch = RangedFetch(transferClient),
                merge = engine::mergeStreams,
                sabr = sabr,
            ),
            // The named domain rule, not a lambda spelled out here. This used to read
            // `{ it.isPermanent }`, which withheld the second route from every 403 — and on
            // 2026-08-18, when YouTube stopped serving yt-dlp's URLs at all, that meant a
            // working route sat unreachable while nothing in the app could be downloaded.
            shouldFallBack = { it.deservesAnotherRoute },
        ),
        podcast = HttpDownloadStrategy(transferClient),
    )
