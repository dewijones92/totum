package com.dewijones92.totum.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.digest.DigestChapter
import com.dewijones92.totum.data.digest.DigestSubject
import com.dewijones92.totum.data.digest.TranscriptAnswer
import com.dewijones92.totum.data.digest.TranscriptDigest
import com.dewijones92.totum.data.digest.TranscriptText
import com.dewijones92.totum.data.transcript.Cue
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val DIGEST_SUMMARISE_TAG = "digestSummarise"
internal const val DIGEST_CARD_TAG = "digestCard"
internal const val DIGEST_CHAPTER_TAG = "digestChapter"
internal const val DIGEST_ASK_TAG = "digestAsk"
internal const val DIGEST_MOMENT_TAG = "digestMoment"
internal const val LONG_ITEM_SECONDS = 600

private sealed interface Phase {
    data object Idle : Phase

    data object Working : Phase

    data class Failed(val why: String) : Phase
}

private data class Asked(val question: String, val phase: AskPhase)

private sealed interface AskPhase {
    data object Working : AskPhase

    data class Answered(val answer: TranscriptAnswer) : AskPhase

    data class Failed(val why: String) : AskPhase
}

private class DigestCall(
    val scope: CoroutineScope,
    val host: DigestHost,
    val subject: DigestSubject,
    val cues: List<Cue>,
)

@Composable
internal fun TranscriptDigestPanel(
    state: PlaybackState,
    cues: List<Cue>,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    val host = LocalDigests.current ?: return
    val seconds = ((state.durationMs ?: cues.lastOrNull()?.endMs ?: 0L) / MS).toInt()
    val subject = remember(state.itemId, state.title, seconds) {
        DigestSubject(state.itemId.value, state.title, state.artist.orEmpty(), seconds)
    }
    val scope = rememberCoroutineScope()
    val kept by host.service.digests.collectAsStateWithLifecycle()
    val digest = kept[subject.itemId]?.digest
    var phase by remember(subject.itemId) { mutableStateOf<Phase>(Phase.Idle) }
    val asked = remember(subject.itemId) { mutableStateListOf<Asked>() }
    val call = remember(scope, host, subject, cues) { DigestCall(scope, host, subject, cues) }
    val summarising = stringResource(R.string.digest_op_summarise, state.title)
    val asking = stringResource(R.string.digest_op_ask, state.title)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (digest != null) {
            DigestCard(digest, positionMs, onSeekTo) {
                Diag.log("digest", "${subject.itemId}: summarise again asked for")
                phase = Phase.Working
                summarise(call, summarising, force = true) { phase = it }
            }
        } else if (seconds >= LONG_ITEM_SECONDS) {
            SummariseButton(phase) {
                phase = Phase.Working
                summarise(call, summarising, force = false) { phase = it }
            }
        }
        AskBox(asked, onSeekTo) { question -> ask(call, question, asking, asked) }
    }
}

@Composable
private fun SummariseButton(phase: Phase, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Button(onClick = onClick, enabled = phase != Phase.Working, modifier = Modifier.testTag(DIGEST_SUMMARISE_TAG)) {
            Text(stringResource(if (phase == Phase.Working) R.string.digest_working else R.string.digest_summarise))
        }
        when (phase) {
            Phase.Idle -> Text(
                stringResource(R.string.digest_summarise_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Phase.Working -> Unit
            is Phase.Failed -> Text(
                stringResource(R.string.digest_failed, phase.why.removePrefix(FAILED)),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DigestCard(digest: TranscriptDigest, positionMs: Long, onSeekTo: (Long) -> Unit, onAgain: () -> Unit) {
    val playing = digest.chapters.indexOfLast { it.startSec * MS <= positionMs }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(DIGEST_CARD_TAG),
    ) {
        Text(stringResource(R.string.digest_title), style = MaterialTheme.typography.titleSmall)
        digest.tldr.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        if (digest.chapters.isNotEmpty()) {
            Text(stringResource(R.string.digest_chapters), style = MaterialTheme.typography.titleSmall)
            digest.chapters.forEachIndexed { index, chapter ->
                ChapterRow(chapter, index == playing, onSeekTo)
            }
        }
        Text(
            stringResource(R.string.digest_ai_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAgain) { Text(stringResource(R.string.digest_again)) }
    }
}

@Composable
private fun ChapterRow(chapter: DigestChapter, playing: Boolean, onSeekTo: (Long) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                Diag.log("digest", "chapter tapped: \"${chapter.title}\" at ${chapter.startSec}s")
                onSeekTo(chapter.startSec * MS)
            }
            .padding(vertical = 4.dp)
            .testTag(DIGEST_CHAPTER_TAG),
    ) {
        Text(
            TranscriptText.stamp(chapter.startSec),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun AskBox(asked: List<Asked>, onSeekTo: (Long) -> Unit, onAsk: (String) -> Unit) {
    var question by remember { mutableStateOf("") }
    val send = {
        val text = question.trim()
        if (text.isNotEmpty()) {
            onAsk(text)
            question = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text(stringResource(R.string.digest_ask_label)) },
                placeholder = { Text(stringResource(R.string.digest_ask_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).testTag(DIGEST_ASK_TAG),
            )
            TextButton(onClick = send, enabled = question.isNotBlank()) {
                Text(stringResource(R.string.digest_ask_send))
            }
        }
        asked.asReversed().forEach { AskedRow(it, onSeekTo) }
    }
}

@Composable
private fun AskedRow(asked: Asked, onSeekTo: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.digest_question, asked.question), fontWeight = FontWeight.SemiBold)
        when (val phase = asked.phase) {
            AskPhase.Working -> Text(stringResource(R.string.digest_ask_working))
            is AskPhase.Failed -> Text(
                stringResource(R.string.digest_ask_failed, phase.why.removePrefix(FAILED)),
                color = MaterialTheme.colorScheme.error,
            )
            is AskPhase.Answered -> {
                val answer = phase.answer
                Text(
                    if (answer.found) answer.answer else stringResource(R.string.digest_ask_not_found, answer.answer),
                    style = MaterialTheme.typography.bodyMedium,
                )
                answer.moments.forEach { moment ->
                    Text(
                        stringResource(R.string.digest_moment, TranscriptText.stamp(moment.atSec), moment.quote),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                Diag.log("digest", "answer moment tapped: ${moment.atSec}s \"${moment.quote}\"")
                                onSeekTo(moment.atSec * MS)
                            }
                            .padding(vertical = 4.dp)
                            .testTag(DIGEST_MOMENT_TAG),
                    )
                }
                if (answer.found && answer.moments.isEmpty()) {
                    Text(stringResource(R.string.digest_ask_no_moment), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun summarise(call: DigestCall, label: String, force: Boolean, onPhase: (Phase) -> Unit) {
    call.scope.launch {
        val key = "digest:${call.subject.itemId}"
        if (call.host.operations?.isRunning(key) == true) return@launch
        var phase: Phase = Phase.Idle
        call.host.operations.tracking(key, OperationKind.TASK, label) {
            when (val outcome = call.host.service.summarise(call.subject, call.cues, force)) {
                is AskOutcome.Answered -> OperationEnd.Done(outcome.value.chapters.size).also { phase = Phase.Idle }
                is AskOutcome.Failed ->
                    OperationEnd.Failed(outcome.reason).also { phase = Phase.Failed(outcome.reason) }
            }
        }
        onPhase(phase)
    }
}

private fun ask(call: DigestCall, question: String, label: String, asked: MutableList<Asked>) {
    asked += Asked(question, AskPhase.Working)
    val index = asked.lastIndex
    call.scope.launch {
        var phase: AskPhase = AskPhase.Working
        val key = "digest-ask:${call.subject.itemId}:$question"
        call.host.operations.tracking(key, OperationKind.TASK, label) {
            when (val outcome = call.host.service.ask(call.subject, call.cues, question)) {
                is AskOutcome.Answered -> OperationEnd.Done(outcome.value.moments.size).also {
                    phase = AskPhase.Answered(outcome.value)
                }
                is AskOutcome.Failed ->
                    OperationEnd.Failed(outcome.reason).also { phase = AskPhase.Failed(outcome.reason) }
            }
        }
        asked.getOrNull(index)?.let { asked[index] = it.copy(phase = phase) }
    }
}

private const val MS = 1000L
private const val FAILED = "❌ "
