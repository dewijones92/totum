package com.dewijones92.totum.data.morning

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ai.AiTasks
import com.dewijones92.totum.data.ai.HttpAiJobs
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.domain.PlayableItem

public data class MorningResult(
    val picks: Int?,
    val picksFailed: String?,
    val digestLines: List<String>?,
    val news: Int,
    val digestFromAi: Boolean,
) {
    val worthTelling: Boolean get() = (picks ?: 0) > 0 || !digestLines.isNullOrEmpty()
}

@Suppress("LongParameterList")
public class MorningRun(
    private val jobs: () -> HttpAiJobs?,
    private val store: MorningStore,
    private val taste: suspend () -> Taste,
    private val have: suspend () -> List<String>,
    private val find: suspend (Suggestion, Taste) -> PlayableItem?,
    private val replaceForYou: suspend (List<PlayableItem>) -> Unit,
    private val titleOf: (InboxEntry) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    public suspend fun run(): MorningResult {
        val state = store.state.value
        Diag.log(TAG, "morning job: for you ${onOff(state.forYou)}, digest ${onOff(state.digest)}")
        val (picks, picksFailed) = if (state.forYou) forYou() else null to null
        val (lines, news, fromAi) = if (state.digest) digest() else Triple(null, 0, false)
        return MorningResult(picks, picksFailed, lines, news, fromAi).also {
            Diag.log(TAG, "morning job done: $it")
        }
    }

    private suspend fun forYou(): Pair<Int?, String?> {
        val client = jobs() ?: return (null to "❌ No home server").also { Diag.warn(TAG, "for you: no home server") }
        val tasteNow = taste()
        val request = ForYouRequest(tasteNow, have().take(MAX_HAVE), store.pickedLately(clock()))
        Diag.log(
            TAG,
            "for you: asking for ${request.videos} videos and ${request.podcasts} shows; ${describe(request)}",
        )
        val outcome = client.run(
            AiTasks.FOR_YOU,
            request,
            ForYouRequest.serializer(),
            ForYouAnswer.serializer(),
        )
        val answer = (outcome as? AskOutcome.Answered)?.value
            ?: return null to (outcome as AskOutcome.Failed).reason.also { Diag.warn(TAG, "for you failed: $it") }
        val tidy = MorningRules.tidyForYou(request, answer)
        val items = mutableListOf<PlayableItem>()
        val found = mutableListOf<String>()
        for (pick in tidy.picks) {
            val item = find(pick, tasteNow)
            if (item == null) {
                Diag.log(TAG, "for you: \"${pick.title}\" (${pick.kind}) not found by the app's search; left out")
            } else if (items.none { it.item.id == item.item.id }) {
                items += item
                found += pick.title
                Diag.log(TAG, "for you: \"${pick.title}\" -> ${item.item.id.value} \"${item.item.title}\"")
            }
        }
        if (items.isNotEmpty()) {
            replaceForYou(items)
            store.picked(found, clock())
        }
        return items.size to null
    }

    private fun describe(request: ForYouRequest): String =
        "taste: ${request.taste.channels.size} channels, ${request.taste.shows.size} shows, " +
            "${request.taste.starred.size} starred, ${request.taste.liked.size} liked, ${request.have.size} have, " +
            "${request.earlier.size} picked lately"

    private suspend fun digest(): Triple<List<String>?, Int, Boolean> {
        val state = store.state.value
        val now = clock()
        val entries = state.inbox.filter { it.at > state.lastDigestAt }
        if (entries.isEmpty()) {
            Diag.log(TAG, "digest: nothing new since ${state.lastDigestAt}; no call made")
            store.consumed(emptyList(), now)
            return Triple(emptyList(), 0, false)
        }
        val byGroup = entries.groupBy { it.group }.mapValues { (_, held) -> held.sortedByDescending { it.at } }
        val sent = byGroup.values.flatMap { it.take(MAX_TITLES) }
        val unsent = byGroup.values.flatMap { it.drop(MAX_TITLES) }
        val request = MorningDigestRequest(
            byGroup.map { (group, held) -> GroupHeadlines(group, held.take(MAX_TITLES).map(titleOf)) }
                .sortedByDescending { it.titles.size },
        )
        val client = jobs()
        val outcome = client?.run(
            AiTasks.MORNING_DIGEST,
            request,
            MorningDigestRequest.serializer(),
            MorningDigestAnswer.serializer(),
        )
        val said = (outcome as? AskOutcome.Answered)?.value?.let { MorningRules.tidyDigest(request, it) }
        val ai = said?.lines.orEmpty().isNotEmpty()
        val lines = if (ai) said!!.lines else MorningRules.fallbackDigest(request).lines
        Diag.log(
            TAG,
            "digest: ${sent.size} new in ${request.groups.size} group(s) told, ${unsent.size} left for the next " +
                "digest; " + (if (ai) "AI lines" else "plain lines because ${because(outcome)}"),
        )
        store.consumed(sent, if (unsent.isEmpty()) now else unsent.minOf { it.at } - 1)
        return Triple(lines, sent.size, ai)
    }

    private fun because(outcome: AskOutcome<*>?): String = when (outcome) {
        null -> "there is no home server"
        is AskOutcome.Failed -> outcome.reason
        is AskOutcome.Answered -> "the AI gave no usable line"
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private companion object {
        const val TAG = "morning"
        const val MAX_HAVE = 200
        const val MAX_TITLES = 40
    }
}
