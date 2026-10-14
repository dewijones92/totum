package com.dewijones92.totum.playback

/**
 * Whether the app evens out how loud things are.
 *
 * It used to be a level you picked — Off / Low / Medium / High / Max, in decibels of makeup gain.
 * Dewi replaced that himself on 2026-08-08, choosing *"Auto — make everything the same loudness"*
 * over keeping the fixed steps, and he was right for a reason worth writing down: **the number was
 * the problem.** Picking it by ear per item means routinely asking for more gain than the audio
 * needs, and gain you did not need is exactly what sounds squashed and over-driven. Measuring the
 * item and applying the difference cannot over-ask.
 *
 * So there is nothing to tune. [AUTO] brings a quiet podcast up a long way, leaves a normal one
 * almost alone, and never applies more than +20 dB — see [LoudnessBoost] for the whole design,
 * including why a hard cap is the distortion rather than the cure for it.
 */
public enum class VolumeBoost {

    /** Bit-exact passthrough; the audio is not touched at all. */
    OFF,

    /**
     * Measure how loud this item is and lift it to a comfortable level.
     *
     * Compressed and limited, so a laugh after a quiet passage cannot clip — which is what the fixed
     * levels did, audibly, from Medium upwards.
     */
    AUTO,

    EVEN_OUT,
    ;

    public companion object {
        /**
         * The stored setting, mapping the retired names onto their nearest meaning.
         *
         * Any of the old levels means "the boost was on", so they become [AUTO] rather than falling
         * back to [OFF]. Silently switching a boost off during an upgrade would be the app changing a
         * setting nobody touched, which Dewi has explicitly ruled out
         * (`docs/todos/settings-only-change-when-asked.md`).
         */
        public fun fromStoredName(name: String?): VolumeBoost = when (name) {
            null, OFF.name -> OFF
            AUTO.name -> AUTO
            EVEN_OUT.name -> EVEN_OUT
            else -> AUTO
        }
    }
}

/**
 * Remembers ONE boost setting for the whole app, mirroring [PlaybackSpeedStore].
 *
 * Dewi, 2026-08-05: *"things like playback speed, brightness, volume booster settings should not
 * change until I deliberately change them in the GUI — no exceptions"*.
 *
 * It was keyed by `SourceId`, on the reasoning that a quietly recorded podcast could stay boosted
 * without shouting at you everywhere else. Same defensible idea as per-source speed, and the same
 * problem: the level then changes on its own every time the queue reaches a different source, which
 * from the outside is the app altering a setting nobody touched. One value, moved only by [save],
 * and [save] is only ever called from the boost control.
 */
public interface VolumeBoostStore {

    /** The remembered setting, or [VolumeBoost.OFF] if the user has never chosen one. */
    public suspend fun boost(): VolumeBoost

    /** Records [boost] as the choice. Called only from a deliberate change in the UI. */
    public suspend fun save(boost: VolumeBoost)
}

/** Default store that remembers nothing — everything plays untouched. */
public object NoOpVolumeBoostStore : VolumeBoostStore {
    override suspend fun boost(): VolumeBoost = VolumeBoost.OFF
    override suspend fun save(boost: VolumeBoost): Unit = Unit
}
