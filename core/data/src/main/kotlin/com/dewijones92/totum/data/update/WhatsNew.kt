package com.dewijones92.totum.data.update

public const val WHATS_NEW_HEADING: String = "## What's new"

public sealed interface WhatsNewVerdict {
    public data object Nothing : WhatsNewVerdict

    public data object RecordOnly : WhatsNewVerdict

    public data object Show : WhatsNewVerdict
}

public fun whatsNewVerdict(installed: AppVersion?, lastSeen: String?, freshInstall: Boolean): WhatsNewVerdict = when {
    installed == null -> WhatsNewVerdict.Nothing
    lastSeen == installed.toString() -> WhatsNewVerdict.Nothing
    lastSeen == null && freshInstall -> WhatsNewVerdict.RecordOnly
    else -> WhatsNewVerdict.Show
}

public fun whatsNewLines(notes: String?): List<String> {
    val body = notes?.lines().orEmpty()
    val start = body.indexOfFirst { it.trim().equals(WHATS_NEW_HEADING, ignoreCase = true) }
    if (start < 0) return emptyList()
    return body.drop(start + 1)
        .takeWhile { !it.trimStart().startsWith("#") }
        .map { it.trim().removePrefix("-").removePrefix("*").trim() }
        .filter { it.isNotEmpty() && NOISE_PREFIXES.none { prefix -> it.startsWith(prefix, ignoreCase = true) } }
        .map(::readable)
        .distinct()
}

private val NOISE_PREFIXES = listOf("Co-Authored-By:", "Claude-Session:", "Signed-off-by:")

private val SUBJECT = Regex("""^(\w+)(?:\(([^)]*)\))?!?:\s*(.+)$""")

private val TYPE_EMOJI = mapOf(
    "feat" to "✨",
    "fix" to "🐛",
    "perf" to "⚡",
    "refactor" to "🧹",
    "test" to "🧪",
    "docs" to "📝",
    "chore" to "🔧",
    "ci" to "🔧",
    "build" to "🔧",
    "style" to "🔧",
)

private fun readable(subject: String): String {
    val match = SUBJECT.matchEntire(subject) ?: return "• $subject"
    val (type, scope, text) = match.destructured
    val emoji = TYPE_EMOJI[type.lowercase()] ?: return "• $subject"
    val area = scope.takeIf { it.isNotBlank() }?.replaceFirstChar(Char::uppercaseChar)?.let { "$it: " }.orEmpty()
    return "$emoji $area$text"
}
