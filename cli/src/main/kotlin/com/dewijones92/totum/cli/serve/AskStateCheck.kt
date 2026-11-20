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
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.state.AppState
import com.dewijones92.totum.data.ask.state.AppStateBuilder
import com.dewijones92.totum.data.ask.state.AppStateInputs
import com.dewijones92.totum.data.ask.state.AskStateEnvelope
import com.dewijones92.totum.data.ask.state.AskStateWire
import com.dewijones92.totum.data.ask.state.ListenFact
import com.dewijones92.totum.data.ask.state.StateSchema
import com.dewijones92.totum.data.ask.tasteOf
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupCodec
import com.dewijones92.totum.data.backup.BackupReadResult
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.StatEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal suspend fun askStateCheck(command: Command.AskStateCheck, out: (String) -> Unit): Int {
    val file = File(command.backup)
    val pack = if (isGzip(file)) {
        stateDumpOf(file)?.let(::packOfDump)
    } else {
        (BackupCodec.decode(file.readText()) as? BackupReadResult.Ok)?.backup?.let { packOfBackup(it, command) }
    }
    if (pack == null) {
        out("❌ that is neither a Totum backup nor a Totum state dump")
        return 1
    }
    out(pack.describe)
    val dir = Files.createTempDirectory("ask-state-check").toFile()
    val usage = UsageLog(File(dir, "ai-usage.jsonl"))
    val server = AiServer(ClaudeCodeCompleter(record = usage::record), usage, "haiku").start(0)
    try {
        val http = OkHttpClient.Builder().readTimeout(CHECK_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
        val brain = PromptBrain(RemoteCompleter("http://127.0.0.1:${server.address.port}", http))
        askAll(command.questions, pack, brain, out)
        out("")
        out("usage: ${usage.read().joinToString { it.describe() }}")
    } finally {
        server.stop(0)
        dir.deleteRecursively()
    }
    return 0
}

private class CheckPack(
    val describe: String,
    val envelope: () -> AskStateEnvelope,
    val titleOf: (String) -> String,
    val have: List<String>,
    val taste: Taste,
)

private suspend fun askAll(questions: List<String>, pack: CheckPack, brain: Brain, out: (String) -> Unit) {
    val states = AskStates()
    val asker = RoutedAsk(brain, states)
    var turns = emptyList<AskTurn>()
    questions.forEach { raw ->
        val followUp = raw.startsWith("+")
        val question = raw.removePrefix("+").trim()
        if (!followUp) turns = emptyList()
        val stateId = "check-${UUID.randomUUID()}"
        states.put(stateId, pack.envelope())
        val began = System.currentTimeMillis()
        val outcome = runCatching {
            asker.answer(AskRequest(turns + AskTurn(question), have = pack.have, taste = pack.taste, stateId = stateId))
        }
        val took = System.currentTimeMillis() - began
        out("")
        out("Q${if (followUp) " (follow-up)" else ""}: $question")
        outcome.onFailure { out("  FAILED in ${took}ms: ${it.message}") }
        outcome.onSuccess { answer ->
            out("  source=${answer.source} took=${took}ms trace=${answer.trace}")
            out("  reply: ${answer.reply}")
            answer.suggestions.forEach { card ->
                val titles = card.itemIds.take(SHOWN_TITLES).map(pack.titleOf)
                out("  card [${card.kind}] ${card.title} :: ${card.why} :: ${card.itemIds.size} id(s) $titles")
            }
            turns = turns + AskTurn(question, answer.suggestions.map { it.label }, answer.reply, answer.source)
        }
    }
}

private fun packOfBackup(backup: Backup, command: Command.AskStateCheck): CheckPack {
    val inputs = inputsFrom(backup, command.events?.let { eventsFrom(File(it)) }.orEmpty())
    val built = AppStateBuilder.build(inputs)
    command.packOut?.let {
        File(it).writeText(built.json.toString())
        File("$it.schema.json").writeText(StateSchema.of(AppState.serializer().descriptor, built.json).toString())
    }
    val listens = inputs.listens.map { it.toStat() }
    return CheckPack(
        describe = "pack: ${built.json.toString().length} chars, counts ${built.counts}, " +
            "dropped ${built.droppedKeys} key(s), redacted ${built.redactedValues} value(s), " +
            "${built.items.size} resolvable item ids",
        envelope = { AskStateWire.unpack(AskStateWire.pack(built)) },
        titleOf = { id -> built.items[id]?.item?.title ?: "?" },
        have = backup.history.map { it.title }.take(CHECK_HAVE),
        taste = tasteOf(
            emptyList(),
            emptyList(),
            emptyList(),
            listens,
            System.currentTimeMillis(),
        ).copy(
            channels = backup.subscriptions.filter { it.kind == "channel" }.map { it.title },
            shows = backup.subscriptions.filter { it.kind == "podcast" }.map { it.title },
        ),
    )
}

private fun packOfDump(dump: JsonObject): CheckPack {
    val state = dump.getValue("state").jsonObject
    val rows = { key: String -> (state[key] as? JsonArray).orEmpty().map { it.jsonObject } }
    val text = { row: JsonObject, key: String -> (row[key] as? JsonPrimitive)?.contentOrNull }
    val subscriptions = rows("subscriptions")
    val listens = rows("listens").map { row ->
        StatEvent.Listen(
            at = (row["atMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
            playId = "check",
            itemId = "check",
            title = text(row, "title").orEmpty(),
            author = text(row, "author"),
            source = text(row, "source"),
            kind = ListenKind.entries.firstOrNull { it.name.equals(text(row, "kind"), ignoreCase = true) }
                ?: ListenKind.VIDEO,
            watched = (row["watched"] as? JsonPrimitive)?.booleanOrNull ?: false,
            heardMs = (row["heardMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
            durationMs = (row["durationMs"] as? JsonPrimitive)?.longOrNull,
        )
    }
    val starred = rows("playlists").filter { text(it, "name")?.contains("Starred") == true }
        .flatMap { (it["items"] as? JsonArray).orEmpty() }
        .mapNotNull { (it as? JsonObject)?.let { row -> text(row, "title") } }
    val taste = tasteOf(
        emptyList(),
        emptyList(),
        emptyList(),
        listens,
        System.currentTimeMillis(),
        starred = starred,
    ).copy(
        channels = subscriptions.filter { text(it, "kind") == "channel" }.mapNotNull { text(it, "title") },
        shows = subscriptions.filter { text(it, "kind") == "podcast" }.mapNotNull { text(it, "title") },
    )
    val owned = (rows("downloads") + rows("history")).mapNotNull { text(it, "title") }.distinct().take(CHECK_HAVE)
    val titles = listOf("queue", "history", "downloads", "progress").flatMap { rows(it) }
        .mapNotNull { row -> text(row, "itemId")?.let { id -> id to (text(row, "title") ?: "?") } }.toMap()
    return CheckPack(
        describe = "state dump: ${state.toString().length} chars, " +
            "counts ${state["snapshot"]?.jsonObject?.get("counts")}, " +
            "taste ${taste.channels.size} channels / ${taste.shows.size} shows / ${taste.played.size} played, " +
            "${owned.size} owned titles",
        envelope = {
            AskStateEnvelope(StateSchema.of(AppState.serializer().descriptor, state), state)
        },
        titleOf = { id -> titles[id] ?: "?" },
        have = owned,
        taste = taste,
    )
}

private fun ListenFact.toStat() = StatEvent.Listen(
    at = atMs,
    playId = "check",
    itemId = "check",
    title = title,
    author = author,
    source = source,
    album = album,
    kind = if (kind == "music") ListenKind.MUSIC else ListenKind.VIDEO,
    watched = watched,
    heardMs = heardMs,
    durationMs = durationMs,
)

private fun isGzip(file: File): Boolean =
    file.inputStream().use { it.read() == GZIP_MAGIC_0 && it.read() == GZIP_MAGIC_1 }

private fun stateDumpOf(file: File): JsonObject? = runCatching {
    GZIPInputStream(file.inputStream()).use { AskJson.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)) }
        .jsonObject.takeIf { (it["kind"] as? JsonPrimitive)?.contentOrNull == "totum-state-dump" }
}.getOrNull()

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
private const val CHECK_HAVE = 200
private const val GZIP_MAGIC_0 = 0x1f
private const val GZIP_MAGIC_1 = 0x8b
