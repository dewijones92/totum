package com.dewijones92.totum.cli

import com.dewijones92.totum.common.HttpUrl

/** What the user asked for. A value, so the parsing is testable without running anything. */
internal sealed interface Command {

    /** Play something: a URL, or a phrase to search for. */
    data class Play(val target: Target, val watch: Boolean) : Command

    /** Print what would play, and why, without playing it. */
    data class Resolve(val target: Target, val json: Boolean) : Command

    /** List what a phrase finds. */
    data class Search(val query: String, val limit: Int) : Command

    data class Help(val reason: String? = null) : Command
    data object Version : Command
}

/** A URL to play, or words to search for. Told apart once, here. */
internal sealed interface Target {
    data class Url(val url: HttpUrl) : Target
    data class Query(val text: String) : Target
}

/**
 * Turns arguments into a [Command].
 *
 * Hand-rolled rather than a parser library, deliberately: five flags do not justify a dependency
 * in a tool whose whole appeal is that it starts instantly, and the shape is small enough to
 * state exhaustively in tests.
 */
internal fun parse(args: List<String>): Command {
    val words = args.filterNot { it.startsWith("-") }
    val flags = args.filter { it.startsWith("-") }.toSet()
    if (flags.any { it in HELP_FLAGS }) return Command.Help()
    if (flags.any { it in VERSION_FLAGS }) return Command.Version
    return command(words, flags)
}

private fun command(words: List<String>, flags: Set<String>): Command {
    val name = words.firstOrNull() ?: return Command.Help()
    val rest = words.drop(1)
    return when (name) {
        "play" -> rest.play(flags, "play")
        "resolve" -> rest.asTarget()?.let { Command.Resolve(it, json = "--json" in flags) }
            ?: Command.Help("resolve needs a URL or something to search for")
        "search" -> rest.joinToString(" ").ifBlank { null }
            ?.let { Command.Search(it, limitFrom(flags)) }
            ?: Command.Help("search needs something to search for")
        "help" -> Command.Help()
        "version" -> Command.Version
        // No sub-command: `totum <url>` and `totum jazz live stream` both mean play. The
        // shortest thing you can type should be the thing you do most.
        else -> words.play(flags, name)
    }
}

private fun List<String>.play(flags: Set<String>, named: String): Command =
    asTarget()?.let { Command.Play(it, watch = "--watch" in flags) }
        ?: Command.Help(
            if (named == "play") "play needs a URL or something to search for" else "unknown command: $named",
        )

/**
 * A single argument that parses as a URL is one; anything else is words to search for.
 *
 * By SHAPE rather than by a flag, because nobody types `--url`. The consequence worth knowing:
 * a search phrase that happens to be a bare URL is treated as that URL, which is what a person
 * pasting a link means every time.
 */
private fun List<String>.asTarget(): Target? {
    val phrase = joinToString(" ").trim().ifBlank { null } ?: return null
    val single = singleOrNull()?.let(HttpUrl::parse)
    return if (single != null) Target.Url(single) else Target.Query(phrase)
}

private fun limitFrom(flags: Set<String>): Int =
    flags.firstOrNull { it.startsWith("--limit=") }
        ?.removePrefix("--limit=")
        ?.toIntOrNull()
        ?.coerceIn(1, MAX_RESULTS)
        ?: DEFAULT_RESULTS

private val HELP_FLAGS = setOf("-h", "--help")
private val VERSION_FLAGS = setOf("-V", "--version")
private const val DEFAULT_RESULTS = 10
private const val MAX_RESULTS = 50
