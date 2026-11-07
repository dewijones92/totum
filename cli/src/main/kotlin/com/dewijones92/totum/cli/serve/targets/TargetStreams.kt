package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.jellyfin.isJellyfin
import com.dewijones92.totum.data.jellyfin.jellyfinId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.playback.cast.CastItem
import java.io.File

internal class TargetStreams(
    private val fromHome: suspend (PlayableItem, Boolean) -> String?,
    private val jellyfin: ((String) -> String)?,
    private val files: SoundFiles = SoundFiles(File("/sounds")),
    private val castFromHome: (suspend (PlayableItem, Boolean) -> CastItem?)? = null,
) : TargetSounds {

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
        )
        return TargetStream(url, cast)
    }

    override suspend fun urlFor(item: PlayableItem, picture: Boolean): String? {
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

    override fun whyNone(item: PlayableItem): String {
        val file = files.resolve(item)
        return when {
            file is FileAnswer.Refused -> file.why
            item.item.id.isJellyfin && jellyfin == null -> "Jellyfin is not set up on this Pi"
            else -> super.whyNone(item)
        }
    }
}
