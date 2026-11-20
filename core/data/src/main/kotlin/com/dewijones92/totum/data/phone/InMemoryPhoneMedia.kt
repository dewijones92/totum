package com.dewijones92.totum.data.phone

import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.domain.PhoneMediaKind

public fun PhoneMediaQuery.applyTo(entries: List<PhoneMediaEntry>): PhoneMediaPage {
    val words = search.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matching = entries
        .filter { it.kind == kind }
        .filter { folder == null || it.folder == folder }
        .filter { entry ->
            words.all { word ->
                listOfNotNull(entry.title, entry.path.substringAfterLast('/'), entry.artist, entry.album)
                    .any { it.contains(word, ignoreCase = true) }
            }
        }
    val newest = compareByDescending<PhoneMediaEntry> { it.takenOrAdded?.toEpochMilli() ?: 0L }
        .thenByDescending { it.key }
    val sorted = when (sort) {
        PhoneSort.NEWEST -> matching.sortedWith(newest)
        PhoneSort.OLDEST -> matching.sortedWith(newest.reversed())
        PhoneSort.TITLE -> matching.sortedBy { it.title.lowercase() }
        PhoneSort.LONGEST -> matching.sortedByDescending { it.durationMs ?: 0L }
        PhoneSort.LARGEST -> matching.sortedByDescending { it.sizeBytes ?: 0L }
    }
    val rows = sorted.drop(offset).take(limit)
    return PhoneMediaPage(rows, nextOffset(rows.size))
}

public class InMemoryPhoneMedia(entries: List<PhoneMediaEntry> = emptyList()) : PhoneMediaSource {
    private var held: List<PhoneMediaEntry> = entries

    public fun set(entries: List<PhoneMediaEntry>) {
        held = entries
    }

    override suspend fun page(query: PhoneMediaQuery): PhoneMediaPage = query.applyTo(held)

    override suspend fun folders(kind: PhoneMediaKind): List<PhoneFolder> =
        held.filter { it.kind == kind }.mapNotNull { it.folder }.groupingBy { it }.eachCount()
            .map { PhoneFolder(it.key, it.value) }.sortedByDescending { it.count }
}
