package com.dewijones92.totum.video.live

import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.settings.PlaybackMode

/**
 * Whether the APP has taken the picture away since a test asked for it — the answer to a question
 * three live tests have to ask, so it is asked in one place.
 *
 * `MeteredAudioSwitch` PERSISTS its decision (`setPlaybackMode(AUDIO)`) and samples on a clock, so on a
 * metered connection it fires BETWEEN a test's precondition and its measurement. CI's emulator is
 * metered. An audio-only route then has no picture, no captions and no quality ladder — so a test that
 * set VIDEO and measured any of those reports a defect in the thing it was measuring, when what actually
 * happened is the app doing exactly what it is designed to do.
 *
 * It cost two red mains: `SubtitlesArriveAndRenderTest` read as "no subtitle track reached the player"
 * (2026-08-19) and `PlaysAcrossContentTypesTest` read as "no picture" for three items (2026-08-18).
 * Restating the precondition per measurement, which is what the second one did, does not help when the
 * switch fires mid-measurement.
 *
 * Returns the mode it switched to, or null while the picture is still ours to measure.
 */
internal fun AppContainer.switchedItselfOutOfVideo(): PlaybackMode? =
    appPreferences.settings.value.playbackMode.takeIf { it != PlaybackMode.VIDEO }
