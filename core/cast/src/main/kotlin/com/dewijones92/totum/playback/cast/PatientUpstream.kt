package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.YoungStreamUrl

public class PatientUpstream(
    private val fetch: (String, String?) -> UpstreamReply = Upstream::get,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    public fun get(url: String, range: String?): UpstreamReply {
        var refusals = 0
        while (true) {
            val reply = fetch(url, range)
            if (reply.status != FORBIDDEN || refusals >= MAX_REFUSALS) return reply
            refusals++
            val wait = YoungStreamUrl.retryDelayMs(url, clock(), refusals) ?: return reply
            Diag.log(
                "cast",
                "upstream refused a young stream URL (aged ${YoungStreamUrl.ageMs(url, clock())}ms) for " +
                    "range=$range; asking again in ${wait}ms (refusal $refusals)",
            )
            runCatching { reply.body().close() }
            sleep(wait)
        }
    }

    private companion object {
        const val FORBIDDEN = 403
        const val MAX_REFUSALS = 3
    }
}
