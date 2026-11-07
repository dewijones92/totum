package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.common.Diag
import java.io.File
import java.nio.file.Files

internal class Scratch private constructor(val dir: File, val bytes: Long) {

    fun delete() {
        val gone = dir.deleteRecursively()
        Diag.log("ai", "scratch ${dir.name}: ${if (gone) "deleted" else "COULD NOT BE DELETED"} ($bytes bytes)")
    }

    companion object {
        const val MAX_BYTES = 128L * 1024 * 1024
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        fun problem(files: Map<String, String>): String? = when {
            files.keys.any { !NAME.matches(it) } -> "file names must be letters, digits, dots, dashes or underscores"
            files.values.sumOf { it.length.toLong() } > MAX_BYTES -> "the files are larger than $MAX_BYTES bytes"
            else -> null
        }

        fun create(files: Map<String, String>): Scratch {
            val dir = Files.createTempDirectory("ai-scratch-").toFile()
            var bytes = 0L
            files.forEach { (name, content) ->
                val data = content.toByteArray(Charsets.UTF_8)
                File(dir, name).writeBytes(data)
                bytes += data.size
            }
            Diag.log("ai", "scratch ${dir.name}: ${files.size} file(s), $bytes bytes, kept only for this call")
            return Scratch(dir, bytes)
        }
    }
}
