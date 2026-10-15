package com.dewijones92.totum.common

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

public class RequestMeter(private val now: () -> Long = System::currentTimeMillis) {
    private val calls = ConcurrentHashMap<String, ConcurrentLinkedDeque<Pair<Long, Int>>>()

    public fun record(host: String, status: Int) {
        val at = now()
        val log = calls.computeIfAbsent(host) { ConcurrentLinkedDeque() }
        log.addLast(at to status)
        while ((log.peekFirst()?.first ?: at) < at - HOUR_MS) log.pollFirst()
    }

    public fun lastHour(): Map<String, String> {
        val since = now() - HOUR_MS
        calls.values.forEach { log -> while ((log.peekFirst()?.first ?: since) < since) log.pollFirst() }
        calls.entries.removeIf { it.value.isEmpty() }
        return calls.entries.associate { (host, log) ->
            val recent = log.filter { it.first >= since }
            host to "${recent.size} call(s), ${recent.count { it.second == HTTP_TOO_MANY }} 429, " +
                "${recent.count { it.second in REFUSED }} other 4xx"
        }.filterValues { !it.startsWith("0 call") }.toSortedMap()
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val HTTP_TOO_MANY = 429
        val REFUSED = setOf(400, 401, 403, 404)
    }
}
