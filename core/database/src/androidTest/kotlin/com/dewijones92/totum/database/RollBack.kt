package com.dewijones92.totum.database

import androidx.sqlite.db.SupportSQLiteDatabase

private val ITEM_TABLES = listOf("queue_items", "play_history", "downloads", "local_playlist_items")

private val COLUMNS_ADDED_AT: Map<Int, List<Pair<String, String>>> = mapOf(
    26 to ITEM_TABLES.flatMap { listOf(it to "albumId", it to "albumTitle") },
    27 to listOf("downloads" to "downloadedAtEpochMs", "downloads" to "openedAtEpochMs"),
    28 to listOf("queue_items" to "addedAtEpochMs"),
)

internal fun SupportSQLiteDatabase.rollBackTo(version: Int) {
    COLUMNS_ADDED_AT.filterKeys { it > version }.values.flatten().forEach { (table, column) ->
        execSQL("ALTER TABLE $table DROP COLUMN $column")
    }
    execSQL("PRAGMA user_version = $version")
}
