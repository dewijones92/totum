package com.dewijones92.totum.screens

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PhoneIdentity(val installId: String, val model: String)

data class PhoneNow(val playing: Boolean, val title: String?) {
    companion object {
        val IDLE = PhoneNow(playing = false, title = null)
    }
}

class ScreenAnnouncer(
    private val scope: CoroutineScope,
    private val post: suspend (String) -> Boolean,
    private val identity: () -> PhoneIdentity,
    private val muted: () -> String? = { null },
    private val everyMs: Long = EVERY_MS,
) {
    fun follow(pages: Flow<String?>, now: Flow<PhoneNow> = flowOf(PhoneNow.IDLE)) = scope.launch {
        combine(pages.distinctUntilChanged(), now.distinctUntilChanged()) { page, state -> page to state }
            .collectLatest { (page, state) ->
                if (page == null) {
                    Diag.log(TAG, "no PC screen page to tell the Pi about (not on Wi-Fi?)")
                    return@collectLatest
                }
                muted()?.let { why ->
                    Diag.log(TAG, "not telling the Pi where the PC screen page is: $why")
                    return@collectLatest
                }
                val phone = identity()
                while (true) {
                    val told = post(announcement(page, phone, state))
                    Diag.log(
                        TAG,
                        "${if (told) "told" else "could not tell"} the Pi where this phone's PC screen page is " +
                            "(${page.substringBefore('#')}) [phone=${phone.model} " +
                            "install=${phone.installId.take(INSTALL_ID_SHOWN)} " +
                            "playing=${state.playing} title=${state.title ?: "none"}]; " +
                            "again in ${everyMs / MS_PER_MINUTE} min",
                    )
                    delay(everyMs)
                }
            }
    }

    private fun announcement(page: String, phone: PhoneIdentity, now: PhoneNow): String = buildJsonObject {
        put("kind", "screen")
        put("url", page)
        put("installId", phone.installId)
        put("model", phone.model)
        put("playing", now.playing)
        now.title?.let { put("title", it) }
    }.toString()

    private companion object {
        const val TAG = "screen"
        const val EVERY_MS = 30 * 60_000L
        const val MS_PER_MINUTE = 60_000L
        const val INSTALL_ID_SHOWN = 8
    }
}
