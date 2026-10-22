package com.dewijones92.totum.settings

enum class RepeatMode {
    OFF,
    QUEUE,
    QUEUE_SHUFFLED,
    GROUP,
    GROUP_SHUFFLED,
    ONE,
    ;

    val repeatsQueue: Boolean get() = this == QUEUE || this == QUEUE_SHUFFLED
    val repeatsGroup: Boolean get() = this == GROUP || this == GROUP_SHUFFLED
    val reshuffles: Boolean get() = this == QUEUE_SHUFFLED || this == GROUP_SHUFFLED
}
