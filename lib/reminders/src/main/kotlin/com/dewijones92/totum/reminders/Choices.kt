package com.dewijones92.totum.reminders

import java.time.LocalTime

public fun minuteChoices(presets: List<Int>, chosen: Int): List<Int> = (presets + chosen).distinct().sorted()

public fun timeChoices(presets: List<LocalTime>, chosen: LocalTime?): List<LocalTime> =
    (presets + listOfNotNull(chosen)).distinct().sorted()
