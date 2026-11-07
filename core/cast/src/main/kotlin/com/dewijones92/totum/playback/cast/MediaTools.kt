package com.dewijones92.totum.playback.cast

import java.io.File

public class MediaTools(
    public val ffmpeg: File?,
    public val ffprobe: File?,
    public val cacheDir: File,
    public val keepDir: File,
)
