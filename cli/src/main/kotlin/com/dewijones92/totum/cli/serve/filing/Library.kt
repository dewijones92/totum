package com.dewijones92.totum.cli.serve.filing

import java.io.File

enum class Shelf(val folder: String) { MOVIES("movies"), SERIES("series") }

interface LibraryView {
    fun dirs(shelf: Shelf, path: String): List<String>
    fun files(shelf: Shelf, path: String): List<String>
    fun exists(shelf: Shelf, path: String): Boolean
}

class FolderLibrary(private val roots: Map<Shelf, File>) : LibraryView {
    private fun at(shelf: Shelf, path: String) = File(roots.getValue(shelf), path)

    override fun dirs(shelf: Shelf, path: String): List<String> =
        at(shelf, path).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    override fun files(shelf: Shelf, path: String): List<String> =
        at(shelf, path).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()

    override fun exists(shelf: Shelf, path: String): Boolean = at(shelf, path).isDirectory

    fun root(shelf: Shelf): File = roots.getValue(shelf)
}

class ListedLibrary(private val paths: List<String>, hidden: Set<String> = emptySet()) : LibraryView {
    private val kept = paths.filter { p -> hidden.none { h -> p.trimEnd('/') == h || p.startsWith("$h/") } }
    private val dirSet = kept.flatMap { entry ->
        val parts = entry.trimEnd('/').split('/')
        (1 until parts.size).map { parts.take(it).joinToString("/") } +
            listOfNotNull(entry.trimEnd('/').takeIf { entry.endsWith('/') })
    }.toSet()
    private val fileSet = kept.filter { !it.endsWith('/') }.toSet()

    private fun children(prefix: String, of: Set<String>): List<String> =
        of.filter { it.startsWith("$prefix/") && '/' !in it.removePrefix("$prefix/") }
            .map { it.removePrefix("$prefix/") }.sorted()

    private fun key(shelf: Shelf, path: String) = listOf(
        shelf.folder,
        path
    ).filter { it.isNotEmpty() }.joinToString("/")

    override fun dirs(shelf: Shelf, path: String) = children(key(shelf, path), dirSet)
    override fun files(shelf: Shelf, path: String) = children(key(shelf, path), fileSet)
    override fun exists(shelf: Shelf, path: String) = path.isEmpty() || key(shelf, path) in dirSet

    fun hiding(more: Set<String>) = ListedLibrary(paths, more)
}
