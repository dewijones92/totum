package com.dewijones92.totum.backup.drive

import java.io.File

data class BackupCopy(val name: String, val modifiedAtMs: Long)

interface BackupFolder {
    val where: String

    suspend fun list(): List<BackupCopy>

    suspend fun write(name: String, text: String)

    suspend fun read(name: String): String?

    suspend fun delete(name: String)
}

class LocalBackupFolder(private val dir: File) : BackupFolder {
    override val where: String = "this phone (${dir.name})"

    override suspend fun list(): List<BackupCopy> =
        dir.listFiles().orEmpty().filter { it.isFile }.map { BackupCopy(it.name, it.lastModified()) }

    override suspend fun write(name: String, text: String) {
        dir.mkdirs()
        val temp = File(dir, "$name.tmp")
        temp.writeText(text)
        check(temp.renameTo(File(dir, name))) { "could not move $name into place" }
    }

    override suspend fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    override suspend fun delete(name: String) {
        File(dir, name).delete()
    }
}
