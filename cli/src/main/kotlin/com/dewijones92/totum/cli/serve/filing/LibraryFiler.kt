package com.dewijones92.totum.cli.serve.filing

import com.dewijones92.totum.cli.serve.FilingProblems
import com.dewijones92.totum.common.Diag
import java.io.File
import java.nio.file.Files

class LibraryFiler(
    private val library: FolderLibrary,
    private val jellyfin: Jellyfin?,
    private val decide: suspend (FileRequest) -> FilePlan,
    private val refused: suspend (String) -> Unit = {},
    private val sidecars: ((File) -> List<File>)? = null,
    private val remux: ((File) -> MkvRemuxOutcome)? = null,
    private val problems: FilingProblems? = null,
) {
    sealed interface Outcome {
        data class Moved(val file: File, val plan: FilePlan) : Outcome
        data class Left(val why: String) : Outcome
    }

    suspend fun file(source: File, name: String, from: String?): Outcome {
        val unfit = when {
            name.substringAfterLast('.', "").lowercase() !in FileCheck.VIDEO -> "not a video file"
            !source.isFile -> "the downloaded file is missing"
            else -> null
        }
        if (unfit != null) return left(name, unfit)
        remux?.invoke(source)?.let { outcome ->
            if (outcome is MkvRemuxOutcome.Failed) {
                problems?.add(
                    "$name (remux)",
                    "⚠️ \"$name\" has its Tracks after its first cluster and could not be fixed: ${outcome.why}",
                )
            }
        }
        val plan = planFor(FileRequest(name, source.length(), Filing.context(name, library, from), from))
        if (plan.action == FilePlan.Action.LEAVE) return left(name, "the brain said leave it: ${plan.reason}")
        return move(source, name, plan, from)
    }

    private fun move(source: File, name: String, plan: FilePlan, from: String?): Outcome {
        val shelf = Shelf.entries.first { it.folder == plan.shelf }
        val root = library.root(shelf).canonicalFile
        val wanted = File(root, plan.folder).canonicalFile
        if (!wanted.path.startsWith(root.path + File.separator)) {
            return left(name, "\"${plan.folder}\" is outside ${root.path}")
        }
        val placed = placeIn(root, plan, wanted, name, from)
            ?: return left(
                name,
                "can't write to ${wanted.path} (owner or permissions), and no clean folder could be made beside it",
            )
        val dir = placed.dir
        val fileName = placed.fileName
        val target = unused(File(dir, fileName))
        return runCatching { Files.move(source.toPath(), target.toPath()).toFile() }.fold(
            onSuccess = { moved ->
                problems?.clear(name)
                Diag.log("filing", "filed \"$name\" -> ${moved.path} (${plan.action}: ${plan.reason})")
                sidecars?.invoke(moved)
                jellyfin?.created(shelf, moved.relativeTo(root).path)
                Outcome.Moved(moved, plan)
            },
            onFailure = { left(name, "the move to ${target.path} failed: ${it.message}") },
        )
    }

    private class Placed(val dir: File, val fileName: String)

    private fun placeIn(root: File, plan: FilePlan, wanted: File, name: String, from: String?): Placed? {
        wanted.mkdirs()
        if (wanted.isDirectory && wanted.canWrite()) return Placed(wanted, plan.fileName)
        val clean = Filing.byRules(name, library, from)
        val shown = wanted.relativeTo(root).path
        val base = if (plan.action == FilePlan.Action.EPISODE) {
            (listOf(clean.folder.substringBefore('/')) + plan.folder.split('/').drop(1)).joinToString("/")
        } else {
            clean.folder
        }
        val fileName = if (plan.action == FilePlan.Action.EPISODE) plan.fileName else clean.fileName
        val fresh = generateSequence(1) { it + 1 }.take(FRESH_TRIES).map { n ->
            File(root, if (n == 1) base else base.numbered(n)).canonicalFile
        }.firstOrNull { candidate ->
            candidate.path.startsWith(root.path + File.separator) &&
                (candidate.mkdirs() || candidate.isDirectory) && candidate.canWrite()
        }
        if (fresh == null) {
            problems?.add(name, "⚠️ \"$name\" could not be filed: $shown is not writable (owner or permissions)")
            return null
        }
        Diag.log(
            "filing",
            "\"$name\": $shown is not writable (owner or permissions); filed into the fresh folder " +
                "${fresh.relativeTo(root).path} instead",
        )
        return Placed(fresh, fileName)
    }

    private fun String.numbered(n: Int): String {
        val top = substringBefore('/')
        return "$top ($n)" + substringAfter(top, "")
    }

    private suspend fun planFor(request: FileRequest): FilePlan {
        val asked = runCatching { decide(request) }
            .onFailure { Diag.warn("filing", "the brain could not file \"${request.name}\"; filing by rules", it) }
            .getOrNull()
        val refused = asked?.let { PlanCheck.problem(it, request.name) }
        if (refused != null) {
            Diag.warn("filing", "the brain's plan for \"${request.name}\" was refused ($refused); filing by rules")
            this.refused("plan for \"${request.name}\" refused: $refused")
        }
        val accepted = asked?.takeIf { refused == null }?.let { PlanCheck.snapped(it, library) }
        if (accepted != null) return accepted
        if (request.from.orEmpty().startsWith(YOUTUBE_FROM)) {
            Diag.log(
                "filing",
                "\"${request.name}\" is from YouTube and has no accepted plan: left, never filed by rules"
            )
            return FilePlan(FilePlan.Action.LEAVE, "", "", request.name, "no plan from the brain for a YouTube video")
        }
        return Filing.byRules(request.name, library, request.from)
    }

    private fun left(name: String, why: String): Outcome {
        Diag.log("filing", "left \"$name\" where it is: $why")
        return Outcome.Left(why)
    }

    private fun unused(wanted: File): File {
        if (!wanted.exists()) return wanted
        val base = wanted.nameWithoutExtension
        val ext = wanted.extension
        return generateSequence(2) { it + 1 }.map { File(wanted.parentFile, "$base ($it).$ext") }.first { !it.exists() }
    }
}

internal const val YOUTUBE_FROM = "YouTube channel:"
private const val FRESH_TRIES = 6
