package com.dewijones92.totum.data.ask.state

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupGroup
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.BackupPlaylist
import com.dewijones92.totum.data.backup.BackupProgress
import com.dewijones92.totum.data.backup.BackupSubscription
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.download.DownloadRecord
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

object StateTestData {

    const val NOW: Long = 1_793_000_000_000L
    const val SECRET_FEED_KEY = "SUPERSECRETFEEDKEY123"
    const val SECRET_TOKEN = "TOKENVALUE-9f8e7d6c5b4a"
    const val GOOGLE_SIG = "AOq0QJ8wRgIhAKsig"

    fun media(
        id: String,
        title: String,
        author: String? = "A Channel",
        source: String = "https://www.youtube.com/channel/UCchannel1",
        minutes: Int? = 10,
        url: String? = "https://media.example/$id.mp4?totumToken=$SECRET_TOKEN&sig=$GOOGLE_SIG",
    ): PlayableItem = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId(source),
        title = title,
        publishedAt = Instant.ofEpochMilli(NOW - DAY_MS),
        duration = minutes?.minutes,
        author = author,
        mediaUrl = url?.let(HttpUrl::of),
        thumbnailUrl = HttpUrl.of("https://img.example/$id.jpg?key=$SECRET_FEED_KEY"),
    ).asPlayable()

    val queue: List<QueueEntry> = listOf(
        QueueEntry(media("v1", "Day 2 highlights"), QueueGroup("g-tennis", "Tennis")),
        QueueEntry(media("v2", "Peep Show S02E03 Mugging", author = "Peep Show"), QueueGroup("g-peep", "Peep Show")),
        QueueEntry(
            media("v3", "Day 3 highlights"),
            QueueGroup("g-tennis", "Tennis"),
            Instant.ofEpochMilli(NOW - HOUR_MS)
        ),
        QueueEntry(media("torrent:0123456789abcdef0123456789abcdef01234567:4", "S01E04", minutes = null)),
    )

    private fun backed(entry: QueueEntry): BackupItem = entry.item.toBackup()

    val backup: Backup = Backup(
        createdAtEpochMs = NOW,
        appVersion = "0.1.900",
        subscriptions = listOf(
            BackupSubscription(
                "https://feeds.example/show.rss?key=$SECRET_FEED_KEY",
                "A Show",
                "https://feeds.example/show.rss?key=$SECRET_FEED_KEY",
                "podcast",
                NOW - DAY_MS,
                "A Network",
            ),
        ),
        playlists = listOf(
            BackupPlaylist("⭐ Starred", listOf(backed(queue[0]))),
            BackupPlaylist("Road trip", listOf(backed(queue[2]))),
        ),
        history = listOf(backed(queue[1])),
        progress = listOf(
            BackupProgress("v1", 0, null, completedAtEpochMs = 1),
            BackupProgress("v2", 4 * MINUTE_MS, 10 * MINUTE_MS),
            BackupProgress("orphan-item", 90_000, null),
        ),
        settings = mapOf(
            "autoPlayNext" to "true",
            "cellularMaxHeight" to "240",
            "pins.v1" to """[{"kind":"album","browseId":"MPREb_x","title":"An Album"}]""",
            "inboxSources" to "a\nb",
            "homeServerToken" to SECRET_TOKEN,
        ),
        groups = listOf(
            BackupGroup(
                "🎾 tennis",
                listOf(
                    BackupSubscription(
                        "https://www.youtube.com/channel/UCchannel1",
                        "WTA",
                        "https://www.youtube.com/channel/UCchannel1",
                        "channel"
                    )
                ),
            ),
        ),
    )

    val downloaded: List<DownloadedMedia> = listOf(
        DownloadedMedia(
            queue[1].item,
            "/data/v2.mp4",
            audioOnly = false,
            downloadedAt = Instant.ofEpochMilli(NOW - HOUR_MS)
        ),
    )

    val records: List<DownloadRecord> = listOf(
        DownloadRecord(queue[0].item, DownloadState.Downloading(50, 100)),
        DownloadRecord(queue[2].item, DownloadState.Failed("403 forbidden")),
    )

    val events: List<Breadcrumbs.Entry> = listOf(
        Breadcrumbs.Entry(
            NOW - 2 * MINUTE_MS,
            "route",
            "v2 -> VideoStream https://rr1.googlevideo.com/videoplayback?expire=1&sig=$GOOGLE_SIG&id=o-ABC",
            1
        ),
        Breadcrumbs.Entry(NOW - MINUTE_MS, "home", "request failed: Authorization: Bearer $SECRET_TOKEN", 2),
        Breadcrumbs.Entry(NOW - 30_000, "queue", "nothing next of 4", 3),
    )

    fun inputs(
        sizeOf: (String) -> Long? = { 5_000_000L },
        listens: List<ListenFact> = listOf(
            ListenFact(NOW - HOUR_MS, "A song", "An Artist", null, "An Album", "music", false, 200_000, 240_000, true),
        ),
        stats: List<StatFact> = listOf(StatFact(NOW - HOUR_MS, "use:search", """{"type":"use","what":"SEARCH"}""")),
        events: List<Breadcrumbs.Entry> = this.events,
        secrets: List<String> = emptyList(),
        hosts: List<String> = emptyList(),
        titleFor: (String, String, String?) -> String = { _, title, _ -> title },
    ): AppStateInputs = AppStateInputs(
        backup = backup,
        queue = QueueSnapshot(queue, currentIndex = 1),
        downloaded = downloaded,
        records = records,
        locked = setOf("v2"),
        listens = listens,
        stats = stats,
        trail = listOf(queue[0].item, queue[1].item),
        trailCursor = 1,
        events = events,
        channels = listOf(
            BackupSubscription(
                "https://www.youtube.com/channel/UCchannel1",
                "WTA",
                "https://www.youtube.com/channel/UCchannel1",
                "channel"
            )
        ),
        starredName = "⭐ Starred",
        appVersion = "0.1.900",
        nowMs = NOW,
        zone = ZoneId.of("Europe/London"),
        sizeOf = sizeOf,
        secrets = secrets,
        hosts = hosts,
        titleFor = titleFor,
    )

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
}
