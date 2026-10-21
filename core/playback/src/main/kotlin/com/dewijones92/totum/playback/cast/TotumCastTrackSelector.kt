package com.dewijones92.totum.playback.cast

import androidx.annotation.OptIn
import androidx.media3.cast.CastTrackSelector
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.dewijones92.totum.common.Diag
import com.google.common.collect.ImmutableSet

@OptIn(markerClass = [UnstableApi::class])
internal class TotumCastTrackSelector : CastTrackSelector() {

    override fun evaluate(request: CastTrackSelectorRequest): CastTrackSelectorResult {
        val params = request.trackSelectionParameters
        val kept = request.currentlySelectedTrackGroups.filter { it.type != C.TRACK_TYPE_TEXT }
        val texts = request.trackGroupList.filter { it.type == C.TRACK_TYPE_TEXT }
        val wanted = params.preferredTextLanguages
        val text = if (C.TRACK_TYPE_TEXT in params.disabledTrackTypes) null else pick(texts, wanted)
        Diag.log(
            "subtitles",
            "on the TV: ${text?.getFormat(0)?.let { "${it.language} (${it.label})" } ?: "none"} " +
                "of ${texts.size} offered " +
                "[wanted=${wanted.ifEmpty { listOf("off") }} reason=${request.trackSelectionRequestReason}]",
        )
        return request.buildResultUpon().setSelections(ImmutableSet.copyOf(kept + listOfNotNull(text))).build()
    }

    private fun pick(texts: List<TrackGroup>, wanted: List<String>): TrackGroup? {
        val languages = wanted.map(::normal)
        return languages.firstNotNullOfOrNull { language -> texts.firstOrNull { normal(it) == language } }
            ?: languages.firstNotNullOfOrNull { language ->
                texts.firstOrNull { normal(it).substringBefore('-') == language.substringBefore('-') }
            }
    }

    private fun normal(group: TrackGroup): String = normal(group.getFormat(0).language.orEmpty())

    private fun normal(language: String): String = Util.normalizeLanguageCode(language).orEmpty().lowercase()
}
