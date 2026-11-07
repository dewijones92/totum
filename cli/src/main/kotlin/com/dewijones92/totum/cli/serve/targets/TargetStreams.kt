package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.jellyfin.isJellyfin
import com.dewijones92.totum.data.jellyfin.jellyfinId
import com.dewijones92.totum.domain.PlayableItem
import java.io.File

internal class TargetStreams(
    private val fromHome: suspend (PlayableItem, Boolean) -> String?,
    private val jellyfin: ((String) -> String)?,
    private val files: SoundFiles = SoundFiles(File("/sounds")),
) : TargetSounds {

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
