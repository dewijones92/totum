package com.dewijones92.totum.domain

public class StreamTeeFiller(
    private val plan: StreamTeePlan,
    private val held: () -> List<ByteRange>,
    private val spot: () -> PlayerSpot?,
    private val fetch: suspend (ByteRange) -> Unit,
    private val wait: suspend () -> Unit,
    private val log: (String) -> Unit = {},
    private val progress: (heldBytes: Long) -> Unit = {},
) {
    public data class Outcome(val complete: Boolean, val chunksFetched: Int, val bytesFetched: Long, val waits: Int)

    public suspend fun run(): Outcome {
        var chunks = 0
        var bytes = 0L
        var waits = 0
        while (true) {
            val now = held()
            progress(now.sumOf { it.length })
            val holes = plan.holes(now)
            if (holes.isEmpty()) {
                log("the stream is whole in the cache: fetched $bytes bytes in $chunks chunk(s), waited $waits time(s)")
                return Outcome(true, chunks, bytes, waits)
            }
            val chunk = plan.nextChunk(now, spot())
            if (chunk == null) {
                waits++
                if (waits == 1 || waits % LOG_WAITS_EVERY == 0) {
                    log(
                        "leaving ${holes.sumOf { it.length }} bytes to the player (it is playing near them); " +
                            "wait $waits",
                    )
                }
                wait()
                continue
            }
            fetch(chunk)
            chunks++
            bytes += chunk.length
        }
    }

    private companion object {
        const val LOG_WAITS_EVERY = 30
    }
}
