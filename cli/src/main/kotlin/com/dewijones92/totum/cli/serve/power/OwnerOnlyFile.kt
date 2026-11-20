package com.dewijones92.totum.cli.serve.power

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

internal fun writeOwnerOnly(target: File, text: String) {
    target.parentFile?.mkdirs()
    val staged = File(target.parentFile, target.name + ".new")
    Files.deleteIfExists(staged.toPath())
    Files.createFile(
        staged.toPath(),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    )
    staged.writeText(text)
    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}
