package com.dewijones92.totum.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.FollowedSources
import com.dewijones92.totum.domain.Following
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.ui.subscriptions.SourceFollowing
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

const val FOLLOW_MARK_TAG = "follow-mark"

class FollowMarkSpec(val following: Following, val onSet: ((Following, Boolean) -> Unit)?)

internal class RowFollowing(
    private val followed: FollowedSources,
    private val set: ((Following, Boolean) -> Unit)?,
) {
    private val noted = mutableSetOf<String>()

    fun markFor(item: MediaItem, pillar: MediaKind): FollowMarkSpec = FollowMarkSpec(of(item, pillar), set)

    private fun of(item: MediaItem, pillar: MediaKind): Following = followed.of(item, pillar).also { answer ->
        val kind = when (answer) {
            is Following.Unknown -> "unknown: ${answer.because.phrase}"
            is Following.NotSubscribedByName -> "not subscribed, judged by channel name only"
            is Following.Subscribed -> if (answer.byName) "subscribed, judged by channel name only" else null
            is Following.NotSubscribed -> null
        }
        if (kind != null && noted.add(kind)) {
            Diag.log(
                "follow",
                "row shows $kind [first seen on \"${item.title}\" pillar=$pillar author=${item.author} " +
                    "sourceUrl=${item.sourceUrl?.value} sourceId=${item.sourceId.value}]",
            )
        }
    }
}

internal val LocalRowFollowing = staticCompositionLocalOf<RowFollowing?> { null }

@Composable
internal fun ProvideFollowing(container: AppContainer, content: @Composable () -> Unit) {
    val subs = container.accountSubscriptions
    val channels by subs.channels.collectAsStateWithLifecycle()
    val signedIn by subs.signedIn.collectAsStateWithLifecycle()
    val loaded by subs.loaded.collectAsStateWithLifecycle()
    val feeds by remember(container) {
        container.podcastRepository.observeSubscriptions().map { list ->
            list.mapNotNull { it.source as? MediaSource.PodcastFeed }
        }
    }.collectAsStateWithLifecycle(null)
    val followed = remember(channels, signedIn, loaded, feeds) {
        FollowedSources(channels.takeIf { signedIn && loaded }, feeds, signedIn)
    }
    LaunchedEffect(followed) {
        Diag.log(
            "follow",
            "basis signedIn=$signedIn youtubeListLoaded=$loaded channels=${followed.channelCount ?: "not known"} " +
                "feeds=${followed.feedCount ?: "not loaded"}",
        )
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val following = remember(container) {
        SourceFollowing(
            setChannel = { source, on -> subs.setSubscribed(source, on) },
            podcasts = container.podcastRepository,
            locate = { item -> container.sourceLocator.locate(item) },
        )
    }
    val words = FollowWords(
        lookingUp = stringResource(R.string.follow_looking_up),
        failedSubscribe = stringResource(R.string.follow_failed_subscribe),
        failedUnsubscribe = stringResource(R.string.follow_failed_unsubscribe),
    )
    val set: (Following, Boolean) -> Unit = remember(following, scope, context, words) {
        {
                answer, on ->
            val name = answer.name()
            if (answer is Following.NotSubscribedByName) toast(context, words.lookingUp.format(name))
            scope.launch {
                if (!following.change(answer, on, from = "a row's mark")) {
                    toast(context, (if (on) words.failedSubscribe else words.failedUnsubscribe).format(name))
                }
            }
            Unit
        }
    }
    val rowFollowing = remember(followed, set) { RowFollowing(followed, set) }
    CompositionLocalProvider(LocalRowFollowing provides rowFollowing, content = content)
}

private data class FollowWords(val lookingUp: String, val failedSubscribe: String, val failedUnsubscribe: String)

private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

private fun Following.name(): String = when (this) {
    is Following.Subscribed -> source.title
    is Following.NotSubscribed -> source.title
    is Following.NotSubscribedByName -> item.author.orEmpty()
    is Following.Unknown -> ""
}

@Composable
internal fun FollowMark(spec: FollowMarkSpec, modifier: Modifier = Modifier) {
    val following = spec.following
    val onSetFollowing = spec.onSet
    var confirming by remember { mutableStateOf<Following.Subscribed?>(null) }
    val scheme = MaterialTheme.colorScheme
    val look = when (following) {
        is Following.Subscribed -> MarkLook(R.string.follow_subscribed, scheme.secondary, scheme.secondary)
        is Following.NotSubscribed, is Following.NotSubscribedByName ->
            MarkLook(R.string.follow_not_subscribed, scheme.primary, scheme.primary)
        is Following.Unknown -> MarkLook(R.string.follow_unknown, scheme.onSurfaceVariant, scheme.outlineVariant)
    }
    val tap: (() -> Unit)? = onSetFollowing?.takeUnless { isSelecting() }?.let { set ->
        when (following) {
            is Following.Subscribed -> ({ confirming = following })
            is Following.NotSubscribed, is Following.NotSubscribedByName -> ({ set(following, true) })
            is Following.Unknown -> null
        }
    }
    val clickLabel = when (following) {
        is Following.Subscribed -> stringResource(R.string.follow_click_unsubscribe, following.source.title)
        is Following.NotSubscribed, is Following.NotSubscribedByName ->
            stringResource(R.string.follow_click_subscribe, following.name())
        is Following.Unknown -> null
    }
    Text(
        text = stringResource(look.text),
        style = MaterialTheme.typography.labelSmall,
        color = look.textColour,
        modifier = modifier
            .testTag(FOLLOW_MARK_TAG)
            .clip(MARK_SHAPE)
            .border(1.dp, look.border, MARK_SHAPE)
            .let { if (tap != null) it.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = tap) else it }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
    confirming?.let { subscribed ->
        val source = subscribed.source
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.follow_unsubscribe_title, source.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onSetFollowing?.invoke(subscribed, false)
                }) { Text(stringResource(R.string.follow_unsubscribe)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    Diag.log("follow", "kept \"${source.title}\" at the unsubscribe question")
                    confirming = null
                }) { Text(stringResource(R.string.follow_keep)) }
            },
        )
    }
}

private class MarkLook(val text: Int, val textColour: Color, val border: Color)

private val MARK_SHAPE = RoundedCornerShape(6.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FactLines(lines: List<String>, followMark: FollowMarkSpec?) {
    val mark: @Composable () -> Unit = { followMark?.let { FollowMark(it) } }
    val makerLine = lines.indexOfFirst { it.startsWith(FactEmoji.CHANNEL) || it.startsWith(FactEmoji.PODCAST) }
    if (makerLine < 0) mark()
    lines.forEachIndexed { index, fact ->
        if (index == makerLine) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FactLine(fact)
                mark()
            }
        } else {
            FactLine(fact)
        }
    }
}

@Composable
private fun FactLine(fact: String) {
    Text(
        text = fact,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
