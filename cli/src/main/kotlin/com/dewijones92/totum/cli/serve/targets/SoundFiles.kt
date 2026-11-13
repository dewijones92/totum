package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import java.io.File

internal sealed interface FileAnswer {
    data object NotAFile : FileAnswer
    data class Found(val file: File) : FileAnswer
    data class Refused(val why: String) : FileAnswer
}

internal class SoundFiles(private val root: File) {

    fun resolve(item: PlayableItem): FileAnswer {
        val path = when (val handle = item.handle) {
            is PlayHandle.Podcast -> handle.localPath
            is PlayHandle.LocalVideo -> handle.localPath
            is PlayHandle.Video -> null
        }
        if (path == null || !path.startsWith(SCHEME, ignoreCase = true)) return FileAnswer.NotAFile
        val answer = answerFor(path)
        Diag.log("targets", "${item.item.id.value}: a file on the Pi, $path -> $answer")
        return answer
    }

    private fun answerFor(path: String): FileAnswer {
        if (!path.startsWith(PREFIX)) return FileAnswer.Refused(ONLY_SOUNDS)
        val name = path.removePrefix(PREFIX)
        if (!NAME.matches(name)) return FileAnswer.Refused(ONLY_SOUNDS)
        val file = File(root, name).canonicalFile
        val inside = file.parentFile == root.canonicalFile
        return when {
            !inside -> FileAnswer.Refused(ONLY_SOUNDS)
            !file.exists() -> FileAnswer.Refused("there is no sound file called $name on the Pi")
            !file.isFile -> FileAnswer.Refused("$name is not a sound file")
            file.extension.lowercase() !in AUDIO_EXTENSIONS ->
                FileAnswer.Refused("$name is not an audio file (${AUDIO_EXTENSIONS.joinToString(", ")})")
            else -> FileAnswer.Found(file)
        }
    }

    private companion object {
        const val SCHEME = "file:"
        const val PREFIX = "file:///sounds/"
        const val ONLY_SOUNDS = "only sound files in the sounds folder can be played from the Pi"
        val AUDIO_EXTENSIONS = listOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav")
        val NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._ -]*""")
    }
}
