package com.dewijones92.totum.cli

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.process.ProcessYtDlpEngine
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Totum on the command line: find something on YouTube and play it, using the app's own
 * extraction and its own rules about which stream is the right one.
 *
 * Everything it knows comes from the same libraries the Android app uses — `:lib:ytdlp` for the
 * engine contract and the stream pickers, `:lib:ytdlp-process` for a desktop engine that runs
 * the very same Python bridge. There is no second copy of any of it.
 */
internal fun main(args: Array<String>) {
    // Silent unless asked: a tool you pipe into another tool must not narrate onto stderr by
    // default, but when something will not play the trail is the whole diagnosis.
    if (System.getenv("TOTUM_VERBOSE") != null) {
        Diag.sink = Diag.Sink { _, tag, message, error ->
            System.err.println("[$tag] $message${error?.let { " — $it" } ?: ""}")
        }
    }
    val outcome = runBlocking { Cli(ProcessYtDlpEngine()).run(parse(args.toList())) }
    exitProcess(outcome)
}

/** The commands themselves, with the engine and the terminal injected so they can be tested. */
internal class Cli(
    private val engine: YtDlpEngine,
    private val out: (String) -> Unit = ::println,
    private val err: (String) -> Unit = System.err::println,
    private val launch: (List<String>) -> Int = ::runPlayer,
    /** Whether a program is runnable. Used for the player AND by `doctor`, hence the plain name. */
    private val onPath: (String) -> Boolean = ::isOnPath,
    private val wanted: List<String> = deviceLanguages(),
    /** Whether the extractor is importable. Injected so `doctor` is testable without a Python. */
    private val pythonHasYtDlp: () -> Boolean = ::ytDlpImports,
) {

    suspend fun run(command: Command): Int = when (command) {
        is Command.Help -> help(command.reason)
        is Command.Version -> version()
        is Command.Doctor -> doctor()
        is Command.Search -> search(command)
        is Command.Resolve -> resolve(command)
        is Command.Play -> play(command)
    }

    private suspend fun play(command: Command.Play): Int {
        val pick = pickFor(command.target, command.watch) ?: return FAILURE
        val player = choosePlayer() ?: return FAILURE
        out("> ${pick.title}${pick.uploader?.let { " - $it" } ?: ""}")
        out("  ${pick.describe()}")
        // The picture is suppressed whenever you did not ask to watch, even when the only stream
        // on offer carries one. A 24/7 live stream publishes no audio-only format at all, so
        // "totum jazz live stream" would otherwise decode video nobody is looking at.
        val showVideo = command.watch && pick.carriesVideo
        return launch(
            player + PlayerCommand.forStream(player.last(), pick.url, pick.title, audioOnly = !showVideo).drop(1)
        )
            .also { if (it != OK) err("the player exited with $it") }
    }

    private suspend fun resolve(command: Command.Resolve): Int {
        val pick = pickFor(command.target, watch = true) ?: return FAILURE
        if (command.json) {
            // Deliberately hand-written rather than serialised: it is four fields, and a schema
            // that a shell script can rely on is worth more than a data class here.
            out(
                """{"title":${pick.title.json()},"uploader":${pick.uploader.json()},""" +
                    """"format":${pick.formatId.json()},"language":${pick.audio.languageCode.json()},""" +
                    """"url":${pick.url.value.json()}}""",
            )
        } else {
            out("${pick.title}${pick.uploader?.let { " - $it" } ?: ""}")
            out(pick.describe())
            out(pick.url.value)
        }
        return OK
    }

    private suspend fun search(command: Command.Search): Int =
        when (val result = engine.searchVideos(command.query, command.limit)) {
            is VideoSearchResult.Success -> {
                if (result.entries.isEmpty()) err("nothing found for \"${command.query}\"")
                result.entries.forEach {
                    out(
                        "${it.title}${it.uploader?.let { u -> " — $u" } ?: ""}\n  ${it.watchUrl.value}"
                    )
                }
                if (result.entries.isEmpty()) FAILURE else OK
            }
            is VideoSearchResult.Failure -> {
                err("search failed: ${result.detail}")
                FAILURE
            }
        }

    /** A URL is extracted directly; a phrase is searched and the first hit taken. */
    private suspend fun pickFor(target: Target, watch: Boolean): StreamPick? {
        val url = when (target) {
            is Target.Url -> target.url
            is Target.Query -> firstHit(target.text) ?: return null
        }
        val metadata = when (val extraction = engine.extract(url)) {
            is ExtractionResult.Success -> extraction.metadata
            is ExtractionResult.Failure -> {
                err("could not resolve ${url.value}: ${extraction.describe()}")
                return null
            }
        }
        return metadata.pick(wanted, watch) ?: run {
            err("\"${metadata.title}\" offers nothing playable")
            null
        }
    }

    private suspend fun firstHit(query: String): HttpUrl? =
        when (val result = engine.searchVideos(query, 1)) {
            is VideoSearchResult.Success -> result.entries.firstOrNull()?.watchUrl
                ?: run {
                    err("nothing found for \"$query\"")
                    null
                }
            is VideoSearchResult.Failure -> {
                err("search failed: ${result.detail}")
                null
            }
        }

    /** `$TOTUM_PLAYER` if set, else the first known player on PATH. */
    private fun choosePlayer(): List<String>? {
        PlayerCommand.override(System.getenv("TOTUM_PLAYER"))?.let { return it }
        val found = PlayerCommand.CANDIDATES.firstOrNull(onPath)
        if (found == null) {
            // Naming all three beats "no player found": the fix is one apt-get away and the user
            // should not have to read our source to learn which packages count.
            err("no media player found. Install one of ${PlayerCommand.CANDIDATES.joinToString(", ")}, ")
            err("or set TOTUM_PLAYER to the command you want (e.g. TOTUM_PLAYER=\"mpv --no-config\").")
        }
        return found?.let(::listOf)
    }

    private fun help(reason: String?): Int {
        reason?.let(err)
        out(USAGE)
        return if (reason == null) OK else FAILURE
    }

    /**
     * Everything the machine needs, in one answer.
     *
     * Exits non-zero when something is missing, so `totum doctor && totum "jazz"` is a usable
     * shape in a script and an install script can check its own work.
     */
    private fun doctor(): Int {
        val found = Prerequisites.check(onPath = onPath, pythonHasYtDlp = pythonHasYtDlp)
        Prerequisites.report(found).forEach(out)
        return if (Prerequisites.ready(found)) OK else FAILURE
    }

    private suspend fun version(): Int {
        val versions = runCatching { engine.versions() }.getOrNull()
        out("totum ${BuildInfo.VERSION}")
        out("yt-dlp ${versions?.ytDlp ?: "not found"}, python ${versions?.python ?: "not found"}")
        return if (versions == null) FAILURE else OK
    }

    internal companion object {
        const val OK = 0
        const val FAILURE = 1
    }
}

/** Enough JSON escaping for titles, which is all that goes through it. */
private fun String?.json(): String =
    this?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"" } ?: "null"

private fun ExtractionResult.Failure.describe(): String = when (this) {
    is ExtractionResult.Failure.UnsupportedUrl -> "no extractor recognises that URL"
    is ExtractionResult.Failure.Network -> "network problem - $detail"
    is ExtractionResult.Failure.Extractor -> detail
}

private fun runPlayer(command: List<String>): Int =
    ProcessBuilder(command).inheritIO().start().waitFor()

/**
 * Whether `python3` can import yt-dlp.
 *
 * Asked by running it, because a pip package can be installed and still not importable — a
 * user-site install under a different interpreter is the common way that happens, and it is exactly
 * the case a message about "install yt-dlp" would send somebody round in circles over.
 */
private fun ytDlpImports(): Boolean = runCatching {
    ProcessBuilder(listOf("python3", "-c", "import yt_dlp"))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
        .waitFor() == 0
}.getOrDefault(false)

private fun isOnPath(program: String): Boolean =
    System.getenv("PATH").orEmpty().split(':').any { java.io.File(it, program).canExecute() }

private val USAGE = """
    totum — play YouTube audio from the command line

    Usage:
      totum <url>                      play a link (audio only)
      totum jazz live stream           search, then play the first hit
      totum play <url|words> [--watch] --watch keeps the picture
      totum resolve <url|words> [--json]   print the stream, do not play it
      totum search <words> [--limit=N]     list what a phrase finds
      totum doctor                     check this machine has what it needs
      totum version

    Environment:
      TOTUM_PLAYER   command to play with (default: the first of ${PlayerCommand.CANDIDATES.joinToString(", ")} on PATH)
      TOTUM_PYTHON   python to run the extractor with (default: python3)
      TOTUM_VERBOSE  set to anything to print what it is doing to stderr

    Needs python3 with yt-dlp installed, and a media player.
""".trimIndent()
