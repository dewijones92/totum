package com.dewijones92.totum.sabr

/**
 * Which itags one SABR response carried, and how many bytes of each.
 *
 * Here to answer a design question with a measurement rather than an assumption. A VIDEO
 * request comes back roughly twice the size of what it keeps — measured 2026-07-31, a
 * 4386440B response of which 2223668B was the itag asked for — and the obvious fix is to hand
 * the remainder to the audio stream instead of throwing it away. Whether that works at all
 * depends on whether the audio the server volunteers is the itag we chose or a default of its
 * own picking, and only the wire can say which.
 *
 * Its own type because a per-response tally is not the stream's job, and because a shared sink
 * between the two tracks is what plugs in here once the measurement says it is worth building.
 */
internal class CarriedItags {

    private val bytesByItag = mutableMapOf<String, Long>()

    fun clear(): Unit = bytesByItag.clear()

    /**
     * A null [itag] is kept and named rather than folded in with the rest: a header that
     * declares no format at all would otherwise be invisible, and it is exactly the sort of
     * thing that turns into "the bytes went somewhere" a week later.
     */
    fun add(itag: Int?, bytes: Int) {
        val name = itag?.toString() ?: "unknown"
        bytesByItag[name] = (bytesByItag[name] ?: 0) + bytes
    }

    /** Whether the response carried media bytes for some format other than [itag] — the answer was spent elsewhere. */
    fun hasOthersThan(itag: Int): Boolean = bytesByItag.any { (name, bytes) -> name != itag.toString() && bytes > 0 }

    /** Biggest first, so the line reads as "what we got" then "what sharing could reclaim". */
    override fun toString(): String = bytesByItag.entries
        .sortedByDescending { it.value }
        .joinToString(" ") { (itag, bytes) -> "$itag=${bytes}B" }
        .ifEmpty { "nothing" }
}
