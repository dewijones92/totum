package com.dewijones92.totum.ui.common

private const val ZERO_WIDTH_SPACE = "\u200B"
private val BREAK_AFTER = Regex("([._])(?=[^\\s._])")

internal fun String.breakableAtDotsAndUnderscores(): String = replace(BREAK_AFTER, "$1$ZERO_WIDTH_SPACE")
