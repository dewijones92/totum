package com.dewijones92.totum.playback

/**
 * Races through silent stretches by raising the playback rate, and remembers the rate the user
 * actually chose so it can put it back.
 *
 * Separate from the service because the arithmetic is where the bugs are, and because a rate the
 * user set is a promise: Dewi, 2026-08-09 — *"if I select 1.5 speed, I don't want that ever to
 * change unless I manually change it"*.
 *
 * The rate the user chose used to be **inferred** from the player's own callback, and ignored
 * while a silent stretch was in progress (`if (!inSilence) userSpeed = playbackParameters.speed`).
 * Speech enters silence every few seconds, so a speed change made during one was simply dropped:
 * the store held 1.5, the racer still believed 1.0, and the moment speech returned the player was
 * put back to 1.0. Told rather than inferred now — [userChose] is the only way in.
 */
internal class SilenceRacer {

    /** The rate the user asked for. Never guessed. */
    var userSpeed: Float = NORMAL
        private set

    /** True while racing through silence. */
    var racing: Boolean = false
        private set

    /** What the player should be playing at right now. */
    val speed: Float
        get() = if (racing) (userSpeed * SILENCE_MULTIPLIER).coerceAtMost(MAX_SILENCE_SPEED) else userSpeed

    /**
     * The user chose a rate. Returns what the player should be at — which is the *raced* rate when
     * a silent stretch is in progress, so choosing 1.5 mid-silence does not audibly drop out of the
     * race and then jump back into it.
     */
    fun userChose(speed: Float): Float {
        // Not clamped here: the controller clamps to the range the UI offers before anything
        // reaches this, and a second range would be a second opinion about the same thing.
        userSpeed = speed
        return this.speed
    }

    /**
     * Silence began or ended. Null when nothing needs to change, so a caller cannot turn a
     * no-op into a redundant `setPlaybackSpeed` — which the player reports back as a parameter
     * change and which used to feed the guessing.
     */
    fun silence(silent: Boolean): Float? {
        if (silent == racing) return null
        racing = silent
        return speed
    }

    /**
     * Stop racing, whatever was happening — the strategy changed, or skip-silence was turned off.
     * Null when it was not racing, for the same reason as [silence].
     */
    fun stopRacing(): Float? = silence(silent = false)

    internal companion object {
        const val NORMAL = 1f

        /** Four times through dead air: fast enough to be worth it, slow enough to hear a cue. */
        const val SILENCE_MULTIPLIER = 4f

        /** Beyond this the decoder struggles and the result is a chirp rather than speech. */
        const val MAX_SILENCE_SPEED = 8f
    }
}
