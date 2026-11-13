package com.dewijones92.totum.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.playback.BoostingAudioProcessor
import com.dewijones92.totum.playback.EXTRA_AUDIO_ONLY
import com.dewijones92.totum.playback.EXTRA_AUDIO_URL
import com.dewijones92.totum.playback.EXTRA_SKIP_SILENCE
import com.dewijones92.totum.playback.EXTRA_VIDEO_LADDER
import com.dewijones92.totum.playback.SilenceCuttingAudioProcessor
import com.dewijones92.totum.playback.SpeechGate
import com.dewijones92.totum.playback.VolumeBoost

@OptIn(markerClass = [UnstableApi::class])
internal class CastStreams(
    context: Context,
    boost: () -> VolumeBoost,
    skipSilence: () -> Boolean,
    newCutter: () -> SilenceCuttingAudioProcessor,
    newBooster: () -> BoostingAudioProcessor = { BoostingAudioProcessor() },
    gate: () -> SpeechGate = { SpeechGate.OFF },
    host: CastHost,
    tvs: CastTvPort,
) {
    private val server = CastStreamServer(
        engines = { request ->
            MediaCodecAudioEngine(
                context,
                request.source,
                request.label,
                boost,
                newCutter.takeIf { request.cuts },
                newBooster,
                gate,
            )
        },
        skipSilence = skipSilence,
        host = host,
        tvs = tvs,
        tools = context.castTools(),
        upstream = PatientUpstream(CachedUpstream(cachedHttpFactory(context))::get)::get,
    )

    var screens: ScreenLink?
        get() = server.screens
        set(value) {
            server.screens = value
        }

    fun serveScreens(): String? = server.serveScreens()

    fun timelineFor(mediaId: String): CastTimeline? = server.timelineFor(mediaId)

    fun stepDown(mediaId: String): CastStep? = server.stepDown(mediaId)

    fun urlFor(item: MediaItem): String? {
        val uri = item.localConfiguration?.uri?.toString() ?: return null
        return server.urlFor(item.toCastItem(uri))
    }

    fun stopAll() = server.stopAll()

    companion object {
        const val PORT: Int = CastStreamServer.DEFAULT_PORT
    }
}

private fun MediaItem.toCastItem(uri: String): CastItem {
    val extras = requestMetadata.extras
    return CastItem(
        mediaId = mediaId,
        uri = uri,
        separateAudioUrl = extras?.getString(EXTRA_AUDIO_URL),
        listening = extras?.getBoolean(EXTRA_AUDIO_ONLY) == true,
        skipsSilence = extras?.takeIf { it.containsKey(EXTRA_SKIP_SILENCE) }?.getBoolean(EXTRA_SKIP_SILENCE),
        ladder = ladderIn(extras),
        durationMs = mediaMetadata.durationMs,
        subtitles = localConfiguration?.subtitleConfigurations.orEmpty()
            .filter { it.mimeType == MimeTypes.TEXT_VTT || it.mimeType == MimeTypes.APPLICATION_SUBRIP }
            .map { CastSubtitleSource(it.uri.toString(), it.language, it.label?.toString()) },
    )
}

private const val LADDER_FIELDS = 3

private fun ladderIn(extras: android.os.Bundle?): List<CastRung> =
    extras?.getStringArrayList(EXTRA_VIDEO_LADDER).orEmpty().mapNotNull { line ->
        val (height, codec, url) = line.split('\t', limit = LADDER_FIELDS).takeIf { it.size == LADDER_FIELDS }
            ?: return@mapNotNull null
        height.toIntOrNull()?.let { CastRung(url, it, codec.ifEmpty { null }) }
    }
