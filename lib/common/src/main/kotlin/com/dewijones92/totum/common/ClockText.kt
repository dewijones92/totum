package com.dewijones92.totum.common

public object ClockText {
    public fun seconds(text: String): Double? {
        val parts = text.trim().ifBlank { return null }.split(":")
        if (parts.size > MAX_FIELDS) return null
        return parts.fold(0.0) { total, part -> total * BASE + (part.trim().toDoubleOrNull() ?: return null) }
    }

    private const val BASE = 60.0
    private const val MAX_FIELDS = 3
}
