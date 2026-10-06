package com.dewijones92.totum.ytdlp.chaquopy

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.dewijones92.totum.common.Diag

internal class V8Solver(context: Context) {
    private val isolates = SandboxIsolates(context)
    val runtime = V8ChallengeRuntime(isolates)

    init {
        context.applicationContext.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return
                    Diag.log("engine", "v8 released on memory trim level $level (${runtime.state})")
                    runtime.close()
                    isolates.close()
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            },
        )
    }
}
