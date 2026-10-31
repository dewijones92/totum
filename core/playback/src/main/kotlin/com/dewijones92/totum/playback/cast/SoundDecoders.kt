package com.dewijones92.totum.playback.cast

internal object SoundDecoders {
    private val MISSING = Regex("""Failed to initialize (audio/[\w.+-]+)""")

    fun missing(failure: Throwable?): String? =
        generateSequence(failure) {
            it.cause
        }.firstNotNullOfOrNull { e -> e.message?.let { MISSING.find(it)?.groupValues?.get(1) } }
}
