package com.dewijones92.totum.common

import java.net.URLEncoder

public data class GitHubDraft(
    val title: String,
    val body: String,
    val url: String,
    val eventsKept: Int,
    val eventsOffered: Int,
    val noteCut: Boolean,
    val traceCut: Boolean = false,
)

public object GitHubReport {
    public const val REPO: String = "dewijones92/totum"
    public const val NEW_ISSUE_URL: String = "https://github.com/$REPO/issues/new"
    public const val MY_ISSUES_URL: String = "https://github.com/$REPO/issues?q=is%3Aissue+author%3A%40me"
    public const val MAX_URL_CHARS: Int = 7_000
    public const val EVENT_LINE_CHARS: Int = 300
    public const val VERSION: String = "App version"

    private const val TITLE_CHARS = 80
    private const val CUT_MARK = "…"

    private class Parts(
        val title: String,
        val note: String,
        val facts: List<Pair<String, String>>,
        val events: List<String>,
        val trace: String = "",
        val traceKept: Int = trace.length,
    ) {
        val body: String get() = bodyOf(note, facts, events, trace)
        val traceCut: Boolean get() = traceKept < trace.length
        val url: String get() = urlFor(title, bodyOf(note, facts, events, shownTrace()))

        fun withEvents(kept: List<String>) = Parts(title, note, facts, kept, trace, traceKept)

        fun withTrace(keep: Int) = Parts(title, note, facts, emptyList(), trace, keep)

        fun withNote(shorter: String) = Parts(title, shorter, facts, emptyList(), trace, 0)

        private fun shownTrace(): String = when {
            traceKept >= trace.length -> trace
            traceKept <= 0 -> ""
            else -> trace.take(traceKept).let { it.substringBeforeLast('\n', it) } + "\n" + CUT_MARK
        }
    }

    public fun draft(
        note: String,
        facts: List<Pair<String, String>>,
        events: List<String>,
        redactor: ReportRedactor,
        maxUrlChars: Int = MAX_URL_CHARS,
        trace: String = "",
    ): GitHubDraft {
        val cleanNote = redactor.redact(note.trim())
        val cleanFacts = facts.map { (label, value) -> label to redactor.redact(value) }
        val cleanEvents = events.map { redactor.redact(it).take(EVENT_LINE_CHARS) }
        val cleanTrace = redactor.redact(trace.trim())
        val whole = Parts(titleOf(cleanNote, cleanFacts), cleanNote, cleanFacts, cleanEvents, cleanTrace)
        if (whole.url.length <= maxUrlChars) return result(whole, cleanEvents.size, noteCut = false)
        val fewer = whole.withEvents(cleanEvents.takeLast(fittingEvents(whole, maxUrlChars)))
        if (fewer.url.length <= maxUrlChars) return result(fewer, cleanEvents.size, noteCut = false)
        if (cleanTrace.isNotEmpty()) {
            val shorter = whole.withTrace(fittingTrace(whole, maxUrlChars))
            if (shorter.url.length <= maxUrlChars) return result(shorter, cleanEvents.size, noteCut = false)
        }
        return result(whole.withNote(fittingNote(whole, maxUrlChars)), cleanEvents.size, noteCut = true)
    }

    private fun result(parts: Parts, offered: Int, noteCut: Boolean): GitHubDraft =
        GitHubDraft(parts.title, parts.body, parts.url, parts.events.size, offered, noteCut, parts.traceCut)

    private fun fittingEvents(whole: Parts, max: Int): Int {
        var low = 0
        var high = whole.events.size
        while (low < high) {
            val middle = (low + high + 1) / 2
            val fits = whole.withEvents(whole.events.takeLast(middle)).url.length <= max
            if (fits) low = middle else high = middle - 1
        }
        return low
    }

    private fun fittingTrace(whole: Parts, max: Int): Int {
        var low = 0
        var high = whole.trace.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            val fits = whole.withTrace(middle).url.length <= max
            if (fits) low = middle else high = middle - 1
        }
        return low
    }

    private fun fittingNote(whole: Parts, max: Int): String {
        var low = 0
        var high = whole.note.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            val fits = whole.withNote(whole.note.take(middle) + CUT_MARK).url.length <= max
            if (fits) low = middle else high = middle - 1
        }
        return whole.note.take(low) + CUT_MARK
    }

    private fun titleOf(note: String, facts: List<Pair<String, String>>): String {
        val first = note.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val version = facts.firstOrNull { it.first == VERSION }?.second
        return when {
            first == null -> "Report from Totum${version?.let { " $it" }.orEmpty()}"
            first.length <= TITLE_CHARS -> first
            else -> first.take(TITLE_CHARS - 1).trimEnd() + CUT_MARK
        }
    }

    private fun bodyOf(
        note: String,
        facts: List<Pair<String, String>>,
        events: List<String>,
        trace: String,
    ): String = buildString {
        if (note.isNotEmpty()) append(note).append("\n\n")
        append("**About this report**\n")
        facts.forEach { (label, value) -> append("- ").append(label).append(": ").append(value).append('\n') }
        if (trace.isNotEmpty()) {
            append("\n<details><summary>Stack trace</summary>\n\n```\n").append(trace).append("\n```\n</details>\n")
        }
        if (events.isNotEmpty()) {
            append("\n<details><summary>Recent activity (").append(events.size).append(" lines)</summary>\n\n```\n")
            events.forEach { append(it).append('\n') }
            append("```\n</details>\n")
        }
    }.trimEnd()

    private fun urlFor(title: String, body: String): String =
        "$NEW_ISSUE_URL?title=${encode(title)}&body=${encode(body)}"

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
