package com.dewijones92.totum.playback

import android.os.Bundle
import androidx.core.os.bundleOf

public enum class PlayingLive { NO, LIVE, WAS_LIVE }

public data class PlaybackContext(
    val repeatOne: Boolean = false,
    val nextTitle: String? = null,
    val explorePicksNext: Boolean = false,
    val nextAfter: String? = null,
    val live: PlayingLive = PlayingLive.NO,
) {
    internal fun toBundle(): Bundle = bundleOf(
        EXTRA_CONTEXT_REPEAT_ONE to repeatOne,
        EXTRA_CONTEXT_NEXT_TITLE to nextTitle,
        EXTRA_CONTEXT_EXPLORE_NEXT to explorePicksNext,
        EXTRA_CONTEXT_NEXT_AFTER to nextAfter,
        EXTRA_CONTEXT_LIVE to live.name,
    )

    internal companion object {
        fun from(args: Bundle): PlaybackContext =
            PlaybackContext(
                repeatOne = args.getBoolean(EXTRA_CONTEXT_REPEAT_ONE, false),
                nextTitle = args.getString(EXTRA_CONTEXT_NEXT_TITLE),
                explorePicksNext = args.getBoolean(EXTRA_CONTEXT_EXPLORE_NEXT, false),
                nextAfter = args.getString(EXTRA_CONTEXT_NEXT_AFTER),
                live = args.getString(EXTRA_CONTEXT_LIVE)?.let { runCatching { PlayingLive.valueOf(it) }.getOrNull() }
                    ?: PlayingLive.NO,
            )
    }
}

internal const val ACTION_PLAYBACK_CONTEXT: String = "com.dewijones92.totum.PLAYBACK_CONTEXT"
internal const val EXTRA_CONTEXT_REPEAT_ONE: String = "context_repeat_one"
internal const val EXTRA_CONTEXT_NEXT_TITLE: String = "context_next_title"
internal const val EXTRA_CONTEXT_EXPLORE_NEXT: String = "context_explore_next"
internal const val EXTRA_CONTEXT_NEXT_AFTER: String = "context_next_after"
internal const val EXTRA_CONTEXT_LIVE: String = "context_live"
