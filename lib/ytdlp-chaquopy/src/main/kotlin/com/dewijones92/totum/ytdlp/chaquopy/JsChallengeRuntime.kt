package com.dewijones92.totum.ytdlp.chaquopy

public interface JsChallengeRuntime {
    public fun available(): Boolean
    public fun hasLibrary(key: String): Boolean
    public fun loadLibrary(key: String, code: String)
    public fun keepPlayer(playerKey: String, playerJson: String): Boolean
    public fun solveKept(playerKey: String, requestsJson: String): String?
    public fun solveWithPlayer(
        playerKey: String,
        playerJson: String,
        preprocessed: Boolean,
        requestsJson: String
    ): String
}
