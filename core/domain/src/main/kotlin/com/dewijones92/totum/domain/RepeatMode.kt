package com.dewijones92.totum.domain

public enum class RepeatMode {
    OFF,
    QUEUE,
    QUEUE_SHUFFLED,
    GROUP,
    GROUP_SHUFFLED,
    ONE,
    ;

    public val repeatsQueue: Boolean get() = this == QUEUE || this == QUEUE_SHUFFLED
    public val repeatsGroup: Boolean get() = this == GROUP || this == GROUP_SHUFFLED
    public val reshuffles: Boolean get() = this == QUEUE_SHUFFLED || this == GROUP_SHUFFLED
}
