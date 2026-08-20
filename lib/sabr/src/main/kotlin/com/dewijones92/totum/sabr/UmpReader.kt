package com.dewijones92.totum.sabr

/**
 * Reads YouTube's UMP framing — the envelope every SABR response arrives in.
 *
 * SABR is how YouTube serves the media it refuses to hand out as plain URLs. Measured on
 * 2026-07-31, an ANDROID-client stream URL serves only its **first megabyte** and then 403s
 * forever; everything past that is behind this protocol, which is why the app pays 2-4s for a
 * yt-dlp extraction instead of taking the 150ms `/player` answer.
 *
 * The framing itself is simple — a flat sequence of `[type][size][bytes]`, each length a
 * [UmpVarint] — and proven against a live response: a POST to `serverAbrStreamingUrl`
 * carrying nothing but `videoPlaybackUstreamerConfig` returned 212KB containing WebM and fMP4
 * initialisation segments and `moof` fragments for audio and video at once.
 *
 * A part may be **split across HTTP responses**, so this reports how many bytes it could not
 * consume rather than guessing: a caller streaming a response must carry the remainder into
 * the next read. Silently dropping a trailing partial part would corrupt exactly the boundary
 * that is hardest to notice.
 */
public object UmpReader {

    /** One framed part. [payload] excludes the header, so it is the part's own bytes. */
    public data class Part(val type: Int, val payload: ByteArray) {
        public val name: String get() = UmpPart.nameOf(type)

        // Data classes compare arrays by identity, which would make every assertion about a
        // payload wrong in a quietly passing way.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Part && type == other.type && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * type + payload.contentHashCode()
    }

    /**
     * Parts fully contained in [buffer], plus the offset where an incomplete part begins.
     *
     * [consumed] is what the caller may discard; anything after it is the start of a part
     * whose bytes have not all arrived.
     */
    public data class Result(val parts: List<Part>, val consumed: Int)

    public fun read(buffer: ByteArray): Result {
        val parts = mutableListOf<Part>()
        var at = 0
        while (at < buffer.size) {
            // A null anywhere here is not an error: it is the tail of a response whose part
            // continues in the next one, and `at` is left pointing at its first byte.
            val part = readPart(buffer, at) ?: break
            parts += part.first
            at = part.second
        }
        return Result(parts, at)
    }

    /**
     * One part and the offset past it, or null when [buffer] does not hold all of it.
     *
     * The length is compared as a LONG against the bytes remaining, before anything narrows it to
     * an Int. A UMP varint carries five bytes, so a hostile or simply non-UMP body can declare
     * 4294967295: `toInt()` wrapped that to -1, the old guard let it through because the Long was
     * positive and the wrapped `end` was small, and `copyOfRange` threw IllegalArgumentException —
     * out of a function whose every caller catches IOException and nothing else. It is reachable
     * from an HTTP error body, which is the most likely thing to be neither UMP nor text.
     */
    private fun readPart(buffer: ByteArray, at: Int): Pair<Part, Int>? {
        val type = UmpVarint.read(buffer, at) ?: return null
        val size = UmpVarint.read(buffer, type.next) ?: return null
        val start = size.next
        if (size.value < 0 || size.value > (buffer.size - start).toLong()) return null
        val end = start + size.value.toInt()
        return Part(type.value.toInt(), buffer.copyOfRange(start, end)) to end
    }
}
