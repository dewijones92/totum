package com.dewijones92.totum.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FeedEntity::class,
        EpisodeEntity::class,
        DownloadEntity::class,
        PlaybackProgressEntity::class,
        LocalPlaylistEntity::class,
        LocalPlaylistItemEntity::class,
        PlayHistoryEntity::class,
        QueueEntity::class,
        SourceGroupEntity::class,
        SourceGroupMemberEntity::class,
        CachedFeedItemEntity::class,
        AccountProgressOutboxEntity::class,
    ],
    version = 20,
    exportSchema = false,
)
public abstract class TotumDatabase : RoomDatabase() {

    public abstract fun podcastDao(): PodcastDao

    public abstract fun downloadDao(): DownloadDao

    public abstract fun playbackProgressDao(): PlaybackProgressDao

    public abstract fun localPlaylistDao(): LocalPlaylistDao

    public abstract fun playHistoryDao(): PlayHistoryDao

    public abstract fun queueDao(): QueueDao

    public abstract fun sourceGroupDao(): SourceGroupDao

    public abstract fun cachedFeedDao(): CachedFeedDao

    public abstract fun accountProgressOutboxDao(): AccountProgressOutboxDao

    public companion object {
        public fun build(context: Context): TotumDatabase =
            Room.databaseBuilder(context, TotumDatabase::class.java, "totum.db")
                .apply { MIGRATIONS.forEach { addMigrations(it) } }
                .build()

        /** Every migration, in one list so a test can run the same ones the app does. */
        public val MIGRATIONS: List<Migration>
            get() = listOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
            )

        /**
         * v20: the account-progress outbox. Progress the account has not been told about yet, one row
         * per item, so listening with no network (or with no working sender, which is the state of
         * the world since 2026-08-18) is reported later rather than lost.
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS account_progress_outbox (" +
                        "mediaItemId TEXT NOT NULL PRIMARY KEY, positionMs INTEGER NOT NULL, " +
                        "durationMs INTEGER NOT NULL, finished INTEGER NOT NULL, recordedAtEpochMs INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v18: what the listing said — the view count and the publication date — kept on every
         * table that stores an item.
         *
         * They were surviving the media session and then dying in the database: the shared rebuild
         * set `publishedAt = null` and there was no column for the other two, so the video page
         * showed them for an item tapped from a feed and showed nothing for the same item replayed
         * from the queue. Nothing else can reconstruct them — a resolution knows the stream and
         * nothing about either.
         *
         * Purely additive and nullable, so there is nothing to backfill and nothing to get wrong:
         * rows written before this simply have no view count, which is the truth about them.
         */
        /**
         * v19: the rest of what a listing said — duration, the channel URL, members-only.
         *
         * The same defect as v18 and found the same way, because v18 fixed three fields of one
         * problem and left three more. `duration` was hardcoded null in the shared rebuild, and the
         * other two had no column at all, so every persisted row lost them: the Library's duration
         * sorts became silent no-ops, length chips vanished, and "Go to channel" fell back to a full
         * yt-dlp extraction to read one string — 12.5 seconds on a real phone, for the same video
         * that was instant from a feed row.
         *
         * Purely additive and nullable like v18, so there is nothing to backfill. Rows written before
         * this have no duration, which is the truth about them; `membersOnly` defaults false, which is
         * both the old behaviour and true of almost everything.
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("queue_items", "play_history", "downloads", "local_playlist_items").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN durationMs INTEGER")
                    db.execSQL("ALTER TABLE $table ADD COLUMN sourceUrl TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN membersOnly INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Every table on the PlaylistItemColumns contract. Named individually rather than
                // looped, so adding a fifth table to that contract and forgetting it here is a
                // compile error in the entity rather than a silent gap in the migration.
                listOf("queue_items", "play_history", "downloads", "local_playlist_items").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN viewsText TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN publishedText TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN publishedAtEpochMs INTEGER")
                }
            }
        }

        /**
         * v17: the last-known contents of each video feed, so the app opens with something on
         * screen instead of a blank tab for the second or so the network takes.
         *
         * Purely additive — one new table, nothing existing touched — so there is no backfill
         * to get wrong. An empty cache is exactly the old behaviour, which is what the first
         * launch after upgrading gets.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cached_feed_items (" +
                        "feedKey TEXT NOT NULL, itemId TEXT NOT NULL, position INTEGER NOT NULL, " +
                        "cachedAtEpochMs INTEGER NOT NULL, sourceId TEXT NOT NULL, title TEXT NOT NULL, " +
                        "author TEXT, thumbnailUrl TEXT, mediaUrl TEXT, publishedText TEXT, " +
                        "viewsText TEXT, durationSeconds INTEGER, membersOnly INTEGER NOT NULL, " +
                        "contentKind TEXT NOT NULL, sourceUrl TEXT, " +
                        "PRIMARY KEY(feedKey, itemId))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_feed_items_feedKey " +
                        "ON cached_feed_items(feedKey)",
                )
            }
        }

        /**
         * v16: a group's membership carries the source, not just its id.
         *
         * Resolving an id against the app's subscriptions found nothing for a channel the
         * user had grouped but never subscribed to — which the picker allows — so those
         * members silently contributed nothing to the feed. Existing rows are backfilled as
         * video channels with the id as both title and URL, which is what every row written
         * so far actually is; a wrong title is visible and fixable, a dropped group is not.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE source_group_members ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE source_group_members ADD COLUMN kind TEXT NOT NULL DEFAULT 'VIDEO'")
                db.execSQL("ALTER TABLE source_group_members ADD COLUMN url TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE source_group_members SET title = sourceId, url = sourceId")
            }
        }

        /**
         * v15: named groups of sources, read as one merged feed. Purely additive — two new
         * tables, nothing existing touched — so there is no backfill to get wrong.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS source_groups (" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS source_group_members (" +
                        "groupId TEXT NOT NULL, sourceId TEXT NOT NULL, position INTEGER NOT NULL, " +
                        "PRIMARY KEY(groupId, sourceId), " +
                        "FOREIGN KEY(groupId) REFERENCES source_groups(id) ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_source_group_members_groupId " +
                        "ON source_group_members(groupId)",
                )
            }
        }

        /**
         * v14: a download record carries the item it is for, on the same denormalized
         * columns as the queue, playlists and history. Until now it held an id alone,
         * so an offline list had to join against a pillar's own catalogue — and the
         * Library, joining against podcast episodes, simply never showed a video.
         *
         * Existing rows are backfilled from wherever the same item is already
         * described, so downloads already on disk keep their titles. Anything that
         * matches nowhere keeps its id as the title rather than being dropped:
         * dropping the row would leave the file on disk with nothing left in the UI
         * able to play or delete it.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS downloads_v14 (" +
                        "itemId TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, " +
                        "downloadedBytes INTEGER NOT NULL, totalBytes INTEGER, localPath TEXT, " +
                        "failureReason TEXT, audioOnly INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, author TEXT, thumbnailUrl TEXT, sourceId TEXT NOT NULL, " +
                        "contentKind TEXT NOT NULL, playbackType TEXT NOT NULL, handle TEXT, mediaUrl TEXT)",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO downloads_v14 " +
                        "SELECT d.mediaItemId, d.status, d.downloadedBytes, d.totalBytes, d.localPath, " +
                        "d.failureReason, d.audioOnly, COALESCE(k.title, d.mediaItemId), k.author, " +
                        "k.thumbnailUrl, COALESCE(k.sourceId, ''), COALESCE(k.contentKind, 'STANDARD'), " +
                        "COALESCE(k.playbackType, 'PODCAST'), k.handle, k.mediaUrl " +
                        "FROM downloads d LEFT JOIN ($KNOWN_ITEMS) k ON k.itemId = d.mediaItemId " +
                        "GROUP BY d.mediaItemId",
                )
                db.execSQL("DROP TABLE downloads")
                db.execSQL("ALTER TABLE downloads_v14 RENAME TO downloads")
            }
        }

        /**
         * Every item the database already describes, for the v14 backfill. An item in
         * more than one of these is described identically by each, so which row the
         * GROUP BY picks does not matter.
         */
        private const val KNOWN_ITEMS =
            "SELECT itemId, title, author, thumbnailUrl, sourceId, contentKind, playbackType, handle, mediaUrl " +
                "FROM queue_items UNION " +
                "SELECT itemId, title, author, thumbnailUrl, sourceId, contentKind, playbackType, handle, mediaUrl " +
                "FROM play_history UNION " +
                "SELECT itemId, title, author, thumbnailUrl, sourceId, contentKind, playbackType, handle, mediaUrl " +
                "FROM local_playlist_items UNION " +
                "SELECT id, title, author, thumbnailUrl, feedId, 'STANDARD', 'PODCAST', NULL, mediaUrl " +
                "FROM podcast_episodes"

        /**
         * v13: a finished item keeps its progress row, marked completed, instead of
         * being deleted — which is what makes "played" distinguishable from "never
         * started". Existing rows are all part-way by definition, so the default is null.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN completedAtEpochMs INTEGER")
            }
        }

        /** v12: the queue remembers which entry is playing, so the cursor survives. */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue_items ADD COLUMN isCurrent INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v11: downloads record whether the local file is audio-only. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN audioOnly INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v10: the up-next queue, persisted so it survives a restart. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS queue_items (" +
                        "rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, position INTEGER NOT NULL, " +
                        "groupId TEXT, groupTitle TEXT, itemId TEXT NOT NULL, title TEXT NOT NULL, " +
                        "author TEXT, thumbnailUrl TEXT, sourceId TEXT NOT NULL, contentKind TEXT NOT NULL, " +
                        "playbackType TEXT NOT NULL, handle TEXT, mediaUrl TEXT)",
                )
            }
        }

        /** v9: play history (recently-played, denormalized like playlist items). */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS play_history (" +
                        "itemId TEXT NOT NULL PRIMARY KEY, lastPlayedAtEpochMs INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, author TEXT, thumbnailUrl TEXT, sourceId TEXT NOT NULL, " +
                        "contentKind TEXT NOT NULL, playbackType TEXT NOT NULL, handle TEXT, mediaUrl TEXT)",
                )
            }
        }

        /** v8: local (cross-pillar) playlists + their items. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_playlists (" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_playlist_items (" +
                        "playlistId TEXT NOT NULL, itemId TEXT NOT NULL, position INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, author TEXT, thumbnailUrl TEXT, sourceId TEXT NOT NULL, " +
                        "contentKind TEXT NOT NULL, playbackType TEXT NOT NULL, handle TEXT, mediaUrl TEXT, " +
                        "PRIMARY KEY(playlistId, itemId), " +
                        "FOREIGN KEY(playlistId) REFERENCES local_playlists(id) ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_playlist_items_playlistId " +
                        "ON local_playlist_items(playlistId)",
                )
            }
        }

        /** v2: episodes gained an author column (notification artist line). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcast_episodes ADD COLUMN author TEXT")
            }
        }

        /** v3: downloads table (offline media). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS downloads (" +
                        "mediaItemId TEXT NOT NULL PRIMARY KEY, " +
                        "status TEXT NOT NULL, " +
                        "downloadedBytes INTEGER NOT NULL, " +
                        "totalBytes INTEGER, " +
                        "localPath TEXT, " +
                        "failureReason TEXT)",
                )
            }
        }

        /** v4: sources gained a sourceType ('podcast' | 'channel'); existing rows are podcasts. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcast_feeds ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'podcast'")
            }
        }

        /** v5: playback_progress table (resume position per item). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playback_progress (" +
                        "mediaItemId TEXT NOT NULL PRIMARY KEY, " +
                        "positionMs INTEGER NOT NULL, " +
                        "durationMs INTEGER, " +
                        "updatedAtEpochMs INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v6: sources gained an `origin` ('manual' | 'youtube_import'). Existing
         * rows default to 'manual' — the safe choice, since it means an account
         * sync never prunes anything already here; new imports mark themselves.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcast_feeds ADD COLUMN origin TEXT NOT NULL DEFAULT 'manual'")
            }
        }

        /** v7: episodes gained a `chapters` JSON column (nullable); existing rows have none. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcast_episodes ADD COLUMN chapters TEXT")
            }
        }
    }
}
