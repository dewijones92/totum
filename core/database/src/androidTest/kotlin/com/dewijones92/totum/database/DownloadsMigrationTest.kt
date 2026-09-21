package com.dewijones92.totum.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The v13 → v14 download migration, which has to carry real files across: a download
 * already on disk must keep its title and stay playable and deletable afterwards.
 *
 * The v13 tables are built by hand rather than by a schema export (the database does not
 * export schemas), so only the tables the migration reads are created.
 */
class DownloadsMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun cleanUp() {
        helper?.close()
        context.deleteDatabase(name)
    }

    private fun openAtV13(): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(V13) {
            override fun onCreate(db: SupportSQLiteDatabase) = V13_TABLES.forEach(db::execSQL)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory()
            .create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build())
            .also { helper = it }
            .writableDatabase
    }

    /**
     * Every migration from v13 upward, in order — the upgrade a real v13 install actually takes.
     *
     * It used to run only the v13 → v14 step and then compare the result against a table Room
     * builds fresh at the CURRENT version, which quietly assumed no later migration would ever
     * touch `downloads` again. v18 did (it adds the view count and publication date to every table
     * that stores an item) and the comparison failed, correctly, on a chain that was never run.
     */
    private fun migrate(db: SupportSQLiteDatabase) {
        TotumDatabase.MIGRATIONS
            .filter { it.startVersion >= V13 }
            .sortedBy { it.startVersion }
            .forEach { it.migrate(db) }
    }

    private fun SupportSQLiteDatabase.rows(sql: String): List<List<String?>> =
        query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { cursor.getString(it) })
                }
            }
        }

    /**
     * v22 has to REPAIR podcast rows, not merely widen them.
     *
     * `author` changed meaning — it was `episodeAuthor ?: feedTitle` and is now always the feed
     * title — so a row written before the upgrade holds the NETWORK's name in the column that now
     * means the show. Additive-only would have left every already-queued episode reading
     * "BBC Radio 5 Live" under a microphone, permanently: nothing ever re-syncs a queue row from
     * its feed.
     */
    @Test
    fun aQueuedEpisodeGetsTheShowsNameAndKeepsTheNetworkAsItsPublisher() {
        val db = openAtV13()
        db.execSQL(
            "INSERT INTO podcast_feeds VALUES ('feed-1', 'podcast', 'Football Daily', " +
                "'https://bbc.example/rss', NULL, 0, 'manual')",
        )
        db.execSQL(
            "INSERT INTO queue_items (position, groupId, groupTitle, isCurrent, itemId, title, author, " +
                "thumbnailUrl, sourceId, contentKind, playbackType, handle, mediaUrl) VALUES " +
                "(0, NULL, NULL, 1, 'ep-1', 'PL Review', 'BBC Radio 5 Live', NULL, 'feed-1', 'STANDARD', " +
                "'PODCAST', NULL, 'https://cdn.example/ep1.mp3')",
        )

        migrate(db)

        assertEquals(
            listOf(listOf("Football Daily", "BBC Radio 5 Live")),
            db.rows("SELECT author, publisher FROM queue_items WHERE itemId = 'ep-1'"),
        )
    }

    /** The publisher must not become a second copy of the show's own name. */
    @Test
    fun aQueuedEpisodeWhoseAuthorWasTheShowGetsNoPublisher() {
        val db = openAtV13()
        db.execSQL(
            "INSERT INTO podcast_feeds VALUES ('feed-2', 'podcast', 'The Rest Is Politics', " +
                "'https://trip.example/rss', NULL, 0, 'manual')",
        )
        db.execSQL(
            "INSERT INTO play_history VALUES ('ep-2', 0, 'Ep 214', '  the REST is politics ', NULL, " +
                "'feed-2', 'STANDARD', 'PODCAST', NULL, 'https://cdn.example/ep2.mp3')",
        )

        migrate(db)

        assertEquals(
            listOf(listOf("The Rest Is Politics", null)),
            db.rows("SELECT author, publisher FROM play_history WHERE itemId = 'ep-2'"),
        )
    }

    /**
     * A VIDEO row is not touched. Its `author` is the channel and always was; "repair" there would
     * be corruption, and the repair is scoped by `playbackType` for exactly that reason.
     */
    @Test
    fun aVideoRowIsLeftAloneByTheV22Repair() {
        val db = openAtV13()
        db.execSQL(
            "INSERT INTO podcast_feeds VALUES ('chan', 'channel', 'Novara Media Feed', " +
                "'https://youtube.example/c', NULL, 0, 'manual')",
        )
        db.execSQL(
            "INSERT INTO queue_items (position, groupId, groupTitle, isCurrent, itemId, title, author, " +
                "thumbnailUrl, sourceId, contentKind, playbackType, handle, mediaUrl) VALUES " +
                "(0, NULL, NULL, 1, 'vid-9', 'A video', 'Novara Media', NULL, 'chan', 'STANDARD', " +
                "'VIDEO', 'https://www.youtube.com/watch?v=zzz', NULL)",
        )

        migrate(db)

        assertEquals(
            listOf(listOf("Novara Media", null)),
            db.rows("SELECT author, publisher FROM queue_items WHERE itemId = 'vid-9'"),
        )
    }

    /**
     * An unsubscribed feed leaves nothing to correct from, so the row keeps exactly what it had.
     * Better a name under the wrong label than a NULL where a name used to be.
     */
    @Test
    fun anEpisodeWhoseFeedIsGoneKeepsWhateverNameItHad() {
        val db = openAtV13()
        db.execSQL(
            "INSERT INTO queue_items (position, groupId, groupTitle, isCurrent, itemId, title, author, " +
                "thumbnailUrl, sourceId, contentKind, playbackType, handle, mediaUrl) VALUES " +
                "(0, NULL, NULL, 1, 'ep-3', 'Orphan', 'Some Network', NULL, 'gone', 'STANDARD', " +
                "'PODCAST', NULL, 'https://cdn.example/ep3.mp3')",
        )

        migrate(db)

        assertEquals(
            listOf(listOf("Some Network", null)),
            db.rows("SELECT author, publisher FROM queue_items WHERE itemId = 'ep-3'"),
        )
    }

    /**
     * A CHANNEL's rows are untouched, even when they look like a podcast.
     *
     * `podcast_feeds` and `podcast_episodes` hold both pillars — one store class serves channels
     * from the same tables, keyed by `sourceType` — so a repair scoped only by `playbackType` (and,
     * for the episodes table, by nothing at all) rewrote a channel's rows and gave a VIDEO a
     * publisher, which the rest of this change says cannot happen. The earlier
     * `aVideoRowIsLeftAloneByTheV22Repair` passes on `playbackType` alone and never exercised this.
     */
    @Test
    fun aChannelsRowsAreNotRepairedEvenWhenTheyLookLikeAPodcast() {
        val db = openAtV13()
        db.execSQL(
            "INSERT INTO podcast_feeds VALUES ('chan-1', 'channel', 'Novara Media', " +
                "'https://youtube.example/@novara', NULL, 0, 'manual')",
        )
        // A queue row whose handle says PODCAST but whose source is a channel.
        db.execSQL(
            "INSERT INTO queue_items (position, groupId, groupTitle, isCurrent, itemId, title, author, " +
                "thumbnailUrl, sourceId, contentKind, playbackType, handle, mediaUrl) VALUES " +
                "(0, NULL, NULL, 1, 'odd-1', 'An upload', 'Some Uploader', NULL, 'chan-1', 'STANDARD', " +
                "'PODCAST', NULL, 'https://cdn.example/odd.mp3')",
        )
        // And an episodes row belonging to that channel, which had no playbackType to be scoped by.
        db.execSQL(
            "INSERT INTO podcast_episodes VALUES ('vid-1', 'chan-1', 'An upload', 'Some Uploader', " +
                "NULL, NULL, NULL, NULL, NULL, NULL)",
        )

        migrate(db)

        assertEquals(
            listOf(listOf("Some Uploader", null)),
            db.rows("SELECT author, publisher FROM queue_items WHERE itemId = 'odd-1'"),
        )
        assertEquals(
            listOf(listOf("Some Uploader", null)),
            db.rows("SELECT author, publisher FROM podcast_episodes WHERE id = 'vid-1'"),
        )
    }

    @Test
    fun aDownloadedVideoKeepsItsTitleFromTheQueue() {
        val db = openAtV13()
        db.execSQL(
            "INSERT INTO downloads VALUES ('vid-1', 'downloaded', 0, NULL, '/data/vid.media', NULL, 1)",
        )
        db.execSQL(
            "INSERT INTO queue_items (position, groupId, groupTitle, isCurrent, itemId, title, author, " +
                "thumbnailUrl, sourceId, contentKind, playbackType, handle, mediaUrl) VALUES " +
                "(0, NULL, NULL, 1, 'vid-1', 'Backpacking Ben', 'Ben', NULL, 'chan', 'STANDARD', " +
                "'VIDEO', 'https://www.youtube.com/watch?v=abc', NULL)",
        )

        migrate(db)

        val watch = "https://www.youtube.com/watch?v=abc"
        assertEquals(
            listOf(listOf("vid-1", "Backpacking Ben", "VIDEO", watch, "/data/vid.media")),
            db.rows("SELECT itemId, title, playbackType, handle, localPath FROM downloads"),
        )
    }

    @Test
    fun aDownloadedEpisodeKeepsItsTitleFromTheFeed() {
        val db = openAtV13()
        db.execSQL("INSERT INTO downloads VALUES ('ep-1', 'downloaded', 0, NULL, '/data/ep.media', NULL, 0)")
        db.execSQL(
            "INSERT INTO podcast_episodes (id, feedId, title, author, publishedAtEpochMs, durationSeconds, " +
                "description, thumbnailUrl, mediaUrl, chapters) VALUES " +
                "('ep-1', 'feed-1', 'Episode one', 'A host', NULL, NULL, NULL, NULL, 'https://x/ep.mp3', NULL)",
        )

        migrate(db)

        assertEquals(
            listOf(listOf("ep-1", "Episode one", "A host", "feed-1", "PODCAST")),
            db.rows("SELECT itemId, title, author, sourceId, playbackType FROM downloads"),
        )
    }

    /**
     * A file whose item is described nowhere still gets a row. Dropping it would strand
     * the bytes on disk with nothing in the UI able to play or delete them.
     */
    @Test
    fun anUnknownDownloadSurvivesUnderItsOwnId() {
        val db = openAtV13()
        db.execSQL("INSERT INTO downloads VALUES ('orphan', 'downloaded', 0, NULL, '/data/o.media', NULL, 0)")

        migrate(db)

        assertEquals(
            listOf(listOf("orphan", "orphan", "/data/o.media")),
            db.rows("SELECT itemId, title, localPath FROM downloads"),
        )
    }

    /**
     * The migrated table must hold exactly the columns Room builds for a fresh install — the same
     * names, types and nullability, **in any order**.
     *
     * Order is excluded because Room's own runtime check excludes it: `TableInfo.equals` compares a
     * map keyed by column name, so a column appended by `ALTER TABLE` where a fresh install would
     * have it in the middle opens perfectly. This asserted the ordered PRAGMA rows *including*
     * `cid`, which is the position — so it failed on v22 purely because `publisher` lands at index
     * 21 after an ALTER and at index 9 in a fresh table, while the schemas are in fact identical.
     * Its own comment already said "ignoring column order noise"; including `cid` is what stopped it
     * doing that.
     *
     * Found by the adversarial review of this change, not by the gate: `./gradlew detekt lint test
     * koverVerify` does not run instrumented tests, so v22 shipped its commit with this red.
     */
    @Test
    fun theMigratedTableMatchesAFreshOne() {
        val db = openAtV13()
        db.execSQL("INSERT INTO downloads VALUES ('ep-1', 'downloaded', 0, NULL, '/data/ep.media', NULL, 0)")
        migrate(db)
        val migrated = db.columnsOf("downloads")

        val fresh = Room.inMemoryDatabaseBuilder(context, TotumDatabase::class.java).build()
        val expected = try {
            fresh.openHelper.readableDatabase.columnsOf("downloads")
        } finally {
            fresh.close()
        }

        assertEquals(expected, migrated)
    }

    /** name, type, notnull — the three things Room validates, keyed by name so position cannot lie. */
    private fun SupportSQLiteDatabase.columnsOf(table: String): Map<String?, List<String?>> =
        rows("PRAGMA table_info($table)").associate { column ->
            column[1] to column.subList(2, 2 + COLUMN_FIELDS)
        }

    private companion object {
        const val V13 = 13

        /**
         * PRAGMA fields after the name that are compared: **type and notnull**. Not `dflt_value`.
         *
         * A review flagged that the comment named three fields while the slice took two, and the
         * comment was the wrong half — including the default fails on a difference Room does not
         * enforce. `ALTER TABLE … ADD COLUMN … NOT NULL` *must* carry a default, while Room's
         * generated `CREATE TABLE` does not, so a migrated `membersOnly` reads `[INTEGER, 1, 0]`
         * against a fresh `[INTEGER, 1, null]` — measured, on this emulator, when the slice was
         * widened. Room compares a default only where the ENTITY declares one, and none of ours
         * does, which is why the app opens against exactly this schema.
         *
         * Room also validates primary keys, foreign keys and indices; this test does not claim to.
         */
        const val COLUMN_FIELDS = 2

        val V13_TABLES = listOf(
            "CREATE TABLE downloads (mediaItemId TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, " +
                "downloadedBytes INTEGER NOT NULL, totalBytes INTEGER, localPath TEXT, failureReason TEXT, " +
                "audioOnly INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE queue_items (rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "position INTEGER NOT NULL, groupId TEXT, groupTitle TEXT, isCurrent INTEGER NOT NULL DEFAULT 0, " +
                "itemId TEXT NOT NULL, title TEXT NOT NULL, author TEXT, thumbnailUrl TEXT, " +
                "sourceId TEXT NOT NULL, contentKind TEXT NOT NULL, playbackType TEXT NOT NULL, handle TEXT, " +
                "mediaUrl TEXT)",
            "CREATE TABLE play_history (itemId TEXT NOT NULL PRIMARY KEY, lastPlayedAtEpochMs INTEGER NOT NULL, " +
                "title TEXT NOT NULL, author TEXT, thumbnailUrl TEXT, sourceId TEXT NOT NULL, " +
                "contentKind TEXT NOT NULL, playbackType TEXT NOT NULL, handle TEXT, mediaUrl TEXT)",
            "CREATE TABLE local_playlist_items (playlistId TEXT NOT NULL, itemId TEXT NOT NULL, " +
                "position INTEGER NOT NULL, title TEXT NOT NULL, author TEXT, thumbnailUrl TEXT, " +
                "sourceId TEXT NOT NULL, contentKind TEXT NOT NULL, playbackType TEXT NOT NULL, handle TEXT, " +
                "mediaUrl TEXT, PRIMARY KEY(playlistId, itemId))",
            "CREATE TABLE podcast_episodes (id TEXT NOT NULL PRIMARY KEY, feedId TEXT NOT NULL, " +
                "title TEXT NOT NULL, author TEXT, publishedAtEpochMs INTEGER, durationSeconds INTEGER, " +
                "description TEXT, thumbnailUrl TEXT, mediaUrl TEXT, chapters TEXT)",
            // Present in a real install since v1, and the v22 repair joins it. Missing here, the
            // chain threw on a table every actual device has.
            "CREATE TABLE podcast_feeds (id TEXT NOT NULL PRIMARY KEY, sourceType TEXT NOT NULL " +
                "DEFAULT 'podcast', title TEXT NOT NULL, feedUrl TEXT NOT NULL, websiteUrl TEXT, " +
                "subscribedAtEpochMs INTEGER NOT NULL, origin TEXT NOT NULL DEFAULT 'manual')",
            // No `publisher` here on purpose: v22 adds it, and a v13 install does not have it.
        )
    }
}
