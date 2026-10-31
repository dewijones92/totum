package com.dewijones92.totum.settings

enum class DownloadPlace(val stored: String) {
    PHONE("phone"),
    HOME("home"),
    ;

    companion object {
        fun parse(stored: String?): DownloadPlace? = entries.firstOrNull { it.stored == stored }
    }
}
