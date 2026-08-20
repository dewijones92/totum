package com.dewijones92.totum.playback

import java.io.IOException

/**
 * SABR will not serve this track again, so there is nothing to retry.
 *
 * Distinct from [SabrPrematureEndException], which says a stream stopped short and a FRESH
 * conversation is worth having. This one says the opposite, and the difference has to be in the type
 * because it is read by [DoNotRetryWhatSabrHasGivenUpOn] rather than by a human.
 *
 * Measured on totum-api35, 2026-08-20: raising this as a plain IOException left Media3's default
 * policy retrying the load ten times with exponential backoff -- 3600ms, 5605, 8611, 12613, 17618,
 * 22632, 27646, 32652, 37656 -- so the fallback that works did not start for about thirty-eight
 * seconds. To a listener that is the stall, and it happened AFTER the wasted bandwidth had already
 * been fixed. A refusal nothing can act on is slower than a failure.
 */
public class SabrGaveUpException(message: String) : IOException(message)
