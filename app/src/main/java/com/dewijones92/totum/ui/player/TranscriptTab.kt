package com.dewijones92.totum.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.transcript.Cue
import com.dewijones92.totum.data.transcript.TranscriptAvailability
import com.dewijones92.totum.data.transcript.TranscriptLoader
import com.dewijones92.totum.data.transcript.TranscriptResult
import com.dewijones92.totum.data.transcript.indexAt
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.ui.common.KeepWhenSettled
import com.dewijones92.totum.ui.common.RecentSearchChips
import com.dewijones92.totum.ui.common.live
import com.dewijones92.totum.ui.common.rememberRecentSearches
import java.util.Locale

val LocalTranscripts = staticCompositionLocalOf<TranscriptLoader?> { null }
internal const val TRANSCRIPT_LINE_TAG = "transcriptLine"
internal const val TRANSCRIPT_SEARCH_TAG = "transcriptSearch"

private val language: String get() = Locale.getDefault().language

@Composable
internal fun rememberHasTranscript(state: PlaybackState): Boolean {
    val loader = LocalTranscripts.current ?: return false
    val has by produceState(false, state.itemId, state.subtitles.size, state.kind) {
        value = false
        val answer = when (state.kind) {
            MediaKind.VIDEO -> loader.forVideoAvailability(state.subtitles, language)
            MediaKind.PODCAST -> state.showKey?.value?.let(HttpUrl::parse)
                ?.let { loader.episodeAvailability(state.itemId.value, it) }
                ?: TranscriptAvailability(false, "the episode names no feed (show ${state.showKey?.value})")
        }
        value = answer.available
        Diag.log(
            "transcript",
            "${state.itemId.value} (${state.kind}): tab ${if (answer.available) "shown" else "hidden"}: ${answer.why}"
        )
    }
    return has
}

@Composable
internal fun TranscriptSection(state: PlaybackState, onSeekTo: (Long) -> Unit) {
    val loader = LocalTranscripts.current ?: return
    val result by produceState<TranscriptResult?>(null, state.itemId) {
        value = null
        value = when (state.kind) {
            MediaKind.VIDEO -> loader.forVideo(state.itemId.value, state.subtitles, language)
            MediaKind.PODCAST -> state.showKey?.value?.let(HttpUrl::parse)
                ?.let { loader.forEpisode(state.itemId.value, it) }
                ?: TranscriptResult.None("the episode names no feed")
        }
        Diag.log("transcript", "${state.itemId.value}: tab opened -> ${value?.let { it::class.simpleName }}")
    }
    when (val shown = result) {
        null -> Text(stringResource(R.string.transcript_loading))
        is TranscriptResult.None -> Text(stringResource(R.string.transcript_none, shown.why))
        is TranscriptResult.Failed -> Text(stringResource(R.string.transcript_failed, shown.why))
        is TranscriptResult.Loaded -> TranscriptLines(shown, state.live().positionMs, onSeekTo)
    }
}

@Composable
private fun TranscriptLines(transcript: TranscriptResult.Loaded, positionMs: Long, onSeekTo: (Long) -> Unit) {
    val cues = transcript.cues
    var query by remember { mutableStateOf("") }
    var match by remember(cues) { mutableIntStateOf(0) }
    var following by remember { mutableStateOf(true) }
    val list = rememberLazyListState()
    val current = cues.indexAt(positionMs)
    val matches = remember(query, cues) { matchesOf(cues, query) }
    KeepInView(list, current = current.takeIf { following && query.isBlank() }, matched = matches.getOrNull(match))
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && following) {
                following = false
                Diag.log("transcript", "scrolled by hand: no longer following the playing line")
            }
        }
    }
    Column {
        Text(
            stringResource(R.string.transcript_from, transcript.from),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TranscriptSearch(
            query = query,
            onQuery = {
                query = it
                match = 0
            },
            match = match,
            matches = matches.size,
            onMatch = { match = it },
        )
        if (!following) {
            AssistChip(onClick = { following = true }, label = { Text(stringResource(R.string.transcript_follow)) })
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(TRANSCRIPT_HEIGHT.dp)) {
            itemsIndexed(cues, key = { i, cue -> "$i:${cue.startMs}" }) { i, cue ->
                TranscriptLine(
                    cue,
                    playing = i == current,
                    found = i in matches,
                    focused = matches.getOrNull(match) == i
                ) {
                    Diag.log("transcript", "line tapped: seek to ${cue.startMs}ms")
                    following = true
                    onSeekTo(cue.startMs)
                }
            }
        }
    }
}

private fun matchesOf(cues: List<Cue>, query: String): List<Int> =
    if (query.isBlank()) {
        emptyList()
    } else {
        cues.indices.filter {
            cues[it].text.contains(
                query.trim(),
                ignoreCase = true
            )
        }
    }

@Composable
private fun KeepInView(list: LazyListState, current: Int?, matched: Int?) {
    LaunchedEffect(current) {
        current?.takeIf { it >= 0 }?.let { list.animateScrollToItem((it - LINES_ABOVE).coerceAtLeast(0)) }
    }
    LaunchedEffect(matched) {
        matched?.let { list.animateScrollToItem((it - LINES_ABOVE).coerceAtLeast(0)) }
    }
}

@Composable
private fun TranscriptSearch(
    query: String,
    onQuery: (String) -> Unit,
    match: Int,
    matches: Int,
    onMatch: (Int) -> Unit
) {
    val recents = rememberRecentSearches("transcript")
    KeepWhenSettled(recents, query, worthKeeping = matches > 0, why = "$matches line(s) found")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                label = { Text(stringResource(R.string.transcript_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { recents?.record(query, "the keyboard's search key") }),
                modifier = Modifier.weight(1f).testTag(TRANSCRIPT_SEARCH_TAG),
            )
            if (matches > 0) {
                Text(stringResource(R.string.transcript_match, match + 1, matches), Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = { onMatch((match - 1 + matches) % matches) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.transcript_previous_match))
                }
                IconButton(onClick = { onMatch((match + 1) % matches) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.transcript_next_match))
                }
            }
        }
        if (query.isEmpty()) RecentSearchChips(recents, onPick = onQuery, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TranscriptLine(cue: Cue, playing: Boolean, found: Boolean, focused: Boolean, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        focused -> colors.tertiaryContainer
        playing -> colors.primaryContainer
        found -> colors.secondaryContainer
        else -> colors.surface
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(TRANSCRIPT_LINE_TAG),
    ) {
        Text(formatTime(cue.startMs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(
            cue.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private const val LINES_ABOVE = 2
private const val TRANSCRIPT_HEIGHT = 420
