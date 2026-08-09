package com.dewijones92.totum.ytdlp.process

import java.io.File

/**
 * The bridge script, unpacked from the jar to somewhere Python can run it.
 *
 * It is a resource rather than a file beside the binary because a jar is one thing to ship and a
 * jar-plus-loose-script is two, and the second one always goes missing. Written once per boot to
 * a temp file and reused, so a shell loop of `totum play …` does not pay for it every time.
 */
public object BridgeScript {

    public const val NAME: String = "totum_ytdlp.py"

    private val unpacked: File by lazy {
        val source = javaClass.classLoader?.getResourceAsStream(NAME)
            ?: error("$NAME is missing from the jar — the build did not copy the bridge script")
        val target = File(System.getProperty("java.io.tmpdir"), "totum-bridge-$VERSION/$NAME")
        target.parentFile?.mkdirs()
        source.use { input -> target.outputStream().use(input::copyTo) }
        target
    }

    /** Where the script is on disk. Unpacks it on first use. */
    public fun onDisk(): File = unpacked

    /**
     * Bumped only when the script's *contract* changes, not its content.
     *
     * It is part of the temp path so two Totum versions on one machine cannot fight over the same
     * file — and so a stale unpack from an older release can never be picked up silently.
     */
    private const val VERSION = "1"
}
