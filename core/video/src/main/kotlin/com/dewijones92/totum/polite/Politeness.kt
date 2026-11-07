package com.dewijones92.totum.polite

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.common.RequestMeter
import com.dewijones92.totum.ytdlp.YtDlpEngine
import okhttp3.OkHttpClient
import java.time.Instant

class Politeness(
    val gate: PoliteGate = PoliteGate(),
    val botBackOff: BotBackOff = BotBackOff(),
    val meter: RequestMeter = RequestMeter(),
) {
    val quiet: Boolean
        get() = (gate.slowDown.value?.untilMs ?: 0) > System.currentTimeMillis()

    fun bannerUp(now: Long = System.currentTimeMillis()): Boolean = quiet || (botBackOff.until.value ?: 0) > now

    fun gated(engine: YtDlpEngine): YtDlpEngine = PoliteYtDlpEngine(engine, gate, botBackOff)

    fun gated(client: OkHttpClient.Builder): OkHttpClient.Builder =
        client.addInterceptor(PoliteInterceptor(gate, meter))

    fun diagnostics(): Map<String, String> = buildMap {
        val slow = gate.slowDown.value
        put("polite.slowDown", slow?.let { "until ${Instant.ofEpochMilli(it.untilMs)} (${it.reason})" } ?: "none")
        put("polite.botBackOff", botBackOff.until.value?.let { "until ${Instant.ofEpochMilli(it)}" } ?: "none")
        putAll(gate.diagnostics())
        meter.lastHour().forEach { (host, line) -> put("polite.lastHour.$host", line) }
    }
}
