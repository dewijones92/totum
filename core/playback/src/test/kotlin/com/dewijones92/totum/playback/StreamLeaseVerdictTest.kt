package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A 403 is only an expiry if the URL's own lease has actually run out. Otherwise the address is
 * being *refused*, and a freshly-signed one will be refused in the same way.
 *
 * Every googlevideo URL carries the epoch second it dies at, in `expire`, so this is a fact the
 * app can read rather than a guess it has to make — and reading it changes the right response.
 *
 * Report 0.1.390, from Dewi's phone, is the case that was diagnosed backwards. Wall clock
 * 19:31:02 BST, and the URL that answered 403:
 *
 * ```
 * …/videoplayback?expire=1787013060&ei=ZFODaqi5D82KoccP9pC4sA8&itag=140&c=ANDROID_VR&…
 * ```
 *
 * `expire=1787013060` is **2026-08-18 00:31:00Z — nearly six hours in the future**. The lease was
 * fine. The app called it `Expired` regardless, spent three re-resolves on it at 12–18 seconds of
 * Python extraction each, and every fresh URL was refused exactly like the first (`expire`
 * 1787013066, then 1787013073, then 1787013081 — each newly signed, each 403 within 150ms).
 * Forty-plus seconds of buffering to reach a conclusion the URL itself could have given instantly,
 * with the item's audio already downloaded on the disk the whole time.
 *
 * The overnight case this machinery was built for — 0.1.170, paused at 23:50 and resumed at
 * 06:07 — is a real expiry and must keep its retries, which is why the split is on the timestamp
 * and not on the status code.
 */
class StreamLeaseVerdictTest {

    /** His URL, trimmed to the parameters the judgement uses. */
    private fun url(expire: Long) =
        "https://rr1---sn-8vq54vox03-cgnl.googlevideo.com/videoplayback?expire=$expire" +
            "&ei=ZFODaqi5D82KoccP9pC4sA8&id=o-ACzJb19hKs&itag=140&source=youtube&c=ANDROID_VR"

    @Test
    fun `a lease still in the future means the stream was refused, not expired`() {
        assertEquals(
            StreamFailure.Reason.Rejected,
            leaseVerdict(url(expire = 1_787_013_060), nowEpochSeconds = 1_786_991_462),
        )
    }

    /** The overnight pause. A fresh URL genuinely fixes this one. */
    @Test
    fun `a lease that has run out is an expiry`() {
        assertEquals(
            StreamFailure.Reason.Expired,
            leaseVerdict(url(expire = 1_786_991_462), nowEpochSeconds = 1_787_013_060),
        )
    }

    /** On the boundary, treat it as gone: a second either way is not worth a wrong answer. */
    @Test
    fun `a lease expiring exactly now is an expiry`() {
        assertEquals(
            StreamFailure.Reason.Expired,
            leaseVerdict(url(expire = 1_787_013_060), nowEpochSeconds = 1_787_013_060),
        )
    }

    /**
     * No `expire` to read — a podcast enclosure, a torrent from the home server, anything not
     * YouTube. Expiry keeps the benefit of the doubt, because that is the behaviour these URLs
     * have always had and a retry is the cheaper mistake.
     */
    @Test
    fun `a URL with no lease is treated as an expiry`() {
        assertEquals(
            StreamFailure.Reason.Expired,
            leaseVerdict("https://media.test/episode-419.mp3", nowEpochSeconds = 1_787_013_060),
        )
    }

    /** Unreadable is not the same as absent, and must not be guessed at either. */
    @Test
    fun `an unparseable lease is treated as an expiry`() {
        assertEquals(
            StreamFailure.Reason.Expired,
            leaseVerdict(url(expire = 0).replace("expire=0", "expire=soon"), 1_787_013_060),
        )
    }

    /** `expire` must be the whole parameter name — `expires_in` is a different thing entirely. */
    @Test
    fun `a similarly-named parameter is not mistaken for the lease`() {
        assertEquals(
            StreamFailure.Reason.Expired,
            leaseVerdict("https://media.test/x?expires_in=999999999999", nowEpochSeconds = 1_000),
        )
    }

    /**
     * The client that signed the refused address, which is the open question a refusal raises.
     * Every refused URL in 0.1.390 was `c=ANDROID_VR`; nothing in the report could say so, because
     * the trail truncates the URL long before `c=`.
     */
    @Test
    fun `the signing client is named`() {
        assertEquals("ANDROID_VR", streamClient(url(expire = 1_787_013_060)))
    }

    @Test
    fun `a URL from no YouTube client says so rather than guessing`() {
        assertEquals("none", streamClient("https://media.test/episode-419.mp3"))
    }
}
