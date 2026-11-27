package com.dewijones92.totum.ui.queue

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.CaptionsAsk
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.offersCaptions
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.ui.player.PickerTile
import java.util.Locale

@Composable
internal fun RemoteCaptions(targets: TargetsRepository, state: TargetState) {
    if (!state.offersCaptions) return
    val shown = state.captions.shown
    PickerTile(
        icon = if (shown != null) Icons.Filled.ClosedCaption else Icons.Outlined.ClosedCaptionOff,
        label = stringResource(R.string.subtitles),
        current = shown,
        options = listOf<String?>(null) + state.captions.offered,
        optionLabel = { language ->
            if (language == null) stringResource(R.string.subtitles_off) else languageName(language)
        },
        onSelect = { chosen ->
            Diag.log(
                "targets",
                "${state.target.id}: captions ${chosen ?: "off"} chosen on the phone (was ${shown ?: "off"}, " +
                    "offered ${state.captions.offered})",
            )
            targets.act(
                state.target.id,
                TargetControl(TargetAction.CAPTIONS, captions = CaptionsAsk(on = chosen != null, language = chosen)),
            )
        },
        active = shown != null,
        shortLabel = stringResource(R.string.target_captions_short),
    )
}

private fun languageName(code: String): String {
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
    return name.takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: code
}
