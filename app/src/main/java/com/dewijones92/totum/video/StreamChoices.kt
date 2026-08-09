package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag

/**
 * The stream choices the user made by hand, carried on to the next video.
 *
 * Dewi, 2026-08-09: *"I want everything to be maintained going to the next video in auto play …
 * I don't want that ever to change unless I manually change it"*.
 *
 * Quality and audio language were per-video, which is defensible in isolation and wrong in a
 * queue: every auto-advance quietly went back to the automatic pick, so a deliberate 720p or a
 * deliberate German track lasted exactly one item. Speed, volume boost and skip-silence already
 * survived because they are settings; these two did not because they are *streams*, and the next
 * video's streams are different objects. That is an implementation detail, not something a person
 * should have to know.
 *
 * Held for the sitting, not persisted — same reasoning as the brightness. A quality picked on a
 * phone tethered in a car should not still be capping things a week later on Wi-Fi.
 *
 * NOT the network's data-saver cap, which is a separate limit and still applies on top: this says
 * what you asked for, that says what the connection will allow.
 */
class StreamChoices(
    /** The phone's own languages, used until a track is chosen by hand. */
    private val deviceLanguages: () -> List<String> = { emptyList() },
) {
    /** The audio language chosen by hand, or null to follow the phone. */
    var audioLanguage: String? = null
        private set

    /** The video height chosen by hand, or null for the best the connection allows. */
    var height: Int? = null
        private set

    fun chooseAudio(languageCode: String) {
        if (audioLanguage == languageCode) return
        audioLanguage = languageCode
        Diag.log("choices", "audio language -> $languageCode; it will hold for the next video too")
    }

    fun chooseHeight(value: Int) {
        if (height == value) return
        height = value
        Diag.log("choices", "quality -> ${value}p; it will hold for the next video too")
    }

    /**
     * Languages to prefer when resolving. An explicit choice beats the phone's own, which is what
     * makes a chosen track stick — the resolver picks streams before the launcher ever sees them,
     * so a preference applied afterwards would arrive too late.
     */
    fun preferredAudioLanguages(): List<String> = audioLanguage?.let(::listOf) ?: deviceLanguages()

    /**
     * The quality to play from [offered], honouring the remembered height and the network's [cap].
     *
     * The tallest at or below your height; the SMALLEST on offer when the video is published
     * only above it. Falling back to the tallest instead would turn "I asked for 480p" into 4K on
     * any video without a low rung — the opposite of the request — and refusing to play at all
     * would black out a video because of a tap made an hour ago.
     */
    fun qualityFrom(offered: List<VideoQuality>, cap: Int): VideoQuality? {
        val allowed = offered.filter { it.height <= cap }
        val wanted = height ?: return allowed.maxByOrNull { it.height }
        return allowed.filter { it.height <= wanted }.maxByOrNull { it.height }
            ?: allowed.minByOrNull { it.height }
    }
}
