package com.dewijones92.totum.dailyalarms

import java.time.LocalTime

public fun spokenLine(label: String, time: LocalTime): String = "$label. It's ${timeInWords(time)}."

public fun timeInWords(time: LocalTime): String {
    val hour = time.hour % HOURS_ON_A_CLOCK
    val next = (time.hour + 1) % HOURS_ON_A_CLOCK
    fun name(h: Int) = HOURS[h]
    return when (val minute = time.minute) {
        0 -> "${name(hour)} o'clock"
        QUARTER -> "quarter past ${name(hour)}"
        HALF -> "half ${name(hour)}"
        QUARTER_TO -> "quarter to ${name(next)}"
        in 1 until HALF -> "${MINUTES[minute]} past ${name(hour)}"
        else -> "${MINUTES[MINUTES_IN_HOUR - minute]} to ${name(next)}"
    }
}

private const val HOURS_ON_A_CLOCK = 12
private const val QUARTER = 15
private const val HALF = 30
private const val QUARTER_TO = 45
private const val MINUTES_IN_HOUR = 60

private val HOURS = listOf(
    "twelve", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
)

private val UNITS = listOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

private val TEENS = listOf(
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
)

private val MINUTES: List<String> = UNITS + TEENS + "twenty" + UNITS.drop(1).map { "twenty-$it" }
