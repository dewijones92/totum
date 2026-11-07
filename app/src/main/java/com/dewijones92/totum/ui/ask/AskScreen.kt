package com.dewijones92.totum.ui.ask

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskCard
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.AskSource
import com.dewijones92.totum.data.ask.SavedChat
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.titleOf
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.LocalItemShield
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.LocalOpenSearch
import com.dewijones92.totum.ui.common.LocalOpenSource
import com.dewijones92.totum.ui.common.LocalSnackbarOffers
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.common.RecentChips
import com.dewijones92.totum.ui.common.RecentSearchChips
import com.dewijones92.totum.ui.common.RecentSearches
import com.dewijones92.totum.ui.common.rememberMediaItemActions
import com.dewijones92.totum.ui.common.rememberRecentSearches
import com.dewijones92.totum.ui.common.rememberRemoveWithUndo
import com.dewijones92.totum.ui.music.AlbumRow
import com.dewijones92.totum.ui.music.ArtistRow
import com.dewijones92.totum.ui.search.BookHitRow
import com.dewijones92.totum.ui.search.LocalTorrentMenu
import com.dewijones92.totum.ui.search.LocalTorrentProgress
import com.dewijones92.totum.ui.search.PodcastHitRow
import com.dewijones92.totum.ui.search.SearchScope
import com.dewijones92.totum.ui.search.SearchViewModel
import com.dewijones92.totum.ui.search.SongHitRow
import com.dewijones92.totum.ui.search.TorrentHitRow
import com.dewijones92.totum.ui.search.TorrentMenuSheet
import com.dewijones92.totum.ui.search.VideoHitRow
import com.dewijones92.totum.ui.search.rememberTorrentProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AskScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: AskViewModel = viewModel(key = "ask", factory = AskViewModel.factory(container))
    val search: SearchViewModel = viewModel(
        key = "search-${SearchScope.EVERYTHING.name}",
        factory = SearchViewModel.factory(container, SearchScope.EVERYTHING),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val recents = rememberRecentSearches("ask")
    val openSearch = LocalOpenSearch.current
    val allCopies = { card: AskCard ->
        Diag.log(
            "ask",
            "all copies of ${card.suggestion.label} asked for: searching \"${card.suggestion.searchWords}\""
        )
        search.onQueryChange(card.suggestion.searchWords)
        search.search(card.suggestion.searchWords)
        openSearch?.invoke()
    }
    val forgetWithUndo = rememberRemoveWithUndo(
        offers = LocalSnackbarOffers.current,
        undoLabel = stringResource(R.string.undo),
        tag = "ask",
        duration = SnackbarDuration.Long,
        removedMessage = { chat: SavedChat -> resources.getString(R.string.ask_chat_forgotten, chat.title) },
        remove = { id: String -> viewModel.forget(id) },
        restore = viewModel::unforget,
    )
    TorrentMenuSheet(search, container)
    CompositionLocalProvider(
        LocalTorrentProgress provides rememberTorrentProgress(search),
        LocalTorrentMenu provides search::openTorrentMenu,
    ) {
        Column(modifier.fillMaxSize().testTag(ASK_SCREEN_TAG)) {
            AskHeader(state, onBack, viewModel::startOver)
            if (!state.available) {
                Text(stringResource(R.string.ask_unavailable), modifier = Modifier.padding(16.dp))
                return@Column
            }
            if (state.turns.isEmpty() && state.recent.isNotEmpty()) {
                EarlierChats(state.recent, onOpen = viewModel::open, onForget = forgetWithUndo)
            }
            if (state.turns.isEmpty()) {
                RecentSearchChips(
                    recents,
                    onPick = { viewModel.ask(it, "recent") },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            val hitRow = rememberAskHitRow(container, search)
            val listState = rememberLazyListState()
            LaunchedEffect(state.turns.size) {
                if (state.turns.isNotEmpty()) {
                    listState.animateScrollToItem(
                        listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
                    )
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                state.turns.forEach { turn ->
                    item { TurnHeader(turn) }
                    items(turn.cards) { card ->
                        SuggestionCard(card, turn.stage, onAllCopies = { allCopies(card) }, row = hitRow) {
                            KindBody(card, container, hitRow)
                        }
                    }
                }
            }
            AskInput(state, recents) { text, from -> viewModel.ask(text, from) }
        }
    }
}

@Composable
private fun EarlierChats(chats: List<SavedChat>, onOpen: (String) -> Unit, onForget: (String) -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll) chats else chats.take(EARLIER_CHATS_SHOWN)
    LaunchedEffect(chats.size, showAll) {
        Diag.log("ask", "earlier chats: showing ${shown.size} of ${chats.size} (expanded=$showAll)")
    }
    Text(
        stringResource(R.string.ask_earlier_chats),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val labelled = shown.associateBy { chatChipLabel(it, zone, locale) }
    RecentChips(
        labelled.keys.toList(),
        onPick = { label -> labelled[label]?.let { onOpen(it.id) } },
        onRemove = { label -> labelled[label]?.let { onForget(it.id) } },
        modifier = Modifier.padding(vertical = 8.dp)
            .heightIn(max = EARLIER_CHATS_MAX_HEIGHT.dp)
            .verticalScroll(rememberScrollState()),
    )
    if (!showAll && chats.size > EARLIER_CHATS_SHOWN) {
        TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(pluralStringResource(R.plurals.ask_show_all_chats, chats.size, chats.size))
        }
    }
}

@Composable
private fun AskHeader(state: AskViewModel.UiState, onBack: () -> Unit, onStartOver: () -> Unit) {
    BackHeader(stringResource(R.string.ask_title), onBack) {
        TextButton(
            onClick = onStartOver,
            enabled = !state.busy,
            modifier = Modifier.padding(end = 4.dp),
        ) { Text(stringResource(R.string.ask_start_over)) }
    }
}

internal fun chatChipLabel(chat: SavedChat, zone: ZoneId, locale: Locale): String =
    "${chat.title} · ${DateTimeFormatter.ofPattern(
        "d MMM, HH:mm",
        locale
    ).format(Instant.ofEpochMilli(chat.at).atZone(zone))}"

@Composable
private fun AskInput(state: AskViewModel.UiState, recents: RecentSearches?, ask: (String, String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = { from: String ->
        if (text.isNotBlank()) {
            recents?.record(text, from)
            ask(text, from)
            text = ""
        }
    }
    val typed = { value: String ->
        text = value.replace("\n", " ")
        if ('\n' in value) send("enter")
    }
    val speak = rememberSpeech { heard ->
        recents?.record(heard, "spoken")
        ask(heard, "speech")
    }
    OutlinedTextField(
        value = text,
        onValueChange = typed,
        enabled = !state.busy,
        placeholder = {
            Text(
                stringResource(if (state.turns.isEmpty()) R.string.ask_hint else R.string.ask_refine_hint)
            )
        },
        trailingIcon = {
            Row {
                speak?.let { start ->
                    IconButton(onClick = start, enabled = !state.busy) {
                        Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.ask_speak))
                    }
                }
                IconButton(onClick = { send("typed") }, enabled = !state.busy && text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.ask_send))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send("keyboard") }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(ASK_INPUT_TAG),
    )
}

@Composable
private fun rememberAskHitRow(container: AppContainer, search: SearchViewModel): @Composable (SearchHit) -> Unit {
    val searchState by search.uiState.collectAsStateWithLifecycle()
    val actions = rememberMediaItemActions(container)
    val openMusic = LocalOpenMusicPage.current
    val openSource = LocalOpenSource.current
    return { hit ->
        when (hit) {
            is SearchHit.Torrent -> TorrentHitRow(
                hit = hit,
                onPlay = { search.playTorrent(hit) },
                onDownload = { search.downloadTorrent(hit) },
            )
            is SearchHit.Video -> VideoHitRow(
                hit = hit,
                resolving = searchState.resolving == hit.watchUrl.value,
                onPlay = { search.playVideo(hit) },
                actions = actions,
                onGoToChannel = { item -> actions.goToSource(item) { source -> openSource?.invoke(source) } },
            )
            is SearchHit.Podcast -> PodcastHitRow(
                hit = hit,
                subscribed = hit.feedUrl.value in searchState.subscribedFeeds,
                onSubscribe = { search.subscribe(hit) },
            )
            is SearchHit.Book -> BookHitRow(hit = hit, onPlay = { search.playBook(hit, hit.title) })
            is SearchHit.Song -> SongHitRow(
                hit = hit,
                resolving = searchState.resolving == hit.watchUrl.value,
                onPlay = { search.playSong(hit) },
                actions = actions,
            )
            is SearchHit.Album -> AlbumRow(hit.ref, onOpen = {
                Diag.log("ask", "album ${hit.ref.browseId} \"${hit.ref.title}\" opened")
                openMusic?.invoke(MusicPage.Album(hit.ref.browseId, title = hit.ref.title))
            })
            is SearchHit.Artist -> ArtistRow(
                hit.ref.name,
                hit.ref.subtitle,
                hit.ref.thumbnailUrl,
                browseId = hit.ref.browseId,
                onOpen = {
                    Diag.log("ask", "artist ${hit.ref.browseId} \"${hit.ref.name}\" opened")
                    openMusic?.invoke(MusicPage.Artist(hit.ref.browseId, hit.ref.name))
                },
            )
        }
    }
}

private val SELF_RENDERED = setOf(AskKind.LATEST, AskKind.RADIO, AskKind.APP)

@Composable
private fun KindBody(card: AskCard, container: AppContainer, row: @Composable (SearchHit) -> Unit) {
    if (!card.searched) return
    when (card.suggestion.kind) {
        AskKind.LATEST -> LatestBody(card, container)
        AskKind.APP -> AppItemsBody(card, container)
        AskKind.RADIO -> RadioBody(card, container, row)
        else -> Unit
    }
}

@Composable
private fun TurnHeader(turn: AskViewModel.Turn) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.align(Alignment.End).padding(start = 48.dp),
        ) {
            Text(
                turn.request,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (turn.reply.isNotBlank()) {
            Text(
                stringResource(R.string.ask_reply, turn.reply),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 10.dp, end = 32.dp),
            )
        }
        SourceLine(turn)
        val status = when (turn.stage) {
            AskViewModel.Stage.PACKING -> stringResource(R.string.ask_packing)
            AskViewModel.Stage.SENDING -> stringResource(R.string.ask_sending)
            AskViewModel.Stage.ASKING -> stringResource(R.string.ask_asking)
            AskViewModel.Stage.SEARCHING -> stringResource(R.string.ask_searching)
            AskViewModel.Stage.VETTING -> stringResource(R.string.ask_vetting)
            AskViewModel.Stage.FAILED -> turn.error ?: stringResource(R.string.ask_failed)
            AskViewModel.Stage.DONE -> null
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (turn.stage in BUSY) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun SourceLine(turn: AskViewModel.Turn) {
    val label = when (turn.source) {
        AskSource.APP -> R.string.ask_source_app
        AskSource.BOTH -> R.string.ask_source_both
        AskSource.WEB -> return
    }
    if (turn.stage != AskViewModel.Stage.DONE) return
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SuggestionCard(
    card: AskCard,
    stage: AskViewModel.Stage,
    onAllCopies: () -> Unit,
    row: @Composable (SearchHit) -> Unit,
    body: @Composable () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(
            horizontal = 12.dp,
            vertical = 6.dp
        ).testTag("$ASK_CARD_TAG-${card.suggestion.title}")
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            val s = card.suggestion
            Text(
                "${s.kind.emoji} ${s.label}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                s.why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (s.alreadyHave) {
                Text(
                    stringResource(R.string.ask_already),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            if (s.kind in SELF_RENDERED) {
                body()
                return@Column
            }
            val best = card.best
            when {
                !card.searched -> Unit
                best != null -> row(best)
                card.copies.isEmpty() -> Text(stringResource(R.string.ask_no_copy), modifier = Modifier.padding(16.dp))
                else -> Text(stringResource(R.string.ask_none_right), modifier = Modifier.padding(16.dp))
            }
            if (stage == AskViewModel.Stage.VETTING && card.copies.isNotEmpty()) {
                Text(
                    stringResource(R.string.ask_checking_copy),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            RejectedCopies(card.rejected)
            if (card.searched && s.kind.vetted) {
                TextButton(onClick = onAllCopies, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.ask_all_copies))
                }
            }
        }
    }
}

@Composable
private fun RejectedCopies(rejected: List<Pair<SearchHit, String>>) {
    if (rejected.isEmpty()) return
    Text(
        pluralStringResource(R.plurals.ask_skipped, rejected.size, rejected.size),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val shield = LocalItemShield.current
    val shownTitle = { hit: SearchHit ->
        (hit as? SearchHit.Video)?.let { shield.titleOf(MediaItemId(it.watchUrl.value), it.title, it.subtitle) }
            ?: hit.title
    }
    rejected.forEach { (hit, why) ->
        Text(
            stringResource(R.string.ask_skipped_line, shownTitle(hit), why),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun rememberSpeech(onHeard: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val prompt = stringResource(R.string.ask_speech_prompt)
    val intent = remember(prompt) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    }
    val available = remember { intent.resolveActivity(context.packageManager) != null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        Diag.log("ask", "speech: result=${result.resultCode == Activity.RESULT_OK} heard=${heard != null}")
        heard?.takeIf { it.isNotBlank() }?.let(onHeard)
    }
    if (!available) return null
    return { launcher.launch(intent) }
}

private const val EARLIER_CHATS_SHOWN = 3
private const val EARLIER_CHATS_MAX_HEIGHT = 240

private val BUSY = setOf(
    AskViewModel.Stage.PACKING,
    AskViewModel.Stage.SENDING,
    AskViewModel.Stage.ASKING,
    AskViewModel.Stage.SEARCHING,
    AskViewModel.Stage.VETTING,
)

const val ASK_SCREEN_TAG = "ask-screen"
const val ASK_INPUT_TAG = "ask-input"
const val ASK_CARD_TAG = "ask-card"
const val ASK_ACTION_TAG = "ask-action"
