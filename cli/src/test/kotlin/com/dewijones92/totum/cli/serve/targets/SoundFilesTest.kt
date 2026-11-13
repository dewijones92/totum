package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SoundFilesTest {

    private val outside = Files.createTempDirectory("outside").toFile().apply {
        resolve("secret.txt").writeText("secret")
    }
    private val root = Files.createTempDirectory("sounds").toFile().apply {
        resolve("brown-noise.opus").writeBytes(ByteArray(8))
        resolve("rain 2.mp3").writeBytes(ByteArray(8))
        resolve(".hidden.opus").writeBytes(ByteArray(8))
        resolve("sub").mkdir()
        resolve("sub/inner.opus").writeBytes(ByteArray(8))
        Files.createSymbolicLink(resolve("escape.opus").toPath(), outside.resolve("secret.txt").toPath())
        Files.createSymbolicLink(resolve("escape-dir").toPath(), outside.toPath())
        Files.createSymbolicLink(resolve("alias.opus").toPath(), resolve("brown-noise.opus").toPath())
    }
    private val files = SoundFiles(root)

    private fun fileItem(path: String, handle: (String) -> PlayHandle = { PlayHandle.Podcast(localPath = it) }) =
        PlayableItem(
            MediaItem(MediaItemId("sound:x"), SourceId("sounds"), "Brown noise", publishedAt = null, duration = null),
            handle(path),
        )

    private fun refused(path: String) {
        val answer = files.resolve(fileItem(path))
        assertTrue("$path -> $answer", answer is FileAnswer.Refused)
        assertTrue((answer as FileAnswer.Refused).why.isNotBlank())
    }

    @Test
    fun `a file in the sounds directory is found by its name`() {
        val answer = files.resolve(fileItem("file:///sounds/brown-noise.opus"))

        assertEquals(FileAnswer.Found(root.resolve("brown-noise.opus").canonicalFile), answer)
    }

    @Test
    fun `only audio files are played, whatever else sits in the folder`() {
        root.resolve("notes.txt").writeText("not sound")
        root.resolve("no-extension").writeBytes(ByteArray(8))
        root.resolve("clip.MP3").writeBytes(ByteArray(8))

        refused("file:///sounds/notes.txt")
        refused("file:///sounds/no-extension")
        assertEquals(
            FileAnswer.Found(root.resolve("clip.MP3").canonicalFile),
            files.resolve(fileItem("file:///sounds/clip.MP3")),
        )
        val why = (files.resolve(fileItem("file:///sounds/notes.txt")) as FileAnswer.Refused).why
        assertTrue(why, why.contains("not an audio file"))
    }

    @Test
    fun `a name with a space is fine`() {
        assertEquals(
            FileAnswer.Found(root.resolve("rain 2.mp3").canonicalFile),
            files.resolve(fileItem("file:///sounds/rain 2.mp3")),
        )
    }

    @Test
    fun `the same rule applies to every handle that can carry a path`() {
        val answer = files.resolve(fileItem("file:///sounds/brown-noise.opus") { PlayHandle.LocalVideo(it) })

        assertEquals(FileAnswer.Found(root.resolve("brown-noise.opus").canonicalFile), answer)
        assertTrue(files.resolve(fileItem("file:///etc/passwd") { PlayHandle.LocalVideo(it) }) is FileAnswer.Refused)
    }

    @Test
    fun `an item that is not a file item is left to the ordinary routes`() {
        val web = PlayableItem(
            MediaItem(
                MediaItemId("a"),
                SourceId("feed"),
                "Talk",
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://example.com/a.mp3"),
            ),
            PlayHandle.Podcast(),
        )
        val video = web.copy(handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=aaaaaaaaaaa")))
        val phonePath = fileItem("/data/user/0/com.dewijones92.totum/files/talk.mp3")

        assertEquals(FileAnswer.NotAFile, files.resolve(web))
        assertEquals(FileAnswer.NotAFile, files.resolve(video))
        assertEquals(FileAnswer.NotAFile, files.resolve(phonePath))
    }

    @Test
    fun `dot dot is refused wherever it appears`() {
        refused("file:///sounds/../secret.txt")
        refused("file:///sounds/sub/../brown-noise.opus")
        refused("file:///sounds/..")
        refused("file:///sounds/../../etc/passwd")
        refused("file:///sounds/..%2fsecret.txt")
        refused("file:///sounds/%2e%2e/secret.txt")
        refused("file:///sounds/..\\secret.txt")
    }

    @Test
    fun `any path outside the sounds directory is refused`() {
        refused("file:///etc/passwd")
        refused("file:///${outside.path.trimStart('/')}/secret.txt")
        refused("file:///sound/brown-noise.opus")
        refused("file:///sounds2/brown-noise.opus")
        refused("file://host/sounds/brown-noise.opus")
        refused("file:////sounds/brown-noise.opus")
        refused("file:/sounds/brown-noise.opus")
        refused("file:sounds/brown-noise.opus")
        refused("FILE:///etc/passwd")
    }

    @Test
    fun `only a name directly in the directory, not a path below it`() {
        refused("file:///sounds/sub/inner.opus")
        refused("file:///sounds/")
        refused("file:///sounds")
        refused("file:///sounds/brown-noise.opus/")
    }

    @Test
    fun `a symlink that leaves the directory is refused, one that stays is not`() {
        refused("file:///sounds/escape.opus")
        refused("file:///sounds/escape-dir")
        assertTrue(files.resolve(fileItem("file:///sounds/alias.opus")) is FileAnswer.Found)
    }

    @Test
    fun `a missing file, a directory and a hidden file are refused`() {
        refused("file:///sounds/nothing.opus")
        refused("file:///sounds/sub")
        refused("file:///sounds/.hidden.opus")
    }

    @Test
    fun `the refusal says why in words the phone can show`() {
        val why = (files.resolve(fileItem("file:///sounds/nothing.opus")) as FileAnswer.Refused).why

        assertTrue(why, why.contains("nothing.opus"))
        val escape = (files.resolve(fileItem("file:///etc/passwd")) as FileAnswer.Refused).why
        assertTrue(escape, escape.contains("sounds"))
    }

    @Test
    fun `a root that does not exist refuses everything`() {
        val none = SoundFiles(File(root, "not-there"))

        assertTrue(none.resolve(fileItem("file:///sounds/brown-noise.opus")) is FileAnswer.Refused)
    }
}
