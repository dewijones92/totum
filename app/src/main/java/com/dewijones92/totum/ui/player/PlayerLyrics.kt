package com.dewijones92.totum.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.music.Lyrics
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue

internal sealed interface LyricsState {
    data object Closed : LyricsState
    data object Loading : LyricsState
    data class Shown(val lyrics: Lyrics) : LyricsState
    data object None : LyricsState
    data class Failed(val detail: String) : LyricsState
}

internal class LyricsLoader(val videoId: String, private val catalogue: YouTubeMusicCatalogue) {
    suspend fun load(): LyricsState {
        val state = when (val result = catalogue.lyrics(videoId)) {
            is MusicResult.Failure -> LyricsState.Failed(result.detail)
            is MusicResult.Success -> result.value?.let(LyricsState::Shown) ?: LyricsState.None
        }
        Diag.log("music", "lyrics $videoId: ${state.described()}")
        return state
    }
}

private fun LyricsState.described(): String = when (this) {
    is LyricsState.Shown -> "${lyrics.text.lines().size} lines, source=${lyrics.source ?: "unstated"}"
    LyricsState.None -> "YouTube Music has none"
    is LyricsState.Failed -> "failed: $detail"
    LyricsState.Closed, LyricsState.Loading -> toString()
}

@Composable
internal fun LyricsSection() {
    val loader = LocalPlayerLinks.current.lyrics ?: return
    var state by remember(loader.videoId) { mutableStateOf<LyricsState>(LyricsState.Closed) }
    LaunchedEffect(loader.videoId, state == LyricsState.Loading) {
        if (state == LyricsState.Loading) state = loader.load()
    }
    val open = { state = LyricsState.Loading }
    Column(modifier = Modifier.fillMaxWidth().testTag(PLAYER_LYRICS_TAG)) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.lyrics_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        when (val now = state) {
            LyricsState.Closed -> LyricsNote(stringResource(R.string.lyrics_show), open)
            LyricsState.Loading -> LyricsNote(stringResource(R.string.lyrics_loading), null)
            LyricsState.None -> LyricsNote(stringResource(R.string.lyrics_none), null)
            is LyricsState.Failed -> LyricsNote(stringResource(R.string.lyrics_failed), open)
            is LyricsState.Shown -> {
                Text(
                    text = now.lyrics.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                now.lyrics.source?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LyricsNote(stringResource(R.string.lyrics_hide)) { state = LyricsState.Closed }
            }
        }
    }
}

@Composable
private fun LyricsNote(text: String, onClick: (() -> Unit)?) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier,
    )
}

internal const val PLAYER_LYRICS_TAG = "player-lyrics"
