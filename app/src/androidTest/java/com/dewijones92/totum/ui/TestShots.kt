package com.dewijones92.totum.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal object TestShots {

    fun take(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString(ARGUMENT) == null) return
        Thread.sleep(HEADS_UP_GONE_MS)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    private const val ARGUMENT = "shots"
    private const val PNG_QUALITY = 100
    private const val HEADS_UP_GONE_MS = 6_000L
}
