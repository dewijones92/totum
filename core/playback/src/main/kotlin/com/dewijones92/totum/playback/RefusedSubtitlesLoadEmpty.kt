package com.dewijones92.totum.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals
import java.io.IOException

@UnstableApi
public class RefusedSubtitlesLoadEmpty(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = Source(upstream.createDataSource())

    @UnstableApi
    private class Source(private val fallback: DataSource) : DataSource by fallback {

        private var empty: DataSource? = null

        override fun open(dataSpec: DataSpec): Long {
            empty = null
            return try {
                fallback.open(dataSpec)
            } catch (e: IOException) {
                val url = dataSpec.uri.toString()
                if (!isRefusedSubtitle(e.httpStatus(), url)) throw e
                Vitals.add("subtitles.refused429")
                Diag.warn(
                    "subtitles",
                    "YouTube refused the ${captionLanguage(url)} subtitles with 429 (too many requests); " +
                        "showing none for this item so the picture and sound keep loading",
                )
                ByteArrayDataSource(emptySubtitleFor(url)).also { empty = it }.open(dataSpec)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            (empty ?: fallback).read(buffer, offset, length)

        override fun getUri(): Uri? = empty?.uri ?: fallback.uri

        override fun close() {
            empty?.close()
            fallback.close()
        }
    }
}

private const val HTTP_TOO_MANY_REQUESTS = 429

internal fun isRefusedSubtitle(status: Int?, url: String): Boolean =
    status == HTTP_TOO_MANY_REQUESTS && "/api/timedtext" in url

internal fun emptySubtitleFor(url: String): ByteArray =
    when (Regex("""[?&]fmt=([^&]+)""").find(url)?.groupValues?.get(1)) {
        "vtt" -> "WEBVTT\n\n"
        "srt" -> ""
        else -> EMPTY_TTML
    }.toByteArray()

private const val EMPTY_TTML =
    """<?xml version="1.0" encoding="utf-8"?><tt xmlns="http://www.w3.org/ns/ttml"><body><div/></body></tt>"""
