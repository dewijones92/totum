package com.dewijones92.totum.common

public class ReportRedactor(
    secrets: Collection<String> = emptyList(),
    hosts: Collection<String> = emptyList(),
    private val keepIdentifiers: Boolean = false,
) {

    private val secretsByLength = secrets.map { it.trim() }.filter { it.length >= MIN_SECRET }
        .distinct().sortedByDescending { it.length }

    private val hostsByLength = hosts.map { it.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
        .filter { it.length >= MIN_HOST }.distinct().sortedByDescending { it.length }

    private val rules: List<Rule> = if (keepIdentifiers) IDENTIFIER_RULES else TEXT_RULES

    public fun redact(text: String): String {
        var out = text
        secretsByLength.forEach { out = out.replace(it, SECRET) }
        hostsByLength.forEach { out = out.replace(it, HOME_SERVER, ignoreCase = true) }
        rules.forEach { rule -> out = rule.regex.replace(out, rule.transform) }
        return out
    }

    private class Rule(val regex: Regex, val transform: (MatchResult) -> String) {
        constructor(regex: Regex, replacement: String) : this(regex, { match -> expand(match, replacement) })
    }

    public companion object {
        public const val SECRET: String = "<secret>"
        public const val HOME_SERVER: String = "<home-server>"
        public const val EMAIL: String = "<email>"
        public const val ADDRESS: String = "<address>"
        public const val LINK: String = "<link removed>"
        public const val REDACTED: String = "<redacted>"
        public const val PATH_REMOVED: String = "/…"

        private const val MIN_SECRET = 6
        private const val MIN_HOST = 4
        private const val OCTET_MAX = 255
        private const val OCTETS = 4
        private const val LONG_TOKEN = 32
        private const val HEX_ID = 40
        private const val SCHEME = 1
        private const val HOST = 2
        private const val REST = 3

        private val VIDEO_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
        private val SAFE_PARAMS = setOf("v", "list", "t", "index")

        private val PARAMETER_NAMES = listOf(
            "token", "access_token", "refresh_token", "id_token", "key", "api_key", "apikey", "secret",
            "password", "passwd", "pwd", "auth", "authorization", "sig", "signature", "sapisid", "cookie",
            "pot", "po_token", "otp",
        ).joinToString("|")

        private val SECRET_KEY = Regex(
            "(?i)(token|secret|passw|credential|authoriz|api[_-]?key|apikey|cookie|bearer|signature|refresh|" +
                "private[_-]?key|access[_-]?key|session[_-]?id)",
        )

        public fun isSecretKey(name: String): Boolean = SECRET_KEY.containsMatchIn(name)

        public fun looksSecret(text: String): Boolean = SHAPES.any { it.containsMatchIn(text) } ||
            HEX_SECRET.containsMatchIn(text)

        private fun expand(match: MatchResult, replacement: String): String =
            replacement.replace(Regex("""\$(\d)""")) { ref -> match.groupValues[ref.groupValues[1].toInt()] }

        private fun address(match: MatchResult): String =
            if (match.groupValues.drop(1).take(OCTETS).all { it.toInt() <= OCTET_MAX }) ADDRESS else match.value

        private fun isVideoHost(host: String): Boolean {
            val bare = host.substringBefore(':').lowercase()
            return VIDEO_HOSTS.any { bare == it || bare.endsWith(".$it") }
        }

        private fun safeQuery(query: String): String =
            query.split('&').filter { pair -> pair.substringBefore('=') in SAFE_PARAMS }.joinToString("&")

        private fun keepVideoParts(match: MatchResult): String {
            val scheme = match.groupValues[SCHEME]
            val host = match.groupValues[HOST]
            val rest = match.groupValues[REST]
            val path = rest.substringBefore('?').substringBefore('#')
            val query = rest.substringAfter('?', "").substringBefore('#')
            val kept = safeQuery(query)
            return "$scheme://$host$path" + if (kept.isEmpty()) "" else "?$kept"
        }

        private fun cutAddress(match: MatchResult): String {
            val host = match.groupValues[HOST]
            val rest = match.groupValues[REST]
            return if (isVideoHost(host)) {
                keepVideoParts(match)
            } else {
                "${match.groupValues[SCHEME]}://$host" + if (rest.isEmpty() || rest == "/") rest else PATH_REMOVED
            }
        }

        private fun trimQuery(match: MatchResult): String {
            val host = match.groupValues[HOST]
            val rest = match.groupValues[REST]
            if (!rest.contains('?') && !rest.contains('#')) return match.value
            val path = rest.substringBefore('?').substringBefore('#')
            val kept = safeQuery(rest.substringAfter('?', "").substringBefore('#'))
            return "${match.groupValues[SCHEME]}://$host$path" + if (kept.isEmpty()) "" else "?$kept"
        }

        private val ADDRESS_IN_TEXT = Regex("""(?i)\b(https?)://([^\s/?#"']*)([^\s"'<>]*)""")

        private val HEX_ID_AFTER_TORRENT =
            """(?<!torrent:)(?<!btih:)(?<![A-Za-z0-9])[A-Fa-f0-9]{$HEX_ID,}(?![A-Za-z0-9])"""

        private val HEX_SECRET = Regex(HEX_ID_AFTER_TORRENT)

        private val SHAPES = listOf(
            Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]{8,}"""),
            Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}"""),
            Regex("""ya29\.[A-Za-z0-9._-]{16,}"""),
            Regex("""1//[A-Za-z0-9._-]{20,}"""),
            Regex("""AIza[A-Za-z0-9_-]{30,}"""),
            Regex("""sk-[A-Za-z0-9_-]{20,}"""),
            Regex("""://[^/\s:@]+:[^/\s@]+@"""),
        )

        private val SHARED_RULES: List<Rule> = listOf(
            Rule(Regex("""(?i)(https?|ftp)://[^\s/@:"'<>]+:[^\s/@"'<>]+@"""), "$1://"),
            Rule(
                Regex(
                    """(?i)(authorization|x-totum-token|proxy-authorization|cookie|set-cookie)(\s*[:=]\s*)[^\r\n]+"""
                ),
                "$1$2$REDACTED",
            ),
            Rule(Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}"""), "Bearer $REDACTED"),
            Rule(Regex("""\bya29\.[A-Za-z0-9_-]+"""), REDACTED),
            Rule(Regex("""\b1//[A-Za-z0-9_-]{20,}"""), REDACTED),
            Rule(Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*"""), REDACTED),
            Rule(Regex("""\bAIza[A-Za-z0-9_-]{30,}"""), REDACTED),
            Rule(Regex("""\bsk-[A-Za-z0-9_-]{20,}"""), REDACTED),
            Rule(
                Regex(
                    """(?i)\b((?:[\w-]*(?:token|secret|password|passwd|api[_-]?key|authorization|cookie)[\w-]*)""" +
                        """\s*[:=]\s*)(?!<redacted>)[^\s,;"'&<]+"""
                ),
                "$1$REDACTED",
            ),
        )

        private val CONTACT_RULES: List<Rule> = listOf(
            Rule(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}"""), EMAIL),
            Rule(Regex("""(?i)\bfe80::[0-9a-f:]+(%\w+)?"""), ADDRESS),
            Rule(Regex("""(?i)\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\b"""), ADDRESS),
            Rule(
                Regex("""(?<![\w.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\w.]*\d)(?::\d{1,5})?"""),
                ::address,
            ),
        )

        private val PARAMETER_RULE = Rule(
            Regex("""(?i)(?<![A-Za-z0-9])($PARAMETER_NAMES)=([^&\s"'#<>;,)\]]+)"""),
            "$1=$REDACTED",
        )

        private val TEXT_RULES: List<Rule> = buildList {
            add(Rule(Regex("""(?i)magnet:\?[^\s"'<>]+"""), LINK))
            addAll(SHARED_RULES)
            add(Rule(ADDRESS_IN_TEXT, ::cutAddress))
            add(PARAMETER_RULE)
            addAll(CONTACT_RULES)
            add(
                Rule(
                    Regex(
                        "(?<!torrent:)(?<!btih:)(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{$LONG_TOKEN,}(?![A-Za-z0-9_-])"
                    ),
                    REDACTED,
                )
            )
        }

        private val IDENTIFIER_RULES: List<Rule> = buildList {
            addAll(SHARED_RULES)
            add(Rule(ADDRESS_IN_TEXT, ::trimQuery))
            add(PARAMETER_RULE)
            addAll(CONTACT_RULES)
            add(Rule(HEX_SECRET, REDACTED))
        }
    }
}
