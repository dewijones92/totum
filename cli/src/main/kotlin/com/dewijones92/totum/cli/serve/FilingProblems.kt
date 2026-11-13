package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeProblem
import com.dewijones92.totum.data.home.HomeSeverity
import java.util.concurrent.ConcurrentHashMap

class FilingProblems(private val clock: () -> Long = System::currentTimeMillis) {
    private class Noted(val text: String, val atMs: Long)

    private val noted = ConcurrentHashMap<String, Noted>()

    fun add(file: String, text: String) {
        val before = noted.put(file, Noted(text, clock()))
        if (before?.text != text) Diag.log("filing", "home problem noted for \"$file\": $text")
    }

    fun clear(file: String) {
        if (noted.remove(file) != null) Diag.log("filing", "home problem cleared for \"$file\": it was filed")
    }

    fun current(): List<HomeProblem> {
        val now = clock()
        noted.entries.removeIf { now - it.value.atMs > EXPIRES_MS }
        return noted.entries.sortedBy { it.value.atMs }
            .map { (file, note) -> HomeProblem("filing:$file", HomeSeverity.WARNING, note.text) }
    }

    private companion object {
        const val EXPIRES_MS = 24 * 60 * 60 * 1_000L
    }
}
