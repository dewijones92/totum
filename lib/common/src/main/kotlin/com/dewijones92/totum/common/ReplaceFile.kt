package com.dewijones92.totum.common

import java.io.File
import java.io.IOException

public fun File.writeTextReplacing(text: String) {
    parentFile?.mkdirs()
    val next = File(parentFile, "$name.next")
    next.writeText(text)
    if (!next.renameTo(this)) {
        next.copyTo(this, overwrite = true)
        if (!next.delete()) throw IOException("could not tidy ${next.name}")
    }
}
