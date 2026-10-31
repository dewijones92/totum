package com.dewijones92.totum.cli.serve.filing

object PlanCheck {

    fun problem(plan: FilePlan, name: String): String? = when {
        plan.action == FilePlan.Action.LEAVE -> null
        plan.shelf !in Shelf.entries.map { it.folder } -> "unknown shelf ${plan.shelf}"
        else -> folderProblem(plan.folder) ?: nameProblem(plan.fileName, name)
    }

    private fun folderProblem(folder: String): String? =
        if (folder.isBlank() || folder.startsWith("/") || folder.split('/').any { it in DOTS }) {
            "folder \"$folder\" is not a plain relative path"
        } else {
            null
        }

    private fun nameProblem(fileName: String, name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val episode = Filing.episodeOf(name)
        return when {
            '/' in fileName || fileName.isBlank() -> "file name \"$fileName\" is not a plain name"
            ext.isNotEmpty() && !fileName.lowercase().endsWith(".$ext") -> "file name lost its .$ext"
            episode != null && Filing.episodeOf(fileName).let { it != null && it != episode } ->
                "file name \"$fileName\" names a different episode from \"$name\""
            else -> null
        }
    }

    fun snapped(plan: FilePlan, library: LibraryView): FilePlan {
        val shelf = Shelf.entries.firstOrNull { it.folder == plan.shelf } ?: return plan
        var at = ""
        plan.folder.split('/').filter { it.isNotEmpty() }.forEach { part ->
            val real = library.dirs(shelf, at).firstOrNull { it == part }
                ?: library.dirs(shelf, at).firstOrNull { squashed(it) == squashed(part) }
            val repeated = real == null && squashed(at.substringAfterLast('/')) == squashed(part)
            if (!repeated) at = listOf(at, real ?: part).filter { it.isNotEmpty() }.joinToString("/")
        }
        return if (at == plan.folder) plan else plan.copy(folder = at)
    }

    private fun squashed(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    private val DOTS = setOf("..", ".")
}
