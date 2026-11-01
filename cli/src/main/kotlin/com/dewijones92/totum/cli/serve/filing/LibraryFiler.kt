package com.dewijones92.totum.cli.serve.filing

import com.dewijones92.totum.common.Diag
import java.io.File
import java.nio.file.Files

class LibraryFiler(
    private val library: FolderLibrary,
    private val jellyfin: Jellyfin?,
    private val decide: suspend (FileRequest) -> FilePlan,
    private val refused: suspend (String) -> Unit = {},
    private val sidecars: ((File) -> List<File>)? = null,
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
        val plan = planFor(FileRequest(name, source.length(), Filing.context(name, library, from), from))
        if (plan.action == FilePlan.Action.LEAVE) return left(name, "the brain said leave it: ${plan.reason}")
        return move(source, name, plan)
    }

    private fun move(source: File, name: String, plan: FilePlan): Outcome {
        val shelf = Shelf.entries.first { it.folder == plan.shelf }
        val root = library.root(shelf).canonicalFile
        val dir = File(root, plan.folder).canonicalFile
        val inside = dir.path.startsWith(root.path + File.separator)
        if (inside) dir.mkdirs()
        val problem = when {
            !inside -> "\"${plan.folder}\" is outside ${root.path}"
            !dir.isDirectory || !dir.canWrite() -> "can't write to ${dir.path} (owner or permissions)"
            else -> null
        }
        if (problem != null) return left(name, problem)
        val target = unused(File(dir, plan.fileName))
        return runCatching { Files.move(source.toPath(), target.toPath()).toFile() }.fold(
            onSuccess = { moved ->
                Diag.log("filing", "filed \"$name\" -> ${moved.path} (${plan.action}: ${plan.reason})")
                sidecars?.invoke(moved)
                jellyfin?.created(shelf, moved.relativeTo(root).path)
                Outcome.Moved(moved, plan)
            },
            onFailure = { left(name, "the move to ${target.path} failed: ${it.message}") },
        )
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
