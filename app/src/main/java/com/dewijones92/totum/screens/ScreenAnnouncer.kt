package com.dewijones92.totum.screens

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class ScreenAnnouncer(
    private val scope: CoroutineScope,
    private val post: suspend (String) -> Boolean,
    private val muted: () -> String? = { null },
    private val everyMs: Long = EVERY_MS,
) {
    fun follow(pages: Flow<String?>) = scope.launch {
        pages.distinctUntilChanged().collectLatest { page ->
            if (page == null) {
                Diag.log(TAG, "no PC screen page to tell the Pi about (not on Wi-Fi?)")
                return@collectLatest
            }
            muted()?.let { why ->
                Diag.log(TAG, "not telling the Pi where the PC screen page is: $why")
                return@collectLatest
            }
            while (true) {
                val told = post("""{"kind":"screen","url":${quote(page)}}""")
                Diag.log(
                    TAG,
                    "${if (told) "told" else "could not tell"} the Pi where this phone's PC screen page is " +
                        "(${page.substringBefore('#')}); again in ${everyMs / MS_PER_MINUTE} min",
                )
                delay(everyMs)
            }
        }
    }

    private companion object {
        const val TAG = "screen"
        const val EVERY_MS = 30 * 60_000L
        const val MS_PER_MINUTE = 60_000L
    }
}

private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
