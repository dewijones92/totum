package com.dewijones92.totum.music

import com.dewijones92.totum.innertube.music.MusicSong

fun interleaveRadios(batches: List<List<MusicSong>>): List<MusicSong> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<MusicSong>()
    val longest = batches.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) {
        batches.forEach { batch -> batch.getOrNull(i)?.takeIf { seen.add(it.videoId) }?.let(out::add) }
    }
    return out
}

fun mixTitle(titles: List<String>): String = when (titles.size) {
    0 -> "Mix"
    1 -> titles[0]
    2 -> "${titles[0]} & ${titles[1]}"
    else -> "${titles[0]}, ${titles[1]} + ${titles.size - 2} more"
}
