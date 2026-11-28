package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.data.jellyfin.isJellyfin
import com.dewijones92.totum.data.jellyfin.jellyfinId
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.innertube.feeds.SeenProgress
import com.dewijones92.totum.playback.cast.CastItem
import com.dewijones92.totum.playback.cast.CastSubtitleSource
import java.io.File

internal class TargetStreams(
    private val fromHome: suspend (PlayableItem, Boolean) -> String?,
    private val jellyfin: ((String) -> String)?,
    private val files: SoundFiles = SoundFiles(File("/sounds")),
    private val accountPlaceOf: (String) -> Long? = { SeenProgress.positions.value[it]?.positionMs },
    private val liveRefusedUntil: () -> Long? = { null },
    private val jellyfinSubtitles: (suspend (String) -> List<CastSubtitleSource>)? = null,
    private val castFromHome: (suspend (PlayableItem, Boolean) -> CastItem?)? = null,
) : TargetSounds {

    override fun resumeFor(item: PlayableItem): Long? {
        val videoId = (item.handle as? PlayHandle.Video)?.watchUrl?.youTubeVideoId() ?: return null
        val place = accountPlaceOf(videoId)
        Diag.log(
            "targets",
            "$videoId: the account's place is ${place?.let { "${it}ms" } ?: "not known to this Pi"}",
        )
        return place
    }

    override suspend fun streamFor(item: PlayableItem, picture: Boolean): TargetStream? {
        val fromYouTube = picture && item.handle is PlayHandle.Video && !item.item.id.isJellyfin &&
            files.resolve(item) == FileAnswer.NotAFile
        if (fromYouTube) castFromHome?.invoke(item, true)?.let { return TargetStream(it.uri, it) }
        val url = urlFor(item, picture) ?: return null
        val cast = CastItem(
            mediaId = item.item.id.value,
            uri = url,
            listening = !picture,
            durationMs = item.item.duration?.inWholeMilliseconds,
            subtitles = jellyfinTracks(item, picture),
        )
        return TargetStream(url, cast)
    }

    private suspend fun jellyfinTracks(item: PlayableItem, picture: Boolean): List<CastSubtitleSource> {
        val id = item.item.id.jellyfinId()
        val lookup = jellyfinSubtitles
        if (!picture || id == null) return emptyList()
        if (lookup == null) {
            Diag.log("targets", "${item.item.id.value}: Jellyfin's subtitle tracks cannot be asked for; none offered")
            return emptyList()
        }
        val tracks = lookup(id)
        Diag.log(
            "targets",
            "${item.item.id.value}: ${tracks.size} subtitle track(s) from Jellyfin for the TV " +
                "[${tracks.joinToString { it.language ?: "?" }}]",
        )
        return tracks
    }

    override suspend fun urlFor(item: PlayableItem, picture: Boolean): String? {
        refusedUntil(item, picture)?.let {
            Diag.warn(
                "targets",
                "${item.item.id.value}: a live stream for a speaker, and YouTube is refusing the Pi until $it"
            )
            return null
        }
        when (val file = files.resolve(item)) {
            is FileAnswer.Found -> return file.file.path
            is FileAnswer.Refused -> return null
            FileAnswer.NotAFile -> Unit
        }
        if (item.item.id.isJellyfin && item.item.id.jellyfinId() == null) {
            Diag.warn("targets", "${item.item.id.value}: not a valid Jellyfin id, no stream")
            return null
        }
        val id = item.item.id.jellyfinId() ?: return fromHome(item, picture)
        val state = if (jellyfin == null) "not configured" else "ok"
        Diag.log("targets", "${item.item.id.value}: from Jellyfin directly ($state)")
        return jellyfin?.invoke(id)
    }

    override fun refusedUntil(item: PlayableItem): Long? = refusedUntil(item, picture = false)

    private fun refusedUntil(item: PlayableItem, picture: Boolean): Long? =
        liveRefusedUntil().takeIf {
            !picture && item.item.contentKind == MediaContentKind.LIVE && item.handle is PlayHandle.Video
        }

    override fun whyNone(item: PlayableItem): String {
        val file = files.resolve(item)
        return when {
            file is FileAnswer.Refused -> file.why
            refusedUntil(item) != null -> "YouTube is refusing the Pi for now"
            item.item.id.isJellyfin && jellyfin == null -> "Jellyfin is not set up on this Pi"
            else -> super.whyNone(item)
        }
    }
}
