package com.dewijones92.totum.cli

/**
 * What `totum` needs on the machine, and what to do when it is missing.
 *
 * It needs three things it does not carry — a Java runtime, `python3` with `yt-dlp`, and a media
 * player — and the failure of any one of them used to surface only when you tried to play
 * something, as a message about that one thing. `totum doctor` answers all of it at once, before
 * you have a reason to be annoyed.
 *
 * Pure on purpose: what is present comes in as a lambda, so every combination is a unit test
 * rather than something you can only see on a machine that happens to be broken.
 */
internal data class Prerequisite(
    val name: String,
    val present: Boolean,
    /** What to do about it. Empty when it is fine. */
    val fix: String,
) {
    val line: String get() = "${if (present) "ok  " else "MISSING"}  $name${if (present) "" else " - $fix"}"
}

internal object Prerequisites {

    /**
     * [onPath] answers "is this program runnable", [pythonHasYtDlp] whether the extractor imports.
     *
     * yt-dlp is checked separately from python because they fail differently and are fixed
     * differently: no python is a system package, no yt-dlp is one pip command, and reporting
     * "python3 missing" to somebody who has python but not yt-dlp sends them the wrong way.
     */
    fun check(
        onPath: (String) -> Boolean,
        pythonHasYtDlp: () -> Boolean,
    ): List<Prerequisite> {
        val python = onPath("python3")
        return listOf(
            Prerequisite(
                name = "python3",
                present = python,
                fix = "install Python 3 (apt install python3 / brew install python)",
            ),
            Prerequisite(
                name = "yt-dlp",
                // Not asked when there is no python to ask: "yt-dlp missing" on a machine with no
                // Python at all is true and useless.
                present = python && pythonHasYtDlp(),
                fix = if (python) "python3 -m pip install --user -U yt-dlp" else "install Python 3 first",
            ),
            Prerequisite(
                name = "a media player",
                present = PlayerCommand.CANDIDATES.any(onPath),
                fix = "install one of ${PlayerCommand.CANDIDATES.joinToString(", ")}, " +
                    "or set TOTUM_PLAYER to your own command",
            ),
        )
    }

    /** True when everything needed is there — the exit code `doctor` reports. */
    fun ready(found: List<Prerequisite>): Boolean = found.all { it.present }

    /**
     * The whole report, ready to print.
     *
     * Returned rather than printed so it is testable, and so the last line is part of the answer:
     * a list of "ok" lines with no verdict leaves the reader to work out whether that was good news.
     */
    fun report(found: List<Prerequisite>): List<String> =
        found.map { it.line } + "" + if (ready(found)) {
            "Ready. Try: totum \"jazz live stream\""
        } else {
            "Fix the above, then run totum doctor again."
        }
}
