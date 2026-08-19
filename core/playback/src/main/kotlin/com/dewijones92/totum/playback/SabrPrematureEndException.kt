package com.dewijones92.totum.playback

import java.io.IOException

/**
 * A SABR stream stopped short of the length it stated.
 *
 * Its own type rather than a plain [IOException] with a recognisable message, because the classification
 * has to be exact: every `IOException` is otherwise read as "the network is gone" and recovery WAITS for
 * a connection that is already there. `Media3PlaybackController`'s own note makes the point — a refused
 * address "deserves an answer of its own rather than a wait for a network that is already there" — and a
 * stalled SABR stream is that case, not a dead network.
 *
 * Reported from a real device (0.1.435, commit 3a31b58): itag 251 served 920030B of 53458433B, 1% of a
 * 61-minute video, and the old code called it the end of the video.
 */
public class SabrPrematureEndException(message: String) : IOException(message)
