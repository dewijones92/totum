package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.queue.PictureOffer
import kotlinx.coroutines.delay

class PictureOfferUi(
    val offer: PictureOffer?,
    val playingId: MediaItemId?,
    val dismiss: (PictureOffer, String) -> Unit,
)

val LocalPictureOffer = compositionLocalOf<PictureOfferUi?> { null }

@Composable
fun pictureOfferUi(container: AppContainer): PictureOfferUi {
    val offers = container.playbackQueue.pictureOffers
    val offer by offers.offer.collectAsStateWithLifecycle()
    val playing by container.playbackQueue.nowPlaying.collectAsStateWithLifecycle()
    val playingId = playing?.item?.id
    return remember(offer, playingId, offers) { PictureOfferUi(offer, playingId) { o, why -> offers.dismiss(o, why) } }
}

@Composable
fun PictureOfferStrip(modifier: Modifier = Modifier) {
    val ui = LocalPictureOffer.current ?: return
    val offer = ui.offer ?: return
    val actions = LocalItemActions.current
    val current = ui.playingId == offer.item.item.id && actions?.audioMode != false
    val leftMs = PICTURE_OFFER_MS - (System.currentTimeMillis() - offer.atMs)
    LaunchedEffect(offer, current) {
        when {
            !current -> ui.dismiss(offer, "playback moved on, or the mode changed, before an answer")
            leftMs <= 0 -> ui.dismiss(
                offer,
                "made ${(PICTURE_OFFER_MS - leftMs) / MS_PER_SECOND} s ago, while the strip could not be " +
                    "seen; too late to ask",
            )
            else -> {
                delay(leftMs)
                ui.dismiss(offer, "no answer in ${PICTURE_OFFER_MS / MS_PER_SECOND} s, still listening")
            }
        }
    }
    if (!current || leftMs <= 0) return
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().testTag(PICTURE_OFFER_TAG),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.picture_offer, offer.item.item.title),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = { ui.dismiss(offer, "Keep listening tapped") }) {
                    Text(stringResource(R.string.picture_offer_keep))
                }
                Button(onClick = {
                    ui.dismiss(offer, "Watch tapped")
                    actions?.watch(offer.item.item)
                }) { Text(stringResource(R.string.picture_offer_watch)) }
            }
        }
    }
}

const val PICTURE_OFFER_TAG: String = "picture-offer"

private const val PICTURE_OFFER_MS = 8_000L
private const val MS_PER_SECOND = 1_000L
