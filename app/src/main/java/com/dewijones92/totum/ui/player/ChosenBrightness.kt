package com.dewijones92.totum.ui.player

/**
 * The screen brightness the user set by gesture, and whether a FULLSCREEN video still wants it.
 *
 * Dewi, 2026-08-05, on settings that must not move on their own: *"brightness … should not change
 * until I deliberately change them in the GUI"*, and on how far that should go for brightness
 * specifically — remember it for the session, do not persist it.
 *
 * **Fullscreen only** (Dewi, 2026-08-09: *"the brightness needs to be only applied if the video
 * has been played in full screen. Otherwise it needs to use my phone brightness … similar to
 * PipePipe"*). The gesture is already fullscreen-only; applying its result was not, so a
 * brightness set while watching stayed on the windowed player and therefore on the whole app.
 * Report 0.1.374 caught it exactly: `fullscreen active=false` at 18:51:39.009, and one
 * millisecond later `stage on screen (1), window brightness 1.0`.
 *
 * The choice is still REMEMBERED when you leave — it is the *override* that is dropped — so
 * going back into fullscreen returns to the brightness you were watching at.
 *
 * Three separate things have to be true at once, and keeping them in one place is the point:
 *
 * - **The CHOICE outlives the window.** The window override has to be dropped whenever video goes
 *   away, or the queue and settings screens inherit a dimmed window. But the gesture state lives
 *   with the player composable, so dropping the override also forgot the choice — and on a queue of
 *   videos the brightness reset at every track change.
 * - **The window is shared, so it is released by the LAST stage, not the first.** Going fullscreen
 *   swaps one subtree for another, which disposes one stage and creates another. Both are correct
 *   on their own; the trouble is the order Compose runs them in. The incoming stage re-applied the
 *   brightness during *composition* and the outgoing one released it in its `onDispose`, during the
 *   *effects* phase afterwards — so the release always landed last and the screen dropped back to
 *   system brightness at every transition, for the rest of the session (Dewi, 2026-08-08:
 *   *"turned down when I go into full screen video"*, and it never came back).
 *
 * Counting stages fixes that by making the answer **independent of the order**: while any stage is
 * on screen the window shows the choice, whichever way round the two lifecycle calls happen to run.
 * A flag on either composable could not do this, because neither one knows about the other.
 *
 * Not persisted, deliberately. A brightness set weeks ago applying to a video today would be the
 * same surprise in the other direction.
 */
internal object ChosenBrightness {

    /** Negative means "follow the system", Android's own convention for no override. */
    internal const val FOLLOW_SYSTEM: Float = -1f

    /** The chosen level, or [FOLLOW_SYSTEM] if the user has not set one this session. */
    var value: Float = FOLLOW_SYSTEM
        private set

    /**
     * How many FULLSCREEN video stages are on screen. Normally 0 or 1; the count survives
     * because a swap can briefly overlap two, and because a count cannot be got wrong by
     * whichever order Compose happens to run the two lifecycles in.
     *
     * A windowed stage deliberately does not count. It is on screen for most of the app's life
     * — beneath the queue, the comments and the description — so letting it hold the override
     * is the same as applying the brightness everywhere.
     */
    var fullscreenStagesOnScreen: Int = 0
        private set

    /** Records a deliberate gesture. Values outside 0..1 are clamped rather than rejected. */
    fun choose(level: Float) {
        value = level.coerceIn(0f, 1f)
    }

    /** Whether there is a choice to re-apply when a video next appears. */
    val isSet: Boolean get() = value >= 0f

    /** What the window should be showing right now. */
    val windowBrightness: Float
        get() = if (fullscreenStagesOnScreen > 0 && isSet) value else FOLLOW_SYSTEM

    /** A fullscreen video stage appeared; returns the brightness the window should now show. */
    fun fullscreenAppeared(): Float {
        fullscreenStagesOnScreen++
        return windowBrightness
    }

    /**
     * A fullscreen video stage went away; returns the brightness the window should now show.
     *
     * Floored at zero rather than trusted to balance: an unbalanced call would otherwise leave the
     * count negative forever and the override could never be re-applied — a far worse failure than
     * one stray release.
     */
    fun fullscreenDisappeared(): Float {
        fullscreenStagesOnScreen = (fullscreenStagesOnScreen - 1).coerceAtLeast(0)
        return windowBrightness
    }

    /** Only for tests and a deliberate reset — never called when a video merely ends. */
    fun forget() {
        value = FOLLOW_SYSTEM
        fullscreenStagesOnScreen = 0
    }
}
