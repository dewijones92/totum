package com.dewijones92.totum.playback

public sealed interface StreamHold {
    public val id: String

    public data class Hold(override val id: String) : StreamHold

    public data class Beside(override val id: String, val why: String) : StreamHold

    public companion object {
        private const val HLS = "hls"

        public fun of(streaming: String?, teeable: Set<String>, route: String?): StreamHold? = when {
            streaming == null -> null
            streaming in teeable -> null
            route == HLS -> Beside(
                streaming,
                "it plays as a YouTube HLS playlist (video and audio cut into segments the cache never stores), so " +
                    "there is no audio stream to copy; its audio is fetched beside the stream, a small fraction " +
                    "of what the video carries [route=$HLS]",
            )
            else -> Hold(streaming)
        }
    }
}
