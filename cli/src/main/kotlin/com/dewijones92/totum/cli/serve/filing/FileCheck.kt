package com.dewijones92.totum.cli.serve.filing

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlin.random.Random

data class Score(val shelf: Boolean, val folder: Boolean, val node: Boolean)

data class CheckResult(val path: String, val plan: FilePlan?, val error: String?, val score: Score, val tookMs: Long)

class FileCheck(private val paths: List<String>, private val decide: suspend (FileRequest, ListedLibrary) -> FilePlan) {

    fun samples(count: Int, seed: Int): List<String> {
        val videos = paths.filter { p ->
            val lower = p.lowercase()
            !p.endsWith('/') && '/' in p && lower.substringAfterLast('.') in VIDEO && EXTRA.none { it in lower }
        }
        return videos.shuffled(Random(seed)).take(count)
    }

    suspend fun run(samples: List<String>, parallel: Int = PARALLEL): List<CheckResult> = coroutineScope {
        val gate = Semaphore(parallel)
        samples.map { path -> async { gate.withPermit { one(path) } } }.awaitAll()
    }

    private suspend fun one(path: String): CheckResult {
        val shelf = Shelf.entries.first { it.folder == path.substringBefore('/') }
        val actualDir = path.substringAfter('/').substringBeforeLast('/', "")
        val ownFolder = actualDir.isNotEmpty() && videosUnder("${shelf.folder}/$actualDir") == 1
        val newFilm = shelf == Shelf.MOVIES && (actualDir.isEmpty() || ownFolder)
        val hidden = if (shelf == Shelf.MOVIES && ownFolder) "${shelf.folder}/$actualDir" else path
        val library = ListedLibrary(paths, setOf(hidden))
        val name = path.substringAfterLast('/')
        val from = actualDir.substringAfterLast('/').takeIf {
            actualDir.isNotEmpty() && Filing.score(name, actualDir.substringBefore('/')) == 0.0
        }
        val started = System.currentTimeMillis()
        val plan = runCatching {
            val request = FileRequest(name, null, Filing.context(name, library, from), from)
            checked(decide(request, library), name, library, from)
        }
        val took = System.currentTimeMillis() - started
        val p = plan.getOrNull()
        val shelfOk = p != null && p.shelf == shelf.folder
        val nodeOk = shelfOk && if (newFilm) titleMatches(name, p!!.folder) else p!!.folder == actualDir
        val folderOk = nodeOk || shelfOk && p!!.folder.substringBefore('/') == actualDir.substringBefore('/')
        return CheckResult(path, p, plan.exceptionOrNull()?.message, Score(shelfOk, folderOk, nodeOk), took)
    }

    private fun checked(plan: FilePlan, name: String, library: ListedLibrary, from: String?): FilePlan =
        if (PlanCheck.problem(plan, name) == null) {
            PlanCheck.snapped(
                plan,
                library
            )
        } else {
            Filing.byRules(name, library, from)
        }

    private fun videosUnder(dir: String): Int =
        paths.count { it.startsWith("$dir/") && it.substringAfterLast('.').lowercase() in VIDEO }

    private fun titleMatches(name: String, folder: String): Boolean {
        val wanted = Filing.titleWords(name).filter { it.length > 1 }
        val have = Filing.words(folder)
        return wanted.isNotEmpty() && wanted.count { it in have } * 2 >= wanted.size
    }

    companion object {
        val VIDEO = setOf("mkv", "mp4", "avi", "m4v", "ts", "webm", "mov")
        val EXTRA = listOf("featurette", "extras", "sample", "deleted scenes", "behind the scenes", "trailer")
        private const val PARALLEL = 3
        private const val LISTING_FIELDS = 5
        private const val PERCENT = 100

        fun listing(file: File): List<String> = file.readLines().mapNotNull { line ->
            line.split('\t').takeIf { it.size >= LISTING_FIELDS }?.let { f ->
                if (f[0] == "d") "${f[LISTING_FIELDS - 1]}/" else f[LISTING_FIELDS - 1]
            }
        }

        fun summary(label: String, results: List<CheckResult>): String = buildString {
            val n = results.size.coerceAtLeast(1)
            fun pct(k: Int) = "$k/${results.size} (${k * PERCENT / n}%)"
            appendLine("# Filing dry run: $label")
            appendLine()
            appendLine("- right library: ${pct(results.count { it.score.shelf })}")
            appendLine("- right show/film folder: ${pct(results.count { it.score.folder })}")
            appendLine("- right exact node: ${pct(results.count { it.score.node })}")
            appendLine("- errors: ${results.count { it.error != null }}")
            appendLine("- median time: ${results.map { it.tookMs }.sorted().getOrElse(results.size / 2) { 0 }}ms")
        }

        fun report(label: String, results: List<CheckResult>): String = buildString {
            append(summary(label, results))
            appendLine()
            results.forEach { r ->
                val mark = when {
                    r.score.node -> "✅"
                    r.score.folder -> "🟡"
                    else -> "❌"
                }
                val where = r.plan?.let { "${it.action} ${it.shelf}/${it.folder}/${it.fileName} (${it.reason})" }
                appendLine("$mark ${r.path}")
                appendLine("   -> ${where ?: "error: ${r.error}"}")
            }
        }
    }
}
