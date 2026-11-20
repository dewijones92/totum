package com.dewijones92.totum.cli.serve.power

internal class ShownOnStep(
    private val display: DevicePower,
    private val displayLabel: String,
    private val webos: WebOsTv?,
    private val input: String?,
) : WakeStep {
    override val name: String = "$displayLabel on" + (input?.let { ", switched to input $it" }.orEmpty())

    override val note: String = "📺 Switching $displayLabel on…"

    override suspend fun run(): Boolean {
        val on = display.ensureAwake()
        val switched = if (webos != null && input != null) webos.switchInput(input) == null else true
        return on && switched
    }
}

internal class SwitchOffDisplay(
    private val display: DevicePower,
    private val displayLabel: String,
) : PowerOff {
    override val name: String = "switch $displayLabel off"

    override val available: Boolean get() = display.canSwitchOff

    override suspend fun run(): String? = display.switchOff()
}
