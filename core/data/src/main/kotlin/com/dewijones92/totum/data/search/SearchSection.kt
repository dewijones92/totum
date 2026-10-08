package com.dewijones92.totum.data.search

/**
 * How one search section is getting on, independently of the others.
 *
 * Dewi, 2026-08-07: *"the search in the app is quite slow as its blocked by the torrent search"*. It
 * was: the three sources ran concurrently and then the screen waited for **all three** before showing
 * anything, so every search cost as much as its slowest part. Torrent search goes out to Prowlarr and
 * through FlareSolverr, which is seconds at best, and when the home server is unreachable it is the
 * full timeout — while YouTube had answered long before and had nowhere to be shown.
 *
 * A section can be waiting, and that is the state the old shape could not express: it had a list and
 * a `failed` boolean per section, so "nothing yet" and "nothing found" were the same value. Naming
 * [Searching] is what lets results appear as they arrive and still leave the screen honest about
 * what is missing.
 *
 * One type for all three sections rather than three sets of parallel fields — the same reason the
 * app has one `SearchSource` and one `SearchHit`. A fourth section would render correctly without
 * anything new being written.
 */
public sealed interface SearchSection<out T> {

    /** Still waiting on this source. The screen says so instead of implying an empty answer. */
    public data object Searching : SearchSection<Nothing>

    /** It answered. [items] may still be empty, which is a real answer and not a failure. */
    public data class Found<T>(val items: T, val stillWaitingFor: List<String> = emptyList()) : SearchSection<T>

    /**
     * It could not answer, and why.
     *
     * Distinct from an empty result because the difference is actionable: the home server is only
     * reachable at home or on the VPN, and "no torrents match" reads very differently from
     * "the Pi is not there".
     */
    public data class Failed(val detail: String, val slow: Boolean = false) : SearchSection<Nothing>

    /**
     * This section does not exist for this install — no home server is configured.
     *
     * Not a failure and not an empty list: the section is not rendered at all. Without it, a person
     * who has never set up a home server would see a torrent section perpetually reporting a problem
     * they do not have.
     */
    public data object Absent : SearchSection<Nothing>

    /** The answer, or null while waiting, failed, or absent. */
    public val itemsOrNull: T? get() = (this as? Found)?.items

    /** True only while an answer is still expected — what a spinner should follow. */
    public val isSearching: Boolean get() = this is Searching
}

/** The same state about a different shape — lets a paged section render as a plain list. */
public fun <T, R> SearchSection<T>.map(transform: (T) -> R): SearchSection<R> = when (this) {
    is SearchSection.Found -> SearchSection.Found(transform(items))
    is SearchSection.Failed -> this
    SearchSection.Searching -> SearchSection.Searching
    SearchSection.Absent -> SearchSection.Absent
}

/**
 * The outcome of one source as a section, with [select] pulling out the hits that belong in it.
 *
 * A source answers with a mixed [Page] of [SearchHit]s and each section wants its own variant, so
 * the filtering lives here once rather than at each call site.
 */
public fun <T> SearchOutcome.asSection(select: (SearchOutcome.Success) -> T): SearchSection<T> =
    when (this) {
        is SearchOutcome.Success -> SearchSection.Found(select(this), stillWaitingFor)
        is SearchOutcome.Failure -> SearchSection.Failed(detail, slow)
    }
