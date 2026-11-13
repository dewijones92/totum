package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.state.AppStateBuilder
import com.dewijones92.totum.data.ask.state.AppStateInputs
import com.dewijones92.totum.data.ask.state.AskStateWire
import com.dewijones92.totum.data.ask.state.BuiltState
import com.dewijones92.totum.data.ask.state.ListenFact
import com.dewijones92.totum.data.ask.state.StatFact
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupSubscription
import com.dewijones92.totum.data.download.DownloadRecord
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.canonicalItemId
import com.dewijones92.totum.stats.StatEvent
import com.dewijones92.totum.stats.StatsCodec
import com.dewijones92.totum.stats.counts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.UUID

data class AskStatePorts(
    val backup: suspend () -> Backup,
    val queue: suspend () -> QueueSnapshot,
    val downloaded: suspend () -> List<DownloadedMedia>,
    val records: suspend () -> List<DownloadRecord>,
    val locked: () -> Set<String>,
    val stats: suspend () -> List<StatEvent>,
    val trail: () -> Pair<List<PlayableItem>, Int>,
    val channels: suspend () -> List<BackupSubscription>,
    val appVersion: String,
    val starredName: String,
    val secrets: () -> List<String> = { emptyList() },
    val hosts: () -> List<String> = { emptyList() },
    val titleFor: (id: String, title: String, author: String?) -> String = { _, title, _ -> title },
)

class AskStateSource(
    private val ports: AskStatePorts,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val sizeOf: (String) -> Long? = { path -> File(path).takeIf(File::isFile)?.length() },
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    class Snapshot(val id: String, val built: BuiltState, val packed: ByteArray, val tookMs: Long)

    private var lastItems: Map<String, PlayableItem> = emptyMap()

    suspend fun snapshot(): Snapshot = withContext(work) {
        val began = now()
        val inputs = inputs()
        val built = AppStateBuilder.build(inputs)
        val packed = AskStateWire.pack(built)
        lastItems = built.items
        val took = now() - began
        Diag.log(
            "ask",
            "state packed in ${took}ms: ${built.json.toString().length} chars, ${packed.size} bytes gzipped, " +
                "counts ${built.counts}, ${built.droppedKeys} secret key(s) dropped, " +
                "${built.redactedValues} value(s) redacted, ${built.items.size} item ids resolvable",
        )
        Snapshot("ask-${UUID.randomUUID()}", built, packed, took)
    }

    suspend fun resolve(ids: List<String>): List<PlayableItem> {
        val known = lastItems.takeIf { map -> ids.all { it in map || canonicalItemId(it) in map } }
            ?: withContext(work) { AppStateBuilder.build(inputs()).items }.also { lastItems = it }
        val found = ids.mapNotNull { known[it] ?: known[canonicalItemId(it)] }
        Diag.log("ask", "app card: ${found.size} of ${ids.size} item id(s) found on this phone")
        return found
    }

    suspend fun inputs(): AppStateInputs {
        val (items, position) = ports.trail()
        val events = Breadcrumbs.snapshot()
        val all = ports.stats()
        return AppStateInputs(
            backup = ports.backup(),
            queue = ports.queue(),
            downloaded = ports.downloaded(),
            records = ports.records(),
            locked = ports.locked(),
            listens = all.filterIsInstance<StatEvent.Listen>().map(::listenFact),
            stats = all.filterNot {
                it is StatEvent.Listen || it is StatEvent.PhoneScreen
            }.map { StatFact(it.at, typeOf(it), StatsCodec.line(it)) },
            trail = items,
            trailCursor = position,
            events = events,
            channels = ports.channels(),
            starredName = ports.starredName,
            appVersion = ports.appVersion,
            nowMs = now(),
            zone = zone(),
            sizeOf = sizeOf,
            secrets = ports.secrets(),
            hosts = ports.hosts(),
            titleFor = ports.titleFor,
        )
    }

    private fun listenFact(listen: StatEvent.Listen) = ListenFact(
        atMs = listen.at,
        title = listen.title,
        author = listen.author,
        source = listen.source,
        album = listen.album,
        kind = listen.kind.name.lowercase(),
        watched = listen.watched,
        heardMs = listen.heardMs,
        durationMs = listen.durationMs,
        counted = listen.counts,
        itemId = listen.itemId,
    )

    private fun typeOf(event: StatEvent): String = when (event) {
        is StatEvent.Listen -> "listen"
        is StatEvent.ExsurgeAction -> "exsurge"
        is StatEvent.ExsurgeSwitch -> "exsurge-switch"
        is StatEvent.Nudge -> "nudge"
        is StatEvent.AlarmChange -> "alarm"
        is StatEvent.AppUse -> "use:${event.what.name.lowercase()}"
        is StatEvent.PhoneUnlock -> "phone-unlock"
        is StatEvent.PhoneScreen -> "phone-screen"
        is StatEvent.UnlockNudge -> "unlock-nudge"
    }
}
