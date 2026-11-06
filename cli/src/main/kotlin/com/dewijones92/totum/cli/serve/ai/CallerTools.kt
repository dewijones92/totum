package com.dewijones92.totum.cli.serve.ai

internal class CallerTools(private val allowed: Map<String, Set<String>>) {

    fun allowedFor(caller: String): Set<String>? = allowed[caller]

    fun refusal(caller: String, asked: String): String? {
        val permitted = allowed[caller]
            ?: return "🚫 \"$caller\" is not a caller the AI service knows; known callers: ${allowed.keys.sorted()}"
        val extra = toolsOf(asked) - permitted
        if (extra.isEmpty()) return null
        return "🚫 \"$caller\" may not use ${extra.sorted().joinToString()}; it may use ${describe(permitted)}"
    }

    companion object {
        const val ASK = "ask"
        const val VET = "vet"
        const val FILE = "file"
        const val ASK_STATE = "ask-state"
        const val FOR_YOU = "for-you"
        const val DIGEST = "digest"
        const val TRANSCRIPT = "transcript"
        const val SPOILER = "spoiler"

        val STANDARD = CallerTools(
            mapOf(
                ASK to setOf("WebSearch", "WebFetch"),
                VET to emptySet(),
                FILE to setOf("WebSearch"),
                ASK_STATE to setOf("Bash(jq:*)"),
                FOR_YOU to emptySet(),
                DIGEST to emptySet(),
                TRANSCRIPT to emptySet(),
                SPOILER to emptySet(),
            ),
        )

        fun toolsOf(tools: String): Set<String> {
            val found = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            for (c in tools) {
                when (c) {
                    '(' -> depth++
                    ')' -> depth = maxOf(0, depth - 1)
                }
                if (c == ',' && depth == 0) {
                    found += current.toString()
                    current.clear()
                } else {
                    current.append(c)
                }
            }
            found += current.toString()
            return found.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }

        fun describe(tools: Set<String>): String = if (tools.isEmpty()) "no tools" else tools.sorted().joinToString()
    }
}
