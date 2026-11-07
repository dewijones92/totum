package com.dewijones92.totum.ui.common

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.ItemShields
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.isYouTubeVideoId
import com.dewijones92.totum.domain.titleOf
import com.dewijones92.totum.innertube.actions.VideoRating
import com.dewijones92.totum.music.MusicFavourites
import com.dewijones92.totum.stars.StarOutcome
import com.dewijones92.totum.stars.Stars
import com.dewijones92.totum.stars.starrable
import com.dewijones92.totum.ui.music.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class StarHost(val stars: Stars, val favourites: MusicFavourites, val scope: CoroutineScope)

internal val LocalStars = staticCompositionLocalOf<StarHost?> { null }

const val STAR_TAG = "sheet-star"

const val STAR_LIST_TAG = "sheet-star-list"

@Composable
internal fun StarAction(item: MediaItem?, onDismiss: () -> Unit) {
    val host = LocalStars.current ?: return
    val playable = item?.starrable() ?: return
    val here by host.stars.starredIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val songId = item.id.value.takeIf { item.contentKind == MediaContentKind.MUSIC && isYouTubeVideoId(it) }
    var likedOnAccount by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(songId) {
        if (songId != null) likedOnAccount = host.favourites.songRating(songId) == VideoRating.LIKE
    }
    val on = item.id in here || likedOnAccount
    val context = LocalContext.current.applicationContext
    SheetAction(
        {
            host.scope.launch {
                val outcome = host.stars.setStarred(playable, !on)
                likedOnAccount = outcome.starredHere && outcome.account == MusicFavourites.Outcome.DONE
                context.say(ItemShields.titleOf(item), outcome)
            }
        },
        if (on) Icons.Filled.Star else Icons.Outlined.StarBorder,
        if (on) R.string.unstar else R.string.star,
        onDismiss,
        tag = STAR_TAG,
    )
}

@Composable
internal fun StarListAction(items: List<PlayableItem>?, title: String, onDismiss: () -> Unit) {
    val host = LocalStars.current ?: return
    if (items.isNullOrEmpty()) return
    val here by host.stars.starredIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val on = items.all { it.item.id in here }
    val context = LocalContext.current.applicationContext
    SheetAction(
        {
            host.scope.launch {
                items.forEach { host.stars.setStarred(it, !on) }
                Diag.log(
                    "stars",
                    "dewidebug ${items.size} file(s) of \"$title\" ${if (on) "unstarred" else "starred"} from its menu",
                )
                val phrase = if (on) R.plurals.unstar_list_done else R.plurals.star_list_done
                toast(context, context.resources.getQuantityString(phrase, items.size, items.size, title))
            }
        },
        if (on) Icons.Filled.Star else Icons.Outlined.StarBorder,
        if (on) R.string.unstar_all else R.string.star_all,
        onDismiss,
        tag = STAR_LIST_TAG,
    )
}

private fun Context.say(title: String, outcome: StarOutcome) {
    val done = getString(if (outcome.starredHere) R.string.star_done else R.string.unstar_done, title)
    val account = outcome.account
    toast(
        this,
        if (account == null || account == MusicFavourites.Outcome.DONE) {
            done
        } else {
            getString(R.string.star_account_problem, done, account.phrase)
        },
    )
}
