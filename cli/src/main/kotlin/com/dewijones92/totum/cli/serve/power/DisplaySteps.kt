package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetPower

internal class ShownOnStep(
    private val display: DevicePower,
    private val displayLabel: String,
    private val webos: WebOsTv?,
    private val input: String?,
) : WakeStep {
    override val name: String = "bring up $displayLabel" + (input?.let { " and put it on input $it" }.orEmpty())

    override val note: String = "📺 Switching $displayLabel on…"

    override suspend fun run(): Boolean {
        val woke = display.ensureAwake()
        val power = display.power
        if (!woke || power != TargetPower.ON) {
            Diag.warn(
                "cast-target",
                "$displayLabel did not come on (woke=$woke, power=${power.name}); " +
                    (input?.let { "input $it was not asked for" } ?: "no input was asked for"),
            )
            return false
        }
        if (webos == null || input == null) {
            Diag.log("cast-target", "$displayLabel is on; no input to switch to")
            return true
        }
        val failure = webos.switchInput(input)
        val result = failure?.let { "switch to input $input failed: $it" } ?: "switched to input $input"
        Diag.log("cast-target", "$displayLabel is on; $result")
        return failure == null
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
