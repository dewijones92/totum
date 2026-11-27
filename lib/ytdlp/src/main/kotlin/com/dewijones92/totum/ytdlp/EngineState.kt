package com.dewijones92.totum.ytdlp

public data class EngineState(
    val warm: Boolean,
    val solverReady: Boolean,
    val solverPlayerCache: Boolean,
    val sharedPlayerCaches: Boolean,
    val networkShared: Boolean,
    val playerFetches: Int,
    val playerParses: Int,
    val solves: Int,
    val preprocessedPlayers: List<String>,
) {
    public fun describe(): String =
        "warm=$warm solverReady=$solverReady solverPlayerCache=${if (solverPlayerCache) "on" else "off"} " +
            "sharedPlayerCaches=$sharedPlayerCaches networkShared=$networkShared " +
            "playerFetches=$playerFetches playerParses=$playerParses solves=$solves " +
            "preprocessedPlayers=${preprocessedPlayers.size}"

    public companion object {
        public val UNKNOWN: EngineState = EngineState(
            warm = false,
            solverReady = false,
            solverPlayerCache = false,
            sharedPlayerCaches = false,
            networkShared = false,
            playerFetches = 0,
            playerParses = 0,
            solves = 0,
            preprocessedPlayers = emptyList(),
        )
    }
}

public data class PlayerWarmth(
    val ok: Boolean,
    val alreadyHeld: Boolean,
    val tookMs: Long,
    val detail: String? = null,
) {
    public companion object {
        public fun unsupported(): PlayerWarmth =
            PlayerWarmth(ok = false, alreadyHeld = false, tookMs = 0, detail = "this engine cannot warm a player")
    }
}
