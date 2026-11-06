package com.dewijones92.totum.update

import com.dewijones92.totum.data.update.Release

sealed interface UpdateInstall {
    data object Idle : UpdateInstall

    data class Downloading(val release: Release, val percent: Int?) : UpdateInstall

    data class Ready(val release: Release, val allowAsked: Boolean = false) : UpdateInstall

    data class Refused(val release: Release, val why: String) : UpdateInstall

    data class Failed(val release: Release, val why: String) : UpdateInstall
}

val UpdateInstall.summary: String
    get() = when (this) {
        UpdateInstall.Idle -> "idle"
        is UpdateInstall.Downloading -> "downloading v${release.version} ${percent ?: "?"}%"
        is UpdateInstall.Ready -> "ready v${release.version}" + if (allowAsked) " (allow-installs asked)" else ""
        is UpdateInstall.Refused -> "refused v${release.version}: $why"
        is UpdateInstall.Failed -> "failed v${release.version}: $why"
    }
