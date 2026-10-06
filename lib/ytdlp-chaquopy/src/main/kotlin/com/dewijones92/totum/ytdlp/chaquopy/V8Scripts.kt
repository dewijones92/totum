package com.dewijones92.totum.ytdlp.chaquopy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal object V8Scripts {
    private const val PLAYERS = "const P = globalThis.__totumPlayers || (globalThis.__totumPlayers = {}); "

    fun keep(playerKey: String, playerJson: String, drop: List<String>): String =
        "(function () { $PLAYERS${dropping(drop)}P[${literal(playerKey)}] = $playerJson; return 'kept'; })()"

    fun solveKept(playerKey: String, requestsJson: String): String =
        "(function () { ${PLAYERS}return JSON.stringify(jsc({type: 'preprocessed', " +
            "preprocessed_player: P[${literal(playerKey)}], requests: $requestsJson})); })()"

    fun solveWithPlayer(
        playerKey: String,
        playerJson: String,
        preprocessed: Boolean,
        requestsJson: String,
        drop: List<String>,
    ): String {
        val input = if (preprocessed) {
            "{type: 'preprocessed', preprocessed_player: player, requests: $requestsJson}"
        } else {
            "{type: 'player', player: player, requests: $requestsJson, output_preprocessed: true}"
        }
        val kept = if (preprocessed) "player" else "out.preprocessed_player"
        return "(function () { $PLAYERS${dropping(drop)}const player = $playerJson; const out = jsc($input); " +
            "const keep = out.type !== 'error' && $kept; if (keep) P[${literal(playerKey)}] = keep; " +
            "return (keep ? '$KEPT' : 'E') + JSON.stringify(out); })()"
    }

    const val KEPT = "K"

    fun shortName(playerKey: String): String =
        Regex("/player/([^/]+)/(.+)$").find(playerKey)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: playerKey

    private fun dropping(keys: List<String>) =
        if (keys.isEmpty()) "" else "for (const k of ${JsonArray(keys.map(::JsonPrimitive))}) delete P[k]; "

    private fun literal(value: String) = JsonPrimitive(value).toString()
}
