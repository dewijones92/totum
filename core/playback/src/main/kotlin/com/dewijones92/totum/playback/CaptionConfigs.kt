package com.dewijones92.totum.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import com.dewijones92.totum.common.SubtitleTrack

internal fun SubtitleTrack.toSubtitleConfiguration(): MediaItem.SubtitleConfiguration =
    captionConfiguration(url.value, format.mimeType, languageCode, label)

internal fun SubtitleTrack.toBundle(): Bundle =
    bundleOf(URL to url.value, MIME to format.mimeType, LANGUAGE to languageCode, LABEL to label)

internal fun Bundle.toSubtitleConfiguration(): MediaItem.SubtitleConfiguration? {
    val url = getString(URL) ?: return null
    return captionConfiguration(url, getString(MIME), getString(LANGUAGE), getString(LABEL))
}

private fun captionConfiguration(url: String, mime: String?, language: String?, label: String?) =
    MediaItem.SubtitleConfiguration.Builder(url.toUri().also { SideLoadedText.remember(it.toString()) })
        .setMimeType(mime)
        .setLanguage(language)
        .setLabel(label)
        .build()

private const val URL = "url"
private const val MIME = "mime"
private const val LANGUAGE = "language"
private const val LABEL = "label"
