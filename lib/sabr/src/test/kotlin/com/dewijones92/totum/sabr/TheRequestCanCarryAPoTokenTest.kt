package com.dewijones92.totum.sabr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request has to be able to carry a proof-of-origin token, and until now it could not.
 *
 * This is the field whose ABSENCE is the whole reason SABR stops after about a minute. Measured on
 * `totum-api35`, 2026-08-20: eighteen independent conversations, every one ending between 968840B and
 * 990078B, after which the server answered with the initialization segment and nothing else. At 1080p
 * that ceiling is roughly four seconds. No work on our byte bookkeeping and no move to a Media3
 * `ChunkSource` could lift it, because the refusal is attestation and the request had nowhere to put
 * an attestation.
 *
 * It also makes a previous conclusion unsafe, which is worth stating: `docs/todos/sabr-streaming.md`
 * recorded "PO token: not needed". That was measured with a request that had **no field capable of
 * carrying one**, so it could only ever have found that adding nothing changed nothing.
 *
 * `streamer_context` is field 19, with the token at 19.2 — the same place the reference
 * implementations put it. Sent only when there is one: an empty context is a change to a request that
 * currently works, and this repo has paid for that kind of change before.
 */
class TheRequestCanCarryAPoTokenTest {

    @Test
    fun `a token is carried inside streamer_context`() {
        val body = VideoPlaybackAbrRequest(byteArrayOf(1), poToken = TOKEN).encode()

        val context = Protobuf.read(body)[FIELD_STREAMER_CONTEXT]?.firstOrNull()
        assertTrue(
            "streamer_context must be present as field 19, got ${Protobuf.read(body).keys}",
            context is Protobuf.Value.Bytes
        )
        val carried = Protobuf.read((context as Protobuf.Value.Bytes).value)[CONTEXT_PO_TOKEN]?.firstOrNull()
        assertTrue("the token must be at 19.2 as bytes", carried is Protobuf.Value.Bytes)
        assertEquals(
            "the token must arrive byte-for-byte — a mangled token is refused exactly like none",
            TOKEN.toList(),
            (carried as Protobuf.Value.Bytes).value.toList(),
        )
    }

    /**
     * No token means no context at all, NOT an empty one.
     *
     * The request as it stands works for the first megabyte, and an added-but-empty field is a change
     * to it. `sabr.malformed_config` is what a request the server dislikes gets, and it is
     * indistinguishable from the wall.
     */
    @Test
    fun `without a token the request is unchanged`() {
        val without = VideoPlaybackAbrRequest(byteArrayOf(1)).encode()

        assertNull(
            "an empty streamer_context was added to a request that had none",
            Protobuf.read(without)[FIELD_STREAMER_CONTEXT],
        )
    }

    /** And the rest of the request must be untouched by adding one. */
    @Test
    fun `adding a token changes nothing else`() {
        val without = VideoPlaybackAbrRequest(byteArrayOf(1), playerTimeMs = 30_000).encode()
        val with = VideoPlaybackAbrRequest(byteArrayOf(1), playerTimeMs = 30_000, poToken = TOKEN).encode()

        val before = Protobuf.read(without)
        val after = Protobuf.read(with)
        assertEquals(
            "the fields that already worked must be identical — this request is the one the server " +
                "accepts today, and the token is an addition to it, not a rewrite",
            before.keys,
            after.keys - FIELD_STREAMER_CONTEXT,
        )
    }

    private companion object {
        /** Not a real token. A real one is a base64url string of a Uint8Array from BotGuard. */
        val TOKEN = byteArrayOf(7, 42, 13, 99, 1)
        const val FIELD_STREAMER_CONTEXT = 19
        const val CONTEXT_PO_TOKEN = 2
    }
}
