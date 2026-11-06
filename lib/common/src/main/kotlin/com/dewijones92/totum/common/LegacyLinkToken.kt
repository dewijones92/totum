package com.dewijones92.totum.common

import java.util.concurrent.atomic.AtomicLong

public object LegacyLinkToken {

    private const val PARAMETER = "totumToken="
    private const val VALUE = "[^&#\"\\\\\\s]*"
    private val parameter = Regex(PARAMETER)
    private val firstWithMore = Regex("\\?totumToken=$VALUE&")
    private val first = Regex("\\?totumToken=$VALUE")
    private val later = Regex("&totumToken=$VALUE")
    private val stripped = AtomicLong()

    public fun strip(text: String, where: String): String {
        if (!text.contains(PARAMETER)) return text
        val found = parameter.findAll(text).count()
        var cleaned = text
        while (cleaned.contains(PARAMETER)) {
            val before = cleaned
            while (firstWithMore.containsMatchIn(cleaned)) cleaned = firstWithMore.replace(cleaned, "?")
            cleaned = later.replace(first.replace(cleaned, ""), "")
            if (cleaned == before) break
        }
        note(found, cleaned.contains(PARAMETER), where)
        return cleaned
    }

    private fun note(found: Int, leftOver: Boolean, where: String) {
        val total = stripped.addAndGet(found.toLong())
        val firstOfThisSize = java.lang.Long.highestOneBit(total) != java.lang.Long.highestOneBit(total - found)
        if (firstOfThisSize || leftOver) {
            Diag.log(
                "link",
                "stripped the retired shared home-server token from $found link parameter(s) in $where; " +
                    "$total so far this launch" + if (leftOver) "; some parameter(s) could not be removed" else "",
            )
        }
    }
}
