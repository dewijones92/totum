package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.Command
import com.dewijones92.totum.common.Diag
import java.io.File

internal fun interface PairingRunner {
    fun run(command: List<String>): Int
}

private val INHERITING = PairingRunner { command -> ProcessBuilder(command).inheritIO().start().waitFor() }

internal fun airPlayPair(
    command: Command.AirPlayPair,
    out: (String) -> Unit,
    env: (String) -> String? = System::getenv,
    runner: PairingRunner = INHERITING,
): Int {
    val configured = knownCastsFrom(env("TOTUM_AIRPLAY_TARGETS"))
    val device = configured.firstOrNull { castIdOf(it.name) == castIdOf(command.device) }
    if (device == null) {
        out(
            "'${command.device}' is not in TOTUM_AIRPLAY_TARGETS " +
                "(${configured.joinToString { it.name }.ifEmpty { "empty" }}). " +
                "Add  ${command.device}=<its address>  there first: " +
                "the credentials are stored under the device's name.",
        )
        Diag.log("airplay-pair", "refused: ${command.device} is not configured")
        return 1
    }
    val dataDir = File(env("TOTUM_DATA") ?: "${System.getProperty("user.home")}/.totum")
    val file = CastPlaces(null, File(dataDir, "airplay")).credentialsOf(castIdOf(device.name))!!
    val arguments = listOf(
        env("TOTUM_PYTHON") ?: "python3",
        env("TOTUM_AIRPLAY_HELPER") ?: "/opt/totum/airplay_helper.py",
        "pair",
        device.host,
        "--credentials",
        file.path,
        "--name",
        "Totum",
    ) + listOfNotNull(command.pin?.let { "--pin=$it" })
    out("Pairing ${device.name} at ${device.host}. The credentials will be stored in ${file.path} (mode 600).")
    Diag.log(
        "airplay-pair",
        "${device.name}: pairing at ${device.host}, credentials to ${file.path}, pin given=${command.pin != null}"
    )
    val code = runner.run(arguments)
    Diag.log("airplay-pair", "${device.name}: the helper exited $code; credentials stored=${file.isFile}")
    return code
}
