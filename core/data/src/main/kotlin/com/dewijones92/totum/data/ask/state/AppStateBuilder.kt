package com.dewijones92.totum.data.ask.state

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.ReportRedactor
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.BackupProgress
import com.dewijones92.totum.data.backup.BackupSubscription
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.download.DownloadRecord
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.canonicalItemId
import com.dewijones92.totum.domain.episodeCodeOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

public data class ListenFact(
    val atMs: Long,
    val title: String,
    val author: String?,
    val source: String?,
    val album: String?,
    val kind: String,
    val watched: Boolean,
    val heardMs: Long,
    val durationMs: Long?,
    val counted: Boolean,
    val itemId: String = "",
)

public data class StatFact(val atMs: Long, val type: String, val detail: String)

public data class AppStateInputs(
    public val backup: Backup,
    public val queue: QueueSnapshot,
    public val downloaded: List<DownloadedMedia>,
    public val records: List<DownloadRecord>,
    public val locked: Set<String>,
    public val listens: List<ListenFact>,
    public val stats: List<StatFact>,
    public val trail: List<PlayableItem>,
    public val trailCursor: Int,
    public val events: List<Breadcrumbs.Entry>,
    public val channels: List<BackupSubscription>,
    public val starredName: String,
    public val appVersion: String,
    public val nowMs: Long,
    public val zone: ZoneId,
    public val sizeOf: (String) -> Long? = { null },
    public val secrets: List<String> = emptyList(),
    public val hosts: List<String> = emptyList(),
    public val titleFor: (id: String, title: String, author: String?) -> String = { _, title, _ -> title },
)

public class BuiltState(
    public val json: JsonObject,
    public val counts: Map<String, Int>,
    public val notes: List<String>,
    public val droppedKeys: Int,
    public val redactedValues: Int,
    public val items: Map<String, PlayableItem>,
)

@Suppress("TooManyFunctions")
public object AppStateBuilder {

    public const val MAX_LISTENS: Int = 3_000
    public const val MAX_OTHER_STATS: Int = 500
    public const val MAX_EVENTS: Int = 5_000

    public fun build(inputs: AppStateInputs): BuiltState {
        val facts = Facts(inputs)
        val prose = ReportRedactor(inputs.secrets, inputs.hosts)
        val identifiers = ReportRedactor(inputs.secrets, inputs.hosts, keepIdentifiers = true)
        val notes = mutableListOf<String>()
        val listens = inputs.listens.sortedByDescending { it.atMs }
        val others = inputs.stats.sortedByDescending { it.atMs }
        if (listens.size > MAX_LISTENS) notes += "listens: newest $MAX_LISTENS of ${listens.size}"
        if (others.size > MAX_OTHER_STATS) notes += "otherStats: newest $MAX_OTHER_STATS of ${others.size}"
        if (inputs.events.size > MAX_EVENTS) notes += "events: newest $MAX_EVENTS of ${inputs.events.size}"
        if (inputs.trail.isEmpty()) notes += "trail: nothing has played since the trail began"
        notes += "downloads at home on the Pi are not in this snapshot"
        val state = AppState(
            snapshot = SnapshotInfo(
                takenAt = facts.at(inputs.nowMs),
                takenAtMs = inputs.nowMs,
                timeZone = inputs.zone.id,
                appVersion = inputs.appVersion,
                counts = emptyMap(),
                notes = notes,
            ),
            queue = facts.queue(),
            history = inputs.backup.history.map { facts.row(it) },
            playlists = inputs.backup.playlists.map { PlaylistRow(it.name, it.items.size, it.items.map(facts::row)) },
            subscriptions = (inputs.backup.subscriptions + inputs.channels).distinctBy { it.id }.map(
                facts::subscription
            ),
            groups = inputs.backup.groups.map { GroupRow(it.name, it.sources.map(facts::subscription)) },
            downloads = facts.downloads(),
            progress = facts.progressRows(),
            listens = listens.take(MAX_LISTENS).map(facts::listen),
            otherStats = others.take(MAX_OTHER_STATS).map { StatRow(facts.at(it.atMs), it.atMs, it.type, it.detail) },
            trail = facts.trail(),
            settings = settings(inputs.backup.settings),
            events = inputs.events.takeLast(
                MAX_EVENTS
            ).map { EventRow(facts.at(it.atEpochMs), it.atEpochMs, it.tag, it.message) },
        ).let { built ->
            built.copy(
                otherStats = built.otherStats.map {
                    it.copy(
                        detail = prose.redact(facts.withoutHiddenTitles(it.detail))
                    )
                },
                events = built.events.map { it.copy(message = prose.redact(facts.withoutHiddenTitles(it.message))) },
            )
        }
        val counts = countsOf(state)
        val counted = state.copy(snapshot = state.snapshot.copy(counts = counts))
        val scrubbed = StateScrub.clean(StateJson.encodeToJsonElement(AppState.serializer(), counted), identifiers)
        return BuiltState(
            json = scrubbed.element as JsonObject,
            counts = counts,
            notes = notes,
            droppedKeys = scrubbed.droppedKeys,
            redactedValues = scrubbed.redactedValues,
            items = facts.index(),
        )
    }

    public fun settings(values: Map<String, String>): JsonObject =
        JsonObject(values.toSortedMap().mapValues { (_, text) -> settingValue(text) })

    private fun settingValue(text: String): JsonElement {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { StateJson.parseToJsonElement(trimmed) }.getOrNull()?.let { return it }
        }
        return when {
            trimmed == "true" || trimmed == "false" -> JsonPrimitive(trimmed.toBoolean())
            trimmed.toLongOrNull() != null -> JsonPrimitive(trimmed.toLong())
            else -> JsonPrimitive(text)
        }
    }

    private fun countsOf(state: AppState): Map<String, Int> = linkedMapOf(
        "queue" to state.queue.items.size,
        "history" to state.history.size,
        "playlists" to state.playlists.size,
        "subscriptions" to state.subscriptions.size,
        "groups" to state.groups.size,
        "downloads" to state.downloads.size,
        "progress" to state.progress.size,
        "listens" to state.listens.size,
        "otherStats" to state.otherStats.size,
        "trail" to state.trail.items.size,
        "settings" to state.settings.size,
        "events" to state.events.size,
    )

    public fun durationText(ms: Long): String {
        val seconds = ms / MS_PER_SECOND
        val h = seconds / SECONDS_PER_HOUR
        val m = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val s = seconds % SECONDS_PER_MINUTE
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    public fun sizeText(bytes: Long): String = when {
        bytes >= BYTES_PER_GB -> "%.2f GB".format(bytes / BYTES_PER_GB.toDouble())
        bytes >= BYTES_PER_MB -> "%.1f MB".format(bytes / BYTES_PER_MB.toDouble())
        else -> "${bytes / BYTES_PER_KB} KB"
    }

    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val BYTES_PER_KB = 1_000L
    private const val BYTES_PER_MB = 1_000_000L
    private const val BYTES_PER_GB = 1_000_000_000L

    private val TIME: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private class Facts(private val inputs: AppStateInputs) {
        private val progress: Map<String, BackupProgress> =
            inputs.backup.progress.associateBy { canonicalItemId(it.itemId) }
        private val queueEntries: List<QueueEntry> = inputs.queue.entries
        private val queuePlace: Map<String, Int> = buildMap {
            queueEntries.forEachIndexed { i, entry -> putIfAbsent(canonicalItemId(entry.item.item.id.value), i + 1) }
        }
        private val starred: Set<String> = inputs.backup.playlists.filter { it.name == inputs.starredName }
            .flatMap { it.items }.mapTo(mutableSetOf()) { canonicalItemId(it.itemId) }
        private val downloaded: Map<String, DownloadedMedia> =
            inputs.downloaded.associateBy { canonicalItemId(it.item.id.value) }
        private val groupIds: List<Pair<String, List<String>>> =
            inputs.backup.groups.map { it.name to it.sources.map(BackupSubscription::id) }
        private val titles: MutableMap<String, String> = mutableMapOf()
        private val hiddenTitles: MutableMap<String, String> = mutableMapOf()

        fun shown(id: String, title: String, author: String?): String =
            inputs.titleFor(id, title, author).also { if (it != title) hiddenTitles[title] = it }

        fun withoutHiddenTitles(text: String): String =
            hiddenTitles.entries.filter { it.key.length >= MIN_HIDDEN_TITLE }.sortedByDescending { it.key.length }
                .fold(text) { acc, (real, safe) -> acc.replace(real, safe, ignoreCase = true) }

        fun at(ms: Long): String = TIME.format(
            Instant.ofEpochMilli(ms).atZone(inputs.zone).toOffsetDateTime().withNano(0)
        )

        fun queue(): QueueState {
            val current = inputs.queue.currentIndex.takeIf { it >= 0 && it < queueEntries.size }?.plus(1)
            val rows = queueEntries.mapIndexed { i, entry -> queueRow(entry, i + 1 == current) }
            val groups = mutableListOf<QueueGroupRow>()
            var lastKey: Pair<String?, String?>? = null
            queueEntries.forEachIndexed { i, entry ->
                val key = entry.group?.id to entry.group?.title
                val unplayed = if (rows[i].played) 0 else 1
                if (key == lastKey) {
                    val last = groups.last()
                    val longer = last.copy(entries = last.entries + 1, unplayed = last.unplayed + unplayed)
                    groups[groups.lastIndex] = longer
                } else {
                    groups += QueueGroupRow(entry.group?.id, entry.group?.title ?: UNGROUPED, 1, unplayed)
                    lastKey = key
                }
            }
            return QueueState(current, groups, rows)
        }

        private fun queueRow(entry: QueueEntry, isCurrent: Boolean): ItemRow = row(
            entry.item.toBackup(),
            queueGroup = entry.group?.title,
            addedAt = entry.addedAt?.toEpochMilli(),
            current = isCurrent,
        )

        fun row(
            item: BackupItem,
            queueGroup: String? = null,
            addedAt: Long? = null,
            current: Boolean = false,
        ): ItemRow {
            val id = canonicalItemId(item.itemId)
            val shown = shown(item.itemId, item.title, item.author)
            titles.putIfAbsent(id, shown)
            val record = progress[id]
            val played = record?.completedAtEpochMs != null
            val position = record?.takeIf { !played && it.positionMs > 0 }?.positionMs
            val length = record?.durationMs ?: item.durationMs
            return ItemRow(
                itemId = item.itemId,
                title = shown,
                author = item.author,
                publisher = item.publisher,
                kind = item.contentKind.lowercase(),
                playback = item.playbackType,
                album = item.albumTitle,
                durationMs = item.durationMs,
                durationText = item.durationMs?.let(::durationText),
                publishedAt = item.publishedAtMs?.let(::at),
                publishedText = item.publishedText,
                viewsText = item.viewsText,
                membersOnly = item.membersOnly,
                episode = episodeOf(item),
                queueGroup = queueGroup,
                addedAt = addedAt?.let(::at),
                current = current,
                queuePosition = queuePlace[id],
                sourceGroups = groupsOf(item),
                played = played,
                positionMs = position,
                positionText = position?.let(::durationText),
                percentDone = if (position != null && length != null && length > 0) {
                    (position * PERCENT / length).toInt().coerceIn(0, PERCENT)
                } else {
                    null
                },
                starred = id in starred,
                downloaded = downloaded[id]?.let { copy ->
                    val size = inputs.sizeOf(copy.localPath)
                    DownloadedHere(copy.audioOnly, size, size?.let(::sizeText))
                },
            )
        }

        private fun episodeOf(item: BackupItem): EpisodeRow? {
            val code = episodeCodeOf(item.title) ?: item.mediaUrl?.let(::fileNameOf)?.let(::episodeCodeOf)
            return code?.let { EpisodeRow(it.season, it.episode, "S%02dE%02d".format(it.season, it.episode)) }
        }

        private fun fileNameOf(url: String): String? = runCatching {
            URLDecoder.decode(URI(url).path.substringAfterLast('/'), Charsets.UTF_8)
        }.getOrNull()

        private fun groupsOf(item: BackupItem): List<String> = groupIds.filter { (_, ids) ->
            ids.any { id -> id == item.sourceId || (item.sourceUrl?.contains(id.substringAfterLast(':')) == true) }
        }.map { it.first }

        fun subscription(entry: BackupSubscription) = SubscriptionRow(
            title = entry.title,
            kind = entry.kind,
            publisher = entry.publisher,
            host = runCatching { URI(entry.url).host }.getOrNull(),
            subscribedAt = entry.subscribedAtEpochMs?.let(::at),
        )

        fun downloads(): List<DownloadRow> {
            val finished = inputs.downloaded.map { copy ->
                val id = canonicalItemId(copy.item.id.value)
                val size = inputs.sizeOf(copy.localPath)
                DownloadRow(
                    itemId = copy.item.id.value,
                    title = shown(copy.item.id.value, copy.item.title, copy.item.author),
                    author = copy.item.author,
                    state = "downloaded",
                    audioOnly = copy.audioOnly,
                    sizeBytes = size,
                    sizeText = size?.let(::sizeText),
                    downloadedAt = copy.downloadedAt?.toEpochMilli()?.let(::at),
                    openedAt = copy.openedAt?.toEpochMilli()?.let(::at),
                    locked = id in inputs.locked,
                    played = progress[id]?.completedAtEpochMs != null,
                    queuePosition = queuePlace[id],
                )
            }
            val finishedIds = finished.mapTo(mutableSetOf()) { canonicalItemId(it.itemId) }
            val others = inputs.records.filter { canonicalItemId(it.item.item.id.value) !in finishedIds }
                .filter { it.state !is DownloadState.NotDownloaded && it.state !is DownloadState.Downloaded }
                .map { record ->
                    val id = canonicalItemId(record.item.item.id.value)
                    DownloadRow(
                        itemId = record.item.item.id.value,
                        title = shown(record.item.item.id.value, record.item.item.title, record.item.item.author),
                        author = record.item.item.author,
                        state = when (record.state) {
                            is DownloadState.Downloading -> "downloading"
                            is DownloadState.Failed -> "failed"
                            else -> "waiting"
                        },
                        percent = (record.state as? DownloadState.Downloading)?.fraction
                            ?.let { (it * PERCENT).toInt() },
                        failure = (record.state as? DownloadState.Failed)?.reason,
                        locked = id in inputs.locked,
                        played = progress[id]?.completedAtEpochMs != null,
                        queuePosition = queuePlace[id],
                    )
                }
            return finished + others
        }

        fun progressRows(): List<ProgressRow> = inputs.backup.progress.map { record ->
            val id = canonicalItemId(record.itemId)
            val played = record.completedAtEpochMs != null
            val position = record.takeIf { !played && it.positionMs > 0 }?.positionMs
            ProgressRow(
                itemId = record.itemId,
                title = titles[id],
                played = played,
                positionMs = position,
                positionText = position?.let(::durationText),
                durationMs = record.durationMs,
                durationText = record.durationMs?.let(::durationText),
                percentDone = if (position != null && record.durationMs != null && record.durationMs > 0) {
                    (position * PERCENT / record.durationMs).toInt().coerceIn(0, PERCENT)
                } else {
                    null
                },
            )
        }

        fun listen(fact: ListenFact) = ListenRow(
            at = at(fact.atMs),
            atMs = fact.atMs,
            title = shown(fact.itemId, fact.title, fact.author),
            author = fact.author,
            source = fact.source,
            album = fact.album,
            kind = fact.kind,
            watched = fact.watched,
            heardMs = fact.heardMs,
            heardText = durationText(fact.heardMs),
            durationMs = fact.durationMs,
            counted = fact.counted,
        )

        fun trail(): TrailState {
            val position = inputs.trailCursor.takeIf { it >= 0 && it < inputs.trail.size }?.plus(1)
            return TrailState(position, inputs.trail.map { row(it.toBackup()) })
        }

        fun index(): Map<String, PlayableItem> = buildMap {
            fun add(item: PlayableItem) {
                putIfAbsent(item.item.id.value, item)
                putIfAbsent(canonicalItemId(item.item.id.value), item)
            }
            queueEntries.forEach { add(it.item) }
            inputs.downloaded.forEach { add(it.playable) }
            inputs.records.forEach { add(it.item) }
            inputs.trail.forEach(::add)
            val backed = inputs.backup.history + inputs.backup.playlists.flatMap { it.items }
            backed.mapNotNull { it.toPlayable() }.forEach(::add)
        }
    }

    private const val PERCENT = 100
    private const val MIN_HIDDEN_TITLE = 6
    private const val UNGROUPED = "(added by you, no group)"
}
