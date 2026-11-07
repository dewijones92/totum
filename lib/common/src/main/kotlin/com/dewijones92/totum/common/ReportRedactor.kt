package com.dewijones92.totum.common

public class ReportRedactor(secrets: Collection<String> = emptyList(), hosts: Collection<String> = emptyList()) {

    private val secretsByLength = secrets.map { it.trim() }.filter { it.length >= MIN_SECRET }
        .distinct().sortedByDescending { it.length }

    private val hostsByLength = hosts.map { it.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
        .filter { it.length >= MIN_HOST }.distinct().sortedByDescending { it.length }

    public fun redact(text: String): String {
        var out = text
        secretsByLength.forEach { out = out.replace(it, SECRET) }
        hostsByLength.forEach { out = out.replace(it, HOME_SERVER, ignoreCase = true) }
        RULES.forEach { rule -> out = rule.regex.replace(out, rule.transform) }
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

        private const val MIN_SECRET = 6
        private const val MIN_HOST = 4
        private const val OCTET_MAX = 255
        private const val OCTETS = 4
        private const val LONG_TOKEN = 32

        private val PARAMETER_NAMES = listOf(
            "token", "access_token", "refresh_token", "id_token", "key", "api_key", "apikey", "secret",
            "password", "passwd", "pwd", "auth", "authorization", "sig", "signature", "sapisid", "cookie",
            "pot", "po_token", "otp",
        ).joinToString("|")

        private fun expand(match: MatchResult, replacement: String): String =
            replacement.replace(Regex("""\$(\d)""")) { ref -> match.groupValues[ref.groupValues[1].toInt()] }

        private fun address(match: MatchResult): String =
            if (match.groupValues.drop(1).take(OCTETS).all { it.toInt() <= OCTET_MAX }) ADDRESS else match.value

        private val RULES: List<Rule> = listOf(
            Rule(Regex("""(?i)magnet:\?[^\s"'<>]+"""), LINK),
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
            Rule(
                Regex("""(?i)(?<![A-Za-z0-9])($PARAMETER_NAMES)=([^&\s"'#<>;,)\]]+)"""),
                "$1=$REDACTED",
            ),
            Rule(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}"""), EMAIL),
            Rule(Regex("""(?i)\bfe80::[0-9a-f:]+(%\w+)?"""), ADDRESS),
            Rule(Regex("""(?i)\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\b"""), ADDRESS),
            Rule(
                Regex("""(?<![\w.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\w.]*\d)(?::\d{1,5})?"""),
                ::address,
            ),
            Rule(Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{$LONG_TOKEN,}(?![A-Za-z0-9_-])"""), REDACTED),
        )
    }
}
