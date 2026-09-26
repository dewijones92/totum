package com.dewijones92.totum.playback

public enum class SilenceMode {
    STANDARD,
    SMART,
    ;

    public companion object {
        public val DEFAULT: SilenceMode = SMART

        public fun fromStoredName(name: String?): SilenceMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
