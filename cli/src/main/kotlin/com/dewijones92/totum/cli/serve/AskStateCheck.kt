package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.Command
import com.dewijones92.totum.cli.serve.ai.AiServer
import com.dewijones92.totum.cli.serve.ai.ClaudeCodeCompleter
import com.dewijones92.totum.cli.serve.ai.RemoteCompleter
import com.dewijones92.totum.cli.serve.ai.UsageLog
import com.dewijones92.totum.cli.serve.ai.UsageRecord
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.state.AppState
import com.dewijones92.totum.data.ask.state.AppStateBuilder
import com.dewijones92.totum.data.ask.state.AppStateInputs
import com.dewijones92.totum.data.ask.state.AskStateWire
import com.dewijones92.totum.data.ask.state.BuiltState
import com.dewijones92.totum.data.ask.state.ListenFact
import com.dewijones92.totum.data.ask.state.StateSchema
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupCodec
import com.dewijones92.totum.data.backup.BackupReadResult
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

internal suspend fun askStateCheck(command: Command.AskStateCheck, out: (String) -> Unit): Int {
    val backup = (BackupCodec.decode(File(command.backup).readText()) as? BackupReadResult.Ok)?.backup
    if (backup == null) {
        out("❌ that is not a Totum backup")
        return 1
    }
    val built = AppStateBuilder.build(inputsFrom(backup, command.events?.let { eventsFrom(File(it)) }.orEmpty()))
    out(
        "pack: ${built.json.toString().length} chars, counts ${built.counts}, dropped ${built.droppedKeys} key(s), " +
            "redacted ${built.redactedValues} value(s), ${built.items.size} resolvable item ids",
    )
    command.packOut?.let {
        File(it).writeText(built.json.toString())
        File("$it.schema.json").writeText(StateSchema.of(AppState.serializer().descriptor, built.json).toString())
    }
    val dir = Files.createTempDirectory("ask-state-check").toFile()
    val usage = UsageLog(File(dir, "ai-usage.jsonl"))
    val server = AiServer(ClaudeCodeCompleter(record = usage::record), usage, "haiku").start(0)
    try {
        val http = OkHttpClient.Builder().readTimeout(CHECK_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
        val brain = PromptBrain(RemoteCompleter("http://127.0.0.1:${server.address.port}", http))
        askAll(command.questions, built, brain, out)
        out("")
        out("usage: ${usage.read().joinToString { it.describe() }}")
    } finally {
        server.stop(0)
        dir.deleteRecursively()
    }
    return 0
}

private suspend fun askAll(questions: List<String>, built: BuiltState, brain: Brain, out: (String) -> Unit) {
    val states = AskStates()
    val asker = RoutedAsk(brain, states)
    var turns = emptyList<AskTurn>()
    questions.forEach { raw ->
        val followUp = raw.startsWith("+")
        val question = raw.removePrefix("+").trim()
        if (!followUp) turns = emptyList()
        val stateId = "check-${UUID.randomUUID()}"
        states.put(stateId, AskStateWire.unpack(AskStateWire.pack(built)))
        val began = System.currentTimeMillis()
        val outcome = runCatching { asker.answer(AskRequest(turns + AskTurn(question), stateId = stateId)) }
        val took = System.currentTimeMillis() - began
        out("")
        out("Q${if (followUp) " (follow-up)" else ""}: $question")
        outcome.onFailure { out("  FAILED in ${took}ms: ${it.message}") }
        outcome.onSuccess { answer ->
            out("  source=${answer.source} took=${took}ms trace=${answer.trace}")
            out("  reply: ${answer.reply}")
            answer.suggestions.forEach { card ->
                val titles = card.itemIds.take(SHOWN_TITLES).map { id -> built.items[id]?.item?.title ?: "?" }
                out("  card [${card.kind}] ${card.title} :: ${card.why} :: ${card.itemIds.size} id(s) $titles")
            }
            turns = turns + AskTurn(question, answer.suggestions.map { it.label }, answer.reply, answer.source)
        }
    }
}

private fun inputsFrom(backup: Backup, events: List<Breadcrumbs.Entry>): AppStateInputs {
    val now = System.currentTimeMillis()
    val queued = backup.queue.mapNotNull { it.toPlayable() }.map { QueueEntry(it) }
    val listens = backup.history.take(HISTORY_AS_LISTENS).mapIndexed { i, item ->
        ListenFact(
            atMs = now - (i + 1) * LISTEN_GAP_MS,
            title = item.title,
            author = item.author,
            source = item.sourceId,
            album = item.albumTitle,
            kind = if (item.contentKind.equals("MUSIC", ignoreCase = true)) "music" else "video",
            watched = false,
            heardMs = item.durationMs ?: DEFAULT_HEARD_MS,
            durationMs = item.durationMs,
            counted = true,
        )
    }
    return AppStateInputs(
        backup = backup,
        queue = QueueSnapshot(queued),
        downloaded = emptyList(),
        records = emptyList(),
        locked = emptySet(),
        listens = listens,
        stats = emptyList(),
        trail = emptyList(),
        trailCursor = -1,
        events = events,
        channels = emptyList(),
        starredName = STARRED,
        appVersion = backup.appVersion,
        nowMs = now,
        zone = ZoneId.systemDefault(),
    )
}

private fun UsageRecord.describe(): String = "$caller/$what ${tookMs}ms turns=$turns tools=$toolCalls cost=$costUsd"

private fun eventsFrom(file: File): List<Breadcrumbs.Entry> =
    (AskJson.parseToJsonElement(file.readText()) as JsonArray).mapIndexed { i, element ->
        val row = element.jsonObject
        Breadcrumbs.Entry(
            row.getValue("atMs").jsonPrimitive.longOrNull ?: 0L,
            row.getValue("tag").jsonPrimitive.content,
            row.getValue("message").jsonPrimitive.content,
            i.toLong(),
        )
    }

private const val STARRED = "⭐ Starred"
private const val HISTORY_AS_LISTENS = 50
private const val LISTEN_GAP_MS = 3 * 60 * 60 * 1000L
private const val DEFAULT_HEARD_MS = 5 * 60 * 1000L
private const val CHECK_READ_TIMEOUT_S = 330L
private const val SHOWN_TITLES = 3
