package com.dewijones92.totum.domain

/**
 * Threads Shorts through a feed of videos, so they are listed in the same place as everything
 * else rather than behind their own button.
 *
 * Dewi, 2026-08-16: *"I want YouTube Shorts, YouTube live streams, YouTube videos to be all
 * treated the same … always displayed everywhere but just tagged."* Live streams already arrive in
 * the feed and already carry their badge; Shorts do not arrive at all, because YouTube's TV
 * subscriptions response contains none — verified against his own account, 45 tiles and no Shorts
 * renderer of any kind, which is SmartTube's open bug #4278 rather than anything of ours. They
 * have to be fetched per channel and threaded in here.
 *
 * **Interleaved rather than sorted, because a Short carries no date.** YouTube's Shorts tiles have
 * a title, a thumbnail and a view count and nothing else — there is no published timestamp to sort
 * by, so a chronological merge is not available however much one would prefer it. Spacing them
 * evenly is the honest alternative: it keeps the videos in the order the feed gave them, and puts
 * Shorts throughout rather than in a clump at one end, which is the only other option.
 *
 * Pure and total, so the spacing is unit-testable without a feed, a network or a screen.
 */
public fun interleaveShorts(
    videos: List<MediaItem>,
    shorts: List<MediaItem>,
    everyNth: Int = SHORTS_EVERY_NTH,
): List<MediaItem> {
    if (shorts.isEmpty()) return videos
    // Already-present ids win: a Short that the feed itself supplied (a shelf, or a Short that
    // came through as an ordinary tile) keeps its place rather than appearing twice.
    val known = videos.mapTo(HashSet()) { it.id }
    val fresh = shorts.filter { known.add(it.id) }
    if (fresh.isEmpty()) return videos
    if (videos.isEmpty()) return fresh

    val spacing = everyNth.coerceAtLeast(1)
    val queued = ArrayDeque(fresh)
    return buildList {
        videos.forEachIndexed { index, video ->
            add(video)
            // After every [spacing] videos, not before the first: a feed that opens on a Short
            // reads as a Shorts app, and the top of the list is where the newest video belongs.
            if ((index + 1) % spacing == 0) queued.removeFirstOrNull()?.let(::add)
        }
        // Whatever did not fit still gets shown. Dropping them would make "how many Shorts you
        // see" depend on how long the video feed happened to be, which is not a rule anyone could
        // predict from the screen.
        addAll(queued)
    }
}

/**
 * One Short per five videos. Enough that they are genuinely present while scrolling, few enough
 * that the feed still reads as a feed of videos — which is what it mostly is.
 */
private const val SHORTS_EVERY_NTH = 5
