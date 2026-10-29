package com.dewijones92.totum.di

import com.dewijones92.totum.data.group.GroupFeed
import com.dewijones92.totum.data.group.SourceGroupStore

/**
 * Groups grouped: the store and the feed read over it, as one collaborator — the same
 * shape as [YouTubeAccountServices] and for the same reason. A screen that offers groups
 * needs at least the store and usually the feed too, and they are meaningless apart.
 */
class GroupServices(
    val store: SourceGroupStore,
    val feed: GroupFeed,
    val news: com.dewijones92.totum.groups.GroupNews? = null,
)
