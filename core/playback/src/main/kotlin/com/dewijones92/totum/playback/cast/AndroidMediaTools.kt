package com.dewijones92.totum.playback.cast

import android.content.Context
import com.dewijones92.totum.common.BundledBinaries
import java.io.File

public fun Context.castTools(): MediaTools {
    val bin = File(applicationInfo.nativeLibraryDir)
    return MediaTools(File(bin, BundledBinaries.FFMPEG), File(bin, BundledBinaries.FFPROBE), cacheDir, filesDir)
}
