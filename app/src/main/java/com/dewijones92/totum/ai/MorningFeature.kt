package com.dewijones92.totum.ai

import android.content.Context
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.squashed
import com.dewijones92.totum.data.morning.MorningResult
import com.dewijones92.totum.data.morning.MorningRun
import com.dewijones92.totum.data.morning.replaceContents
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.notifications.HousekeepingNotifier
import com.dewijones92.totum.ui.common.toMediaItem

val FOR_YOU_PLAYLIST: PlaylistId = PlaylistId("for-you")
private val FOR_YOU_SOURCE = SourceId("for-you")
private const val TAG = "morning"

class MorningJob(
    private val context: Context,
    private val run: MorningRun,
    private val notifier: HousekeepingNotifier,
) {

    suspend fun runAndTell(why: String): MorningResult {
        Diag.log(TAG, "morning job asked to run ($why)")
        val result = run.run()
        val lines = notificationLines(result)
        if (lines.isEmpty()) {
            Diag.log(TAG, "nothing to tell the person this morning [$result]")
        } else {
            notifier.tell(
                HousekeepingNotifier.MORNING_ID,
                context.getString(R.string.morning_notification_title),
                lines.joinToString("\n"),
            )
        }
        return result
    }

    fun detail(result: MorningResult): String = listOfNotNull(
        result.picks?.let { "$it picks" },
        result.picksFailed,
        result.digestLines?.let { "${result.news} new" },
    ).joinToString(" · ").ifEmpty { "nothing switched on" }

    private fun notificationLines(result: MorningResult): List<String> {
        val news = result.digestLines.orEmpty().map { "🆕 ${it.removePrefix("🆕 ")}" }
        val picks = result.picks?.takeIf { it > 0 }
            ?.let { context.resources.getQuantityString(R.plurals.morning_picks_line, it, it) }
        val failed = result.picksFailed?.let {
            context.getString(
                R.string.morning_picks_failed_line,
                it.removePrefix("❌ ")
            )
        }
        return news + listOfNotNull(picks, failed)
    }
}

internal suspend fun findPick(c: AppContainer, pick: Suggestion, taste: Taste): PlayableItem? = when (pick.kind) {
    AskKind.YOUTUBE -> findVideo(c, pick, taste)
    AskKind.PODCAST -> findLatestEpisode(c, pick, taste)
    else -> null
}

private suspend fun findVideo(c: AppContainer, pick: Suggestion, taste: Taste): PlayableItem? {
    val followed = taste.channels.map { it.squashed() }
    val hit = c.askCopies.find(pick).filterIsInstance<SearchHit.Video>().firstOrNull { video ->
        val channel = video.subtitle.orEmpty().squashed()
        val followedAlready = channel.isNotEmpty() && followed.any { it == channel }
        val ok = video.kind == MediaContentKind.STANDARD && !video.membersOnly && !followedAlready
        if (!ok) {
            Diag.log(
                TAG,
                "for you: skipped \"${video.title}\" by \"${video.subtitle}\" " +
                    "(followed=$followedAlready, kind=${video.kind}, members=${video.membersOnly})",
            )
        }
        ok
    }
    return hit?.toMediaItem(FOR_YOU_SOURCE)?.asPlayable()
}

private suspend fun findLatestEpisode(c: AppContainer, pick: Suggestion, taste: Taste): PlayableItem? {
    val followed = taste.shows.map { it.squashed() }
    val hits = c.askCopies.find(pick).filterIsInstance<SearchHit.Podcast>()
    val show = hits.firstOrNull { it.title.squashed() !in followed } ?: return null
    val preview = c.podcastRepository.preview(show.feedUrl) as? PreviewResult.Loaded ?: return null
    return preview.episodes.maxByOrNull { it.publishedAt ?: java.time.Instant.EPOCH }?.asPlayable()
}

internal suspend fun replaceForYou(c: AppContainer, name: String, items: List<PlayableItem>) {
    c.localPlaylistStore.replaceContents(FOR_YOU_PLAYLIST, name, items)
    Diag.log(TAG, "the \"$name\" playlist now holds ${items.size} item(s), new ones added before the old were removed")
}
