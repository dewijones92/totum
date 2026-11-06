package com.dewijones92.totum.data.history

import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.flow.Flow

/**
 * Records recently-played items across both pillars, most-recent first. Reuses
 * [PlayableItem] (a [com.dewijones92.totum.domain.MediaItem] + a stable play
 * handle) so a video keeps its watch URL and a podcast its enclosure — history
 * replays survive stream-URL expiry, and it plays back through the same seam.
 */
public interface PlayHistoryStore {

    /** Recently-played items, most-recent first (capped). */
    public fun observe(): Flow<List<PlayableItem>>

    /** Records [item] as just-played (moving it to the front if already present). */
    public suspend fun record(item: PlayableItem)

    public suspend fun clear(): List<PlayableItem>

    public suspend fun restore(items: List<PlayableItem>)
}
