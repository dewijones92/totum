package com.dewijones92.totum.cli

import com.dewijones92.totum.common.HttpUrl

/**
 * How to hand a stream to a media player, without becoming one.
 *
 * Decoding audio ourselves would be weeks of work for a worse result than mpv already gives, and
 * would put this tool in the business of codecs rather than of finding the right stream. So the
 * job here ends at "here is a URL and a title", and the player does the rest.
 *
 * `$TOTUM_PLAYER` overrides the whole command line, so anyone who prefers vlc, ffplay or a
 * pipeline of their own is one environment variable away from it.
 */
internal object PlayerCommand {

    /** The players tried in order, first one on PATH wins. */
    val CANDIDATES: List<String> = listOf("mpv", "vlc", "ffplay")

    /**
     * The command to run for [url].
     *
     * [title] is passed for the player's own window/status line, so what is playing is nameable
     * from the terminal rather than being a 400-character googlevideo URL.
     */
    fun forStream(
        player: String,
        url: HttpUrl,
        title: String,
        audioOnly: Boolean,
    ): List<String> = buildList {
        add(player)
        when {
            player.endsWith("mpv") -> {
                if (audioOnly) add("--no-video")
                add("--force-media-title=$title")
                // Terminal players buffer conservatively by default; a stream that has to be
                // fetched over the internet wants a bigger runway than a local file does.
                add("--cache=yes")
            }
            player.endsWith("vlc") -> {
                add("--play-and-exit")
                if (audioOnly) add("--no-video")
                add("--meta-title=$title")
            }
            player.endsWith("ffplay") -> {
                if (audioOnly) add("-nodisp")
                add("-autoexit")
                addAll(listOf("-loglevel", "warning"))
            }
        }
        add(url.value)
    }

    /**
     * An explicit `$TOTUM_PLAYER` split into words, or null.
     *
     * Split on whitespace so `TOTUM_PLAYER="mpv --no-config"` works; anything needing quoting or
     * a pipeline belongs in a shell script that calls `totum resolve --json` instead, which is
     * why this stays deliberately simple rather than half-implementing a shell.
     */
    fun override(value: String?): List<String>? =
        value?.trim()?.ifBlank { null }?.split(Regex("\\s+"))
}
