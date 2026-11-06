package com.dewijones92.totum.diagnostics

import android.os.Build

internal fun thisDevice(): String =
    "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
