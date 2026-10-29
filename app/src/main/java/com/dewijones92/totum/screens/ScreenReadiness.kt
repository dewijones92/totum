package com.dewijones92.totum.screens

class ScreenReadiness(private val onChange: (ready: Boolean, why: String) -> Unit) {
    @Volatile
    private var charging: Boolean? = null

    @Volatile
    private var wifi: Boolean? = null

    @Volatile
    private var told: Boolean? = null

    val ready: Boolean get() = charging == true && wifi == true

    @Synchronized
    fun charging(on: Boolean) {
        charging = on
        settle()
    }

    @Synchronized
    fun wifi(on: Boolean) {
        wifi = on
        settle()
    }

    fun diagnostics(): Map<String, String> = mapOf(
        "screen.ready" to ready.toString(),
        "screen.charging" to (charging?.toString() ?: "unknown"),
        "screen.wifi" to (wifi?.toString() ?: "unknown"),
    )

    private fun settle() {
        val now = ready
        if (told == now) return
        told = now
        onChange(now, "charging=${charging ?: "unknown"} wifi=${wifi ?: "unknown"}")
    }
}
