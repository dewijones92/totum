package com.dewijones92.totum.playback

/** Normal playback speed; what everything plays at until the user picks otherwise. */
public const val DEFAULT_PLAYBACK_SPEED: Float = 1.0f

/**
 * Remembers ONE playback speed for the whole app.
 *
 * Dewi, 2026-08-05: *"things like playback speed, brightness, volume booster settings should not
 * change until I deliberately change them in the GUI — no exceptions"*, and, asked whether speed
 * should be per-podcast or global: *"global please"*.
 *
 * It used to be keyed by `SourceId`, on the reasoning that a podcast you listen to at 1.5× and a
 * channel you watch at 1× should each resume at their own speed. That is a defensible feature and
 * it is not what was wanted: the visible effect is that the speed changes on its own every time the
 * queue moves to something from a different source, which is indistinguishable from a bug. One
 * value, changed only by [save], and [save] is only ever called from the speed control.
 */
public interface PlaybackSpeedStore {

    /** The remembered speed, or [DEFAULT_PLAYBACK_SPEED] if the user has never chosen one. */
    public suspend fun speed(): Float

    /** Records [speed] as the chosen speed. Called only from a deliberate change in the UI. */
    public suspend fun save(speed: Float)

    public suspend fun skipSilence(): Boolean = false

    public suspend fun saveSkipSilence(on: Boolean) {}
}

/** Default store that remembers nothing — everything plays at [DEFAULT_PLAYBACK_SPEED]. */
public object NoOpPlaybackSpeedStore : PlaybackSpeedStore {
    override suspend fun speed(): Float = DEFAULT_PLAYBACK_SPEED
    override suspend fun save(speed: Float): Unit = Unit
}
