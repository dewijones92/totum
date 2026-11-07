package com.dewijones92.totum.ui.common

private const val ZERO_WIDTH_SPACE = '​'

internal fun String.breakableAtDotsAndUnderscores(): String {
    val name = this
    return buildString(name.length + name.length / 4) {
        name.forEachIndexed { index, char ->
            append(char)
            val next = name.getOrNull(index + 1)
            if ((char == '.' || char == '_') && next != null && !next.isWhitespace() && next != char) {
                append(ZERO_WIDTH_SPACE)
            }
        }
    }
}
