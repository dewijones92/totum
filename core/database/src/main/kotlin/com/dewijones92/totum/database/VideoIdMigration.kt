package com.dewijones92.totum.database

import androidx.sqlite.db.SupportSQLiteDatabase

internal object VideoIdMigration {

    private const val PREFIX = "https://www.youtube.com/watch?v="
    private const val BARE_FROM = PREFIX.length + 1
    private const val LENGTH = PREFIX.length + 11

    private fun watch(column: String) = "($column LIKE '$PREFIX%' AND length($column) = $LENGTH)"

    private fun bare(column: String) = "substr($column, $BARE_FROM)"

    fun run(db: SupportSQLiteDatabase) {
        merge(db, Keyed("play_history", "itemId", keeps = "b.lastPlayedAtEpochMs >= {t}.lastPlayedAtEpochMs"))
        merge(db, Keyed("downloads", "itemId", keeps = "(b.status = 'downloaded' OR {t}.status != 'downloaded')"))
        merge(db, Keyed("playback_progress", "mediaItemId", keeps = "b.updatedAtEpochMs >= {t}.updatedAtEpochMs"))
        merge(
            db,
            Keyed("account_progress_outbox", "mediaItemId", keeps = "b.recordedAtEpochMs >= {t}.recordedAtEpochMs")
        )
        merge(
            db,
            Keyed(
                "account_progress_reconciled",
                "mediaItemId",
                keeps = "b.reconciledAtEpochMs >= {t}.reconciledAtEpochMs"
            ),
        )
        merge(db, Keyed("local_playlist_items", "itemId", keeps = "1", within = "playlistId"))
        db.execSQL("UPDATE queue_items SET itemId = ${bare("itemId")} WHERE ${watch("itemId")}")
    }

    private class Keyed(val table: String, val key: String, val keeps: String, val within: String? = null)

    private fun merge(db: SupportSQLiteDatabase, keyed: Keyed) = with(keyed) {
        fun same(alias: String) = within?.let { " AND $alias.$it = $table.$it" }.orEmpty()
        db.execSQL(
            "DELETE FROM $table WHERE ${watch("$table.$key")} AND EXISTS (SELECT 1 FROM $table b " +
                "WHERE b.$key = ${bare("$table.$key")}${same("b")} AND ${keeps.replace("{t}", table)})",
        )
        db.execSQL(
            "DELETE FROM $table WHERE EXISTS (SELECT 1 FROM $table u " +
                "WHERE ${watch("u.$key")} AND ${bare("u.$key")} = $table.$key${same("u")})",
        )
        db.execSQL("UPDATE $table SET $key = ${bare(key)} WHERE ${watch(key)}")
    }
}
