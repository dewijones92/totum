package com.dewijones92.totum.innertube.subscriptions

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.BrowseTarget
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed

/**
 * Fetches subscribed channels by browsing the account's `FEchannels` feed
 * with a live token from [YouTubeAccount] (refreshed transparently), then
 * parsing via [SubscriptionsResponseParser].
 *
 * **Every page, not just the first.** `FEchannels` is a TV grid, and a grid arrives one
 * screenful at a time with a continuation token for the rest. Reading only the first
 * response meant the app's idea of "my subscriptions" was whatever fitted on page one, so a
 * channel further down was invisible to it — and the channel page, finding it absent, offered
 * to Subscribe to something already subscribed (Novara Media, reported 2026-07-30).
 */
public class HttpYouTubeSubscriptions(
    private val account: YouTubeAccount,
    private val innerTube: InnerTubeClient,
) : YouTubeSubscriptions {

    override suspend fun list(): SubscriptionsResult =
        when (val result = account.accessToken()) {
            is AccessTokenResult.Available -> collectPages(result.token)
            AccessTokenResult.SignedOut -> SubscriptionsResult.SignedOut
            is AccessTokenResult.Failure -> SubscriptionsResult.Failure(result.detail)
        }

    private suspend fun collectPages(token: AccessToken): SubscriptionsResult {
        val channels = LinkedHashMap<String, SubscribedChannel>()
        var target: BrowseTarget? = BrowseTarget.Id(SUBSCRIPTIONS_BROWSE_ID)
        var pagesRead = 0

        while (target != null && pagesRead < MAX_PAGES) {
            val page = when (val outcome = fetchPage(target, token)) {
                is PageOutcome.Ready -> outcome.page
                PageOutcome.SignedOut -> return SubscriptionsResult.SignedOut
                is PageOutcome.Broken -> return channels.orFailure(outcome.detail, pagesRead)
            }
            val before = channels.size
            page.channels.forEach { channels.putIfAbsent(it.channelId, it) }
            pagesRead++
            Diag.log(
                "subs",
                "page $pagesRead gave ${page.channels.size} (${channels.size - before} new), " +
                    "${channels.size} total, " +
                    if (page.next == null) "no more pages" else "more to come",
            )
            // A token that returns only what we already have is a token pointing at itself.
            // Following it would spend forty requests to learn nothing, on every launch.
            target = page.next?.value
                ?.takeIf { pagesRead == 1 || channels.size > before }
                ?.let(BrowseTarget::Continuation)
        }
        if (pagesRead == MAX_PAGES) {
            Diag.warn("subs", "stopped at $MAX_PAGES pages holding ${channels.size} channels")
        }
        return SubscriptionsResult.Success(channels.values.toList())
    }

    private suspend fun fetchPage(target: BrowseTarget, token: AccessToken): PageOutcome =
        when (val browsed = innerTube.browse(target, token)) {
            is InnerTubeResponse.Success ->
                browsed.parsed { SubscriptionsResponseParser.parsePage(it) }
                    ?.let(PageOutcome::Ready)
                    ?: PageOutcome.Broken("unparseable page")
            InnerTubeResponse.Unauthorized -> PageOutcome.SignedOut
            is InnerTubeResponse.Failure -> PageOutcome.Broken(browsed.detail)
        }

    private sealed interface PageOutcome {
        data class Ready(val page: SubscriptionsResponseParser.Page) : PageOutcome
        data object SignedOut : PageOutcome
        data class Broken(val detail: String) : PageOutcome
    }

    /**
     * A page that fails is no reason to throw away the pages that worked: a short list is wrong
     * in the same direction as no list at all, but far less so — and on a
     * [SubscriptionsResult.Failure] the caller keeps whatever it already had, which is staler.
     */
    private fun Map<String, SubscribedChannel>.orFailure(detail: String, pagesRead: Int) =
        if (isEmpty()) {
            SubscriptionsResult.Failure(detail)
        } else {
            Diag.warn("subs", "keeping $size channels from $pagesRead page(s); next page failed: $detail")
            SubscriptionsResult.Success(values.toList())
        }

    private companion object {
        const val SUBSCRIPTIONS_BROWSE_ID = "FEchannels"

        /**
         * A backstop against a continuation that points at itself, not a real limit: at roughly a
         * screenful per page this is thousands of channels.
         */
        const val MAX_PAGES = 40
    }
}
